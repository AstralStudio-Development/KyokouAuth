package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VelocityPortsTest {
    @Test
    fun `private engines remain connectable without entering public server lookup or enumeration`() {
        val fixture = Fixture()
        val backend = nativeServer(ServerInfo("lobby", InetSocketAddress("127.0.0.1", 25566)))
        fixture.publicServers["lobby"] = backend
        val ports = VelocityPorts(fixture.proxy)

        for (protocol in 767..777) {
            val engine = ports.registerEngine(protocol, InetSocketAddress("127.0.0.1", 28000 + protocol))
            assertSame(engine, ports.getServer(engine.name).orElseThrow())
            assertEquals(engine.name, ports.unwrap(engine).serverInfo.name)
            assertFalse(fixture.proxy.getServer(engine.name).isPresent, "private endpoint leaked through native lookup")
        }

        assertEquals(listOf("lobby"), fixture.proxy.allServers.map { it.serverInfo.name })
        assertSame(backend, ports.unwrap(ports.getServer("lobby").orElseThrow()))
    }

    @Test
    fun `same named event targets cannot replace a private endpoint handle`() {
        val fixture = Fixture()
        val ports = VelocityPorts(fixture.proxy)
        val engine = ports.registerEngine(777, InetSocketAddress("127.0.0.1", 28777))
        val native = ports.unwrap(engine)
        val impostor = ports.server(nativeServer(native.serverInfo))

        assertNotSame(engine, impostor)
        assertFailsWith<IllegalStateException> { ports.unwrap(impostor) }
        assertSame(engine, ports.server(native))
        assertSame(engine, ports.getServer(engine.name).orElseThrow())
        assertSame(native, ports.unwrap(engine))
    }

    @Test
    fun `public name collisions deny the private target without removing unrelated registrations`() {
        val fixture = Fixture()
        val ports = VelocityPorts(fixture.proxy)
        val engine = ports.registerEngine(777, InetSocketAddress("127.0.0.1", 28777))
        val collision = nativeServer(ServerInfo(engine.name, InetSocketAddress("127.0.0.1", 9)))
        fixture.publicServers[engine.name] = collision

        assertTrue(ports.getServer(engine.name).isEmpty)
        assertFailsWith<IllegalStateException> { ports.unwrap(engine) }
        ports.unregisterEngines()
        assertSame(collision, fixture.proxy.getServer(engine.name).orElseThrow())
    }

    @Test
    fun `closing private endpoints invalidates old handles and leaves public servers unchanged`() {
        val fixture = Fixture()
        val backend = nativeServer(ServerInfo("lobby", InetSocketAddress("127.0.0.1", 25566)))
        fixture.publicServers["lobby"] = backend
        val ports = VelocityPorts(fixture.proxy)
        val old = ports.registerEngine(777, InetSocketAddress("127.0.0.1", 28777))
        val retiredNative = ports.unwrap(old)
        ports.unregisterEngines()
        ports.unregisterEngines()

        assertTrue(ports.getServer(old.name).isEmpty)
        assertFailsWith<IllegalStateException> { ports.unwrap(old) }
        fixture.publicServers[old.name] = retiredNative
        assertTrue(ports.getServer(old.name).isEmpty)
        assertFailsWith<IllegalStateException> { ports.unwrap(ports.server(retiredNative)) }
        fixture.publicServers.remove(old.name)
        val replacement = ports.registerEngine(777, InetSocketAddress("127.0.0.1", 29777))
        assertNotSame(old, replacement)
        assertFailsWith<IllegalStateException> { ports.unwrap(old) }
        assertEquals(listOf(backend), fixture.proxy.allServers.toList())
    }

    @Test
    fun `duplicate protocol unknown protocol and reserved public name are rejected`() {
        val fixture = Fixture()
        val ports = VelocityPorts(fixture.proxy)
        val address = InetSocketAddress("127.0.0.1", 28777)
        ports.registerEngine(777, address)
        assertFailsWith<IllegalStateException> { ports.registerEngine(777, address) }
        assertFailsWith<IllegalArgumentException> { ports.registerEngine(778, address) }
        val reserved = ServerInfo("${LoginGate.LIMBO_SERVER_NAME}-767", address)
        fixture.publicServers[reserved.name] = nativeServer(reserved)
        assertFailsWith<IllegalStateException> { ports.registerEngine(767, address) }
    }

    private class Fixture {
        val publicServers = linkedMapOf<String, RegisteredServer>()
        val proxy: ProxyServer = stub { method, arguments ->
            when (method) {
                "getServer" -> Optional.ofNullable(publicServers[arguments[0]])
                "getAllServers" -> publicServers.values.toList()
                "registerServer" -> nativeServer(arguments[0] as ServerInfo).also {
                    publicServers[it.serverInfo.name] = it
                }
                "createRawRegisteredServer" -> nativeServer(arguments[0] as ServerInfo)
                "unregisterServer" -> { publicServers.remove((arguments[0] as ServerInfo).name); Unit }
                else -> error("Unexpected proxy call: $method")
            }
        }
    }

    companion object {
        private fun nativeServer(info: ServerInfo): RegisteredServer = stub { method, _ ->
            when (method) {
                "getServerInfo" -> info
                else -> error("Unexpected server call: $method")
            }
        }

        private inline fun <reified T> stub(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, arguments ->
                when (method.name) {
                    "equals" -> self === arguments?.get(0)
                    "hashCode" -> System.identityHashCode(self)
                    "toString" -> T::class.java.simpleName
                    else -> invoke(method.name, arguments ?: emptyArray())
                }
            } as T
    }
}
