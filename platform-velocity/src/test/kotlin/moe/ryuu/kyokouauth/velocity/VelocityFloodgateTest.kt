package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.plugin.PluginDescription
import com.velocitypowered.api.plugin.PluginManager
import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.ProxyServer
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.AttributeKey
import moe.ryuu.kyokouauth.proxy.BedrockFailure
import moe.ryuu.kyokouauth.proxy.BedrockLogin
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/** Native adapter seam: installed metadata and a channel read view, never a UUID lookup. */
class VelocityFloodgateTest {
    private val native = stub<InboundConnection> { method ->
        if (method == "isActive") true else error("Unexpected native connection access: $method")
    }

    @Test
    fun `default Java-only installation never resolves an internal channel or loads Floodgate classes`() {
        val adapter = VelocityFloodgate(proxy(null), false) { error("Java-only must not access native internals") }
        assertSame(BedrockLogin.Java, adapter.probe(native))
        adapter.close()
    }

    @Test
    fun `enabled Bedrock requires the native provider before resolving a channel`() {
        assertFailsWith<IllegalStateException> {
            VelocityFloodgate(proxy(null), true) { error("No channel may be accessed during missing-provider startup") }
        }
    }

    @Test
    fun `enabled Bedrock rejects incompatible provider metadata during initialization`() {
        assertFails {
            VelocityFloodgate(proxy(provider("1.0.0")), true) { error("Incompatible provider must fail during startup") }
        }
    }

    @Test
    fun `disabled integration still refuses a current Floodgate channel without loading the SDK`() {
        val channel = EmbeddedChannel()
        try {
            channel.attr(AttributeKey.valueOf<Any>("floodgate-player")).set(Any())
            val adapter = VelocityFloodgate(proxy(provider()), false) { channel }
            assertEquals(BedrockLogin.Rejected(BedrockFailure.DISABLED), adapter.probe(native))
            adapter.close()
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `an ordinary Java channel remains Java when an optional provider is installed but disabled`() {
        val channel = EmbeddedChannel()
        try {
            val adapter = VelocityFloodgate(proxy(provider()), false) { channel }
            assertSame(BedrockLogin.Java, adapter.probe(native))
            adapter.close()
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `each native stage resolves its own channel and never reuses the last probe`() {
        val channel = EmbeddedChannel()
        try {
            var probes = 0
            val adapter = VelocityFloodgate(proxy(provider()), false) { probes++; channel }
            assertSame(BedrockLogin.Java, adapter.probe(native))
            channel.attr(AttributeKey.valueOf<Any>("floodgate-player")).set(Any())
            assertIs<BedrockLogin.Rejected>(adapter.probe(native))
            channel.attr(AttributeKey.valueOf<Any>("floodgate-player")).set(null)
            assertSame(BedrockLogin.Java, adapter.probe(native))
            assertEquals(3, probes)
            adapter.close()
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `unsupported native connection shape and a closed adapter fail closed`() {
        val adapter = VelocityFloodgate(proxy(provider()), false) { error("Unsupported native shape") }
        assertEquals(BedrockLogin.Rejected(BedrockFailure.UNAVAILABLE), adapter.probe(native))
        adapter.close()
        assertEquals(BedrockLogin.Rejected(BedrockFailure.UNAVAILABLE), adapter.probe(native))
    }

    private class InstalledPlugin

    private fun provider(version: String = "2.2.5-SNAPSHOT"): PluginContainer {
        val instance = InstalledPlugin()
        val description = stub<PluginDescription> { method ->
            when (method) {
                "getVersion" -> Optional.of(version)
                else -> error(method)
            }
        }
        return stub { method ->
            when (method) {
                "getInstance" -> Optional.of(instance)
                "getDescription" -> description
                else -> error(method)
            }
        }
    }

    private fun proxy(provider: PluginContainer?): ProxyServer {
        val manager = stub<PluginManager> { method ->
            if (method == "getPlugin") Optional.ofNullable(provider) else error(method)
        }
        return stub { method ->
            if (method == "getPluginManager") manager else error(method)
        }
    }

    private inline fun <reified T> stub(crossinline invoke: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
            when (method.name) {
                "equals" -> self === args?.get(0)
                "hashCode" -> System.identityHashCode(self)
                "toString" -> T::class.java.simpleName
                else -> invoke(method.name)
            }
        } as T
}
