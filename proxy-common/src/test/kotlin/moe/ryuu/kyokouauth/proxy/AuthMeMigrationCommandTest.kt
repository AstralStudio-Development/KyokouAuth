package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.core.text.RichText
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Optional
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class AuthMeMigrationCommandTest {
    @TempDir lateinit var folder: Path
    private val oldUuid = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")
    private val hash = "\$SHA\$11aa0706173d7272\$dbba96681c2ae4e0bfdf226d70fbbc5e4ee3d8071faa613bc533fe8a64817d10"

    @Test
    fun `empty inbox gives the operator a missing-file action and retains that safe reason in progress`() {
        val config = ProxyConfig(mapOf("database.type" to "h2", "database.database" to folder.resolve("identities").toString(),
            "database.auto-download-driver" to "false"), folder.toFile())
        val messages = MessageCatalogue("en_US", folder.resolve("messages").toFile()).also { it.load() }
        val server = object : ProxyServer {
            override fun getServer(name: String) = Optional.empty<RegisteredServer>()
            override fun getPlayer(name: String) = Optional.empty<Player>()
            override fun getPlayer(uuid: UUID) = Optional.empty<Player>()
            override val allPlayers = emptyList<Player>()
            override val configuration = object : ProxyConfiguration { override val attemptConnectionOrder = emptyList<String>() }
        }
        val replies = LinkedBlockingQueue<String>()
        val source = object : CommandSource {
            override val isConsole = true
            override fun hasPermission(permission: String) = true
            override fun sendMessage(message: RichText.Parsed) { replies.add(message.spans.joinToString("") { it.text }) }
        }
        val logger = ProxyLog { _, _, _ -> }
        fun reply() = assertNotNull(replies.poll(5, TimeUnit.SECONDS))
        AuthService(config, logger, supportsProfileClaims = true).use { auth ->
            val command = KyokouAuthCommand(server, config, messages, auth, LoginGate(server, config, messages, auth, logger)) {}
            command.execute(source, arrayOf("migration", "scan"))
            val first = reply()
            val failure = if (first.contains("Background scan started")) reply() else first
            assertTrue(failure.contains("migration/inbox/AuthMe/config.yml"), failure)
            assertFalse(failure.contains("Migration did not complete"), failure)
            assertEquals(AuthMeImportFailure.MISSING_INBOX_CONFIG, auth.inboxLegacyImportStatus()?.failure)
            command.execute(source, arrayOf("migration", "progress"))
            assertTrue(reply().contains("operation failed"))
            assertTrue(reply().contains("migration/inbox/AuthMe/config.yml"))
            assertFalse(auth.authMeClaimsAvailable())
        }
    }

    @Test
    fun `console migrates a fixed inbox without migration config and claims use only the persistent local snapshot`() {
        val oldServer = folder.resolve("migration/inbox")
        val oldAuthMe = Files.createDirectories(oldServer.resolve("AuthMe"))
        Files.writeString(oldAuthMe.resolve("config.yml"), """
            DataSource:
              backend: SQLITE
              mySQLDatabase: legacy
              mySQLTablename: old_accounts
              mySQLColumnName: login_name
              mySQLColumnPassword: password_hash
              mySQLtotpKey: two_factor
        """.trimIndent())
        val snapshot = oldAuthMe.resolve("legacy.db")
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$snapshot").use { c -> c.createStatement().use { sql ->
            sql.execute("CREATE TABLE old_accounts(login_name TEXT,password_hash TEXT,two_factor TEXT)")
            sql.execute("INSERT INTO old_accounts VALUES ('archivedalice','$hash','')")
        } }
        val playerData = Files.createDirectories(oldServer.resolve("world/playerdata")).resolve("$oldUuid.dat")
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(playerData))).use { out ->
            out.writeByte(10); out.writeUTF("")
            out.writeByte(11); out.writeUTF("UUID"); out.writeInt(4)
            out.writeInt((oldUuid.mostSignificantBits shr 32).toInt()); out.writeInt(oldUuid.mostSignificantBits.toInt())
            out.writeInt((oldUuid.leastSignificantBits shr 32).toInt()); out.writeInt(oldUuid.leastSignificantBits.toInt())
            out.writeByte(10); out.writeUTF("bukkit")
            out.writeByte(8); out.writeUTF("lastKnownName"); out.writeUTF("ArchivedAlice")
            out.writeByte(0); out.writeByte(0)
        }
        val sourceBefore = Files.readAllBytes(snapshot)
        val playerBefore = Files.readAllBytes(playerData)
        val values = mapOf("database.type" to "h2", "database.database" to folder.resolve("identities").toString(),
            "database.auto-download-driver" to "false")
        val config = ProxyConfig(values, folder.toFile())
        assertFalse(config.profileClaimsEnabled)
        assertNull(config.authMeClaims)
        val messages = MessageCatalogue("en_US", folder.resolve("messages").toFile()).also { it.load() }
        val logs = mutableListOf<String>()
        val logger = ProxyLog { _, text, failure -> logs += text + (failure?.toString() ?: "") }
        val server = object : ProxyServer {
            override fun getServer(name: String) = Optional.empty<RegisteredServer>()
            override fun getPlayer(name: String) = Optional.empty<Player>()
            override fun getPlayer(uuid: UUID) = Optional.empty<Player>()
            override val allPlayers = emptyList<Player>()
            override val configuration = object : ProxyConfiguration { override val attemptConnectionOrder = emptyList<String>() }
        }
        val replies = LinkedBlockingQueue<String>()
        fun source(console: Boolean, permitted: Boolean = true) = object : CommandSource {
            override val locale = "en_US"
            override val isConsole = console
            override fun hasPermission(permission: String) = permitted
            override fun sendMessage(message: RichText.Parsed) { replies.add(message.spans.joinToString("") { it.text }) }
        }
        fun reply() = assertNotNull(replies.poll(5, TimeUnit.SECONDS))
        AuthService(config, logger, supportsProfileClaims = true).use { auth ->
            val gate = LoginGate(server, config, messages, auth, logger)
            val command = KyokouAuthCommand(server, config, messages, auth, gate) {}
            command.execute(source(true, false), arrayOf("migration", "scan"))
            assertNull(auth.automaticLegacyImportReport())
            assertTrue(replies.isEmpty())
            command.execute(source(false), arrayOf("migration", "scan"))
            assertTrue(reply().contains("console"))
            assertNull(auth.automaticLegacyImportReport())
            command.execute(source(true), arrayOf("migration", "scan"))
            assertTrue(reply().contains("Background scan started"))
            val summary = reply()
            val instruction = reply()
            assertTrue(summary.contains("ready 1"), summary)
            val preview = assertNotNull(auth.automaticLegacyImportReport())
            assertTrue(instruction.contains("confirm ${preview.token}"), instruction)
            assertNull(auth.profileClaims.byProfile(oldUuid))
            command.execute(source(true), arrayOf("migration", "issues"))
            assertTrue(reply().contains("0 records"))
            command.execute(source(true), arrayOf("migration", "confirm", preview.token))
            reply()
            assertTrue(reply().contains("Registered 1"))
            val record = assertNotNull(auth.profileClaims.byProfile(oldUuid))
            assertEquals("authme-inbox", record.batch)
            assertTrue(config.profileClaimsEnabled)
            assertTrue(auth.authMeClaimsAvailable())
            assertEquals("ArchivedAlice", record.legacyName)
            assertNull(auth.profileClaims.ownerByProfile(oldUuid))
            val newName = GameName.parseOrThrow("NewAlice")
            assertNull(auth.identities.byName(newName))
            val prepared = assertNotNull(auth.proveAuthMeProfile(GameName.parseOrThrow("ArchivedAlice"),
                "password".toCharArray(), UUID.randomUUID(), newName) { true })
            assertEquals(record.id, prepared.recordId)
            assertNull(auth.identities.byName(newName))
        }
        assertContentEquals(sourceBefore, Files.readAllBytes(snapshot))
        assertContentEquals(playerBefore, Files.readAllBytes(playerData))
        Files.delete(snapshot)
        Files.delete(oldAuthMe.resolve("config.yml"))
        val restoredConfig = ProxyConfig(values, folder.toFile())
        assertFalse(restoredConfig.profileClaimsEnabled)
        AuthService(restoredConfig, logger, supportsProfileClaims = true).use { restored ->
            assertTrue(restoredConfig.profileClaimsEnabled)
            assertTrue(restored.authMeClaimsAvailable())
            val prepared = assertNotNull(restored.proveAuthMeProfile(GameName.parseOrThrow("ArchivedAlice"),
                "password".toCharArray(), UUID.randomUUID(), GameName.parseOrThrow("NewAlice")) { true })
            assertEquals(oldUuid, prepared.profileUuid)
            restored.disableInboxLegacyImport().get(5, TimeUnit.SECONDS)
            assertFalse(restoredConfig.profileClaimsEnabled)
            assertFalse(restored.authMeClaimsAvailable())
            assertNotNull(restored.profileClaims.byProfile(oldUuid))
        }
        assertFalse(logs.any { it.contains(hash) || it.contains("password_hash") })
    }
}
