package moe.ryuu.kyokouauth.bungee

import io.netty.channel.Channel
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.AttributeKey
import moe.ryuu.kyokouauth.proxy.BedrockFailure
import moe.ryuu.kyokouauth.proxy.BedrockLogin
import net.md_5.bungee.api.connection.PendingConnection
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.plugin.PluginDescription
import net.md_5.bungee.netty.ChannelWrapper
import org.junit.jupiter.api.Test
import kotlin.test.*

class BungeeFloodgateTest {
    private fun plugin(version: String = "2.2.5"): Plugin {
        val description = PluginDescription().also { it.name = "floodgate"; it.version = version }
        val plugin = object : Plugin(TestProxy(), description) {}
        // Official init requires a running native PluginClassloader. This metadata-only fixture
        // needs no plugin lifecycle or SDK; set its real descriptor without relaxing the adapter.
        Plugin::class.java.getDeclaredField("description").apply {
            check(trySetAccessible())
            set(plugin, description)
        }
        return plugin
    }

    private fun player(fixture: PendingFixture = PendingFixture()): BungeePlayer = BungeePlatform(TestProxy()).pending(fixture.pending)

    @Test fun `Java-only installation never initializes channel reflection or loads a Floodgate SDK`() {
        val adapter = BungeeFloodgate(false, { null }, channelFactory = { error("Native channels must remain lazy") })
        assertSame(BedrockLogin.Java, adapter.probe(player()))
        assertNull(adapter.provider)
        adapter.close()
    }

    @Test fun `explicit enabling without a compatible installed provider fails initialization before channels`() {
        assertFails { BungeeFloodgate(true, { null }, channelFactory = { error("No native channel access during startup") }) }
        assertFails { BungeeFloodgate(true, { plugin("1.0") }, channelFactory = { error("No native channel access during startup") }) }
    }

    @Test fun `provider uses installed plugin descriptor loader and exact installation generation`() {
        val original = plugin()
        var installed: Plugin? = original
        val adapter = BungeeFloodgate(false, { installed }, channelFactory = { error("Provider snapshots do not inspect channels") })
        val provider = assertNotNull(adapter.provider)
        assertSame(original, provider.instance)
        assertSame(original.javaClass.classLoader, provider.classLoader)
        assertEquals("2.2.5", provider.version)
        assertTrue(provider.current())
        installed = plugin()
        assertFalse(provider.current(), "A new plugin with the same descriptor must not inherit the old generation")
        installed = original
        assertTrue(provider.current())
        adapter.close()
        assertFalse(provider.current())
    }

    @Test fun `disabled installed Floodgate still samples attributes and refuses recognized connection without SDK loading`() {
        val channel = EmbeddedChannel()
        try {
            val adapter = BungeeFloodgate(false, { plugin() }, channelFactory = { object : FloodgateChannels {
                override fun pending(connection: PendingConnection): Channel = channel
                override fun native(player: ProxiedPlayer): Channel = channel
            } })
            val player = player()
            assertSame(BedrockLogin.Java, adapter.probe(player))
            channel.attr(AttributeKey.valueOf<Any>("floodgate-player")).set(Any())
            assertEquals(BedrockLogin.Rejected(BedrockFailure.DISABLED), adapter.probe(player))
            channel.attr(AttributeKey.valueOf<Any>("floodgate-player")).set(null)
            channel.attr(AttributeKey.valueOf<Any>("floodgate-kick-message")).set("controlled refusal")
            assertIs<BedrockLogin.Rejected>(adapter.probe(player))
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun `pending and native must resolve the identical physical Channel not equivalent names or UUIDs`() {
        val pendingChannel = EmbeddedChannel()
        val nativeChannel = EmbeddedChannel()
        try {
            val adapter = BungeeFloodgate(false, { plugin() }, channelFactory = { object : FloodgateChannels {
                override fun pending(connection: PendingConnection): Channel = pendingChannel
                override fun native(player: ProxiedPlayer): Channel = nativeChannel
            } })
            val fixture = PendingFixture()
            val player = player(fixture)
            player.bind(fixture.player)
            assertIs<BedrockLogin.Rejected>(adapter.probe(player))
        } finally {
            pendingChannel.finishAndReleaseAll()
            nativeChannel.finishAndReleaseAll()
        }
    }

    private fun allocate(type: Class<*>): Any {
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)
    }

    @Test fun `official2111 half-login watcher follows only its exact physical close and detaches cleanly`() {
        val type = Class.forName("net.md_5.bungee.connection.InitialHandler", false, javaClass.classLoader)
        val pending = allocate(type) as PendingConnection
        val channel = object : EmbeddedChannel(object : io.netty.channel.ChannelInboundHandlerAdapter() {}) {
            override fun remoteAddress0(): java.net.SocketAddress = java.net.InetSocketAddress("127.0.0.1", 43123)
        }
        val unrelated = EmbeddedChannel()
        try {
            type.getDeclaredField("ch").apply { isAccessible = true }
                .set(pending, ChannelWrapper(checkNotNull(channel.pipeline().firstContext())))
            val player = BungeePlatform(TestProxy()).pending(pending)
            val access = BungeeFloodgateChannels(javaClass.classLoader)
            var closes = 0
            var detachedCloses = 0
            val watcher = access.watchClosed(player) { closes++ }
            access.watchClosed(player) { detachedCloses++ }.close()
            unrelated.close()
            unrelated.runPendingTasks()
            assertEquals(0, closes)
            channel.close()
            channel.runPendingTasks()
            assertEquals(1, closes)
            assertEquals(0, detachedCloses)
            watcher.close()
        } finally { channel.finishAndReleaseAll(); unrelated.finishAndReleaseAll() }
    }

    @Test fun `official2111 pending and native accessors reach the same actual Channel wrapper handle`() {
        val loader = javaClass.classLoader
        val pendingType = Class.forName("net.md_5.bungee.connection.InitialHandler", false, loader)
        val nativeType = Class.forName("net.md_5.bungee.UserConnection", false, loader)
        val pending = allocate(pendingType) as PendingConnection
        val native = allocate(nativeType) as ProxiedPlayer
        val channel = EmbeddedChannel(object : io.netty.channel.ChannelInboundHandlerAdapter() {})
        try {
            val wrapper = ChannelWrapper(checkNotNull(channel.pipeline().firstContext()))
            pendingType.getDeclaredField("ch").apply { isAccessible = true }.set(pending, wrapper)
            nativeType.getDeclaredField("ch").apply { isAccessible = true }.set(native, wrapper)
            val access = BungeeFloodgateChannels(loader)
            assertSame(channel, access.pending(pending))
            assertSame(channel, access.native(native))
            assertFails { access.pending(PendingFixture().pending) }
            assertFails { access.native(PendingFixture().player) }
        } finally { channel.finishAndReleaseAll() }
    }
}
