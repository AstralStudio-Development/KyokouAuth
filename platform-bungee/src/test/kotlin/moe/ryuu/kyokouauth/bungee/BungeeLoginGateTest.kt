package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.PrivateTicketAuthority
import moe.ryuu.kyokouauth.proxy.LoginGate
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.proxy.ProxyLog
import net.md_5.bungee.api.event.*
import net.md_5.bungee.api.plugin.Plugin
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.*

class BungeeLoginGateTest {
    private class Harness {
        val proxy = TestProxy()
        val platform = BungeePlatform(proxy)
        val services = TestServices()
        val profiles = TestProfiles()
        val authority = PrivateTicketAuthority()
        val tickets = BungeeTickets(platform, profiles, authority)
        val log = ProxyLog { _, _, _ -> }
        val config = ProxyConfig(mapOf("post-login.server" to "backend"), File("build/bungee-tests"))
        val gate = LoginGate(platform, config, MessageCatalogue("en_US", File("build/bungee-tests/messages")), services, log)
        var accepting = true
        val adapter = BungeeLoginGate(object : Plugin(proxy, net.md_5.bungee.api.plugin.PluginDescription()) {}, platform, profiles, tickets, gate, log,
            services::submit, services::schedule, { accepting })
        val backend = proxy.constructServerInfo("backend", InetSocketAddress("127.0.0.1", 29001), "test", false)
        init {
            proxy.registered["backend"] = backend
            (767..777).forEach { platform.registerEngine(it, InetSocketAddress("127.0.0.1", 30000 + it)) }
            gate.activate()
        }
        fun pre(fixture: PendingFixture): PreLoginEvent {
            profiles.add(fixture)
            val event = PreLoginEvent(fixture.pending, null)
            adapter.onPreLogin(event)
            services.drain()
            return event
        }
        fun login(fixture: PendingFixture) {
            assertFalse(pre(fixture).isCancelled)
            val login = LoginEvent(fixture.pending, null)
            adapter.onLogin(login)
            services.drain()
            assertFalse(login.isCancelled)
            proxy.online += fixture.player
            adapter.onPostLogin(PostLoginEvent(fixture.player, backend, null))
        }
    }

    @Test fun `intent holds USERNAME and stable UUID is applied after forcing offline`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.profiles.add(fixture)
        var callbacks = 0
        val event = PreLoginEvent(fixture.pending) { _, _ -> callbacks++ }
        h.adapter.onPreLogin(event)
        event.postCall()
        assertEquals(0, callbacks)
        assertTrue(fixture.setterOrder.isEmpty())
        val bridge = h.platform.find(fixture.pending)
        h.services.drain()
        assertEquals(1, callbacks)
        assertEquals(listOf("online:false", "uuid"), fixture.setterOrder)
        assertEquals(h.services.stableUuid, fixture.uuid)
        assertSame(bridge, h.platform.find(fixture.pending))
        assertEquals("preserved", h.profiles.read(fixture.pending).properties.first { it.name == "textures" }.value)
    }

    @Test fun `Login and PostLogin retain the same exact player and protocol engine`() {
        for (protocol in 767..777) {
            val h = Harness()
            val fixture = PendingFixture(protocol = protocol)
            assertFalse(h.pre(fixture).isCancelled)
            val bridge = h.platform.find(fixture.pending)
            h.adapter.onLogin(LoginEvent(fixture.pending, null))
            h.services.drain()
            val post = PostLoginEvent(fixture.player, h.backend, null)
            h.adapter.onPostLogin(post)
            assertSame(bridge, h.platform.find(fixture.player))
            assertEquals("kyokouauth-limbo-$protocol", post.target.name)
            assertNull(h.proxy.getServerInfo(post.target.name))
            assertSame(h.platform.getServer(post.target.name).orElseThrow(), h.platform.commonServer(post.target))
            assertEquals(0, h.gate.verifyingCount(), "Native post-login/TCP completion is not Limbo READY")
        }
    }

    @Test fun `held connections fail closed for every connect reason and fallback has no current server`() {
        for (reason in ServerConnectEvent.Reason.entries) {
            val h = Harness()
            val fixture = PendingFixture()
            h.login(fixture)
            assertNull(fixture.player.server)
            val event = ServerConnectEvent(fixture.player, h.backend, reason)
            h.adapter.onServerConnect(event)
            assertTrue(event.isCancelled, reason.name)
            assertFalse(fixture.connected, reason.name)
            assertEquals(0, h.gate.verifyingCount())
            assertTrue(h.profiles.read(fixture.pending).properties.none { it.name == h.authority.propertyKey })
        }
    }

    @Test fun `wrong private engine forged object and public same-name conflict fail closed`() {
        for (attempt in listOf("wrong protocol", "forged object", "public conflict")) {
            val h = Harness()
            val fixture = PendingFixture()
            h.login(fixture)
            val name = if (attempt == "wrong protocol") "kyokouauth-limbo-767" else "kyokouauth-limbo-777"
            val privateTarget = assertNotNull(h.platform.nativeServer(h.platform.getServer(name).orElseThrow()))
            val target = if (attempt == "forged object")
                h.proxy.constructServerInfo(name, InetSocketAddress("127.0.0.1", 9), "forged", true) else privateTarget
            if (attempt == "public conflict") {
                h.proxy.registered[name] = h.proxy.constructServerInfo(name, InetSocketAddress("127.0.0.1", 9), "public conflict", false)
            }
            val event = ServerConnectEvent(fixture.player, target, ServerConnectEvent.Reason.PLUGIN)
            h.adapter.onServerConnect(event)
            assertTrue(event.isCancelled, attempt)
            assertFalse(fixture.connected, attempt)
        }
    }

    @Test fun `timeout completes intent once and late work never mutates the pending connection`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.profiles.add(fixture)
        var callbacks = 0
        val event = PreLoginEvent(fixture.pending) { _, _ -> callbacks++ }
        h.adapter.onPreLogin(event)
        event.postCall()
        h.services.tasks.first().fire()
        assertEquals(1, callbacks)
        assertTrue(event.isCancelled)
        assertFalse(fixture.connected)
        h.services.drain()
        h.services.tasks.first().fire()
        assertEquals(1, callbacks)
        assertTrue(fixture.setterOrder.isEmpty())
        assertNull(h.platform.find(fixture.pending))
    }

    @Test fun `half-login disconnect releases intent and cannot affect replacement exact connection`() {
        val h = Harness()
        val old = PendingFixture()
        h.profiles.add(old)
        var callbacks = 0
        val event = PreLoginEvent(old.pending) { _, _ -> callbacks++ }
        h.adapter.onPreLogin(event)
        event.postCall()
        old.connected = false
        h.adapter.sweepClosed()
        assertEquals(1, callbacks)
        val fresh = PendingFixture()
        h.login(fresh)
        h.services.drain()
        h.adapter.sweepClosed()
        assertTrue(fresh.connected)
        assertEquals(1, callbacks)
        assertNotEquals(h.platform.find(fresh.pending)?.sessionId, h.platform.find(old.pending)?.sessionId)
    }

    @Test fun `native online-mode tampering never downgrades to device-code or premium binding`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.services.decision = AuthCoordinator.Decision.MojangSession(UUID.randomUUID())
        assertFalse(h.pre(fixture).isCancelled)
        assertEquals(listOf("online:true"), fixture.setterOrder)
        fixture.online = false
        val login = LoginEvent(fixture.pending, null)
        h.adapter.onLogin(login)
        h.services.drain()
        assertTrue(login.isCancelled)
        assertFalse(fixture.connected)
        assertEquals(0, h.services.premiumBindings)
        assertEquals(0, h.gate.verifyingCount())
        assertEquals(listOf("online:true"), fixture.setterOrder)
    }

    @Test fun `premium final UUID mismatch is refused before a storage claim`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.services.decision = AuthCoordinator.Decision.MojangSession(UUID.randomUUID())
        assertFalse(h.pre(fixture).isCancelled)
        val event = LoginEvent(fixture.pending, null)
        h.adapter.onLogin(event)
        h.services.drain()
        assertTrue(event.isCancelled)
        assertEquals(0, h.services.premiumBindings)
    }

    private class ReadyPlayer(private val frontend: BungeePlayer) : moe.ryuu.kyokouauth.limbo.LimboPlayer {
        override val name get() = frontend.username
        override val uuid get() = frontend.uniqueId
        override val connectionId get() = frontend.sessionId
        override val address get() = frontend.remoteAddress.address.hostAddress
        var titles = 0
        var clears = 0
        var kicked = false
        override fun sendMessage(message: moe.ryuu.kyokouauth.limbo.protocol.TextComponent) = Unit
        override fun showTitle(title: moe.ryuu.kyokouauth.limbo.protocol.TextComponent,
            subtitle: moe.ryuu.kyokouauth.limbo.protocol.TextComponent?, fadeIn: Int, stay: Int, fadeOut: Int) { titles++ }
        override fun clearTitle() { clears++ }
        override fun disconnect(reason: moe.ryuu.kyokouauth.limbo.protocol.TextComponent) { kicked = true }
        override fun close() = Unit
    }

    @Test fun `stable Microsoft verification only releases after trustworthy join and strips ticket on backend event`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.services.authorize = true
        h.login(fixture)
        val frontend = h.platform.find(fixture.player)!!
        val engine = assertNotNull(h.platform.nativeServer(h.platform.getServer("kyokouauth-limbo-777").orElseThrow()))
        val connect = ServerConnectEvent(fixture.player, engine, ServerConnectEvent.Reason.JOIN_PROXY)
        h.adapter.onServerConnect(connect)
        assertFalse(connect.isCancelled)
        assertTrue(fixture.connects.isEmpty())
        assertEquals(0, h.gate.verifyingCount())
        val ready = ReadyPlayer(frontend)
        h.gate.onJoin(ready)
        h.services.drain()
        assertEquals(1, ready.titles)
        h.services.tasks.last().fire()
        h.services.drain()
        assertEquals(listOf(h.services.stableUuid), h.services.granted)
        assertEquals(listOf(h.backend), fixture.connects)
        val backend = ServerConnectEvent(fixture.player, h.backend, ServerConnectEvent.Reason.PLUGIN)
        h.adapter.onServerConnect(backend)
        assertFalse(backend.isCancelled)
        assertEquals(h.services.stableUuid, h.profiles.read(fixture.pending).id)
        assertTrue(h.profiles.read(fixture.pending).properties.none { it.name == h.authority.propertyKey })
        assertEquals(1, ready.clears)
    }

    @Test fun `first binding grants stable session but provisional frontend must reconnect before backend`() {
        val h = Harness()
        val fixture = PendingFixture()
        h.services.authorize = true
        h.services.decision = AuthCoordinator.Decision.Verify(checkNotNull(fixture.uuid), AuthCoordinator.Reason.FIRST_BIND, true, null)
        h.login(fixture)
        val old = h.platform.find(fixture.player)!!
        val ready = ReadyPlayer(old)
        h.gate.onJoin(ready)
        h.services.drain()
        h.services.tasks.last().fire()
        h.services.drain()
        assertFalse(fixture.connected, "First binding disconnects the frontend, not merely the private backend")
        assertTrue(fixture.connects.isEmpty())
        assertNotEquals(h.services.stableUuid, fixture.uuid)
        assertEquals(listOf(h.services.stableUuid), h.services.granted)
        fixture.connected = false
        h.adapter.sweepClosed()
        h.proxy.online.clear()
        val name = moe.ryuu.kyokouauth.core.identity.GameName.parse(fixture.name)!!
        val identity = moe.ryuu.kyokouauth.core.identity.Identity.Microsoft(name,
            moe.ryuu.kyokouauth.core.identity.Xuid(h.services.stableUuid.leastSignificantBits), "Controlled")
        h.services.decision = AuthCoordinator.Decision.Admit(identity,
            moe.ryuu.kyokouauth.core.storage.BoundIdentity.of(identity, 1L))
        val reconnect = PendingFixture()
        h.login(reconnect)
        assertNotSame(old, h.platform.find(reconnect.player))
        val backend = ServerConnectEvent(reconnect.player, h.backend, ServerConnectEvent.Reason.JOIN_PROXY)
        h.adapter.onServerConnect(backend)
        assertFalse(backend.isCancelled)
        assertEquals(h.services.stableUuid, reconnect.uuid)
        assertTrue(h.profiles.read(reconnect.pending).properties.none { it.name == h.authority.propertyKey })
    }

    @Test fun `missing login context and shutdown keep admission closed`() {
        val h = Harness()
        val fixture = PendingFixture()
        val event = LoginEvent(fixture.pending, null)
        h.adapter.onLogin(event)
        assertTrue(event.isCancelled)
        h.accepting = false
        assertTrue(h.pre(PendingFixture("ClosedLocal")).isCancelled)
        h.adapter.onPostLogin(PostLoginEvent(fixture.player, h.backend, null))
        assertFalse(fixture.connected)
    }
}
