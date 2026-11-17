package moe.ryuu.kyokouauth.core.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DatabaseFailureTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a migration callback failure closes the newly created pool`() {
        val existingThreads = Thread.getAllStackTraces().keys.toSet()
        val failure = IllegalStateException("migration callback failed")

        val actual = assertFailsWith<IllegalStateException> {
            Database(
                DatabaseDescriptor(Dialect.H2, database = directory.resolve("failed-startup").toString()),
                onMigrated = { throw failure },
            )
        }

        assertSame(failure, actual)
        val deadline = System.nanoTime() + 2_000_000_000L
        fun leakedThreads() = Thread.getAllStackTraces().keys.filter {
            it !in existingThreads && it.isAlive && it.name.startsWith("KyokouAuth-H2")
        }
        while (leakedThreads().isNotEmpty() && System.nanoTime() < deadline) LockSupport.parkNanos(10_000_000L)
        assertTrue(leakedThreads().isEmpty(), "failed initialization left a pool worker alive")
    }

    @ParameterizedTest
    @EnumSource(value = Dialect::class, names = ["H2", "SQLITE"])
    fun `real file transactions roll back and committed rows survive reopen`(dialect: Dialect) {
        val descriptor = DatabaseDescriptor(dialect, database = directory.resolve("transaction-${dialect.name}").toString())
        val original = IllegalStateException("abort transaction")
        Database(descriptor).use { database ->
            database.withConnection { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE transaction_probe (id INTEGER PRIMARY KEY)") }
            }
            assertSame(original, assertFailsWith<IllegalStateException> {
                database.transaction<Unit> { connection ->
                    connection.createStatement().use { it.execute("INSERT INTO transaction_probe (id) VALUES (1)") }
                    throw original
                }
            })
            database.withConnection { connection ->
                assertTrue(connection.autoCommit)
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM transaction_probe").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(0, rows.getInt(1))
                    }
                }
            }
            database.transaction { connection ->
                connection.createStatement().use { it.execute("INSERT INTO transaction_probe (id) VALUES (2)") }
            }
        }
        Database(descriptor).use { database ->
            database.withConnection { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id FROM transaction_probe").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(2, rows.getInt(1))
                        assertTrue(!rows.next())
                    }
                }
            }
        }
    }

    @Test
    fun `transaction cleanup failures are suppressed on the original failure`() {
        JdbcFaults().use { jdbc ->
            jdbc.open().use { database ->
                val original = IllegalArgumentException("transaction failed")
                val rollback = SQLException("rollback failed")
                val restore = SQLException("autoCommit restore failed")
                jdbc.rollbackFailure = rollback
                jdbc.restoreFailure = restore

                val actual = assertFailsWith<IllegalArgumentException> {
                    database.transaction<Unit> { throw original }
                }

                assertSame(original, actual)
                assertTrue(actual.suppressed.contains(rollback))
                assertTrue(actual.suppressed.contains(restore))
            }
        }
    }

    @Test
    fun `migration cleanup failures are suppressed and its pool is closed`() {
        JdbcFaults().use { jdbc ->
            jdbc.version = 0
            val original = SQLException("migration failed", "42000")
            val rollback = SQLException("rollback failed")
            val restore = SQLException("autoCommit restore failed")
            jdbc.migrationFailure = original
            jdbc.rollbackFailure = rollback
            jdbc.restoreFailure = restore

            val actual = assertFailsWith<SQLException> { jdbc.open() }

            assertSame(original, actual)
            assertTrue(actual.suppressed.contains(rollback))
            assertTrue(actual.suppressed.contains(restore))
            assertEquals(jdbc.opened.get(), jdbc.closed.get())
        }
    }

    @Test
    fun `a pragma failure closes the sqlite pool`() {
        JdbcFaults().use { jdbc ->
            val original = SQLException("pragma failed")
            jdbc.pragmaFailure = original

            assertSame(original, assertFailsWith<SQLException> { jdbc.open(Dialect.SQLITE) })
            assertEquals(jdbc.opened.get(), jdbc.closed.get())
        }
    }

    @ParameterizedTest
    @EnumSource(Dialect::class)
    fun `schema permission errors do not masquerade as a fresh database`(dialect: Dialect) {
        JdbcFaults().use { jdbc ->
            // MySQL uses the same SQLSTATE for permission and syntax/missing-table errors.
            val original = SQLException("permission denied", "42000", 1142)
            jdbc.versionFailure = original
            assertSame(original, assertFailsWith<SQLException> { jdbc.open(dialect) })
            assertEquals(0, jdbc.migrationStatements.get())
            assertEquals(jdbc.opened.get(), jdbc.closed.get())
        }
    }

    @ParameterizedTest
    @EnumSource(Dialect::class)
    fun `only genuine missing table errors permit first run migrations`(dialect: Dialect) {
        JdbcFaults().use { jdbc ->
            jdbc.versionFailure = when (dialect) {
                Dialect.H2 -> SQLException("Table kyokouauth_meta not found", "42S02", 42102)
                Dialect.MYSQL, Dialect.MARIADB -> SQLException("Table kyokouauth_meta doesn't exist", "42S02", 1146)
                Dialect.POSTGRESQL -> SQLException("relation kyokouauth_meta does not exist", "42P01")
                Dialect.SQLITE -> SQLException("[SQLITE_ERROR] SQL error or missing database (no such table: kyokouauth_meta)", null, 1)
            }
            jdbc.open(dialect).use { assertTrue(jdbc.migrationStatements.get() > 0) }
            assertEquals(jdbc.opened.get(), jdbc.closed.get())
        }
    }

    @ParameterizedTest
    @EnumSource(Dialect::class)
    fun `a disconnected schema query is not treated as version zero`(dialect: Dialect) {
        JdbcFaults().use { jdbc ->
            val original = SQLException("connection lost", "08006")
            jdbc.versionFailure = original
            assertSame(original, assertFailsWith<SQLException> { jdbc.open(dialect) })
            assertEquals(0, jdbc.migrationStatements.get())
        }
    }

    @Test
    fun `sqlite corruption is not treated as a missing table`() {
        JdbcFaults().use { jdbc ->
            val original = SQLException("database disk image is malformed", null, 11)
            jdbc.versionFailure = original
            assertSame(original, assertFailsWith<SQLException> { jdbc.open(Dialect.SQLITE) })
            assertEquals(0, jdbc.migrationStatements.get())
        }
    }

    private class JdbcFaults : Driver, AutoCloseable {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val migrationStatements = AtomicInteger()
        var version = Schema.version
        var versionFailure: SQLException? = null
        var migrationFailure: SQLException? = null
        var pragmaFailure: SQLException? = null
        var rollbackFailure: SQLException? = null
        var restoreFailure: SQLException? = null
        private val id = UUID.randomUUID().toString()

        init { DriverManager.registerDriver(this) }

        fun open(dialect: Dialect = Dialect.H2): Database = Database(
            DatabaseDescriptor(dialect, database = "unused", properties = mapOf("fixture-id" to id)),
            driverLoader = { FaultDriverClasses.loader(Database.driverClass(it), javaClass.classLoader) },
        )

        override fun acceptsURL(url: String?): Boolean = url == "jdbc:kyokou-fault:$id"
        override fun connect(url: String?, info: Properties?): Connection? {
            if (!acceptsURL(url)) return null
            opened.incrementAndGet()
            var autoCommit = true
            var isClosed = false
            return proxy(Connection::class.java) { method, arguments ->
                when (method) {
                    "getAutoCommit" -> autoCommit
                    "setAutoCommit" -> {
                        val next = arguments[0] as Boolean
                        if (next && !autoCommit) restoreFailure?.let { throw it }
                        autoCommit = next
                        null
                    }
                    "rollback" -> { rollbackFailure?.let { throw it }; null }
                    "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED
                    "isClosed" -> isClosed
                    "close" -> { if (!isClosed) closed.incrementAndGet(); isClosed = true; null }
                    "createStatement" -> statement()
                    "prepareStatement" -> {
                        if ((arguments[0] as String).startsWith("SELECT meta_value")) versionFailure?.let { throw it }
                        preparedStatement()
                    }
                    else -> null
                }
            }
        }

        private fun statement(): Statement = proxy(Statement::class.java) { method, arguments ->
            when (method) {
                "execute" -> {
                    val sql = arguments[0] as String
                    if (sql.startsWith("PRAGMA")) pragmaFailure?.let { throw it }
                    if (sql.startsWith("CREATE")) {
                        migrationStatements.incrementAndGet()
                        migrationFailure?.let { throw it }
                    }
                    false
                }
                else -> null
            }
        }

        private fun preparedStatement(): PreparedStatement = proxy(PreparedStatement::class.java) { method, _ ->
            when (method) {
                "executeUpdate" -> 1
                "executeQuery" -> {
                    var next = true
                    proxy(ResultSet::class.java) { rowMethod, _ ->
                        when (rowMethod) {
                            "next" -> next.also { next = false }
                            "getString" -> version.toString()
                            else -> null
                        }
                    }
                }
                else -> null
            }
        }

        override fun getPropertyInfo(url: String?, info: Properties?) = emptyArray<java.sql.DriverPropertyInfo>()
        override fun getMajorVersion() = 1
        override fun getMinorVersion() = 0
        override fun jdbcCompliant() = false
        override fun getParentLogger(): java.util.logging.Logger = java.util.logging.Logger.getGlobal()
        override fun close() { DriverManager.deregisterDriver(this) }

        private fun <T> proxy(type: Class<T>, invoke: (String, Array<out Any?>) -> Any?): T {
            val instance = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
                when (method.name) {
                    "toString" -> "Fault${type.simpleName}"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.get(0)
                    else -> invoke(method.name, arguments ?: emptyArray()) ?: when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                }
            }
            return type.cast(instance)
        }
    }
}
