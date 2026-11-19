package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.storage.Dialect
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class AuthMeClaimSettingsTest {
    @TempDir lateinit var folder: Path
    private val enabled = mapOf("migration.enabled" to "true", "migration.authme.enabled" to "true",
        "migration.authme.source-frozen" to "true", "migration.authme.batch" to "cutover-1")

    @Test
    fun `unsupported source dialect algorithms fields and charsets are refused without exposing secrets`() {
        for (change in listOf(mapOf("migration.authme.type" to "h2"), mapOf("migration.authme.hash" to "MD5"),
            mapOf("migration.authme.table" to "authme;DROP TABLE authme"), mapOf("migration.authme.password-charset" to "bad secret charset"),
            mapOf("migration.authme.type" to "mysql", "migration.authme.jdbc-url" to "jdbc:postgresql://db/source"))) {
            val failure = assertFailsWith<IllegalArgumentException> { ProxyConfig(enabled + change, folder.toFile()) }
            assertFalse(failure.toString().contains("bad secret charset"))
        }
        val settings = ProxyConfig(enabled + mapOf("migration.authme.password" to "PRIVATE_SQL_PASSWORD"), folder.toFile()).authMeClaims!!
        assertFalse(settings.toString().contains("PRIVATE_SQL_PASSWORD"))
        assertFalse(settings.source.toString().contains("PRIVATE_SQL_PASSWORD"))
    }

    @Test
    fun `old AuthMe config and server folder enable automatic source discovery`() {
        val authmeConfig = folder.resolve("old/plugins/AuthMe/config.yml")
        java.nio.file.Files.createDirectories(authmeConfig.parent)
        java.nio.file.Files.writeString(authmeConfig, """
            DataSource:
              backend: SQLITE
              mySQLDatabase: authme
              mySQLTablename: authme
              mySQLColumnName: username
              mySQLColumnPassword: password
              mySQLtotpKey: totp
            settings:
              security:
                passwordHash: SHA256
        """.trimIndent())
        val settings = ProxyConfig(enabled + mapOf(
            "migration.authme.config-file" to authmeConfig.toString(),
            "migration.authme.server-folder" to folder.resolve("old").toString()), folder.toFile()).authMeClaims!!
        assertTrue(settings.automaticDiscovery)
        assertEquals(authmeConfig.toAbsolutePath().normalize(), settings.configFile)
        assertEquals(folder.resolve("old").toAbsolutePath().normalize(), settings.serverFolder)
        assertEquals(folder.resolve("old/plugins/AuthMe/authme.db").toAbsolutePath().normalize().toString(), settings.source.location)
    }

    @Test
    fun `server folder alone discovers old config and supplies the automatic batch default`() {
        val old = folder.resolve("old")
        val authmeConfig = old.resolve("plugins/AuthMe/config.yml")
        java.nio.file.Files.createDirectories(authmeConfig.parent)
        java.nio.file.Files.writeString(authmeConfig, """
            DataSource:
              backend: SQLITE
              mySQLDatabase: custom
              mySQLTablename: legacy_accounts
              mySQLColumnName: login_name
              mySQLColumnPassword: old_hash
              mySQLtotpKey: old_mfa
        """.trimIndent())
        val settings = ProxyConfig(enabled - "migration.authme.batch" + mapOf(
            "migration.authme.server-folder" to old.toString()), folder.toFile()).authMeClaims!!
        assertTrue(settings.automaticDiscovery)
        assertEquals("authme", settings.batch)
        assertEquals("legacy_accounts", settings.source.table)
        assertEquals("login_name", settings.source.nameColumn)
        assertEquals("old_hash", settings.source.passwordColumn)
        assertEquals("old_mfa", settings.source.totpColumn)
    }

    @Test
    fun `remote automatic discovery requires explicit read-only credentials but no copied field mappings`() {
        val old = folder.resolve("old")
        val authmeConfig = old.resolve("plugins/AuthMe/config.yml")
        java.nio.file.Files.createDirectories(authmeConfig.parent)
        java.nio.file.Files.writeString(authmeConfig, """
            DataSource:
              backend: MYSQL
              mySQLHost: database.example.org
              mySQLDatabase: legacy
              mySQLUsername: legacy_writer
              mySQLPassword: PRIVATE_WRITER_PASSWORD
              mySQLTablename: accounts
              mySQLColumnName: account_name
        """.trimIndent())
        val values = enabled + mapOf("migration.authme.server-folder" to old.toString())
        assertFailsWith<IllegalArgumentException> { ProxyConfig(values, folder.toFile()) }
        val settings = ProxyConfig(values + mapOf("migration.authme.username" to "migration_reader",
            "migration.authme.password" to "PRIVATE_READER_PASSWORD"), folder.toFile()).authMeClaims!!
        assertEquals(Dialect.MYSQL, settings.source.dialect)
        assertEquals("migration_reader", settings.source.username)
        assertEquals("PRIVATE_READER_PASSWORD", settings.source.password)
        assertEquals("accounts", settings.source.table)
        assertEquals("account_name", settings.source.nameColumn)
        assertTrue(settings.source.location.startsWith("jdbc:mysql://database.example.org:3306/legacy?"))
        assertFalse(settings.toString().contains("PRIVATE_"))
    }

    @Test
    fun `AuthMe private source is opt in requires cutover and does not require any HTTP settings`() {
        assertNull(ProxyConfig(emptyMap(), folder.toFile()).authMeClaims)
        // The parent stop switch must not break already-persisted mappings when child keys remain.
        assertNull(ProxyConfig(enabled - "migration.enabled", folder.toFile()).authMeClaims)
        assertFailsWith<IllegalArgumentException> { ProxyConfig(enabled - "migration.authme.source-frozen", folder.toFile()) }
        assertFailsWith<IllegalArgumentException> { ProxyConfig(enabled - "migration.authme.batch", folder.toFile()) }
        val settings = assertNotNull(ProxyConfig(enabled, folder.toFile()).authMeClaims)
        assertEquals(Dialect.SQLITE, settings.source.dialect)
        assertEquals(folder.resolve("migration/authme.db").toFile().absolutePath, settings.source.location)
        assertEquals("cutover-1", settings.batch)
        assertFalse(settings.toString().contains("authme.db"))
    }
}
