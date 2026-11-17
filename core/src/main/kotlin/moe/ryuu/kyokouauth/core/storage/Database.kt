package moe.ryuu.kyokouauth.core.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLException

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

        try {
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
        } catch (failure: Throwable) {
            try {
                dataSource.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
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

                inTransaction(connection) {
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
                }
                onMigrated(migration.version)
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
    } catch (failure: SQLException) {
        // Permission, syntax, connection and corruption failures must not trigger fresh migrations.
        val missingTable = when (descriptor.dialect) {
            Dialect.H2 -> failure.sqlState == "42S02" && failure.errorCode in setOf(42102, 42104)
            Dialect.MYSQL, Dialect.MARIADB -> failure.sqlState == "42S02" && failure.errorCode == 1146
            Dialect.POSTGRESQL -> failure.sqlState == "42P01"
            Dialect.SQLITE -> failure.errorCode == 1 && SQLITE_MISSING_TABLE.find(failure.message.orEmpty())
                ?.groupValues?.get(1) in setOf(Schema.META, "main.${Schema.META}")
        }
        if (!missingTable) throw failure
        0
    }

    fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use(block)

    fun <T> transaction(block: (Connection) -> T): T = withConnection { connection ->
        inTransaction(connection, block)
    }

    private fun <T> inTransaction(connection: Connection, block: (Connection) -> T): T {
        val autoCommit = connection.autoCommit
        var primary: Throwable? = null
        try {
            connection.autoCommit = false
            val result = block(connection)
            connection.commit()
            return result
        } catch (failure: Throwable) {
            primary = failure
            try {
                connection.rollback()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        } finally {
            try {
                connection.autoCommit = autoCommit
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            }
        }
    }

    override fun close() {
        dataSource.close()
    }

    companion object {
        private val SQLITE_MISSING_TABLE = Regex("no such table:\\s*([A-Za-z_][A-Za-z0-9_.]*)", RegexOption.IGNORE_CASE)

        fun driverClass(dialect: Dialect): String = when (dialect) {
            Dialect.SQLITE -> "org.sqlite.JDBC"
            Dialect.H2 -> "org.h2.Driver"
            Dialect.MYSQL -> "com.mysql.cj.jdbc.Driver"
            Dialect.MARIADB -> "org.mariadb.jdbc.Driver"
            Dialect.POSTGRESQL -> "org.postgresql.Driver"
        }

    }
}
