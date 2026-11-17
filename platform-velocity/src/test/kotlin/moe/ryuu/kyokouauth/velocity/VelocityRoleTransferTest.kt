package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.Player
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import io.netty.channel.Channel
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CompletionException
import kotlin.test.*

class VelocityRoleTransferTest {
    private data class Packet(val key: Key, val bytes: ByteArray?)
    private val key = Key.key("kyokouauth:role_transfer")
    private fun connection(protocol: Int = 777): InboundConnection = Proxy.newProxyInstance(javaClass.classLoader,
        arrayOf(InboundConnection::class.java)) { _, method, _ -> when (method.name) {
        "isActive" -> true
        "getProtocolVersion" -> ProtocolVersion.getProtocolVersion(protocol)
        else -> error(method.name)
    } } as InboundConnection

    private fun access(channel: Channel) = object : VelocityCookieAccess {
        override fun channel(connection: InboundConnection) = channel
        override fun request(): Any = Packet(key, null)
        override fun response(packet: Any): VelocityCookieResponse? =
            (packet as? Packet)?.let { VelocityCookieResponse(it.key, it.bytes) }
    }

    private fun channel() = EmbeddedChannel().apply { pipeline().addLast("minecraft-decoder", ChannelInboundHandlerAdapter()) }

    @Test fun `absent native cookie returns null and overlong payload is rejected`() {
        for (payload in listOf(null, ByteArray(513))) {
            val channel = channel()
            try {
                VelocityRoleTransfer(access(channel)).use { bridge ->
                    val future = bridge.retrieve(connection())
                    channel.runPendingTasks()
                    channel.writeInbound(Packet(key, payload))
                    if (payload == null) assertNull(future.join()) else assertFailsWith<CompletionException> { future.join() }
                    assertFalse(channel.pipeline().names().any { it.startsWith("kyokouauth-role-cookie-") })
                }
            } finally { channel.finishAndReleaseAll() }
        }
    }

    @Test fun `shutdown disconnect cancellation timeout and decoder loss remove the interceptor`() {
        for (case in listOf("shutdown", "disconnect", "cancel", "timeout", "decoder")) {
            val channel = channel()
            val bridge = VelocityRoleTransfer(access(channel))
            try {
                val connection = connection()
                if (case == "decoder") channel.pipeline().remove("minecraft-decoder")
                val future = bridge.retrieve(connection)
                channel.runPendingTasks()
                when (case) {
                    "shutdown" -> bridge.close()
                    "disconnect" -> channel.close()
                    "cancel" -> bridge.cancel(connection)
                    "timeout" -> { channel.advanceTimeBy(4, TimeUnit.SECONDS); channel.runScheduledPendingTasks() }
                }
                channel.runPendingTasks()
                assertFailsWith<CompletionException> { future.join() }
                assertFalse(channel.pipeline().names().any { it.startsWith("kyokouauth-role-cookie-") })
            } finally { bridge.close(); channel.finishAndReleaseAll() }
        }
    }

    @Test fun `duplicate exact channel request and unsupported protocol send no second packet`() {
        val channel = channel()
        try {
            VelocityRoleTransfer(access(channel)).use { bridge ->
                val first = bridge.retrieve(connection())
                channel.runPendingTasks()
                assertNotNull(channel.readOutbound<Any>())
                assertFailsWith<CompletionException> { bridge.retrieve(connection()).join() }
                assertFailsWith<CompletionException> { bridge.retrieve(connection(766)).join() }
                assertNull(channel.readOutbound<Any>())
                channel.writeInbound(Packet(key, null))
                assertNull(first.join())
            }
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun `native player store precedes transfer and null hint never stores a cookie`() {
        val channel = channel()
        val calls = mutableListOf<String>()
        var stored: ByteArray? = null
        val player = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Player::class.java)) { _, method, args -> when (method.name) {
            "isActive" -> true
            "getProtocolVersion" -> ProtocolVersion.getProtocolVersion(777)
            "storeCookie" -> { assertEquals(key, args[0]); stored = args[1] as ByteArray; calls += "store"; null }
            "transferToHost" -> {
                val target = args[0] as InetSocketAddress
                assertEquals("proxy.example", target.hostString)
                assertEquals(25565, target.port)
                calls += "transfer"; null
            }
            else -> error(method.name)
        } } as Player
        val bridge = VelocityRoleTransfer(access(channel))
        try {
            val bytes = byteArrayOf(3, 4)
            bridge.transfer(player, "proxy.example", 25565, bytes).join()
            bytes[0] = 7
            assertContentEquals(byteArrayOf(3, 4), stored)
            bridge.transfer(player, "proxy.example", 25565, null).join()
            assertEquals(listOf("store", "transfer", "transfer"), calls)
            assertFailsWith<CompletionException> { bridge.transfer(player, "bad host", 25565, null).join() }
            assertFailsWith<CompletionException> { bridge.transfer(player, "proxy.example", 0, null).join() }
            assertFailsWith<CompletionException> { bridge.transfer(player, "proxy.example", 25565, ByteArray(513)).join() }
            bridge.close()
            assertFailsWith<CompletionException> { bridge.transfer(player, "proxy.example", 25565, null).join() }
            assertEquals(3, calls.size)
        } finally { bridge.close(); channel.finishAndReleaseAll() }
    }

    @Test fun `only exact key on exact channel completes pending cookie and removes interceptor first`() {
        val channel = channel()
        val other = channel()
        try {
            VelocityRoleTransfer(access(channel)).use { bridge ->
                val result = bridge.retrieve(connection())
                channel.runPendingTasks()
                assertNotNull(channel.readOutbound<Any>())
                val unrelated = Packet(Key.key("other:cookie"), byteArrayOf(1))
                channel.writeInbound(unrelated)
                assertSame(unrelated, channel.readInbound<Any>())
                other.writeInbound(Packet(key, byteArrayOf(9)))
                assertFalse(result.isDone)
                var removedBeforeResume = false
                result.thenRun { removedBeforeResume = channel.pipeline().names().none { it.startsWith("kyokouauth-role-cookie-") } }
                val bytes = byteArrayOf(4, 2)
                channel.writeInbound(Packet(key, bytes))
                assertContentEquals(byteArrayOf(4, 2), result.join())
                bytes[0] = 7
                assertContentEquals(byteArrayOf(4, 2), result.join())
                assertTrue(removedBeforeResume)
                assertNull(channel.readInbound<Any>())
            }
        } finally { channel.finishAndReleaseAll(); other.finishAndReleaseAll() }
    }
}
