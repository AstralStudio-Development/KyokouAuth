package moe.ryuu.kyokouauth.core.authme

import moe.ryuu.kyokouauth.core.storage.Dialect
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthMeConfigReaderTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `official SQLite fields resolve database name against the old AuthMe directory`() {
        val configFile = directory.resolve("config.yml")
        Files.writeString(configFile, """
            DataSource:
              backend: SQLITE
              mySQLDatabase: legacy
              mySQLTablename: players
              mySQLColumnName: login
              mySQLColumnPassword: secret_hash
              mySQLtotpKey: mfa
            settings:
              security:
                passwordHash: SHA256
                passwordCharset: ISO-8859-1
        """.trimIndent())

        val source = AuthMeConfigReader.read(configFile)

        assertEquals(Dialect.SQLITE, source.dialect)
        assertEquals(directory.resolve("legacy.db").toString(), source.location)
        assertEquals("players", source.table)
        assertEquals("login", source.nameColumn)
        assertEquals("secret_hash", source.passwordColumn)
        assertEquals("mfa", source.totpColumn)
        assertEquals("ISO-8859-1", source.charsetName)
    }

    @Test
    fun `backend must be explicit even when an old SQLite database is present`() {
        val configFile = directory.resolve("defaults.yml")
        Files.writeString(directory.resolve("authme_default.db"), "old database must not select the backend")
        for (yaml in listOf(
            "DataSource:\n  mySQLDatabase: authme_default\n",
            "settings: {}",
            "DataSource: {backend: null}",
            "DataSource: {backend: ''}",
            "DataSource: {backend: UNKNOWN}",
        )) {
            Files.writeString(configFile, yaml)
            assertSanitized { AuthMeConfigReader.read(configFile) }
        }
    }

    @Test
    fun `server backends replace weak legacy transport with identity verified TLS and finite timeouts`() {
        for ((backend, protocol, tls) in listOf(
            Triple("MYSQL", "mysql", "VERIFY_IDENTITY"),
            Triple("MARIADB", "mariadb", "verify-full"),
        )) {
            val configFile = directory.resolve("$backend.yml")
            Files.writeString(configFile, """
                DataSource:
                  backend: $backend
                  mySQLHost: db.example.invalid
                  mySQLPort: '3307'
                  mySQLDatabase: old_authme
                  mySQLUsername: legacy_user
                  mySQLPassword: private_old_password
                  mySQLUseSSL: false
            """.trimIndent())

            val source = AuthMeConfigReader.read(configFile)

            assertEquals(Dialect.valueOf(backend), source.dialect)
            assertEquals("jdbc:$protocol://db.example.invalid:3307/old_authme?sslMode=$tls&connectTimeout=5000&socketTimeout=5000&allowMultiQueries=false&autoReconnect=false", source.location)
            assertEquals("legacy_user", source.username)
            assertEquals("private_old_password", source.password)
            assertEquals("totp", source.totpColumn)
            assertEquals("UTF-8", source.charsetName)
            assertFalse(source.location.contains("private_old_password"))
            assertFalse(source.toString().contains("legacy_user"))
            assertFalse(source.toString().contains("private_old_password"))
            assertFalse(source.toString().contains(source.location))
        }
    }

    @Test
    fun `read only credential overrides preserve null defaults and explicit empty passwords`() {
        val configFile = directory.resolve("credentials.yml")
        Files.writeString(configFile, """
            DataSource:
              backend: MYSQL
              mySQLPort: 3306
              mySQLUsername: old_user
              mySQLPassword: old_private_password
        """.trimIndent())
        val original = AuthMeConfigReader.read(configFile)
        assertEquals("old_user", original.username)
        assertTrue(original.location.startsWith("jdbc:mysql://127.0.0.1:3306/authme?"))
        assertEquals("old_private_password", original.password)
        val readOnly = AuthMeConfigReader.read(configFile, "reader", "")
        assertEquals("reader", readOnly.username)
        assertEquals("", readOnly.password)
        assertEquals("old_private_password", AuthMeConfigReader.read(configFile, "reader").password)
    }

    @Test
    fun `configuration only accepts bounded regular existing files and never creates a database`() {
        val missing = directory.resolve("missing.yml")
        assertSanitized { AuthMeConfigReader.read(missing) }
        assertFalse(Files.exists(missing))
        assertSanitized { AuthMeConfigReader.read(directory) }
        val configFile = directory.resolve("config.yml")
        Files.writeString(configFile, "DataSource:\n  backend: SQLITE\n  mySQLDatabase: authme\n")
        val bytes = Files.readAllBytes(configFile)
        val modified = Files.getLastModifiedTime(configFile)
        val files = directory.toFile().list().orEmpty().toSet()
        AuthMeConfigReader.read(configFile)
        assertTrue(bytes.contentEquals(Files.readAllBytes(configFile)))
        assertEquals(modified, Files.getLastModifiedTime(configFile))
        assertEquals(files, directory.toFile().list().orEmpty().toSet())
        assertFalse(Files.exists(directory.resolve("authme.db")))
        Files.writeString(configFile, "#" + "x".repeat(1_048_577))
        assertSanitized { AuthMeConfigReader.read(configFile) }
    }

    @Test
    fun `unsafe YAML construction aliases duplicate keys and nesting are safely refused`() {
        val configFile = directory.resolve("unsafe.yml")
        for (yaml in listOf(
            "!!java.net.URL [https://secret_password.invalid]",
            "DataSource: &source {backend: SQLITE}\ncopy: *source",
            "DataSource: {backend: SQLITE, backend: MYSQL}",
            "settings: " + "[".repeat(50) + "secret_password" + "]".repeat(50),
            "DataSource: [secret_password]",
            "[secret_password]",
            "DataSource:\n  backend: [secret_password",
        )) {
            Files.writeString(configFile, yaml)
            assertSanitized { AuthMeConfigReader.read(configFile) }
        }
    }

    @Test
    fun `unknown algorithms mappings and remote URL injection cannot leak untrusted configuration`() {
        val configFile = directory.resolve("invalid.yml")
        for (yaml in listOf(
            "DataSource: {backend: POSTGRESQL}",
            "DataSource: {backend: secret_password}",
            "DataSource: {backend: SQLITE}\nsettings: {security: {passwordHash: BCRYPT}}",
            "DataSource: {backend: SQLITE}\nsettings: {security: {passwordHash: secret_password}}",
            "DataSource: {backend: SQLITE, mySQLTablename: 'secret_password; DROP TABLE authme'}",
            "DataSource: {backend: SQLITE, mySQLDatabase: '../secret_password'}",
            "DataSource: {backend: SQLITE, mySQLtotpKey: 'secret_password.invalid'}",
            "DataSource: {backend: SQLITE}\nsettings: {security: {passwordCharset: secret_password}}",
            "DataSource: {backend: MYSQL, mySQLHost: 'user:secret_password@host.invalid'}",
            "DataSource: {backend: MYSQL, mySQLHost: 'host.invalid?password=secret_password'}",
            "DataSource: {backend: MYSQL, mySQLDatabase: 'authme?password=secret_password'}",
            "DataSource: {backend: MYSQL, mySQLPort: 0}",
            "DataSource: {backend: MYSQL, mySQLPort: 65536}",
            "DataSource: {backend: MYSQL, mySQLPort: 1.5}",
        )) {
            Files.writeString(configFile, yaml)
            assertSanitized { AuthMeConfigReader.read(configFile) }
        }
    }

    private fun assertSanitized(action: () -> Unit) {
        val error = assertFailsWith<IllegalArgumentException>(block = action)
        assertEquals("Invalid or unsupported AuthMe configuration", error.message)
        assertEquals(null, error.cause)
        assertFalse(error.stackTraceToString().contains("secret_password"))
    }
}
