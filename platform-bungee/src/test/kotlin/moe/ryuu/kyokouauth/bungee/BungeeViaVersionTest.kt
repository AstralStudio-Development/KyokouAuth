package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.PrivateTicketAuthority
import moe.ryuu.kyokouauth.proxy.LoginGate
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.proxy.ProxyLog
import net.md_5.bungee.api.event.LoginEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.PreLoginEvent
import net.md_5.bungee.api.event.ServerConnectEvent
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.plugin.PluginDescription
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.*

class BungeeViaVersionTest {
    @Test fun `absent Via never resolves or links its API`() {
        var resolutions = 0
        val via = BungeeViaVersion({ null }) {
            resolutions++
            error("Absent optional provider must not load classes")
        }
        via.register("kyokouauth-limbo-777", 777)
        via.install()
        via.prepare("kyokouauth-limbo-777", 777)
        via.close()
        assertEquals(0, resolutions)
    }

    @Test fun `all eleven private names declare their actual protocols without changing default or public backends`() {
        val h = Harness()
        h.detector.cache["kyokouauth-limbo-777"] = 47
        h.platform.installPrivateProtocols()
        for (protocol in 767..777) {
            val name = "kyokouauth-limbo-$protocol"
            assertEquals(protocol, h.config.protocols[name])
            assertEquals(protocol, h.detector.serverProtocolVersion(name).version)
            assertEquals(protocol, h.platform.engineProtocol(h.platform.getServer(name).orElseThrow()))
        }
        assertEquals(13, h.config.protocols.size)
        assertEquals(47, h.config.protocols["default"])
        assertEquals(763, h.config.protocols["backend"])
        assertEquals(setOf("backend"), h.proxy.servers.keys)
        assertEquals(11, h.config.protocols.writes)
        h.platform.unregisterEngines()
    }

    @Test fun `probe eviction falls back to private config and connections refresh stale cache without rewriting map`() {
        val h = Harness()
        h.platform.installPrivateProtocols()
        h.detector.cache.clear() // ViaBungee probe retains only publicly registered names.
        for (protocol in 767..777) {
            assertEquals(protocol, h.detector.serverProtocolVersion("kyokouauth-limbo-$protocol").version)
        }
        val writes = h.config.protocols.writes
        val target = h.platform.getServer("kyokouauth-limbo-777").orElseThrow()
        h.detector.cache[target.name] = 47
        repeat(3) { h.platform.prepareEngineConnection(target) }
        assertEquals(777, h.detector.cache[target.name])
        assertEquals(writes, h.config.protocols.writes, "Live logins must not structurally modify Via's HashMap")
        assertEquals(47, h.config.protocols["default"])
        assertEquals(763, h.config.protocols["backend"])
        h.platform.unregisterEngines()
    }

    @Test fun `reload declares the new map generation and restores only owned values in both generations`() {
        val h = Harness()
        val original = h.config.protocols
        h.platform.installPrivateProtocols()
        val replacement = TrackedProtocols(mapOf("default" to 340, "backend" to 765, "kyokouauth-limbo-777" to 767), h.monitor)
        h.config.protocols = replacement
        h.detector.cache["kyokouauth-limbo-777"] = 47
        h.platform.prepareEngineConnection(h.platform.getServer("kyokouauth-limbo-777").orElseThrow())
        assertEquals(mapOf("default" to 47, "backend" to 763), original)
        assertEquals(340, replacement["default"])
        assertEquals(765, replacement["backend"])
        for (protocol in 767..777) assertEquals(protocol, replacement["kyokouauth-limbo-$protocol"])
        assertEquals(777, h.detector.cache["kyokouauth-limbo-777"])
        assertEquals(11, replacement.writes)
        h.platform.unregisterEngines()
        assertEquals(mapOf("default" to 340, "backend" to 765, "kyokouauth-limbo-777" to 767), replacement)
    }

    @Test fun `shutdown restores prior private declarations but preserves later external configuration and cache edits`() {
        val h = Harness()
        synchronized(h.monitor) { h.config.protocols["kyokouauth-limbo-777"] = 47 }
        h.detector.cache["kyokouauth-limbo-777"] = 47
        h.detector.cache["backend"] = 763
        h.platform.installPrivateProtocols()
        synchronized(h.monitor) { h.config.protocols["kyokouauth-limbo-776"] = 340 }
        h.detector.cache["kyokouauth-limbo-775"] = 765
        h.platform.unregisterEngines()
        h.platform.unregisterEngines()
        assertEquals(mapOf("default" to 47, "backend" to 763, "kyokouauth-limbo-777" to 47,
            "kyokouauth-limbo-776" to 340), h.config.protocols)
        assertEquals(mapOf("backend" to 763, "kyokouauth-limbo-777" to 47, "kyokouauth-limbo-775" to 765), h.detector.cache)
        assertEquals(setOf("backend"), h.proxy.servers.keys)
        assertTrue(h.platform.getServer("kyokouauth-limbo-777").isEmpty)
    }

    @Test fun `external live declaration changes refuse that private target instead of being overwritten`() {
        val h = Harness()
        h.platform.installPrivateProtocols()
        synchronized(h.monitor) { h.config.protocols["kyokouauth-limbo-777"] = 47 }
        assertFailsWith<IllegalStateException> {
            h.platform.prepareEngineConnection(h.platform.getServer("kyokouauth-limbo-777").orElseThrow())
        }
        assertEquals(47, h.config.protocols["kyokouauth-limbo-777"])
        h.platform.prepareEngineConnection(h.platform.getServer("kyokouauth-limbo-776").orElseThrow())
        h.platform.unregisterEngines()
        assertEquals(47, h.config.protocols["kyokouauth-limbo-777"])
    }

    @Test fun `incompatible optional API refuses private connections without inspecting or changing real backends`() {
        val plugin = Any()
        val via = BungeeViaVersion({ plugin }) { Any() to Any() }
        val proxy = TestProxy()
        val platform = BungeePlatform(proxy, via)
        proxy.registered["backend"] = proxy.constructServerInfo("backend", InetSocketAddress("127.0.0.1", 29001), "test", false)
        platform.registerEngine(777, InetSocketAddress("127.0.0.1", 30777))
        assertFailsWith<IllegalStateException> { platform.installPrivateProtocols() }
        platform.prepareEngineConnection(platform.getServer("backend").orElseThrow())
        assertFailsWith<IllegalStateException> {
            platform.prepareEngineConnection(platform.getServer("kyokouauth-limbo-777").orElseThrow())
        }
        platform.unregisterEngines()
        assertEquals(setOf("backend"), proxy.servers.keys)
    }

    @Test fun `Via API failure cancels the native private connection before issuing a ticket`() {
        val h = Harness()
        h.platform.installPrivateProtocols()
        val services = TestServices()
        val profiles = TestProfiles()
        val fixture = PendingFixture()
        profiles.add(fixture)
        val folder = File("build/bungee-via-tests")
        val config = ProxyConfig(mapOf("post-login.server" to "backend"), folder)
        val log = ProxyLog { _, _, _ -> }
        val gate = LoginGate(h.platform, config, MessageCatalogue("en_US", File(folder, "messages")), services, log)
        val authority = PrivateTicketAuthority()
        val tickets = BungeeTickets(h.platform, profiles, authority)
        val adapter = BungeeLoginGate(object : Plugin(h.proxy, PluginDescription()) {}, h.platform, profiles, tickets,
            gate, log, services::submit, services::schedule, { true }, watchClosed = { _, _ -> AutoCloseable {} })
        gate.activate()
        val pre = PreLoginEvent(fixture.pending, null)
        adapter.onPreLogin(pre)
        services.drain()
        assertFalse(pre.isCancelled)
        val login = LoginEvent(fixture.pending, null)
        adapter.onLogin(login)
        services.drain()
        assertFalse(login.isCancelled)
        val player = assertNotNull(h.platform.find(fixture.pending))
        adapter.onPostLogin(PostLoginEvent(fixture.player, h.proxy.registered.getValue("backend"), null))
        assertTrue(fixture.connected)
        val profileBeforeConnect = profiles.read(fixture.pending)
        val uuidBeforeConnect = fixture.uuid
        h.detector.failSet = true
        val target = assertNotNull(h.platform.nativeServer(h.platform.getServer("kyokouauth-limbo-777").orElseThrow()))
        val connect = ServerConnectEvent(fixture.player, target, ServerConnectEvent.Reason.JOIN_PROXY)
        adapter.onServerConnect(connect)
        assertTrue(connect.isCancelled)
        assertFalse(fixture.connected)
        assertNull(player.engineAttempt, "No private forwarding ticket may be issued after a declaration failure")
        assertNull(h.platform.find(fixture.pending))
        assertTrue(fixture.connects.isEmpty())
        assertEquals(1, h.detector.failedSets, "The refusal must originate at the Via API boundary")
        assertEquals(uuidBeforeConnect, fixture.uuid)
        assertEquals(profileBeforeConnect, profiles.read(fixture.pending))
        assertFalse(profiles.read(fixture.pending).properties.any { it.name == authority.propertyKey })
        adapter.close()
        gate.deactivate()
        h.platform.unregisterEngines()
    }

    private class Harness {
        val monitor = Any()
        val plugin = Any()
        val config = PublicConfig(TrackedProtocols(mapOf("default" to 47, "backend" to 763), monitor))
        val detector = PublicDetector(config, monitor)
        val api = PublicPlatform(config, detector)
        val via = BungeeViaVersion({ plugin }) { api to monitor }
        val proxy = TestProxy()
        val platform = BungeePlatform(proxy, via)
        init {
            proxy.registered["backend"] = proxy.constructServerInfo("backend", InetSocketAddress("127.0.0.1", 29001), "test", false)
            (767..777).forEach { platform.registerEngine(it, InetSocketAddress("127.0.0.1", 30000 + it)) }
        }
    }

    class TrackedProtocols(entries: Map<String, Int>, private val monitor: Any) : HashMap<String, Int>(entries) {
        var writes = 0
        override fun put(key: String, value: Int): Int? {
            assertTrue(Thread.holdsLock(monitor), "Configuration writes must share ViaBungee's configuration monitor")
            writes++
            return super.put(key, value)
        }
    }

    class PublicConfig(var protocols: TrackedProtocols) {
        fun getBungeeServerProtocols(): MutableMap<String, Int> = protocols
    }

    class PublicPlatform(private val config: PublicConfig, private val detector: PublicDetector) {
        fun getBungeeConfig(): PublicConfig = config
        fun protocolDetectorService(): PublicDetector = detector
    }

    class PublicProtocol(val version: Int)

    class PublicDetector(private val config: PublicConfig, private val monitor: Any) {
        val cache = hashMapOf<String, Int>()
        var failSet = false
        var failedSets = 0
        fun detectedProtocolVersions(): Map<String, Int> = HashMap(cache)
        fun serverProtocolVersion(name: String): PublicProtocol =
            PublicProtocol(cache[name] ?: config.protocols[name] ?: config.protocols.getValue("default"))
        fun setProtocolVersion(name: String, protocol: Int) {
            assertTrue(Thread.holdsLock(monitor))
            if (failSet) {
                failedSets++
                error("injected Via API failure")
            }
            cache[name] = protocol
        }
        fun uncacheProtocolVersion(name: String): Int = cache.remove(name) ?: -1
    }
}
