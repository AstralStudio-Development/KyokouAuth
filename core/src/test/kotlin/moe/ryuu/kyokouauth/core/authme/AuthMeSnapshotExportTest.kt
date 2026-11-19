package moe.ryuu.kyokouauth.core.authme

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.storage.Dialect
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthMeSnapshotExportTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `configuration exports a standalone local snapshot without changing its source`() {
        val source = source()
        val configFile = directory.resolve("config.yml")
        Files.writeString(configFile, """
            DataSource:
              backend: SQLITE
              mySQLDatabase: old
        """.trimIndent())
        val sourceBytes = Files.readAllBytes(source)
        val sourceModified = Files.getLastModifiedTime(source)
        val destination = directory.resolve("claim-snapshot.db")
        val config = AuthMeConfigReader.read(configFile)
        AuthMeLegacyVerifier(config).use {
            assertEquals(1, it.exportSnapshot(destination))
        }
        assertContentEquals(sourceBytes, Files.readAllBytes(source))
        assertEquals(sourceModified, Files.getLastModifiedTime(source))
        Files.delete(source)
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, destination.toString(), charsetName = config.charsetName)).use {
            assertEquals(listOf(AuthMeAccount("Alice", null)), it.inspectAccounts())
            assertEquals(AuthMeVerification.VERIFIED, it.verify(GameName.parseOrThrow("Alice"), "password".toCharArray()))
        }
    }

    @Test
    fun `canonical export retains only supported hashes validated names and TOTP presence`() {
        val source = source(listOf(
            Row(),
            Row(name = "Unknown", hash = "private_unknown_hash"),
            Row(name = "HugeHash", hash = SHA_PASSWORD + "f".repeat(100_000)),
            Row(name = "NulHash", hash = SHA_PASSWORD + "\u0000private_suffix"),
            Row(name = "Mfa", totp = "private_totp_secret"),
            Row(name = "NulMfa", totp = "\u0000private_totp_secret"),
            Row(name = "bad-name"),
            Row(name = "x".repeat(100_000)),
            Row(name = null),
        ))
        val destination = directory.resolve("canonical.db")
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, source.toString(), "private_connection_user", "private_connection_password")).use {
            assertEquals(9, it.exportSnapshot(destination))
        }
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, destination.toString())).use {
            assertEquals(listOf(
                AuthMeAccount("Alice", null),
                AuthMeAccount("Unknown", AuthMeAccountIssue.UNSUPPORTED_HASH),
                AuthMeAccount("HugeHash", AuthMeAccountIssue.UNSUPPORTED_HASH),
                AuthMeAccount("NulHash", AuthMeAccountIssue.UNSUPPORTED_HASH),
                AuthMeAccount("Mfa", AuthMeAccountIssue.TOTP_REQUIRED),
                AuthMeAccount("NulMfa", AuthMeAccountIssue.TOTP_REQUIRED),
                AuthMeAccount(null, AuthMeAccountIssue.INVALID_NAME),
                AuthMeAccount(null, AuthMeAccountIssue.INVALID_NAME),
                AuthMeAccount(null, AuthMeAccountIssue.INVALID_NAME),
            ), it.inspectAccounts())
        }
        val artifact = Files.readAllBytes(destination).toString(Charsets.ISO_8859_1)
        for (secret in listOf("private_unknown_hash", "private_suffix", "private_totp_secret", "private_email", "private_connection_user", "private_connection_password")) {
            assertFalse(artifact.contains(secret))
        }
        driver().connect("jdbc:sqlite:${destination.toUri()}?mode=ro", Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(authme)").use { rows ->
                    val columns = mutableListOf<String>()
                    while (rows.next()) columns.add(rows.getString("name"))
                    assertEquals(listOf("username", "password", "totp"), columns)
                }
                statement.executeQuery("SELECT password, totp FROM authme WHERE username = 'Mfa'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(null, rows.getString(1))
                    assertEquals("required", rows.getString(2))
                }
                statement.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'authme'").use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getString(1).contains("LOWER(username)"))
                }
            }
        }
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(destination))
        }
    }

    @Test
    fun `bounded batches report only counts and keep every duplicate for local inspection`() {
        val source = source(listOf(Row()) + List(511) { Row(name = "Player$it") } + Row(name = "ALICE", totp = "private_duplicate_totp"))
        val destination = directory.resolve("batched.db")
        val counts = mutableListOf<Int>()
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, source.toString())).use {
            assertEquals(513, it.exportSnapshot(destination, progress = counts::add))
        }
        assertEquals(listOf(256, 512, 513), counts)
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, destination.toString())).use {
            val accounts = it.inspectAccounts()
            assertEquals(513, accounts.size)
            assertEquals(AuthMeAccountIssue.DUPLICATE_NAME, accounts.first().issue)
            assertEquals(AuthMeAccountIssue.DUPLICATE_NAME, accounts.last().issue)
            assertEquals(511, accounts.count { account -> account.issue == null })
        }
    }

    @Test
    fun `exceeding the export limit deletes the entire owned target instead of leaving a prefix`() {
        val source = source(listOf(Row(), Row("Bob")))
        val destination = directory.resolve("limited.db")
        val before = Files.readAllBytes(source)
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, source.toString())).use {
            assertExportFailure { it.exportSnapshot(destination, maxAccounts = 1) }
            assertEquals(setOf("old.db"), directory.toFile().list().orEmpty().toSet())
            assertContentEquals(before, Files.readAllBytes(source))
            assertEquals(2, it.exportSnapshot(destination, maxAccounts = 2))
        }
    }

    @Test
    fun `cancellation during a batch and immediately before commit removes all target files`() {
        val source = source(List(300) { Row("Player$it") })
        val destination = directory.resolve("cancelled.db")
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, source.toString())).use { verifier ->
            assertExportFailure { verifier.exportSnapshot(destination, current = { false }) }
            assertFalse(Files.exists(destination))
            val current = AtomicBoolean(true)
            assertExportFailure {
                verifier.exportSnapshot(destination, current = current::get, progress = { current.set(false) })
            }
            assertEquals(setOf("old.db"), directory.toFile().list().orEmpty().toSet())
            current.set(true)
            val counts = mutableListOf<Int>()
            assertExportFailure {
                verifier.exportSnapshot(destination, current = current::get, progress = {
                    counts.add(it)
                    if (it == 300) current.set(false)
                })
            }
            assertEquals(listOf(256, 300), counts)
            assertEquals(setOf("old.db"), directory.toFile().list().orEmpty().toSet())
        }
    }

    @Test
    fun `closing the verifier or failing a progress callback rolls back and deletes its export`() {
        val source = source(List(260) { Row("Player$it") })
        val destination = directory.resolve("closed.db")
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, source.toString())).use { verifier ->
            assertExportFailure { verifier.exportSnapshot(destination, progress = { verifier.close() }) }
            assertEquals(setOf("old.db"), directory.toFile().list().orEmpty().toSet())
            assertExportFailure { verifier.exportSnapshot(destination) }
        }
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, source.toString())).use { verifier ->
            assertExportFailure { verifier.exportSnapshot(destination, progress = { throw SQLException("private_callback_secret") }) }
            assertEquals(setOf("old.db"), directory.toFile().list().orEmpty().toSet())
        }
    }

    @Test
    fun `existing targets sidecars and missing parents are never overwritten deleted or created`() {
        val source = source()
        val destination = directory.resolve("existing.db")
        val marker = "private_existing_file".toByteArray()
        Files.write(destination, marker)
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, source.toString())).use { verifier ->
            assertExportFailure { verifier.exportSnapshot(destination) }
            assertContentEquals(marker, Files.readAllBytes(destination))
            assertExportFailure { verifier.exportSnapshot(source) }
            assertEquals(1, verifier.inspectAccounts().size)
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                val fresh = directory.resolve("fresh$suffix.db")
                val sidecar = fresh.resolveSibling(fresh.fileName.toString() + suffix)
                Files.write(sidecar, marker)
                assertExportFailure { verifier.exportSnapshot(fresh) }
                assertFalse(Files.exists(fresh))
                assertContentEquals(marker, Files.readAllBytes(sidecar))
            }
            val missingParent = directory.resolve("absent").resolve("snapshot.db")
            assertExportFailure { verifier.exportSnapshot(missingParent) }
            assertFalse(Files.exists(missingParent.parent))
            assertExportFailure { verifier.exportSnapshot(directory) }
            assertExportFailure { verifier.exportSnapshot(Path.of("relative-snapshot.db")) }
            for (limit in listOf(-1, 0, 100_001, Int.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> { verifier.exportSnapshot(directory.resolve("invalid.db"), maxAccounts = limit) }
            }
        }
    }

    @Test
    fun `a destination sidecar appearing during source connection is preserved rather than adopted`() {
        val source = source(listOf(Row(), Row("Bob")))
        val destination = directory.resolve("late-sidecar.db")
        val sidecar = destination.resolveSibling(destination.fileName.toString() + "-journal")
        val marker = "private_preexisting_journal".toByteArray()
        val loader = AuthMeLegacyVerifierTest.ProbeLoader("org.sqlite.JDBC") { _, _ ->
            Files.write(sidecar, marker)
            readonly(source)
        }
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, source.toString()), loader).use {
            assertExportFailure { it.exportSnapshot(destination, maxAccounts = 1) }
        }
        assertFalse(Files.exists(destination))
        assertTrue(Files.exists(sidecar))
        assertContentEquals(marker, Files.readAllBytes(sidecar))
    }

    @Test
    fun `schema and target driver failures are sanitized and leave no newly created files`() {
        val source = source(missingTotp = true)
        val destination = directory.resolve("failed.db")
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, source.toString())).use {
            assertExportFailure { it.exportSnapshot(destination) }
        }
        assertEquals(setOf("old.db"), directory.toFile().list().orEmpty().toSet())
        Files.delete(source)
        source()
        val failingTarget = AuthMeLegacyVerifierTest.ProbeLoader("org.sqlite.JDBC") { _, properties ->
            assertEquals(null, properties.getProperty("password"))
            Files.write(destination.resolveSibling(destination.fileName.toString() + "-journal"), byteArrayOf(1))
            throw SQLException("private_target_password $SHA_PASSWORD jdbc:secret")
        }
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, source.toString())).use {
            assertExportFailure { it.exportSnapshot(destination, snapshotDriverLoader = failingTarget) }
        }
        assertEquals(setOf("old.db"), directory.toFile().list().orEmpty().toSet())
    }

    @Test
    fun `each server export opens exactly one readonly source and a separate SQLite target driver`() {
        val source = source(List(300) { Row("Player$it") })
        val original = Files.readAllBytes(source)
        val sqliteDriver = driver()
        for (dialect in listOf(Dialect.MYSQL, Dialect.MARIADB)) {
            val events = mutableListOf<String>()
            val sourceConnects = AtomicInteger()
            val targetConnects = AtomicInteger()
            val protocol = if (dialect == Dialect.MYSQL) "mysql" else "mariadb"
            val driverName = if (dialect == Dialect.MYSQL) "com.mysql.cj.jdbc.Driver" else "org.mariadb.jdbc.Driver"
            val tls = if (dialect == Dialect.MYSQL) "VERIFY_IDENTITY" else "verify-full"
            val config = AuthMeSourceConfig(dialect, "jdbc:$protocol://example.invalid/authme?sslMode=$tls", "private_server_user", "private_server_password")
            val sourceLoader = AuthMeLegacyVerifierTest.ProbeLoader(driverName) { url, properties ->
                sourceConnects.incrementAndGet()
                assertEquals(config.location, url)
                assertEquals(tls, properties.getProperty("sslMode"))
                assertEquals("private_server_user", properties.getProperty("user"))
                assertEquals("private_server_password", properties.getProperty("password"))
                assertEquals("5000", properties.getProperty("connectTimeout"))
                assertEquals("5000", properties.getProperty("socketTimeout"))
                assertEquals("false", properties.getProperty("allowMultiQueries"))
                assertEquals("false", properties.getProperty("autoReconnect"))
                assertEquals(if (dialect == Dialect.MYSQL) "true" else null, properties.getProperty("useCursorFetch"))
                recordingSource(readonly(source), events)
            }
            val targetLoader = AuthMeLegacyVerifierTest.ProbeLoader("org.sqlite.JDBC") { url, properties ->
                targetConnects.incrementAndGet()
                assertTrue(url.endsWith("?mode=rw"))
                assertEquals("66", properties.getProperty("open_mode"))
                assertEquals(null, properties.getProperty("user"))
                assertEquals(null, properties.getProperty("password"))
                val target = sqliteDriver.connect(url, properties)
                jdbc<Connection> { method, arguments ->
                    if (method.name == "commit") events.add("TARGET_COMMIT")
                    invoke(target, method, arguments)
                }
            }
            val destination = directory.resolve("$protocol-snapshot.db")
            AuthMeLegacyVerifier(config, sourceLoader).use {
                assertEquals(300, it.exportSnapshot(destination, snapshotDriverLoader = targetLoader))
            }
            assertEquals(1, sourceConnects.get())
            assertEquals(1, targetConnects.get())
            assertEquals(1, events.count { it == "FETCH_256" })
            assertEquals(1, events.count { it == "TARGET_COMMIT" })
            assertEquals(listOf("SOURCE_ROLLBACK", "SOURCE_CLOSE"), events.takeLast(2))
            assertTrue(events.contains("READ_ONLY"))
            assertTrue(events.contains("START TRANSACTION READ ONLY"))
            val deadline = if (dialect == Dialect.MYSQL) "SET SESSION max_execution_time=5000" else "SET SESSION max_statement_time=5"
            assertTrue(events.contains(deadline))
            assertContentEquals(original, Files.readAllBytes(source))
            AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, destination.toString())).use {
                assertEquals(300, it.inspectAccounts().size)
            }
            assertFalse(Files.readAllBytes(destination).toString(Charsets.ISO_8859_1).contains("private_server_"))
        }
    }

    @Test
    fun `a failed server read still rolls back its only source connection and deletes its target`() {
        val source = source()
        val destination = directory.resolve("server-failure.db")
        val events = mutableListOf<String>()
        val connects = AtomicInteger()
        val loader = AuthMeLegacyVerifierTest.ProbeLoader("com.mysql.cj.jdbc.Driver") { _, _ ->
            connects.incrementAndGet()
            recordingSource(readonly(source), events, failRows = true)
        }
        val config = AuthMeSourceConfig(Dialect.MYSQL, "jdbc:mysql://example.invalid/authme?sslMode=VERIFY_IDENTITY")
        AuthMeLegacyVerifier(config, loader).use {
            assertExportFailure { it.exportSnapshot(destination) }
        }
        assertEquals(1, connects.get())
        assertEquals(listOf("SOURCE_ROLLBACK", "SOURCE_CLOSE"), events.takeLast(2))
        assertEquals(setOf("old.db"), directory.toFile().list().orEmpty().toSet())
    }

    @Test
    fun `failure starting the readonly transaction still rolls back and closes the source`() {
        val source = source()
        val events = mutableListOf<String>()
        val connects = AtomicInteger()
        val loader = AuthMeLegacyVerifierTest.ProbeLoader("com.mysql.cj.jdbc.Driver") { _, _ ->
            connects.incrementAndGet()
            recordingSource(readonly(source), events, failTransactionStart = true)
        }
        val destination = directory.resolve("transaction-failure.db")
        val config = AuthMeSourceConfig(Dialect.MYSQL, "jdbc:mysql://example.invalid/authme?sslMode=VERIFY_IDENTITY")
        AuthMeLegacyVerifier(config, loader).use {
            assertExportFailure { it.exportSnapshot(destination) }
        }
        assertEquals(1, connects.get())
        assertEquals(listOf("SOURCE_ROLLBACK", "SOURCE_CLOSE"), events.takeLast(2))
        assertEquals(setOf("old.db"), directory.toFile().list().orEmpty().toSet())
    }

    private fun readonly(source: Path): Connection = driver().connect(
        "jdbc:sqlite:${source.toUri()}?mode=ro&immutable=1",
        Properties().apply { setProperty("open_mode", "65") },
    )

    private fun recordingSource(
        real: Connection,
        events: MutableList<String>,
        failRows: Boolean = false,
        failTransactionStart: Boolean = false,
    ): Connection =
        jdbc<Connection> { method, arguments ->
            when (method.name) {
                "setReadOnly" -> { assertEquals(true, arguments[0]); events.add("READ_ONLY"); null }
                "isReadOnly" -> real.isReadOnly
                "createStatement" -> {
                    val statement = real.createStatement()
                    jdbc<Statement> { call, values ->
                        if (call.name == "execute") {
                            val sql = values[0] as String
                            assertTrue(sql.startsWith("SET SESSION ") || sql == "START TRANSACTION READ ONLY")
                            events.add(sql)
                            if (failTransactionStart && sql == "START TRANSACTION READ ONLY") throw SQLException("private_transaction_secret")
                            false
                        } else invoke(statement, call, values)
                    }
                }
                "prepareStatement" -> {
                    val sql = arguments[0] as String
                    val schema = sql.endsWith("WHERE 1=0")
                    assertTrue(sql.startsWith("SELECT "))
                    if (!schema) {
                        assertTrue(sql.endsWith("LIMIT 100001"))
                        assertTrue(sql.contains("SUBSTR(`username`, 1, 17)"))
                        assertTrue(sql.contains("SUBSTR(`password`, 1, 257)"))
                        assertFalse(sql.contains("email"))
                    }
                    val statement = real.prepareStatement(sql)
                    jdbc<PreparedStatement> { call, values ->
                        when (call.name) {
                            "setFetchSize" -> { assertEquals(256, values[0]); events.add("FETCH_256"); null }
                            "executeQuery" -> {
                                if (!schema && failRows) throw SQLException("private_source_password $SHA_PASSWORD")
                                invoke(statement, call, values)
                            }
                            "executeUpdate", "addBatch", "executeBatch" -> error("The legacy source must never be written")
                            else -> invoke(statement, call, values)
                        }
                    }
                }
                "rollback" -> { events.add("SOURCE_ROLLBACK"); invoke(real, method, arguments) }
                "close" -> { events.add("SOURCE_CLOSE"); invoke(real, method, arguments) }
                else -> invoke(real, method, arguments)
            }
        }

    private fun invoke(target: Any, method: Method, arguments: Array<out Any?>): Any? = try {
        method.invoke(target, *arguments)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private inline fun <reified T> jdbc(noinline invoke: (Method, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
            invoke(method, arguments ?: emptyArray())
        } as T

    private fun assertExportFailure(action: () -> Unit) {
        val error = assertFailsWith<IllegalStateException>(block = action)
        assertEquals("Unable to export AuthMe snapshot", error.message)
        assertEquals(null, error.cause)
        assertFalse(error.stackTraceToString().contains("private_"))
        assertFalse(error.stackTraceToString().contains(SHA_PASSWORD))
    }

    private data class Row(val name: String? = "Alice", val hash: String? = SHA_PASSWORD, val totp: String? = "")

    private fun source(rows: List<Row> = listOf(Row()), missingTotp: Boolean = false): Path {
        val path = directory.resolve("old.db")
        driver().connect("jdbc:sqlite:$path", Properties()).use { connection ->
            connection.createStatement().use {
                val totp = if (missingTotp) "" else ", totp TEXT"
                it.execute("CREATE TABLE authme (username TEXT, password TEXT$totp, email TEXT)")
            }
            val values = if (missingTotp) "?, ?, 'private_email@example.invalid'" else "?, ?, ?, 'private_email@example.invalid'"
            connection.prepareStatement("INSERT INTO authme VALUES ($values)").use {
                for (row in rows) {
                    it.setString(1, row.name)
                    it.setString(2, row.hash)
                    if (!missingTotp) it.setString(3, row.totp)
                    it.executeUpdate()
                }
            }
        }
        return path
    }

    private fun driver(): Driver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as Driver

    companion object {
        private const val SHA_PASSWORD = "\$SHA\$11aa0706173d7272\$dbba96681c2ae4e0bfdf226d70fbbc5e4ee3d8071faa613bc533fe8a64817d10"
    }
}
