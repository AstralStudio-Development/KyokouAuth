package moe.ryuu.kyokouauth.core.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the pool and the schema. Every repository goes through [withConnection]; nothing else in the
 * project is allowed to know which backend is in use.
 *
 * SQLite is single-writer, so its pool is capped at one connection and WAL is enabled - without WAL
 * a concurrent read during a write fails with `SQLITE_BUSY`, which on a busy login burst is exactly
 * the moment it would happen.
 */
class Database(
    private val descriptor: DatabaseDescriptor,
    poolSize: Int = 4,
    /**
     * Called once per applied migration with its version number.
     *
     * A plain version rather than a formatted sentence: the wording belongs to the locale files,
     * which this module has no access to.
     */
    private val onMigrated: (version: Int) -> Unit = {},
    /**
     * Where to load the JDBC driver from.
     *
     * Returning null means "it is already on the plugin's classpath" - Paper bundles some drivers,
     * and an operator may have provided one. The drivers are not bundled with this plugin otherwise
     * (see [DriverCatalog]), so this supplies a classloader that can see the downloaded jar.
     */
    private val driverLoader: ((Dialect) -> ClassLoader?)? = null,
) : AutoCloseable {

    private val dataSource: HikariDataSource

    init {
        val driver = instantiateDriver()

        val config = HikariConfig().apply {
            // A Driver instance rather than `jdbcUrl` + `driverClassName`: HikariCP resolves a driver
            // class by name through its own classloader, which would not see a jar the plugin
            // downloaded at runtime.
            dataSource = DriverBackedDataSource(driver, descriptor.jdbcUrl(), descriptor)
            username = descriptor.username.takeIf { it.isNotEmpty() }
            password = descriptor.password.takeIf { it.isNotEmpty() }
            maximumPoolSize = if (descriptor.dialect.embedded) 1 else poolSize
            minimumIdle = 1
            connectionTimeout = 10_000
            poolName = "KyokouAuth-${descriptor.dialect.name}"
            isAutoCommit = true
            // Never let HikariCP validate a connection with `Connection.isValid`.
            //
            // That method belongs to the JDBC4 driver variant, and the SQLite driver an old server
            // bundles does not implement it - the call fails with
            // `NoSuchMethodError: org.sqlite.Conn.isValid` before the first query ever runs. A plain
            // test query works against every driver version and every backend.
            connectionTestQuery = "SELECT 1"
            descriptor.properties.forEach { (k, v) -> addDataSourceProperty(k, v) }
        }
        dataSource = HikariDataSource(config)

        if (descriptor.dialect == Dialect.SQLITE) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA journal_mode=WAL")
                    statement.execute("PRAGMA synchronous=NORMAL")
                    statement.execute("PRAGMA foreign_keys=ON")
                    statement.execute("PRAGMA busy_timeout=5000")
                }
            }
        }

        migrate()
    }

    private fun instantiateDriver(): java.sql.Driver {
        val name = driverClass(descriptor.dialect)
        val loader = driverLoader?.invoke(descriptor.dialect)

        val type = if (loader != null) {
            Class.forName(name, true, loader)
        } else {
            Class.forName(name)
        }
        return type.getDeclaredConstructor().newInstance() as java.sql.Driver
    }

    private fun migrate() {
        withConnection { connection ->
            for (migration in Schema.migrations) {
                val current = currentVersion(connection)
                if (migration.version <= current) continue

                val autoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    migration.statements(descriptor.dialect).forEach { sql ->
                        connection.createStatement().use { it.execute(sql) }
                    }
                    // Update first, insert only when the row is absent. `schema_version` is one row
                    // shared by every migration, so an unconditional INSERT collides on the second
                    // step - a defect that stayed invisible while the schema had exactly one.
                    val updated = connection.prepareStatement(
                        "UPDATE ${Schema.META} SET meta_value = ? WHERE meta_key = 'schema_version'",
                    ).use { statement ->
                        statement.setString(1, migration.version.toString())
                        statement.executeUpdate()
                    }
                    if (updated == 0) {
                        connection.prepareStatement(
                            "INSERT INTO ${Schema.META} (meta_key, meta_value) VALUES ('schema_version', ?)",
                        ).use { statement ->
                            statement.setString(1, migration.version.toString())
                            statement.executeUpdate()
                        }
                    }
                    connection.commit()
                    onMigrated(migration.version)
                } catch (failure: SQLException) {
                    connection.rollback()
                    throw failure
                } finally {
                    connection.autoCommit = autoCommit
                }
            }
        }
    }

    private fun currentVersion(connection: Connection): Int = try {
        connection.prepareStatement(
            "SELECT meta_value FROM ${Schema.META} WHERE meta_key = 'schema_version'",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1).toIntOrNull() ?: 0 else 0
            }
        }
    } catch (_: SQLException) {
        // The meta table does not exist yet, which means this is a first run.
        0
    }

    fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use(block)

    fun <T> transaction(block: (Connection) -> T): T = withConnection { connection ->
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = autoCommit
        }
    }

    override fun close() {
        dataSource.close()
    }

    companion object {
        fun driverClass(dialect: Dialect): String = when (dialect) {
            Dialect.SQLITE -> "org.sqlite.JDBC"
            Dialect.H2 -> "org.h2.Driver"
            Dialect.MYSQL -> "com.mysql.cj.jdbc.Driver"
            Dialect.MARIADB -> "org.mariadb.jdbc.Driver"
            Dialect.POSTGRESQL -> "org.postgresql.Driver"
        }

        /**
         * JDBC calls are blocking. Running them on the main server thread is the single easiest way
         * to turn a login plugin into a lag source, so every repository hop is dispatched here.
         */
        fun ioExecutor(threads: Int = 4): ExecutorService {
            val counter = AtomicInteger()
            val factory = ThreadFactory { runnable ->
                Thread(runnable, "KyokouAuth-IO-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
            return Executors.newFixedThreadPool(threads, factory)
        }
    }
}
