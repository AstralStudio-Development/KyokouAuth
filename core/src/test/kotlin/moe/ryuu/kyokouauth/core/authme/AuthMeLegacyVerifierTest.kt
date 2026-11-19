package moe.ryuu.kyokouauth.core.authme

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.storage.Dialect
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverPropertyInfo
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthMeLegacyVerifierTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `official default SHA vector verifies through a read only snapshot and clears input`() {
        val source = snapshot()
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, source.toString())).use { verifier ->
            val password = "password".toCharArray()
            assertEquals(AuthMeVerification.VERIFIED, verifier.verify(GameName.parseOrThrow("Alice"), password))
            assertContentEquals(CharArray(password.size), password)
        }
    }

    @Test
    fun `password case whitespace and Unicode normalization are never altered`() {
        verifier(snapshot(rows = listOf(Row(hash = SHA_MIXED_CASE)))) { verifier ->
            expect(AuthMeVerification.VERIFIED, verifier, "PassWord1")
            expect(AuthMeVerification.REJECTED, verifier, "password1")
            expect(AuthMeVerification.REJECTED, verifier, "passWord1")
            expect(AuthMeVerification.REJECTED, verifier, " PassWord1")
            expect(AuthMeVerification.REJECTED, verifier, "PassWord1 ")
        }
        verifier(snapshot(rows = listOf(Row(hash = SHA_UNICODE)))) { verifier ->
            expect(AuthMeVerification.VERIFIED, verifier, UNICODE_PASSWORD)
            expect(AuthMeVerification.REJECTED, verifier, "a\u0302\u00cb_3(\u00ed\u00f9*")
        }
    }

    @Test
    fun `upstream non ASCII vector uses only the explicitly selected legacy charset`() {
        val path = snapshot(rows = listOf(Row(hash = SHA_UNICODE)))
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, path.toString(), charsetName = "UTF-8")).use {
            expect(AuthMeVerification.VERIFIED, it, UNICODE_PASSWORD)
        }
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, path.toString(), charsetName = "ISO-8859-1")).use {
            expect(AuthMeVerification.REJECTED, it, UNICODE_PASSWORD)
        }
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, snapshot().toString(), charsetName = "ISO-8859-1")).use {
            expect(AuthMeVerification.VERIFIED, it)
        }
    }

    @Test
    fun `unsupported or malformed hashes are never guessed or accepted by prefix`() {
        val unsupported = listOf(
            null,
            "",
            "5f4dcc3b5aa765d61d8327deb882cf99",
            "\$2a\$10\$not-a-supported-authme-hash",
            SHA_PASSWORD.replace("\$SHA\$", "\$sha\$"),
            SHA_PASSWORD.replace("11aa0706173d7272", "11aa0706173d727"),
            SHA_PASSWORD.uppercase(),
            SHA_PASSWORD + "\u0000trailing-data",
            SHA_PASSWORD + "f".repeat(100_000),
        )
        for (hash in unsupported) {
            verifier(snapshot(rows = listOf(Row(hash = hash)))) { expect(AuthMeVerification.UNSUPPORTED, it) }
        }
    }

    @Test
    fun `any nonempty TOTP including spaces or NUL requires unsupported MFA`() {
        for (totp in listOf("SECRETBASE32", " ", "0", "\u0000", "\u0000secret")) {
            verifier(snapshot(rows = listOf(Row(totp = totp)))) { expect(AuthMeVerification.UNSUPPORTED, it) }
        }
        verifier(snapshot(rows = listOf(Row(totp = null)))) { expect(AuthMeVerification.VERIFIED, it) }
    }

    @Test
    fun `name keys follow GameName case insensitive semantics and duplicate rows fail closed`() {
        verifier(snapshot(rows = listOf(Row(name = "aLiCe")))) {
            expect(AuthMeVerification.VERIFIED, it, name = "ALIce")
            expect(AuthMeVerification.REJECTED, it, name = "Nobody")
        }
        verifier(snapshot(rows = listOf(Row(), Row(name = "ALICE"), Row(name = "AlIcE")))) {
            expect(AuthMeVerification.UNSUPPORTED, it)
        }
    }

    @Test
    fun `mapped identifiers use only the configured table and required columns`() {
        val path = snapshot(table = "legacy", nameColumn = "login", passwordColumn = "secret_hash", totpColumn = "mfa")
        AuthMeLegacyVerifier(AuthMeSourceConfig(
            Dialect.SQLITE, path.toString(), table = "legacy", nameColumn = "login", passwordColumn = "secret_hash", totpColumn = "mfa",
        )).use { expect(AuthMeVerification.VERIFIED, it) }
    }

    @Test
    fun `a missing TOTP password or account table and a corrupt file are unavailable not missing accounts`() {
        for (path in listOf(snapshot(totpColumn = null), snapshot(passwordColumn = "different"), snapshot(table = "different"))) {
            verifier(path) { expect(AuthMeVerification.UNAVAILABLE, it) }
        }
        val corrupt = directory.resolve("corrupt.db")
        Files.writeString(corrupt, "not a SQLite database")
        verifier(corrupt) { expect(AuthMeVerification.UNAVAILABLE, it) }
    }

    @Test
    fun `missing relative directory URI and sidecar paths cannot be opened or created`() {
        val missing = directory.resolve("does-not-exist.db")
        for (location in listOf(missing.toString(), "relative-authme.db", directory.toString(), "jdbc:sqlite:$missing", missing.toUri().toString())) {
            AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, location)).use {
                expect(AuthMeVerification.UNAVAILABLE, it)
            }
        }
        assertFalse(Files.exists(missing))
        assertTrue(directory.toFile().listFiles().orEmpty().isEmpty())
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val path = snapshot()
            val sidecar = path.resolveSibling(path.fileName.toString() + suffix)
            Files.write(sidecar, byteArrayOf(1, 2, 3))
            val before = Files.readAllBytes(path)
            verifier(path) { expect(AuthMeVerification.UNAVAILABLE, it) }
            assertContentEquals(before, Files.readAllBytes(path))
            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(sidecar))
            val namedSidecar = directory.resolve("snapshot-${sequence.incrementAndGet()}$suffix")
            Files.copy(path, namedSidecar)
            verifier(namedSidecar) { expect(AuthMeVerification.UNAVAILABLE, it) }
        }
    }

    @Test
    fun `verification changes neither source bytes modification time nor directory contents`() {
        val path = snapshot()
        val bytes = Files.readAllBytes(path)
        val modified = Files.getLastModifiedTime(path)
        val files = directory.toFile().list().orEmpty().toSet()
        verifier(path) {
            expect(AuthMeVerification.VERIFIED, it)
            expect(AuthMeVerification.REJECTED, it, "wrong")
            expect(AuthMeVerification.REJECTED, it, name = "Nobody")
        }
        assertContentEquals(bytes, Files.readAllBytes(path))
        assertEquals(modified, Files.getLastModifiedTime(path))
        assertEquals(files, directory.toFile().list().orEmpty().toSet())
    }

    @Test
    fun `closed verifier is idempotent and always erases subsequent input`() {
        val verifier = AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, snapshot().toString()))
        expect(AuthMeVerification.VERIFIED, verifier)
        verifier.close()
        verifier.close()
        expect(AuthMeVerification.UNAVAILABLE, verifier)
    }

    @Test
    fun `empty or oversized passwords and isolated Bedrock names are rejected and erased`() {
        verifier(snapshot()) {
            expect(AuthMeVerification.REJECTED, it, "")
            expect(AuthMeVerification.REJECTED, it, "x".repeat(1025))
            val password = "password".toCharArray()
            assertEquals(AuthMeVerification.REJECTED, it.verify(GameName.forBedrock(".Alice"), password))
            assertContentEquals(CharArray(password.size), password)
        }
    }

    @Test
    fun `all identifiers dialects and charset errors are sanitized`() {
        val secret = "do_not_expose_this"
        val badIdentifiers = listOf("", "authme.$secret", "authme;--$secret", "`$secret`", "$secret ", "x".repeat(65))
        for (identifier in badIdentifiers) {
            for (column in 0..3) {
                val error = assertFailsWith<IllegalArgumentException> {
                    AuthMeSourceConfig(
                        Dialect.SQLITE, directory.resolve("$secret.db").toString(),
                        username = secret, password = secret,
                        table = if (column == 0) identifier else "authme",
                        nameColumn = if (column == 1) identifier else "username",
                        passwordColumn = if (column == 2) identifier else "password",
                        totpColumn = if (column == 3) identifier else "totp",
                    )
                }
                assertFalse(error.stackTraceToString().contains(secret))
                assertEquals(null, error.cause)
            }
        }
        assertFailsWith<IllegalArgumentException> { AuthMeSourceConfig(Dialect.H2, "unused") }
        val error = assertFailsWith<IllegalArgumentException> {
            AuthMeSourceConfig(Dialect.SQLITE, "unused", charsetName = "$secret-")
        }
        assertFalse(error.stackTraceToString().contains(secret))
        assertEquals(null, error.cause)
    }

    @Test
    fun `driver failures configuration and verifier rendering do not reveal secrets`() {
        val secret = "private-password-and-account"
        val config = AuthMeSourceConfig(Dialect.SQLITE, snapshot().toString(), username = secret, password = secret)
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "org.sqlite.JDBC") throw ClassNotFoundException("$secret $SHA_PASSWORD ${config.location}")
                return super.loadClass(name, resolve)
            }
        }
        AuthMeLegacyVerifier(config, loader).use {
            expect(AuthMeVerification.UNAVAILABLE, it)
            for (text in listOf(config.toString(), it.toString())) {
                assertFalse(text.contains(secret))
                assertFalse(text.contains(config.location))
                assertFalse(text.contains(SHA_PASSWORD))
            }
        }
        val url = "jdbc:mysql://$secret:$secret@example.invalid/secret?sslMode=VERIFY_IDENTITY"
        val remote = AuthMeSourceConfig(Dialect.MYSQL, url, secret, secret)
        assertFalse(remote.toString().contains(secret))
        assertFalse(remote.toString().contains(url))
    }

    @Test
    fun `server source URLs require exact TLS and safe bounded transport options before driver loading`() {
        val valid = listOf(
            Dialect.MYSQL to "jdbc:mysql://example.invalid/authme?sslMode=VERIFY_IDENTITY",
            Dialect.MYSQL to "jdbc:mysql://localhost:3306/authme?sslMode=VERIFY_IDENTITY&connectTimeout=1&socketTimeout=10000&allowMultiQueries=false&autoReconnect=false",
            Dialect.MARIADB to "jdbc:mariadb://example.invalid/authme?sslMode=verify-full&connectTimeout=5000&socketTimeout=5000",
        )
        for ((dialect, url) in valid) assertDriverAttempt(dialect, url, expected = 1)
        val mysql = "jdbc:mysql://example.invalid/authme?sslMode=VERIFY_IDENTITY"
        val invalid = listOf(
            "jdbc:postgresql://example.invalid/authme?sslMode=VERIFY_IDENTITY",
            "jdbc:mysql:loadbalance://example.invalid/authme?sslMode=VERIFY_IDENTITY",
            "jdbc:mysql://example.invalid,other.invalid/authme?sslMode=VERIFY_IDENTITY",
            "jdbc:mysql://username:password@example.invalid/authme?sslMode=VERIFY_IDENTITY",
            "jdbc:mysql://example.invalid/?sslMode=VERIFY_IDENTITY",
            "jdbc:mysql://example.invalid/authme%2Fother?sslMode=VERIFY_IDENTITY",
            "jdbc:mysql://example.invalid/authme",
            mysql.replace("VERIFY_IDENTITY", "PREFERRED"),
            mysql.replace("sslMode", "SSLMODE"),
            "$mysql&sslMode=DISABLED",
            "$mysql&connectTimeout=0",
            "$mysql&connectTimeout=10001",
            "$mysql&socketTimeout=0",
            "$mysql&socketTimeout=9999999999999999",
            "$mysql&allowMultiQueries=true",
            "$mysql&autoReconnect=true",
            "$mysql&sessionVariables=anything",
            "$mysql&user=secret",
            "$mysql&%73slMode=DISABLED",
            "$mysql#sslMode=DISABLED",
        )
        for (url in invalid) assertDriverAttempt(Dialect.MYSQL, url, expected = 0)
        assertDriverAttempt(Dialect.MARIADB, "jdbc:mariadb://example.invalid/authme?sslMode=trust", expected = 0)
    }

    @Test
    fun `server verification enforces read only transaction server deadline and bounded parameterized SELECT`() {
        for (dialect in listOf(Dialect.MYSQL, Dialect.MARIADB)) {
            val events = mutableListOf<String>()
            val tls = if (dialect == Dialect.MYSQL) "VERIFY_IDENTITY" else "verify-full"
            val protocol = if (dialect == Dialect.MYSQL) "mysql" else "mariadb"
            val driverName = if (dialect == Dialect.MYSQL) "com.mysql.cj.jdbc.Driver" else "org.mariadb.jdbc.Driver"
            val config = AuthMeSourceConfig(dialect, "jdbc:$protocol://example.invalid/authme?sslMode=$tls", "select_only", "private")
            val loader = ProbeLoader(driverName) { url, properties ->
                assertEquals(config.location, url)
                assertEquals("select_only", properties.getProperty("user"))
                assertEquals("private", properties.getProperty("password"))
                assertEquals("5000", properties.getProperty("connectTimeout"))
                assertEquals("5000", properties.getProperty("socketTimeout"))
                assertEquals("false", properties.getProperty("allowMultiQueries"))
                assertEquals("false", properties.getProperty("autoReconnect"))
                assertEquals(tls, properties.getProperty("sslMode"))
                serverConnection(events)
            }
            AuthMeLegacyVerifier(config, loader).use { expect(AuthMeVerification.VERIFIED, it) }
            val deadline = if (dialect == Dialect.MYSQL) "SET SESSION max_execution_time=5000" else "SET SESSION max_statement_time=5"
            assertEquals(listOf("READ_ONLY", deadline, "AUTOCOMMIT_OFF", "START TRANSACTION READ ONLY", "SELECT", "ROLLBACK", "CLOSE"), events)
        }
    }

    @Test
    fun `JDBC read failure stays unavailable while rollback and close are performed`() {
        val events = mutableListOf<String>()
        val config = AuthMeSourceConfig(Dialect.MYSQL, "jdbc:mysql://example.invalid/authme?sslMode=VERIFY_IDENTITY")
        val loader = ProbeLoader("com.mysql.cj.jdbc.Driver") { _, _ -> serverConnection(events, failSelect = true) }
        AuthMeLegacyVerifier(config, loader).use { expect(AuthMeVerification.UNAVAILABLE, it) }
        assertEquals(listOf("ROLLBACK", "CLOSE"), events.takeLast(2))
    }

    @Test
    fun `explicit SQLite driver receives immutable read only URI flags with finite busy timeout`() {
        val path = snapshot()
        val driver = sqliteDriver()
        val connects = AtomicInteger()
        val loader = ProbeLoader("org.sqlite.JDBC") { url, properties ->
            connects.incrementAndGet()
            assertTrue(url.startsWith("jdbc:sqlite:file:"))
            assertTrue(url.endsWith("?mode=ro&immutable=1"))
            assertEquals("65", properties.getProperty("open_mode"))
            assertEquals("5000", properties.getProperty("busy_timeout"))
            driver.connect(url, properties)
        }
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, path.toString()), loader).use {
            expect(AuthMeVerification.VERIFIED, it)
            expect(AuthMeVerification.VERIFIED, it)
        }
        assertEquals(2, connects.get(), "source connections and successful verifications must not be cached")
    }

    @Test
    fun `at most four source reads run while the fifth fails immediately and erases input`() {
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val verifier = AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, snapshot().toString()), blockingLoader(entered, release))
        val workers = Executors.newFixedThreadPool(5)
        val passwords = List(4) { "password".toCharArray() }
        try {
            val tasks = passwords.map { input ->
                workers.submit<AuthMeVerification> { verifier.verify(GameName.parseOrThrow("Alice"), input) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val fifthInput = "password".toCharArray()
            val fifth = workers.submit<AuthMeVerification> { verifier.verify(GameName.parseOrThrow("Alice"), fifthInput) }
            assertEquals(AuthMeVerification.UNAVAILABLE, fifth.get(1, TimeUnit.SECONDS))
            assertContentEquals(CharArray(fifthInput.size), fifthInput)
            assertScanUnavailable(verifier)
            release.countDown()
            tasks.forEach { assertEquals(AuthMeVerification.VERIFIED, it.get(10, TimeUnit.SECONDS)) }
            passwords.forEach { assertContentEquals(CharArray(it.size), it) }
            expect(AuthMeVerification.VERIFIED, verifier)
        } finally {
            release.countDown()
            verifier.close()
            workers.shutdownNow()
        }
    }

    @Test
    fun `closing an active read prevents its successful password from becoming verified`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val verifier = AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, snapshot().toString()), blockingLoader(entered, release))
        val worker = Executors.newSingleThreadExecutor()
        val input = "password".toCharArray()
        try {
            val task = worker.submit<AuthMeVerification> { verifier.verify(GameName.parseOrThrow("Alice"), input) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            verifier.close()
            release.countDown()
            assertEquals(AuthMeVerification.UNAVAILABLE, task.get(10, TimeUnit.SECONDS))
            assertContentEquals(CharArray(input.size), input)
        } finally {
            release.countDown()
            verifier.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `official SQLite configuration can inspect and verify the mapped unchanged snapshot`() {
        val source = snapshot(table = "old_accounts", nameColumn = "login", passwordColumn = "secret_hash", totpColumn = "mfa")
        val configFile = directory.resolve("old-config.yml")
        Files.writeString(configFile, """
            DataSource:
              backend: SQLITE
              mySQLDatabase: ${source.fileName.toString().removeSuffix(".db")}
              mySQLTablename: old_accounts
              mySQLColumnName: login
              mySQLColumnPassword: secret_hash
              mySQLtotpKey: mfa
            settings:
              security:
                passwordHash: SHA256
        """.trimIndent())
        val sourceBytes = Files.readAllBytes(source)
        val sourceModified = Files.getLastModifiedTime(source)
        val configBytes = Files.readAllBytes(configFile)
        val files = directory.toFile().list().orEmpty().toSet()
        AuthMeLegacyVerifier(AuthMeConfigReader.read(configFile)).use {
            assertEquals(listOf(AuthMeAccount("alice", null)), it.inspectAccounts())
            expect(AuthMeVerification.VERIFIED, it)
            expect(AuthMeVerification.REJECTED, it, "wrong")
        }
        assertContentEquals(sourceBytes, Files.readAllBytes(source))
        assertEquals(sourceModified, Files.getLastModifiedTime(source))
        assertContentEquals(configBytes, Files.readAllBytes(configFile))
        assertEquals(files, directory.toFile().list().orEmpty().toSet())
    }

    @Test
    fun `closing an active inspection prevents account results from escaping`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val verifier = AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, snapshot().toString()), blockingLoader(entered, release))
        val worker = Executors.newSingleThreadExecutor()
        try {
            val task = worker.submit<List<AuthMeAccount>> { verifier.inspectAccounts() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            verifier.close()
            release.countDown()
            val failure = assertFailsWith<ExecutionException> { task.get(10, TimeUnit.SECONDS) }
            assertTrue(failure.cause is IllegalStateException)
            assertEquals("Unable to inspect AuthMe source", failure.cause?.message)
            assertEquals(null, failure.cause?.cause)
        } finally {
            release.countDown()
            verifier.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `account inspection returns candidates without legacy password or TOTP material`() {
        val source = snapshot(rows = listOf(Row(name = "Alice"), Row(name = "BoB")))
        verifier(source) {
            assertEquals(
                listOf(AuthMeAccount("Alice", null), AuthMeAccount("BoB", null)),
                it.inspectAccounts(),
            )
        }
    }

    @Test
    fun `account inspection classifies invalid names unknown hashes and any enabled TOTP`() {
        val source = snapshot(rows = listOf(
            Row(name = "Valid"),
            Row(name = "Unknown", hash = "legacy_private_hash"),
            Row(name = "NullHash", hash = null),
            Row(name = "Oversized", hash = SHA_PASSWORD + "f".repeat(100_000)),
            Row(name = "NulHash", hash = SHA_PASSWORD + "\u0000secret"),
            Row(name = "MfaEnabled", totp = "private_totp_secret"),
            Row(name = "MfaSpace", totp = " "),
            Row(name = "MfaNul", totp = "\u0000secret"),
            Row(name = "NoMfa", totp = null),
            Row(name = "bad-name"),
            Row(name = ".Bedrock"),
            Row(name = "x".repeat(100_000)),
            Row(name = "Alice\u0000suffix"),
            Row(name = null),
        ))
        verifier(source) {
            val accounts = it.inspectAccounts()
            assertEquals(listOf(
                null,
                AuthMeAccountIssue.UNSUPPORTED_HASH,
                AuthMeAccountIssue.UNSUPPORTED_HASH,
                AuthMeAccountIssue.UNSUPPORTED_HASH,
                AuthMeAccountIssue.UNSUPPORTED_HASH,
                AuthMeAccountIssue.TOTP_REQUIRED,
                AuthMeAccountIssue.TOTP_REQUIRED,
                AuthMeAccountIssue.TOTP_REQUIRED,
                null,
                AuthMeAccountIssue.INVALID_NAME,
                AuthMeAccountIssue.INVALID_NAME,
                AuthMeAccountIssue.INVALID_NAME,
                AuthMeAccountIssue.INVALID_NAME,
                AuthMeAccountIssue.INVALID_NAME,
            ), accounts.map(AuthMeAccount::issue))
            assertTrue(accounts.all { account -> account.name == null || account.name.length <= 17 })
            assertEquals(List(5) { null }, accounts.drop(9).map(AuthMeAccount::name))
            for (secret in listOf(SHA_PASSWORD, "legacy_private_hash", "private_totp_secret", "\u0000secret")) {
                assertFalse(accounts.toString().contains(secret))
            }
        }
    }

    @Test
    fun `every case normalized duplicate is blocked even when its other issues differ`() {
        verifier(snapshot(rows = listOf(
            Row(name = "Alice"),
            Row(name = "ALICE", totp = "secret"),
            Row(name = "Bob"),
            Row(name = "alice", hash = "unknown"),
        ))) {
            assertEquals(listOf(
                AuthMeAccount("Alice", AuthMeAccountIssue.DUPLICATE_NAME),
                AuthMeAccount("ALICE", AuthMeAccountIssue.DUPLICATE_NAME),
                AuthMeAccount("Bob", null),
                AuthMeAccount("alice", AuthMeAccountIssue.DUPLICATE_NAME),
            ), it.inspectAccounts())
        }
    }

    @Test
    fun `inspection validates exact configured schema even when the source has no accounts`() {
        for (source in listOf(
            snapshot(rows = emptyList(), table = "different"),
            snapshot(rows = emptyList(), nameColumn = "similar_username"),
            snapshot(rows = emptyList(), passwordColumn = "similar_password"),
            snapshot(rows = emptyList(), totpColumn = null),
        )) {
            verifier(source, ::assertScanUnavailable)
        }
        verifier(snapshot(rows = emptyList())) { assertEquals(emptyList(), it.inspectAccounts()) }
        val source = snapshot(table = "old_players", nameColumn = "login", passwordColumn = "hash", totpColumn = "mfa")
        AuthMeLegacyVerifier(AuthMeSourceConfig(
            Dialect.SQLITE, source.toString(), table = "old_players", nameColumn = "login", passwordColumn = "hash", totpColumn = "mfa",
        )).use { assertEquals(listOf(AuthMeAccount("alice", null)), it.inspectAccounts()) }
    }

    @Test
    fun `inspection rejects the entire result above the requested account bound`() {
        verifier(snapshot(rows = listOf(Row(name = "Alice"), Row(name = "Bob")) )) {
            assertEquals(2, it.inspectAccounts(maxAccounts = 2).size)
            val error = assertFailsWith<IllegalStateException> { it.inspectAccounts(maxAccounts = 1) }
            assertEquals("Unable to inspect AuthMe source", error.message)
            assertEquals(null, error.cause)
            for (limit in listOf(-1, 0, 100_001, Int.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> { it.inspectAccounts(limit) }
            }
            assertEquals(2, it.inspectAccounts().size)
        }
    }

    @Test
    fun `inspection preserves source files and rejects missing corrupt sidecar and closed sources`() {
        val source = snapshot()
        val before = Files.readAllBytes(source)
        val modified = Files.getLastModifiedTime(source)
        val names = directory.toFile().list().orEmpty().toSet()
        verifier(source) {
            assertEquals(1, it.inspectAccounts().size)
            assertEquals(1, it.inspectAccounts().size)
            it.close()
            assertScanUnavailable(it)
        }
        assertContentEquals(before, Files.readAllBytes(source))
        assertEquals(modified, Files.getLastModifiedTime(source))
        assertEquals(names, directory.toFile().list().orEmpty().toSet())
        verifier(directory.resolve("not-created.db"), ::assertScanUnavailable)
        assertEquals(names, directory.toFile().list().orEmpty().toSet())
        val corrupt = directory.resolve("corrupt-scan.db")
        Files.writeString(corrupt, "secret corrupt database data")
        verifier(corrupt, ::assertScanUnavailable)
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val withSidecar = snapshot()
            Files.write(withSidecar.resolveSibling(withSidecar.fileName.toString() + suffix), byteArrayOf(1))
            verifier(withSidecar, ::assertScanUnavailable)
        }
    }

    @Test
    fun `reader generated server sources inspect under the same TLS read only and deadline policy`() {
        for (dialect in listOf(Dialect.MYSQL, Dialect.MARIADB)) {
            val backend = dialect.name
            val driverName = if (dialect == Dialect.MYSQL) "com.mysql.cj.jdbc.Driver" else "org.mariadb.jdbc.Driver"
            val tls = if (dialect == Dialect.MYSQL) "VERIFY_IDENTITY" else "verify-full"
            val configFile = directory.resolve("$backend.yml")
            Files.writeString(configFile, """
                DataSource:
                  backend: $backend
                  mySQLHost: example.invalid
                  mySQLUsername: source_reader
                  mySQLPassword: private_connection_password
            """.trimIndent())
            val config = AuthMeConfigReader.read(configFile)
            val events = mutableListOf<String>()
            val loader = ProbeLoader(driverName) { url, properties ->
                assertEquals(config.location, url)
                assertEquals("source_reader", properties.getProperty("user"))
                assertEquals("private_connection_password", properties.getProperty("password"))
                assertEquals(tls, properties.getProperty("sslMode"))
                assertEquals("5000", properties.getProperty("socketTimeout"))
                assertEquals("5000", properties.getProperty("connectTimeout"))
                assertEquals("false", properties.getProperty("allowMultiQueries"))
                assertEquals("false", properties.getProperty("autoReconnect"))
                serverConnection(events, inspecting = true)
            }
            AuthMeLegacyVerifier(config, loader).use {
                assertEquals(listOf(AuthMeAccount("alice", null)), it.inspectAccounts())
            }
            val deadline = if (dialect == Dialect.MYSQL) "SET SESSION max_execution_time=5000" else "SET SESSION max_statement_time=5"
            assertEquals(listOf("READ_ONLY", deadline, "AUTOCOMMIT_OFF", "START TRANSACTION READ ONLY", "SCHEMA", "SELECT", "ROLLBACK", "CLOSE"), events)
        }
    }

    @Test
    fun `inspection sanitizes driver and SQL failures while still rolling back and closing`() {
        val events = mutableListOf<String>()
        val config = AuthMeSourceConfig(Dialect.MYSQL, "jdbc:mysql://example.invalid/authme?sslMode=VERIFY_IDENTITY")
        val loader = ProbeLoader("com.mysql.cj.jdbc.Driver") { _, _ -> serverConnection(events, failSelect = true, inspecting = true) }
        AuthMeLegacyVerifier(config, loader).use(::assertScanUnavailable)
        assertEquals(listOf("ROLLBACK", "CLOSE"), events.takeLast(2))
        val invalid = AuthMeSourceConfig(Dialect.MYSQL, "jdbc:mysql://secret:secret@example.invalid/authme?sslMode=DISABLED")
        val noConnect = ProbeLoader("com.mysql.cj.jdbc.Driver") { _, _ -> error("Unsafe source must not connect") }
        AuthMeLegacyVerifier(invalid, noConnect).use(::assertScanUnavailable)
    }

    private fun assertScanUnavailable(verifier: AuthMeLegacyVerifier) {
        val error = assertFailsWith<IllegalStateException> { verifier.inspectAccounts() }
        assertEquals("Unable to inspect AuthMe source", error.message)
        assertEquals(null, error.cause)
        assertFalse(error.stackTraceToString().contains("secret"))
        assertFalse(error.stackTraceToString().contains(SHA_PASSWORD))
    }

    private fun serverConnection(
        events: MutableList<String>,
        failSelect: Boolean = false,
        inspecting: Boolean = false,
    ): Connection {
        val metadata = jdbc<ResultSetMetaData> { method, arguments ->
            when (method.name) {
                "getColumnCount" -> 3
                "getColumnName" -> listOf("username", "password", "totp")[(arguments[0] as Int) - 1]
                else -> error("Unexpected metadata operation")
            }
        }
        val schemaResult = jdbc<ResultSet> { method, _ ->
            when (method.name) {
                "next" -> false
                "getMetaData" -> metadata
                "close" -> null
                else -> error("Unexpected schema result operation")
            }
        }
        val schemaStatement = jdbc<PreparedStatement> { method, arguments ->
            when (method.name) {
                "setQueryTimeout" -> { assertEquals(5, arguments[0]); null }
                "setMaxRows" -> { assertEquals(1, arguments[0]); null }
                "executeQuery" -> { events.add("SCHEMA"); schemaResult }
                "close" -> null
                else -> error("Unexpected schema statement operation")
            }
        }
        var row = 0
        val result = jdbc<ResultSet> { method, arguments ->
            when (method.name) {
                "next" -> row++ == 0
                "getString" -> if (arguments[0] == 1) "alice" else SHA_PASSWORD
                "getInt" -> 0
                "wasNull" -> false
                "close" -> null
                else -> error("Unexpected result set operation")
            }
        }
        val prepared = jdbc<PreparedStatement> { method, arguments ->
            when (method.name) {
                "setQueryTimeout" -> { assertEquals(5, arguments[0]); null }
                "setMaxRows" -> { assertEquals(if (inspecting) 100_001 else 2, arguments[0]); null }
                "setString" -> { assertEquals(listOf(1, "alice"), arguments.toList()); null }
                "executeQuery" -> {
                    events.add("SELECT")
                    if (failSelect) throw SQLException("source-password username jdbc:secret hash:secret")
                    result
                }
                "close" -> null
                else -> error("Unexpected prepared statement operation")
            }
        }
        val statement = jdbc<Statement> { method, arguments ->
            when (method.name) {
                "setQueryTimeout" -> { assertEquals(5, arguments[0]); null }
                "execute" -> { events.add(arguments[0] as String); false }
                "close" -> null
                else -> error("Unexpected statement operation")
            }
        }
        return jdbc<Connection> { method, arguments ->
            when (method.name) {
                "setReadOnly" -> { assertEquals(true, arguments[0]); events.add("READ_ONLY"); null }
                "isReadOnly" -> true
                "setAutoCommit" -> { assertEquals(false, arguments[0]); events.add("AUTOCOMMIT_OFF"); null }
                "createStatement" -> statement
                "prepareStatement" -> {
                    val sql = arguments[0] as String
                    assertTrue(sql.startsWith("SELECT "))
                    assertFalse(sql.contains("alice"))
                    if (inspecting && sql.endsWith("WHERE 1=0")) {
                        assertEquals("SELECT `username`, `password`, `totp` FROM `authme` WHERE 1=0", sql)
                        schemaStatement
                    } else {
                        assertTrue(sql.contains("SUBSTR(`username`, 1, 17)"))
                        assertTrue(sql.contains("SUBSTR(`password`, 1, 257)"))
                        assertTrue(sql.contains("INSTR(`totp`, CHAR(0))"))
                        assertTrue(sql.endsWith(if (inspecting) "LIMIT 100001" else "WHERE LOWER(`username`) = ? LIMIT 2"))
                        prepared
                    }
                }
                "rollback" -> { events.add("ROLLBACK"); null }
                "close" -> { events.add("CLOSE"); null }
                else -> error("Unexpected connection operation")
            }
        }
    }

    private inline fun <reified T> jdbc(noinline invoke: (Method, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
            invoke(method, arguments ?: emptyArray())
        } as T

    private fun blockingLoader(entered: CountDownLatch, release: CountDownLatch): ClassLoader {
        val driver = sqliteDriver()
        // Block connect, not class loading: the JVM may serialize loads of the same driver class.
        return ProbeLoader("org.sqlite.JDBC") { url, properties ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            driver.connect(url, properties)
        }
    }

    /** A tiny driver loaded only through the verifier's public ClassLoader seam, never registered. */
    open class ProbeDriver : Driver {
        override fun connect(url: String, info: Properties): Connection? =
            (javaClass.classLoader as ProbeLoader).connect(url, info)

        override fun acceptsURL(url: String): Boolean = true
        override fun getPropertyInfo(url: String, info: Properties): Array<DriverPropertyInfo> = emptyArray()
        override fun getMajorVersion(): Int = 1
        override fun getMinorVersion(): Int = 0
        override fun jdbcCompliant(): Boolean = false
        override fun getParentLogger(): Logger = Logger.getAnonymousLogger()
    }

    class ProbeLoader(
        private val driverName: String,
        val connect: (String, Properties) -> Connection?,
    ) : ClassLoader(AuthMeLegacyVerifierTest::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name != driverName) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                val existing = findLoadedClass(name)
                val type = existing ?: run {
                    val bytes = driverBytes(name)
                    defineClass(name, bytes, 0, bytes.size)
                }
                if (resolve) resolveClass(type)
                return type
            }
        }

        private fun driverBytes(name: String): ByteArray = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                // public class <canonical driver name> extends ProbeDriver { public <init>() { super(); } }
                // Handwritten class metadata avoids compiling Java or pulling a bytecode dependency.
                output.writeInt(0xCAFEBABE.toInt())
                output.writeShort(0)
                output.writeShort(52)
                output.writeShort(10)
                output.writeByte(1); output.writeUTF(name.replace('.', '/')) // #1 UTF8 class name
                output.writeByte(7); output.writeShort(1) // #2 class
                output.writeByte(1); output.writeUTF(ProbeDriver::class.java.name.replace('.', '/')) // #3
                output.writeByte(7); output.writeShort(3) // #4 superclass
                output.writeByte(1); output.writeUTF("<init>") // #5
                output.writeByte(1); output.writeUTF("()V") // #6
                output.writeByte(12); output.writeShort(5); output.writeShort(6) // #7 name and type
                output.writeByte(10); output.writeShort(4); output.writeShort(7) // #8 super constructor
                output.writeByte(1); output.writeUTF("Code") // #9
                output.writeShort(0x21)
                output.writeShort(2)
                output.writeShort(4)
                output.writeShort(0)
                output.writeShort(0)
                output.writeShort(1)
                output.writeShort(1)
                output.writeShort(5)
                output.writeShort(6)
                output.writeShort(1)
                output.writeShort(9)
                output.writeInt(17)
                output.writeShort(1)
                output.writeShort(1)
                output.writeInt(5)
                output.write(byteArrayOf(0x2a, 0xb7.toByte(), 0, 8, 0xb1.toByte()))
                output.writeShort(0)
                output.writeShort(0)
                output.writeShort(0)
            }
            bytes.toByteArray()
        }
    }

    private fun assertDriverAttempt(dialect: Dialect, location: String, expected: Int) {
        val attempts = AtomicInteger()
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.mysql.cj.jdbc.Driver" || name == "org.mariadb.jdbc.Driver") {
                    attempts.incrementAndGet()
                    throw ClassNotFoundException("No real network connection is allowed in this test")
                }
                return super.loadClass(name, resolve)
            }
        }
        AuthMeLegacyVerifier(AuthMeSourceConfig(dialect, location), loader).use {
            expect(AuthMeVerification.UNAVAILABLE, it)
        }
        assertEquals(expected, attempts.get())
    }

    private fun expect(
        expected: AuthMeVerification,
        verifier: AuthMeLegacyVerifier,
        raw: String = "password",
        name: String = "Alice",
    ) {
        val input = raw.toCharArray()
        assertEquals(expected, verifier.verify(GameName.parseOrThrow(name), input))
        assertContentEquals(CharArray(input.size), input)
    }

    private fun verifier(path: Path, action: (AuthMeLegacyVerifier) -> Unit) {
        AuthMeLegacyVerifier(AuthMeSourceConfig(Dialect.SQLITE, path.toString())).use(action)
    }

    private fun sqliteDriver(): Driver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as Driver

    private val sequence = AtomicInteger()

    private class Row(val name: String? = "alice", val hash: String? = SHA_PASSWORD, val totp: String? = "")

    private fun snapshot(
        rows: List<Row> = listOf(Row()),
        table: String = "authme",
        nameColumn: String = "username",
        passwordColumn: String = "password",
        totpColumn: String? = "totp",
    ): Path {
        val path = directory.resolve("authme-${sequence.incrementAndGet()}.db")
        sqliteDriver().connect("jdbc:sqlite:$path", Properties()).use { connection ->
            connection.createStatement().use { statement ->
                val totp = if (totpColumn == null) "" else ", `$totpColumn` TEXT"
                statement.execute("CREATE TABLE `$table` (`$nameColumn` TEXT, `$passwordColumn` TEXT$totp)")
            }
            val placeholders = if (totpColumn == null) "?, ?" else "?, ?, ?"
            connection.prepareStatement("INSERT INTO `$table` VALUES ($placeholders)").use { statement ->
                for (row in rows) {
                    statement.setString(1, row.name)
                    statement.setString(2, row.hash)
                    if (totpColumn != null) statement.setString(3, row.totp)
                    statement.executeUpdate()
                }
            }
        }
        return path
    }

    companion object {
        // AuthMe 5.6.0, commit 107be9ab083ab43b7ff533ad3155f7fe29fb3b0f,
        // src/test/java/fr/xephi/authme/security/crypts/Sha256Test.java (literal upstream fixture).
        private const val SHA_PASSWORD = "\$SHA\$11aa0706173d7272\$dbba96681c2ae4e0bfdf226d70fbbc5e4ee3d8071faa613bc533fe8a64817d10"
        private const val SHA_MIXED_CASE = "\$SHA\$3c72a18a29b08d40\$8e50a7a4f69a80f4893dc921eac84bd74b3f9ebfa22908302c9965eac3aa45e5"
        private const val SHA_UNICODE = "\$SHA\$0b503d90dd9949d4\$ba70c330242e0daa9a154ec9f4cce7f01dd05aff489d37c653e36a507c74d84f"
        private const val UNICODE_PASSWORD = "\u00e2\u00cb_3(\u00ed\u00f9*"
    }
}
