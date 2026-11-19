package moe.ryuu.kyokouauth.core.authme

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.storage.Dialect
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthMeSourceSelectionTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `copied localhost source requires explicit confirmation before it can be selected`() {
        val source = AuthMeSourceConfig(Dialect.MYSQL, "jdbc:mysql://localhost/authme?sslMode=VERIFY_IDENTITY")

        val error = assertFailsWith<AuthMeLocalSourceConfirmationRequired> {
            AuthMeSourceSelection().select(source)
        }
        assertEquals("AuthMe local source requires explicit confirmation", error.message)
        assertEquals(null, error.cause)
        val selected = AuthMeSourceSelection(localSourceConfirmed = true).select(source)
        assertEquals(source, selected.source)
        assertEquals(AuthMeSourceSummary(Dialect.MYSQL, "localhost", 3306, "authme"), selected.summary)
    }

    @Test
    fun `local aliases IPv4 range IPv6 and mapped loopback all require explicit confirmation`() {
        for (host in listOf(
            "localhost", "LOCALHOST", "localhost.", "LOCALHOST.", "db.localhost", "db.LOCALHOST.",
            "127.0.0.1", "127.0.0.2", "127.255.255.255", "127.10.20.30",
            "[::1]", "[0:0:0:0:0:0:0:1]", "[::ffff:127.0.0.1]", "[::FFFF:127.255.0.2]", "[::ffff:7f00:1]",
        )) {
            for (dialect in listOf(Dialect.MYSQL, Dialect.MARIADB)) {
                val source = remote(dialect, host)
                val error = assertFailsWith<AuthMeLocalSourceConfirmationRequired> { AuthMeSourceSelection().select(source) }
                assertEquals("AuthMe local source requires explicit confirmation", error.message)
                assertEquals(null, error.cause)
                val selected = AuthMeSourceSelection(localSourceConfirmed = true).select(source)
                assertEquals(source.location, selected.source.location)
                assertEquals(host.removePrefix("[").removeSuffix("]"), selected.summary.host)
            }
        }
    }

    @Test
    fun `server overrides retain credentials mapped columns charset and every original safe URL option`() {
        for (dialect in listOf(Dialect.MYSQL, Dialect.MARIADB)) {
            val protocol = if (dialect == Dialect.MYSQL) "mysql" else "mariadb"
            val tls = if (dialect == Dialect.MYSQL) "VERIFY_IDENTITY" else "verify-full"
            val query = "socketTimeout=9999&sslMode=$tls&allowMultiQueries=false&connectTimeout=17&autoReconnect=false"
            val source = AuthMeSourceConfig(
                dialect, "jdbc:$protocol://localhost:3307/old_authme?$query", "private_user", "private_password",
                table = "old_accounts", nameColumn = "login", passwordColumn = "secret_hash", totpColumn = "mfa", charsetName = "ISO-8859-1",
            )
            val selected = AuthMeSourceSelection(host = "Old-DB.Example.INVALID.", port = 4407).select(source)
            assertEquals("jdbc:$protocol://Old-DB.Example.INVALID.:4407/old_authme?$query", selected.source.location)
            assertEquals(source.username, selected.source.username)
            assertEquals(source.password, selected.source.password)
            assertEquals(source.table, selected.source.table)
            assertEquals(source.nameColumn, selected.source.nameColumn)
            assertEquals(source.passwordColumn, selected.source.passwordColumn)
            assertEquals(source.totpColumn, selected.source.totpColumn)
            assertEquals(source.charsetName, selected.source.charsetName)
            assertEquals(AuthMeSourceSummary(dialect, "Old-DB.Example.INVALID.", 4407, "old_authme"), selected.summary)
            for (secret in listOf("private_user", "private_password", "secret_hash", "ISO-8859-1", "jdbc:", "sslMode", "socketTimeout")) {
                assertFalse(selected.summary.toString().contains(secret))
            }
            assertEquals("localhost", AuthMeSourceSelection(localSourceConfirmed = true).select(source).summary.host)
        }
    }

    @Test
    fun `host only port only and unchanged selection preserve the original TLS hostname and options`() {
        val source = remote(host = "DB.Example.INVALID.")
        assertSame(source, AuthMeSourceSelection().select(source).source)
        val portOnly = AuthMeSourceSelection(port = 4444).select(source)
        assertEquals(source.location.replace(":3307/", ":4444/"), portOnly.source.location)
        assertEquals("DB.Example.INVALID.", portOnly.summary.host)
        val hostOnly = AuthMeSourceSelection(host = "snapshot.example.invalid").select(source)
        assertEquals(source.location.replace("DB.Example.INVALID.", "snapshot.example.invalid"), hostOnly.source.location)
        assertEquals(3307, hostOnly.summary.port)
        val defaultPort = AuthMeSourceConfig(Dialect.MYSQL, "jdbc:mysql://old.example.invalid/legacy?sslMode=VERIFY_IDENTITY")
        assertEquals(3306, AuthMeSourceSelection().select(defaultPort).summary.port)
        assertEquals("jdbc:mysql://new.example.invalid/legacy?sslMode=VERIFY_IDENTITY", AuthMeSourceSelection(host = "new.example.invalid").select(defaultPort).source.location)
    }

    @Test
    fun `effective local overrides and port only changes still require local confirmation`() {
        val remote = remote()
        for (host in listOf("localhost", "a.LOCALHOST.", "127.20.30.40", "::1", "[::ffff:7f00:1]")) {
            assertFailsWith<AuthMeLocalSourceConfirmationRequired> { AuthMeSourceSelection(host = host).select(remote) }
            val selected = AuthMeSourceSelection(host = host, localSourceConfirmed = true).select(remote)
            assertEquals(host.removePrefix("[").removeSuffix("]"), selected.summary.host)
        }
        assertFailsWith<AuthMeLocalSourceConfirmationRequired> { AuthMeSourceSelection(port = 4407).select(remote(host = "localhost")) }
        assertEquals("old.example.invalid", AuthMeSourceSelection(host = "old.example.invalid").select(remote(host = "localhost")).summary.host)
    }

    @Test
    fun `nonlocal literals and unresolved DNS aliases are selected without lookup or hostname substitution`() {
        for (host in listOf("db", "localhost.example.invalid", "unresolved.example.invalid", "203.0.113.15", "[2001:db8::1]", "[fd00::abcd]", "[::ffff:192.0.2.1]")) {
            val source = remote(host = host)
            val selected = AuthMeSourceSelection().select(source)
            assertSame(source, selected.source)
            assertEquals(host.removePrefix("[").removeSuffix("]"), selected.summary.host)
        }
        val source = remote()
        assertEquals("jdbc:mysql://[2001:db8::1]:3307/legacy?sslMode=VERIFY_IDENTITY", AuthMeSourceSelection(host = "2001:db8::1").select(source).source.location)
    }

    @Test
    fun `SQLite selection exposes only its filename and rejects every server override`() {
        val path = directory.resolve("private_parent").resolve("authme.db")
        val source = AuthMeSourceConfig(Dialect.SQLITE, path.toString(), "private_user", "private_password")
        val selected = AuthMeSourceSelection().select(source)
        assertSame(source, selected.source)
        assertEquals(AuthMeSourceSummary(Dialect.SQLITE, null, null, "authme.db"), selected.summary)
        for (secret in listOf("private_parent", "private_user", "private_password", directory.toString(), "jdbc:")) {
            assertFalse(selected.summary.toString().contains(secret))
        }
        for (selection in listOf(
            AuthMeSourceSelection(host = "old.example.invalid"),
            AuthMeSourceSelection(host = ""),
            AuthMeSourceSelection(port = 3306),
            AuthMeSourceSelection(port = 0),
            AuthMeSourceSelection(localSourceConfirmed = true),
        )) assertInvalid { selection.select(source) }
        assertFalse(Files.exists(path))
    }

    @Test
    fun `wildcards malformed hosts ambiguous numeric IPv4 and invalid ports are rejected without secrets`() {
        val source = remote()
        for (host in listOf(
            "", " ", "db.invalid ", "db.invalid/private_password", "user:private_password@db.invalid",
            "jdbc:mysql://db.invalid", "db.invalid:3306", "db.invalid?password=private_password", "db.invalid#private_password",
            "db..invalid", "-db.invalid", "db-.invalid", "db_invalid", "db%2einvalid", ".", "db\nprivate_password",
            "0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0", "::ffff:0.0.0.0",
            "127.1", "2130706433", "0x7f000001", "0X7F000001", "0177.0.0.1", "127.000.0.1", "127.0x0.0.1",
            "0x7f.0.0.1", "4294967296", "256.0.0.1", "1.2.3.4.", "1.2.3.4.5",
            "1::2::3", "::gg", "1:2:3", "1:2:3:4:5:6:7:8:9", "[::1", "[[::1]]", "[db.invalid]",
            "fe80::1%eth0", "[fe80::1%25lo0]", "::ffff:127.1", "::ffff:0177.0.0.1", "a".repeat(254), "a".repeat(64) + ".invalid",
        )) {
            assertInvalid { AuthMeSourceSelection(host = host, localSourceConfirmed = true).select(source) }
        }
        for (port in listOf(-1, 0, 65536, Int.MAX_VALUE)) {
            assertInvalid { AuthMeSourceSelection(port = port).select(source) }
        }
        for (secret in listOf("private_password", "private_user", "db.invalid")) {
            assertFalse(AuthMeSourceSelection(host = "user:private_password@db.invalid", port = 1234).toString().contains(secret))
        }
    }

    @Test
    fun `unsafe original URLs cannot be laundered through otherwise valid endpoint overrides`() {
        val good = "jdbc:mysql://old.example.invalid:3306/legacy?sslMode=VERIFY_IDENTITY"
        for (url in listOf(
            good.replace("jdbc:mysql://", "jdbc:postgresql://"),
            good.replace("old.example.invalid", "private_user:private_password@old.example.invalid"),
            good.replace("old.example.invalid", "old.example.invalid,other.invalid"),
            good.replace("old.example.invalid", "0.0.0.0"),
            good.replace("old.example.invalid", "2130706433"),
            good.replace(":3306/", ":0/"), good.replace(":3306/", ":65536/"), good.replace(":3306/", ":/"),
            good.replace("/legacy?", "/?"), good.replace("/legacy?", "/legacy/other?"), good.replace("/legacy?", "/legacy%2Fother?"),
            good.substringBefore('?'), good.replace("VERIFY_IDENTITY", "PREFERRED"), good.replace("sslMode", "sslmode"),
            "$good&sslMode=DISABLED", "$good&connectTimeout=0", "$good&socketTimeout=10001", "$good&autoReconnect=true",
            "$good&allowMultiQueries=true", "$good&password=private_password", "$good&sessionVariables=private_password",
            "$good#private_password", "$good&",
        )) {
            val source = AuthMeSourceConfig(Dialect.MYSQL, url, "private_user", "private_password")
            assertInvalid { AuthMeSourceSelection().select(source) }
            assertInvalid { AuthMeSourceSelection(host = "fixed.example.invalid", port = 4407, localSourceConfirmed = true).select(source) }
        }
        val wrongMariaTls = AuthMeSourceConfig(Dialect.MARIADB, "jdbc:mariadb://old.example.invalid/legacy?sslMode=VERIFY_IDENTITY")
        assertInvalid { AuthMeSourceSelection().select(wrongMariaTls) }
    }

    @Test
    fun `configured MySQL remains authoritative despite leftover SQLite and a failed server driver`() {
        val sqlite = directory.resolve("authme.db")
        val bytes = "private leftover database must not be opened".toByteArray()
        Files.write(sqlite, bytes)
        val configFile = directory.resolve("config.yml")
        Files.writeString(configFile, "DataSource:\n  backend: MYSQL\n  mySQLHost: localhost\n  mySQLDatabase: authme\n")
        val source = AuthMeConfigReader.read(configFile)
        assertEquals(Dialect.MYSQL, source.dialect)
        assertTrue(source.location.startsWith("jdbc:mysql://"))
        assertFailsWith<AuthMeLocalSourceConfirmationRequired> { AuthMeSourceSelection().select(source) }
        val selected = AuthMeSourceSelection(host = "old.example.invalid").select(source)
        val attempted = mutableListOf<String>()
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.mysql.cj.jdbc.Driver" || name == "org.sqlite.JDBC") {
                    attempted.add(name)
                    throw ClassNotFoundException("private_password")
                }
                return super.loadClass(name, resolve)
            }
        }
        AuthMeLegacyVerifier(selected.source, loader).use {
            assertEquals(AuthMeVerification.UNAVAILABLE, it.verify(GameName.parseOrThrow("Alice"), "password".toCharArray()))
        }
        assertEquals(listOf("com.mysql.cj.jdbc.Driver"), attempted)
        assertContentEquals(bytes, Files.readAllBytes(sqlite))
        assertEquals(Dialect.MYSQL, AuthMeConfigReader.read(configFile).dialect)
    }

    private fun assertInvalid(action: () -> Unit) {
        val error = assertFailsWith<IllegalArgumentException>(block = action)
        assertFalse(error is AuthMeLocalSourceConfirmationRequired)
        assertEquals("Invalid AuthMe source selection", error.message)
        assertEquals(null, error.cause)
        for (secret in listOf("private_password", "private_user", "jdbc:", "sslMode")) {
            assertFalse(error.stackTraceToString().contains(secret))
        }
    }

    private fun remote(dialect: Dialect = Dialect.MYSQL, host: String = "old.example.invalid"): AuthMeSourceConfig {
        val protocol = if (dialect == Dialect.MYSQL) "mysql" else "mariadb"
        val tls = if (dialect == Dialect.MYSQL) "VERIFY_IDENTITY" else "verify-full"
        return AuthMeSourceConfig(dialect, "jdbc:$protocol://$host:3307/legacy?sslMode=$tls", "private_user", "private_password")
    }
}
