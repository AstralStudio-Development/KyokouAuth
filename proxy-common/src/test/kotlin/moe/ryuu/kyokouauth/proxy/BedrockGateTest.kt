package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.core.text.RichText
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import kotlin.test.*

class BedrockGateTest {
    @Test fun `verified unlinked connection preserves provider profile and admits without Java IO`() {
        val h = Fixture()
        val pre = h.pre()
        assertTrue(pre.result.isAllowed)
        assertEquals(false, pre.result.onlineMode)
        assertEquals(0, h.services.decisions, "A prefixed Floodgate name never enters the Mojang directory")
        val native = h.profile()
        val event = GameProfileRequestEvent(h.player, native, h.probe())
        h.gate.onGameProfile(event)
        assertSame(native, event.gameProfile, "Keep the final Floodgate profile and textures, not a provisional UUID")
        assertTrue(h.login().result.isAllowed)
        assertTrue(h.gate.isAuthenticated(h.player))
        assertTrue(h.gate.allowCommand(h.player, "server lobby"))
        val choose = PlayerChooseInitialServerEvent(h.player)
        choose.setInitialServer(h.backend)
        h.gate.onChooseInitialServer(choose)
        assertSame(h.backend, choose.initialServer)
        val connect = ServerPreConnectEvent(h.player, h.backend)
        h.gate.onPreConnect(connect)
        assertSame(h.backend, connect.result.server.orElseThrow())
        assertEquals(0, h.services.writes)
        assertEquals(0, h.services.external)
        assertEquals(0, h.services.timers)
        assertEquals(0, h.gate.verifyingCount())
    }

    @Test fun `disabled rejected and missing channel proofs never fall back to Java admission`() {
        val disabled = Fixture(enabled = false)
        assertFalse(disabled.pre().result.isAllowed)
        for (reason in BedrockFailure.entries) {
            val h = Fixture()
            assertFalse(h.pre(BedrockLogin.Rejected(reason)).result.isAllowed, reason.name)
            assertEquals(0, h.services.decisions)
        }
        val forged = Fixture()
        val pre = PreLoginEvent(forged.player, ".Bedrock", forged.player.uniqueId)
        forged.gate.onPreLogin(pre)
        assertFalse(pre.result.isAllowed)
        assertEquals(0, forged.services.decisions)
    }

    @Test fun `same UUID or same data on a different channel cannot reuse a pending proof`() {
        for (stage in listOf("profile", "login")) {
            val h = Fixture()
            assertTrue(h.pre().result.isAllowed)
            val otherChannel = h.probe(Any())
            val profile = GameProfileRequestEvent(h.player, h.profile(), if (stage == "profile") otherChannel else h.probe())
            h.gate.onGameProfile(profile)
            val login = h.login(if (stage == "login") otherChannel else h.probe())
            assertFalse(login.result.isAllowed, stage)
            assertFalse(h.gate.isAuthenticated(h.player))
            assertEquals(0, h.services.writes)
        }
    }

    @Test fun `stale proof changed profile and another plugins denial stay closed`() {
        for (failure in listOf("stale", "uuid", "name", "online", "denied")) {
            val h = Fixture()
            assertTrue(h.pre().result.isAllowed)
            h.gate.onGameProfile(GameProfileRequestEvent(h.player, h.profile(), h.probe()))
            when (failure) {
                "stale" -> h.valid = false
                "uuid" -> h.player.uniqueId = UUID.randomUUID()
                "name" -> h.player.username = ".Other"
                "online" -> h.player.isOnlineMode = true
            }
            val login = LoginEvent(h.player, h.probe())
            if (failure == "denied") login.result = ResultedEvent.ComponentResult.denied(RichText.parse("other denial"))
            h.gate.onLogin(login)
            assertFalse(login.result.isAllowed, failure)
            assertFalse(h.gate.isAuthenticated(h.player))
            assertEquals(0, h.services.writes)
        }
    }

    @Test fun `provider loss or disconnect immediately invalidates later command and backend admission`() {
        val h = Fixture()
        h.pre()
        h.gate.onGameProfile(GameProfileRequestEvent(h.player, h.profile(), h.probe()))
        assertTrue(h.login().result.isAllowed)
        h.valid = false
        assertFalse(h.gate.isAuthenticated(h.player))
        assertFalse(h.gate.allowCommand(h.player, "server lobby"))
        val connect = ServerPreConnectEvent(h.player, h.backend)
        h.gate.onPreConnect(connect)
        assertTrue(connect.result.server.isEmpty)
        h.player.isActive = false
        h.gate.onDisconnect(DisconnectEvent(h.player))
        assertFalse(h.gate.isAuthenticated(h.player))
        assertEquals(0, h.services.writes)
    }

    private class Fixture(enabled: Boolean = true) {
        var valid = true
        val channel = Any()
        val player = BedrockPlayer()
        val backend = RegisteredServer("lobby")
        val services = NoJavaServices()
        val proxy = object : ProxyServer {
            override fun getServer(name: String) = Optional.ofNullable(backend.takeIf { it.name == name })
            override fun getPlayer(name: String) = Optional.ofNullable<Player>(player.takeIf { it.username == name })
            override fun getPlayer(uuid: UUID) = Optional.ofNullable<Player>(player.takeIf { it.uniqueId == uuid })
            override val allPlayers: Collection<Player> get() = listOf(player)
            override val configuration = object : ProxyConfiguration { override val attemptConnectionOrder = listOf("lobby") }
        }
        val gate = LoginGate(proxy, ProxyConfig(mapOf("bedrock.enabled" to enabled.toString()), File("unused")),
            MessageCatalogue("en_US", File("unused")), services, ProxyLog { _, _, _ -> }).also { it.activate() }
        fun probe(source: Any = channel): BedrockLogin = BedrockLogin.Verified(
            BedrockProof.create(Xuid(123L), UUID(0L, 123L), ".Bedrock", "Bedrock", ".", source) { valid && player.isActive })
        fun pre(input: BedrockLogin = probe()) = PreLoginEvent(player, "Bedrock", UUID.randomUUID(), input).also(gate::onPreLogin)
        fun profile() = GameProfile(player.uniqueId, player.username, listOf(GameProfile.Property("textures", "test-texture", "test-signature")))
        fun login(input: BedrockLogin = probe()) = LoginEvent(player, input).also(gate::onLogin)
    }

    private class BedrockPlayer : Player {
        override val sessionId = UUID.randomUUID()
        override var isActive = true
        override val remoteAddress = InetSocketAddress("127.0.0.1", 25500)
        override var uniqueId = UUID(0L, 123L)
        override var username = ".Bedrock"
        override var isOnlineMode = false
        override val effectiveLocale = Locale.US
        override val protocolVersion = 777
        override fun hasPermission(permission: String) = true
        override fun sendMessage(message: RichText.Parsed) = Unit
        override fun disconnect(message: RichText.Parsed) { isActive = false }
        override fun createConnectionRequest(target: RegisteredServer) = object : ConnectionRequestBuilder {
            override fun connect() = CompletableFuture.completedFuture(ConnectionRequestBuilder.Result(ConnectionRequestBuilder.Status.SUCCESS))
        }
    }

    private class NoJavaServices : LoginGate.Services {
        var decisions = 0; var writes = 0; var external = 0; var timers = 0
        override fun allow(key: String) = true
        override fun decide(name: GameName, address: String): AuthCoordinator.Decision {
            decisions++
            return AuthCoordinator.Decision.Verify(UUID(0L, 999L), AuthCoordinator.Reason.FIRST_BIND, true, null)
        }
        override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome { writes++; error("Java binding on Bedrock path") }
        override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome { writes++; error("Java binding on Bedrock path") }
        override fun grantSession(uuid: UUID, address: String) { writes++; error("Java IP session on Bedrock path") }
        override fun submit(task: () -> Unit) { task() }
        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*> { timers++; error("Verification timer on Bedrock path") }
        override fun begin(): DeviceCodeFlow.Challenge { external++; error("Device code on Bedrock path") }
        override fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult { external++; error("Device poll on Bedrock path") }
        override fun exchange(accessToken: String): XboxLive.XstsToken { external++; error("Xbox exchange on Bedrock path") }
    }
}
