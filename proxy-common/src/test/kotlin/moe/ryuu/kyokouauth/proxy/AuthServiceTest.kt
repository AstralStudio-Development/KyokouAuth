package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.core.text.RichText
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthServiceTest {
    @TempDir lateinit var directory: Path
    private val quiet = ProxyLog { _, _, _ -> }
    private fun config(values: Map<String, String> = emptyMap()) = ProxyConfig(mapOf(
        "database.type" to "h2", "database.database" to directory.resolve("identities").toString(),
        "database.auto-download-driver" to "false") + values, directory.toFile())

    @Test
    fun `real read-only AuthMe proof uses the configured batch and never binds before confirmations`() {
        val snapshot = directory.resolve("legacy.db")
        Class.forName("org.sqlite.JDBC")
        val hash = "\$SHA\$11aa0706173d7272\$dbba96681c2ae4e0bfdf226d70fbbc5e4ee3d8071faa613bc533fe8a64817d10"
        java.sql.DriverManager.getConnection("jdbc:sqlite:$snapshot").use { c -> c.createStatement().use {
            it.execute("CREATE TABLE authme(username TEXT, password TEXT, totp TEXT)")
            it.execute("INSERT INTO authme VALUES ('oldsteve', '$hash', '')")
        } }
        val before = java.nio.file.Files.readAllBytes(snapshot)
        val settings = config(mapOf("migration.enabled" to "true", "migration.authme.enabled" to "true",
            "migration.authme.source-frozen" to "true", "migration.authme.batch" to "cutover-1",
            "migration.authme.sqlite-file" to snapshot.toString()))
        val legacy = moe.ryuu.kyokouauth.core.identity.GameName.parseOrThrow("OldSteve")
        val target = moe.ryuu.kyokouauth.core.identity.GameName.parseOrThrow("NewSteve")
        val old = UUID.fromString("f66564fa-f35f-3ec0-8f67-f28f4ee58a44")
        val session = UUID.randomUUID()
        val logs = mutableListOf<String>()
        AuthService(settings, ProxyLog { _, text, failure -> logs += text + (failure?.toString() ?: "") }, supportsProfileClaims = true).use { service ->
            assertTrue(service.authMeClaimsAvailable())
            val source = service.importLegacyProfile("cutover-1", legacy.value, old)
            val wrong = "PRIVATE_WRONG_PASSWORD_SENTINEL".toCharArray()
            kotlin.test.assertNull(service.proveAuthMeProfile(legacy, wrong, session, target) { true })
            assertTrue(wrong.all { it == '\u0000' })
            val password = "password".toCharArray()
            val prepared = kotlin.test.assertNotNull(service.proveAuthMeProfile(legacy, password, session, target) { true })
            assertTrue(password.all { it == '\u0000' })
            kotlin.test.assertEquals(source.id, prepared.recordId)
            kotlin.test.assertNull(service.profileClaims.ownerByProfile(old))
            kotlin.test.assertNull(service.identities.byName(target))
            kotlin.test.assertEquals(moe.ryuu.kyokouauth.core.storage.LegacyProfile.Status.AVAILABLE, service.profileClaims.byId(source.id)?.status)
            kotlin.test.assertNull(service.proveAuthMeProfile(legacy, "password".toCharArray(), session, target) { false })
            service.close()
            kotlin.test.assertFalse(service.authMeClaimsAvailable())
            val closed = "PRIVATE_CLOSED_PASSWORD_SENTINEL".toCharArray()
            kotlin.test.assertNull(service.proveAuthMeProfile(legacy, closed, session, target) { true })
            assertTrue(closed.all { it == '\u0000' })
        }
        kotlin.test.assertContentEquals(before, java.nio.file.Files.readAllBytes(snapshot))
        assertTrue(logs.none { it.contains("PRIVATE_WRONG_PASSWORD_SENTINEL") || it.contains("PRIVATE_CLOSED_PASSWORD_SENTINEL") || it.contains(hash) })
    }

    @Test
    fun `parent migration stop switch ignores stale source settings and preserves committed old UUID ownership`() {
        val name = moe.ryuu.kyokouauth.core.identity.GameName.parseOrThrow("NewSteve")
        val xuid = moe.ryuu.kyokouauth.core.identity.Xuid(13579L)
        val old = UUID.fromString("f66564fa-f35f-3ec0-8f67-f28f4ee58a44")
        lateinit var saved: moe.ryuu.kyokouauth.core.storage.BoundIdentity
        AuthService(config(), quiet, supportsProfileClaims = true).use { service ->
            val source = service.profileClaims.importLegacy("cutover-1", "OldSteve", old)
            val session = UUID.randomUUID()
            val grant = service.profileClaims.issue(source.id, session, name)
            saved = service.profileClaims.commit(grant.token, session, name, xuid, "SyntheticGT")
        }
        val stopped = config(mapOf("migration.enabled" to "false", "migration.authme.enabled" to "true",
            "migration.authme.sqlite-file" to directory.resolve("source-no-longer-present.db").toString()))
        AuthService(stopped, quiet, supportsProfileClaims = true).use { service ->
            kotlin.test.assertFalse(service.authMeClaimsAvailable())
            kotlin.test.assertEquals(old, service.profileClaims.resolveMicrosoftProfile(xuid))
            kotlin.test.assertEquals(saved, service.identities.byName(name))
            assertTrue(service.permitsMappedProfile(saved))
            kotlin.test.assertFalse(service.permitsMappedProfile(saved.copy(uuid = moe.ryuu.kyokouauth.core.identity.Identity.ofXuid(xuid))))
        }
    }

    @Test
    fun `real service preserves task exceptions and is closed idempotently`() {
        val service = AuthService(config(), quiet)
        try {
            val expected = IOException("controlled task failure")
            val failure = assertFailsWith<ExecutionException> { service.submit<Int> { throw expected }.get(3, TimeUnit.SECONDS) }
            assertSame(expected, failure.cause)
            service.close()
            service.close()
            assertFailsWith<RejectedExecutionException> { service.execute { error("closed admission ran") } }
            val rejected = assertFailsWith<ExecutionException> { service.submit { 1 }.get(3, TimeUnit.SECONDS) }
            assertTrue(rejected.cause is RejectedExecutionException)
        } finally { service.close() }
    }

    @Test
    fun `native Microsoft profile is refused after a legacy claim including after unbind and restart`() {
        val name = moe.ryuu.kyokouauth.core.identity.GameName.parseOrThrow("NewSteve")
        val xuid = moe.ryuu.kyokouauth.core.identity.Xuid(13579L)
        val original = moe.ryuu.kyokouauth.core.storage.BoundIdentity.of(
            moe.ryuu.kyokouauth.core.identity.Identity.Microsoft(name, xuid, "SteveGT"), 1L)
        val old = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")
        AuthService(config(), quiet, supportsProfileClaims = true).use { service ->
            assertTrue(service.permitsMappedProfile(original))
            val source = service.profileClaims.importLegacy("test-backup", "OldSteve", old)
            val session = UUID.randomUUID()
            val grant = service.profileClaims.issue(source.id, session, name)
            val bound = service.profileClaims.commit(grant.token, session, name, xuid, "SteveGT")
            assertTrue(service.permitsMappedProfile(bound))
            kotlin.test.assertFalse(service.permitsMappedProfile(original))
            assertTrue(service.identities.unbind(old))
            kotlin.test.assertFalse(service.permitsMappedProfile(original))
        }
        AuthService(config(), quiet, supportsProfileClaims = true).use { service ->
            // The migration switch is off and there is no name binding, but ownership still applies.
            kotlin.test.assertFalse(service.permitsMappedProfile(original))
            assertTrue(service.permitsMappedProfile(original.copy(uuid = old)))
        }
    }

    @Test
    fun `migration preflight command is console only and never imports the checked source`() {
        val config = config(mapOf("migration.enabled" to "true"))
        val messages = MessageCatalogue("en_US", directory.resolve("messages").toFile()).also { it.load() }
        val server = object : ProxyServer {
            override fun getServer(name: String) = Optional.empty<RegisteredServer>()
            override fun getPlayer(name: String) = Optional.empty<Player>()
            override fun getPlayer(uuid: UUID) = Optional.empty<Player>()
            override val allPlayers = emptyList<Player>()
            override val configuration = object : ProxyConfiguration { override val attemptConnectionOrder = emptyList<String>() }
        }
        val replies = java.util.concurrent.LinkedBlockingQueue<RichText.Parsed>()
        fun source(console: Boolean) = object : CommandSource {
            override val locale = "en_US"
            override val isConsole = console
            override fun hasPermission(permission: String) = true
            override fun sendMessage(message: RichText.Parsed) { replies.add(message) }
        }
        val old = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")
        AuthService(config, quiet, supportsProfileClaims = true).use { service ->
            val gate = LoginGate(server, config, messages, service, quiet)
            val command = KyokouAuthCommand(server, config, messages, service, gate) { messages.load() }
            val args = arrayOf("migration", "check", "synthetic-backup", "OldSteve", old.toString())
            command.execute(source(false), args)
            assertTrue(replies.poll(3, TimeUnit.SECONDS)!!.spans.joinToString("") { it.text }.contains("console"))
            kotlin.test.assertNull(service.profileClaims.byProfile(old))
            command.execute(source(true), args)
            val checked = replies.poll(3, TimeUnit.SECONDS)!!.spans.joinToString("") { it.text }
            assertTrue(checked.contains("metadata") && checked.contains("passed"), checked)
            kotlin.test.assertNull(service.profileClaims.byProfile(old))
            command.execute(source(true), args.copyOf().also { it[1] = "import" })
            val imported = replies.poll(3, TimeUnit.SECONDS)!!.spans.joinToString("") { it.text }
            val record = kotlin.test.assertNotNull(service.profileClaims.byProfile(old))
            assertTrue(imported.contains(record.id.toString()), imported)
            kotlin.test.assertEquals(moe.ryuu.kyokouauth.core.storage.LegacyProfile.Status.AVAILABLE, record.status)
            kotlin.test.assertNull(service.profileClaims.ownerByProfile(old))
        }
    }

    @Test
    fun `premium profile permission never accepts a different legacy UUID`() {
        AuthService(config(), quiet, supportsProfileClaims = true).use { service ->
            val premium = moe.ryuu.kyokouauth.core.storage.BoundIdentity.of(
                moe.ryuu.kyokouauth.core.identity.Identity.Premium(
                    moe.ryuu.kyokouauth.core.identity.GameName.parseOrThrow("PremiumSteve"),
                    UUID.fromString("91a12bae-83eb-401c-bb35-352da2293f9e")), 1L)
            assertTrue(service.permitsMappedProfile(premium))
            kotlin.test.assertFalse(service.permitsMappedProfile(premium.copy(
                uuid = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716"))))
        }
    }

    @Test
    fun `a closed command queue reports busy rather than inventing an unknown identity`() {
        val config = config()
        val messages = MessageCatalogue("en_US", directory.resolve("messages").toFile()).also { it.load() }
        val server = object : ProxyServer {
            override fun getServer(name: String) = Optional.empty<RegisteredServer>()
            override fun getPlayer(name: String) = Optional.empty<Player>()
            override fun getPlayer(uuid: UUID) = Optional.empty<Player>()
            override val allPlayers = emptyList<Player>()
            override val configuration = object : ProxyConfiguration { override val attemptConnectionOrder = emptyList<String>() }
        }
        val replies = mutableListOf<RichText.Parsed>()
        val source = object : CommandSource {
            override val locale = "en_US"
            override fun hasPermission(permission: String) = true
            override fun sendMessage(message: RichText.Parsed) { replies += message }
        }
        AuthService(config, quiet).use { service ->
            val gate = LoginGate(server, config, messages, service, quiet)
            val command = KyokouAuthCommand(server, config, messages, service, gate) { messages.load() }
            service.close()
            command.execute(source, arrayOf("whois", "Controlled"))
            val response = replies.single().spans.joinToString("") { it.text }
            assertTrue(response.contains("busy"), response)
            assertTrue(!response.contains("No record named"), response)
        }
    }
}
