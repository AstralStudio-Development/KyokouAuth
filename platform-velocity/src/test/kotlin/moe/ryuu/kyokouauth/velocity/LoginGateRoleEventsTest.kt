package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.event.Continuation
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.network.ProtocolState
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.LoginPhaseConnection
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.util.GameProfile
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.proxy.RoleOption
import moe.ryuu.kyokouauth.proxy.RoleOptions
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*

/** A public disconnect method mirrors the fixed native LoginInboundConnection read-only API seam. */
interface RoleEventTestInbound : LoginPhaseConnection { fun disconnect(reason: Component) }

class LoginGateRoleEventsTest {
    private class Resume : Continuation {
        var count = 0
        override fun resume() { count++ }
        override fun resumeWithException(error: Throwable): Unit = throw AssertionError("Native must be denied and resumed", error)
    }

    private class Timer(val action: () -> Unit) : ScheduledFuture<Unit> {
        private var cancelled = false
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean { cancelled = true; return true }
        override fun isCancelled() = cancelled
        override fun isDone() = cancelled
        override fun get() = Unit
        override fun get(timeout: Long, unit: TimeUnit) = Unit
        override fun getDelay(unit: TimeUnit) = 0L
        override fun compareTo(other: Delayed) = 0
        fun fire() { if (!cancelled) action() }
    }

    private class Fixture(val roles: Boolean = true, val bedrock: Boolean = roles, val dual: Boolean = false) : AutoCloseable {
        val identity = Identity.Microsoft(GameName.parseOrThrow("Steve"), Xuid(1), "Steve")
        val premium = Identity.Premium(GameName.parseOrThrow("PremiumRole"), UUID.fromString("f4688ebd-ccf7-4dea-922e-b18f68014f71"))
        val record = BoundIdentity.of(identity, 1L)
        val backend = stub<com.velocitypowered.api.proxy.server.RegisteredServer> { method, _ -> when (method) {
            "getServerInfo" -> com.velocitypowered.api.proxy.server.ServerInfo("backend", InetSocketAddress("127.0.0.1", 29001))
            else -> error("Unexpected backend call: $method")
        } }
        val clients = mutableListOf<Client>()
        val services = Services()
        val deadlines = mutableListOf<CompletableFuture<Unit>>()
        val events = if (roles) VelocityRoleEventTasks(16, services::submit, deadline = {
            assertEquals(35_000L, it)
            CompletableFuture<Unit>().also { future -> deadlines += future }
        }) else null
        val proxy = stub<ProxyServer> { method, args -> when (method) {
            "getAllPlayers" -> clients.filter { it.active }.map { it.player }
            "getPlayer" -> Optional.ofNullable(clients.firstOrNull { it.active && (it.id == args[0] || it.name == args[0]) }?.player)
            "getServer" -> if (args[0] == "backend") Optional.of(backend) else Optional.empty<Any>()
            else -> error("Unexpected proxy access: $method")
        } }
        val config = ProxyConfig(mapOf("post-login.server" to "backend", "role-selection.enabled" to roles.toString(), "bedrock.enabled" to bedrock.toString(),
            "verification.poll-interval-seconds" to "1"), File("unused-role-events"))
        val gate = LoginGate(proxy, config, MessageCatalogue("en_US", File("unused-role-events/messages")),
            services, NOPLogger.NOP_LOGGER, bedrockProbe = { connection ->
                if (!bedrock) moe.ryuu.kyokouauth.proxy.BedrockLogin.Java
                else moe.ryuu.kyokouauth.proxy.BedrockLogin.Verified(clients.single { it.sid == connection.sessionId }.proof)
            }, watchClosed = { connection, action ->
                val channel = clients.single { it.sid == connection.sessionId }.channel
                val listener = io.netty.channel.ChannelFutureListener { action() }
                channel.closeFuture().addListener(listener)
                AutoCloseable { channel.closeFuture().removeListener(listener) }
            }, roleEvents = events).also { it.activate() }

        inner class Client {
            val sid: UUID = UUID.randomUUID()
            var id: UUID = if (dual) premium.uuid else if (bedrock) identity.uuid else UUID.randomUUID()
            var name = if (dual) "PremiumRole" else if (bedrock) ".Steve" else "Steve"
            var active = true
            var online = false
            var disconnects = 0
            val channel = EmbeddedChannel().apply { pipeline().addLast("minecraft-decoder", ChannelInboundHandlerAdapter()) }
            val proof by lazy {
                val current = { active && channel.isActive }
                if (dual) moe.ryuu.kyokouauth.proxy.BedrockProof.createLinked(Xuid(1), premium.uuid, "PremiumRole", "Steve", channel, current)
                else moe.ryuu.kyokouauth.proxy.BedrockProof.create(Xuid(1), identity.uuid, ".Steve", "Steve", ".", channel, current)
            }
            var properties = emptyList<GameProfile.Property>()
            var menu: moe.ryuu.kyokouauth.limbo.RoleSelectorMenu? = null
            var backendRequests = 0
            private fun invoke(method: String, args: Array<out Any?>): Any? = when (method) {
                "getSessionId" -> sid
                "getUsername" -> name
                "getUniqueId" -> id
                "getRemoteAddress" -> InetSocketAddress("127.0.0.1", 40000)
                "getProtocolVersion" -> ProtocolVersion.getProtocolVersion(777)
                "getProtocolState" -> ProtocolState.LOGIN
                "getIdentifiedKey" -> null
                "getEffectiveLocale" -> Locale.US
                "getCurrentServer" -> Optional.empty<com.velocitypowered.api.proxy.ServerConnection>()
                "isActive" -> active
                "isOnlineMode" -> online
                "disconnect" -> { disconnects++; active = false; channel.close(); null }
                "getGameProfileProperties" -> properties
                "setGameProfileProperties" -> {
                    @Suppress("UNCHECKED_CAST")
                    properties = (args[0] as Collection<GameProfile.Property>).toList(); null
                }
                "storeCookie", "transferToHost", "retrieveCookie" -> error("Ordinary reconnect never uses Cookie or Transfer")
                "createConnectionRequest" -> {
                    assertSame(backend, args[0]); backendRequests++
                    stub<com.velocitypowered.api.proxy.ConnectionRequestBuilder> { method, _ -> when (method) {
                        "connect" -> CompletableFuture.completedFuture(stub<com.velocitypowered.api.proxy.ConnectionRequestBuilder.Result> { query, _ -> when (query) {
                            "getStatus" -> com.velocitypowered.api.proxy.ConnectionRequestBuilder.Status.SUCCESS
                            else -> error(query)
                        } })
                        else -> error(method)
                    } }
                }
                "sendMessage" -> null
                else -> error("Unexpected native client access: $method")
            }
            val inbound = stub<RoleEventTestInbound>(::invoke)
            val player = stub<Player>(::invoke)
            val limbo = object : LimboPlayer {
                override val connectionId get() = sid
                override val uuid get() = id
                override val name get() = this@Client.name
                override val address = "127.0.0.1"
                override fun sendMessage(message: TextComponent) = Unit
                override fun showTitle(title: TextComponent, subtitle: TextComponent?, fadeIn: Int, stay: Int, fadeOut: Int) = Unit
                override fun clearTitle() = Unit
                override fun openRoleSelector(menu: moe.ryuu.kyokouauth.limbo.RoleSelectorMenu) { this@Client.menu = menu }
                override fun closeRoleSelector() { this@Client.menu = null }
                override fun disconnect(reason: TextComponent) { active = false }
                override fun close() = Unit
            }
        }

        inner class Services : LoginGate.Services {
            val queue = ArrayDeque<() -> Unit>()
            val timers = mutableListOf<Timer>()
            var worker = false
            var roleCalls = 0
            var premiumCalls = 0
            var decision: AuthCoordinator.Decision = AuthCoordinator.Decision.Verify(record.uuid,
                AuthCoordinator.Reason.SESSION_EXPIRED, false, record)
            override fun allow(key: String) = true
            override fun decide(name: GameName, address: String) = decision
            override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome {
                premiumCalls++
                return AuthCoordinator.ClaimOutcome.Bound(BoundIdentity.of(Identity.Premium(name, uuid), 1L))
            }
            override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome {
                assertEquals("Steve", name.value, "Chooser name must never replace the original authenticated input")
                return AuthCoordinator.ClaimOutcome.Bound(record)
            }
            override fun grantSession(uuid: UUID, address: String) = Unit
            override fun submit(task: () -> Unit) { queue += { worker = true; try { task() } finally { worker = false } } }
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*> = Timer(task).also { timers += it }
            override fun begin() = DeviceCodeFlow.Challenge("test", "https://example.invalid", "https://example.invalid", 300, 1, "synthetic")
            override fun poll(challenge: DeviceCodeFlow.Challenge) = DeviceCodeFlow.PollResult.Authorized("synthetic", null, 300)
            override fun exchange(accessToken: String) = XboxLive.XstsToken("synthetic", 1L, "Steve", "synthetic")
            override fun roleOptions(xuid: Xuid, gamertag: String, fallbackBedrockName: String?): RoleOptions {
                check(worker) { "Role/network revalidation ran on the native event thread" }
                roleCalls++
                return if (dual) RoleOptions(listOf(RoleOption(identity, record, null), RoleOption(premium, null, null)), premium.uuid)
                else RoleOptions(listOf(RoleOption(identity, record, null)), null)
            }
            fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
        }

        fun client(): Client = Client().also { clients += it }

        fun preAndProfile(client: Client) {
            val pre = PreLoginEvent(client.inbound, client.name, null)
            val task = gate.onPreLogin(pre)
            if (task != null) {
                val continuation = Resume()
                task.execute(continuation)
                client.channel.runPendingTasks()
                assertNull(client.channel.readOutbound<Any>(), "PreLogin must not emit Cookie/Transfer packets")
                services.drain()
                assertEquals(1, continuation.count)
            }
            assertTrue(pre.result.isAllowed)
            val profile = GameProfileRequestEvent(client.inbound, GameProfile(client.id, client.name, emptyList()), false)
            gate.onPrepareJavaProfile(profile)
            gate.onGameProfile(profile)
            client.id = profile.gameProfile.id
            client.name = profile.gameProfile.name
            client.properties = profile.gameProfile.properties.toList()
            assertTrue(client.active)
        }

        fun login(client: Client): LoginEvent {
            preAndProfile(client)
            val event = LoginEvent(client.player, "")
            val task = gate.onLogin(event)
            if (task != null) {
                val continuation = Resume()
                task.execute(continuation)
                services.drain()
                assertEquals(1, continuation.count)
            }
            assertTrue(event.result.isAllowed)
            return event
        }

        fun selectedRole(): Client {
            val client = client()
            preAndProfile(client)
            assertEquals(identity.uuid, client.id)
            assertEquals("Steve", client.name)
            services.roleCalls = 0
            return client
        }

        override fun close() {
            gate.deactivate()
            clients.forEach { it.channel.finishAndReleaseAll() }
        }
    }

    @Test fun `native prelogin with dropped auth queue is bounded and late work cannot reopen result`() {
        Fixture().use { fixture ->
            val client = fixture.client()
            val event = PreLoginEvent(client.inbound, client.name, null)
            val continuation = Resume()
            assertNotNull(fixture.gate.onPreLogin(event)).execute(continuation)
            client.channel.runPendingTasks()
            val dropped = fixture.services.queue.removeFirst()
            assertEquals(0, continuation.count)
            fixture.deadlines.single().completeExceptionally(TimeoutException("dropped work"))
            assertEquals(1, continuation.count)
            assertFalse(event.result.isAllowed)
            assertFalse(client.active)
            dropped()
            assertFalse(event.result.isAllowed)
            assertEquals(1, continuation.count)
            assertEquals(0, fixture.events?.pendingCount())
        }
    }

    @Test fun `external native refusal during queued prelogin closes once instead of applying a late success`() {
        Fixture().use { fixture ->
            val client = fixture.client()
            val event = PreLoginEvent(client.inbound, client.name, null)
            val continuation = Resume()
            assertNotNull(fixture.gate.onPreLogin(event)).execute(continuation)
            event.result = PreLoginEvent.PreLoginComponentResult.denied(Component.text("late native refusal"))
            fixture.services.drain()
            assertFalse(client.active)
            assertFalse(client.channel.isActive)
            assertEquals(1, continuation.count)
            assertEquals(1, client.disconnects)
            assertFalse(event.result.isAllowed)
        }
    }

    @Test fun `half-login exact channel close before auth work immediately resumes once and removes slot`() {
        Fixture().use { fixture ->
            val client = fixture.client()
            val event = PreLoginEvent(client.inbound, client.name, null)
            val continuation = Resume()
            assertNotNull(fixture.gate.onPreLogin(event)).execute(continuation)
            client.channel.runPendingTasks()
            assertEquals(1, fixture.services.queue.size)
            client.active = false
            client.channel.close()
            client.channel.runPendingTasks()
            assertEquals(1, continuation.count)
            assertFalse(event.result.isAllowed)
            fixture.services.drain()
            assertEquals(1, continuation.count)
            assertFalse(event.result.isAllowed)
            assertEquals(0, fixture.events?.pendingCount())
        }
    }

    @Test fun `deactivation releases native continuation while queued work is waiting`() {
        Fixture().use { fixture ->
            val client = fixture.client()
            val event = PreLoginEvent(client.inbound, client.name, null)
            val continuation = Resume()
            assertNotNull(fixture.gate.onPreLogin(event)).execute(continuation)
            client.channel.runPendingTasks()
            fixture.gate.deactivate()
            fixture.services.drain()
            assertEquals(1, continuation.count)
            assertFalse(event.result.isAllowed)
            assertFalse(client.active)
            assertEquals(0, fixture.events?.pendingCount())
            assertFalse(client.channel.pipeline().names().any { it.startsWith("kyokouauth-role-cookie-") })
        }
    }

    @Test fun `selected role Login network revalidation only runs inside bounded auth submit`() {
        Fixture().use { fixture ->
            val client = fixture.selectedRole()
            val event = LoginEvent(client.player, "")
            val task = assertNotNull(fixture.gate.onLogin(event))
            assertEquals(0, fixture.services.roleCalls)
            val continuation = Resume()
            task.execute(continuation)
            assertEquals(0, fixture.services.roleCalls)
            assertEquals(0, continuation.count)
            assertEquals(1, fixture.services.queue.size)
            fixture.services.drain()
            assertTrue(fixture.services.roleCalls > 0)
            assertTrue(event.result.isAllowed)
            assertEquals(1, continuation.count)
        }
    }

    @Test fun `selected role queued Login fails closed on timeout shutdown or disconnect without late IO`() {
        for (cause in listOf("timeout", "shutdown", "disconnect")) Fixture().use { fixture ->
            val client = fixture.selectedRole()
            val event = LoginEvent(client.player, "")
            val continuation = Resume()
            assertNotNull(fixture.gate.onLogin(event)).execute(continuation)
            val dropped = fixture.services.queue.removeFirst()
            when (cause) {
                "timeout" -> fixture.deadlines.last().completeExceptionally(TimeoutException("login queued"))
                "shutdown" -> fixture.gate.deactivate()
                else -> {
                    client.active = false
                    fixture.gate.onDisconnect(DisconnectEvent(client.player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN))
                }
            }
            dropped()
            assertEquals(0, fixture.services.roleCalls)
            assertFalse(event.result.isAllowed)
            assertEquals(1, continuation.count)
            assertEquals(0, fixture.events?.pendingCount())
        }
    }

    @Test fun `same-identity Bedrock click keeps current Player and late native skin guard preserves extras`() {
        Fixture(dual = true).use { fixture ->
            val client = fixture.client()
            fixture.login(client)
            val player = fixture.gate.ports.player(client.player)
            assertEquals(fixture.premium.uuid, client.id)
            assertEquals("PremiumRole", client.name)
            assertFalse(fixture.gate.common.isAuthenticated(player))
            fixture.gate.onJoin(client.limbo)
            fixture.services.drain()
            val generation = assertNotNull(client.menu).generation
            fixture.gate.onRoleSelect(client.limbo, generation, "premium")
            fixture.services.drain()
            assertTrue(fixture.gate.common.isAuthenticated(player))
            assertTrue(client.active)
            assertEquals(0, client.disconnects)
            assertEquals(1, client.backendRequests)
            assertEquals(fixture.premium.uuid, client.id)
            assertEquals("PremiumRole", client.name)
            val extra = GameProfile.Property("native-extra", "preserved", "signed")
            client.properties = client.properties + extra + GameProfile.Property("textures", "late-wrong-skin", "wrong")
            val event = com.velocitypowered.api.event.player.ServerPreConnectEvent(client.player, fixture.backend)
            fixture.gate.onPreConnect(event)
            assertTrue(event.result.isAllowed)
            assertEquals(emptyList(), client.properties.filter { it.name == "textures" })
            assertTrue(extra in client.properties)
            assertEquals(client.sid.toString(), client.properties.single { it.name == LoginGate.CONNECTION_PROPERTY }.value)
            assertEquals(0, client.disconnects)
            assertNull(client.channel.readOutbound<Any>())
        }
    }

    @Test fun `enabled Bedrock role selection keeps Java Microsoft login on basic path without cookie wait`() {
        Fixture(bedrock = false).use { fixture ->
            val client = fixture.client()
            fixture.login(client)
            val player = fixture.gate.ports.player(client.player)
            assertEquals("Steve", client.name)
            assertEquals(fixture.identity.uuid, client.id)
            assertFalse(fixture.gate.common.requiresRoleProfile(player))
            assertEquals(0, fixture.services.roleCalls)
            assertNull(client.channel.readOutbound<Any>())
            assertTrue(fixture.services.queue.isEmpty())
        }
    }

    @Test fun `feature off and ordinary Mojang Login retain synchronous null EventTask behavior`() {
        Fixture(roles = false).use { fixture ->
            val client = fixture.client()
            val pre = PreLoginEvent(client.inbound, client.name, null)
            assertNull(fixture.gate.onPreLogin(pre))
            val profile = GameProfileRequestEvent(client.inbound, GameProfile(client.id, client.name, emptyList()), false)
            fixture.gate.onPrepareJavaProfile(profile)
            fixture.gate.onGameProfile(profile)
            client.id = profile.gameProfile.id
            client.name = profile.gameProfile.name
            val login = LoginEvent(client.player, "")
            assertNull(fixture.gate.onLogin(login))
            assertTrue(login.result.isAllowed)
            assertTrue(fixture.services.queue.isEmpty())
        }
        Fixture(bedrock = false).use { fixture ->
            val client = fixture.client().apply { online = true }
            fixture.services.decision = AuthCoordinator.Decision.MojangSession(client.id)
            fixture.preAndProfile(client)
            val event = LoginEvent(client.player, "")
            assertNull(fixture.gate.onLogin(event))
            assertEquals(1, fixture.services.premiumCalls)
            assertEquals(0, fixture.services.roleCalls)
            assertTrue(event.result.isAllowed)
            assertTrue(fixture.services.queue.isEmpty())
        }
    }

    companion object {
        private inline fun <reified T> stub(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args -> when (method.name) {
                "equals" -> self === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "role-events-test-${T::class.java.simpleName}"
                else -> call(method.name, args ?: emptyArray())
            } } as T
    }
}
