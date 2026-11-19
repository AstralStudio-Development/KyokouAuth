package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.proxy.Player
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelInboundHandler
import io.netty.channel.EventLoop
import io.netty.util.ReferenceCountUtil
import io.netty.util.AbstractReferenceCounted
import io.netty.util.ReferenceCounted
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Public install + the real, decoded Velocity 4.3.1 packets, before its native handler. */
class VelocityClaimCommandsTest {
    private val loader = javaClass.classLoader

    @BeforeEach
    fun requireFixedNativeRuntime() {
        val implementation = try {
            Class.forName("com.velocitypowered.proxy.connection.MinecraftConnection", false, loader)
        } catch (_: ClassNotFoundException) {
            // A fresh checkout may have no cached proxy. A present but unusable JAR is NOT skipped.
            assertFalse(File("run-proxy/velocity.jar").isFile || File("../run-proxy/velocity.jar").isFile,
                "Cached Velocity JAR exists but its native implementation is unavailable on testRuntimeClasspath")
            assumeTrue(false, "Requires the cached, verified run-proxy/velocity.jar (Velocity 4.3.1)")
            return
        }
        assertEquals("4.3.1", implementation.`package`.implementationVersion,
            "An unknown native implementation must fail rather than be treated as a valid runtime")
    }

    @Test
    fun `private unsigned command reaches only its connection-bound receiver`() {
        val channel = EmbeddedChannel()
        val player = player()
        val received = mutableListOf<Pair<Player, String>>()
        val downstream = mutableListOf<Any>()
        val native = nativeConnection(channel)
        channel.pipeline().addLast("handler", native)
        val commands = VelocityClaimCommands(loader, { it.startsWith("ka claim ") }, { p, text -> received += p to text }) { channel }
        try {
            val installed = commands.install(player)
            channel.runPendingTasks()
            installed.join()
            channel.pipeline().addBefore("handler", "spy", object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) { downstream += msg }
            })
            val raw = "ka claim OldName SENTINEL-PRIVATE"
            assertFalse(channel.writeInbound(unsigned(raw)))
            assertEquals(listOf(player to raw), received)
            assertTrue(downstream.isEmpty())
            assertTrue(channel.isOpen)
            val ordinary = unsigned("help")
            channel.writeInbound(ordinary)
            assertSame(ordinary, downstream.single())
            assertEquals(1, received.size)
        } finally {
            commands.close()
            channel.runPendingTasks()
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `completion with a private prefix never reaches the downstream completion dispatcher`() {
        Fixture().use { f ->
            val packet = textPacket("TabCompleteRequestPacket", "command", "/ka claim OldName SENTINEL-COMPLETION")
            assertFalse(f.channel.writeInbound(packet))
            assertTrue(f.received.isEmpty())
            assertTrue(f.downstream.isEmpty())
            assertTrue(f.channel.isOpen)
            val normal = textPacket("TabCompleteRequestPacket", "command", "/help")
            f.channel.writeInbound(normal)
            assertSame(normal, f.downstream.single())
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test
    fun `legacy private chat retains the raw remainder while ordinary chat is untouched`() {
        Fixture().use { f ->
            val raw = "/ka claim OldName  SENTINEL with spaces  "
            f.channel.writeInbound(textPacket("chat.legacy.LegacyChatPacket", "message", raw))
            assertEquals(listOf(f.player to raw), f.received)
            assertTrue(f.downstream.isEmpty())
            val normal = textPacket("chat.legacy.LegacyChatPacket", "message", "hello everyone")
            f.channel.writeInbound(normal)
            assertSame(normal, f.downstream.single())
        }
    }

    @Test
    fun `sensitive modern signed command is refused without receiver event or signature rewriting`() {
        Fixture().use { f ->
            val raw = "ka claim OldName SENTINEL-SIGNED"
            val packet = textPacket("chat.keyed.KeyedPlayerCommandPacket", "command", raw)
            val signature = byteArrayOf(5, 7, 11)
            setField(packet, "arguments", mapOf("password" to signature))
            setField(packet, "salt", 19L)
            assertEquals(false, packet.javaClass.getMethod("isUnsigned").invoke(packet))
            f.channel.writeInbound(packet)
            assertFalse(f.channel.isOpen)
            assertTrue(f.received.isEmpty())
            assertTrue(f.downstream.isEmpty())
            assertTrue(f.errors.isEmpty())
            assertEquals(raw, packet.javaClass.getMethod("getCommand").invoke(packet))
            val arguments = packet.javaClass.getDeclaredField("arguments").apply { isAccessible = true }.get(packet) as Map<*, *>
            assertSame(signature, arguments["password"])
            assertNull(f.channel.readOutbound<Any>())
        }
    }

    @Test
    fun `session command without argument signatures is still refused rather than skipping last-seen state`() {
        Fixture().use { f ->
            val packet = textPacket("chat.session.SessionPlayerCommandPacket", "command", "ka claim SENTINEL-LAST-SEEN")
            setField(packet, "argumentSignatures", native("com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerCommandPacket\$ArgumentSignatures").getConstructor().newInstance())
            val lastSeen = native("com.velocitypowered.proxy.protocol.packet.chat.LastSeenMessages").getConstructor().newInstance()
            setField(packet, "lastSeenMessages", lastSeen)
            assertEquals(false, packet.javaClass.getMethod("isSigned").invoke(packet))
            f.channel.writeInbound(packet)
            assertFalse(f.channel.isOpen)
            assertTrue(f.received.isEmpty())
            assertTrue(f.downstream.isEmpty())
            assertTrue(f.errors.isEmpty())
            assertSame(lastSeen, packet.javaClass.getDeclaredField("lastSeenMessages").apply { isAccessible = true }.get(packet))
        }
    }

    @Test
    fun `modern chat carrying the private prefix cannot be broadcast even when unsigned`() {
        listOf("chat.session.SessionPlayerChatPacket", "chat.keyed.KeyedPlayerChatPacket").forEach { type ->
            Fixture().use { f ->
                f.channel.writeInbound(textPacket(type, "message", "/ka claim SENTINEL-MODERN-CHAT"))
                assertFalse(f.channel.isOpen)
                assertTrue(f.received.isEmpty())
                assertTrue(f.downstream.isEmpty())
                assertTrue(f.errors.isEmpty())
            }
        }
    }

    @Test
    fun `ordinary packets including signed chat keep identity and reference ownership`() {
        Fixture().use { f ->
            val bytes = Unpooled.buffer().writeByte(42)
            val chat = textPacket("chat.session.SessionPlayerChatPacket", "message", "hello")
            setField(chat, "signed", true)
            val signature = byteArrayOf(23, 29)
            setField(chat, "signature", signature)
            listOf(bytes, chat, Any()).forEach { packet ->
                f.channel.writeInbound(packet)
                assertSame(packet, f.downstream.last())
            }
            assertEquals(1, bytes.refCnt())
            assertSame(signature, chat.javaClass.getMethod("getSignature").invoke(chat))
            assertTrue(f.received.isEmpty())
            assertTrue(f.channel.isOpen)
        }
    }

    @Test
    fun `predicate receiver and malformed packet failures never propagate secret errors`() {
        listOf("predicate", "receiver", "getter").forEach { failing ->
            val secret = "SENTINEL-ERROR-$failing"
            Fixture(
                predicate = { if (failing == "predicate") error(secret) else true },
                receiver = { _, _ -> if (failing == "receiver") error(secret) },
            ).use { f ->
                val packet = if (failing == "getter") {
                    native("com.velocitypowered.proxy.protocol.packet.chat.session.UnsignedPlayerCommandPacket").getConstructor().newInstance()
                } else unsigned("ka claim $secret")
                f.channel.writeInbound(packet)
                f.channel.checkException()
                assertFalse(f.channel.isOpen)
                assertTrue(f.downstream.isEmpty())
                assertTrue(f.errors.isEmpty())
                assertNull(f.channel.readOutbound<Any>())
            }
        }
    }

    @Test
    fun `duplicate installation fails closed rather than adding another receiver`() {
        Fixture().use { f ->
            assertRefused(f.commands.install(f.player))
            assertFalse(f.channel.isOpen)
            assertTrue(f.received.isEmpty())
        }
    }

    @Test
    fun `a conflicting handler is never replaced and install fails`() {
        val channel = EmbeddedChannel()
        val collision = ChannelInboundHandlerAdapter()
        val commands = VelocityClaimCommands(loader, { true }, { _, _ -> error("Must not receive") }) { channel }
        try {
            channel.pipeline().addLast("handler", nativeConnection(channel))
            channel.pipeline().addBefore("handler", "kyokouauth-private-claim", collision)
            assertRefused(commands.install(player()))
            assertFalse(channel.isOpen)
        } finally {
            commands.close()
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `missing wrong native handler mismatched channel and inactive channel fail closed`() {
        listOf("missing", "wrong", "mismatched", "inactive").forEach { shape ->
            val channel = EmbeddedChannel()
            val other = EmbeddedChannel()
            val commands = VelocityClaimCommands(loader, { true }, { _, _ -> error("Must not receive") }) { channel }
            try {
                when (shape) {
                    "wrong" -> channel.pipeline().addLast("handler", ChannelInboundHandlerAdapter())
                    "mismatched" -> channel.pipeline().addLast("handler", nativeConnection(other))
                    "inactive" -> channel.close()
                }
                assertRefused(commands.install(player()))
                assertFalse(channel.isOpen)
            } finally {
                commands.close()
                channel.finishAndReleaseAll()
                other.finishAndReleaseAll()
            }
        }
    }

    @Test
    fun `close shuts the original channel cleans its guard and late installs fail without channel lookup`() {
        Fixture().use { f ->
            f.commands.close()
            f.commands.close()
            f.channel.runPendingTasks()
            assertNull(f.channel.pipeline().get("kyokouauth-private-claim"))
            assertFalse(f.channel.isOpen)
            assertRefused(f.commands.install(f.player))
            assertFalse(f.channel.isOpen)
        }
        var lookups = 0
        val commands = VelocityClaimCommands(loader, { true }, { _, _ -> }) { lookups++; error("SENTINEL-CLOSED") }
        commands.close()
        assertRefused(commands.install(player()))
        assertEquals(0, lookups)
    }

    @Test
    fun `a stale exact-channel handler cannot act on a replacement player session`() {
        val first = EmbeddedChannel()
        val second = EmbeddedChannel()
        var current: Channel = first
        val samePlayer = player()
        val received = mutableListOf<String>()
        val commands = VelocityClaimCommands(loader, { true }, { _, text -> received += text }) { current }
        try {
            first.pipeline().addLast("handler", nativeConnection(first))
            commands.install(samePlayer).join()
            val oldContext = first.pipeline().context("kyokouauth-private-claim")
            val oldHandler = oldContext.handler() as ChannelInboundHandler
            first.close()
            first.runPendingTasks()
            current = second
            second.pipeline().addLast("handler", nativeConnection(second))
            commands.install(samePlayer).join()
            oldHandler.channelRead(oldContext, unsigned("ka claim SENTINEL-OLD"))
            assertTrue(second.isOpen)
            assertTrue(received.isEmpty())
            second.writeInbound(unsigned("ka claim NEW-SESSION"))
            assertEquals(listOf("ka claim NEW-SESSION"), received)
        } finally {
            commands.close()
            first.runPendingTasks()
            second.runPendingTasks()
            first.finishAndReleaseAll()
            second.finishAndReleaseAll()
        }
    }

    @Test
    fun `install schedules on the supplied event loop and its future completes only after attachment`() {
        val actual = EmbeddedChannel()
        var onLoop = false
        val queued = ArrayDeque<Runnable>()
        val loop = Proxy.newProxyInstance(loader, arrayOf(EventLoop::class.java)) { _, method, args ->
            when (method.name) {
                "inEventLoop" -> onLoop
                "execute" -> { queued.addLast(args!![0] as Runnable); null }
                else -> error("Unexpected EventLoop access")
            }
        } as EventLoop
        val channel = Proxy.newProxyInstance(loader, arrayOf(Channel::class.java)) { _, method, args ->
            if (method.name == "eventLoop") loop else method.invoke(actual, *(args ?: emptyArray()))
        } as Channel
        val commands = VelocityClaimCommands(loader, { true }, { _, _ -> }) { channel }
        try {
            actual.pipeline().addLast("handler", nativeConnection(channel))
            val result = commands.install(player())
            assertFalse(result.isDone)
            assertNull(actual.pipeline().get("kyokouauth-private-claim"))
            onLoop = true
            queued.removeFirst().run()
            result.join()
            assertTrue(actual.pipeline().get("kyokouauth-private-claim") != null)
        } finally {
            onLoop = true
            commands.close()
            actual.finishAndReleaseAll()
        }
    }

    @Test
    fun `native metadata is pinned to the verified runtime with exact inherited unsigned getter`() {
        val connection = native("com.velocitypowered.proxy.connection.MinecraftConnection")
        assertEquals("4.3.1", connection.`package`.implementationVersion)
        assertSame(ChannelInboundHandlerAdapter::class.java, connection.superclass)
        assertSame(Channel::class.java, connection.getDeclaredMethod("getChannel").returnType)
        assertSame(UUID::class.java, connection.getDeclaredMethod("getSessionId").returnType)
        assertEquals("handler", native("com.velocitypowered.proxy.network.Connections").getDeclaredField("HANDLER").get(null))
        val unsigned = native("com.velocitypowered.proxy.protocol.packet.chat.session.UnsignedPlayerCommandPacket")
        assertEquals("SessionPlayerCommandPacket", unsigned.superclass.simpleName)
        assertSame(unsigned.superclass, unsigned.getMethod("getCommand").declaringClass)
        assertSame(String::class.java, unsigned.getMethod("getCommand").returnType)
        assertSame(Boolean::class.javaPrimitiveType, unsigned.getDeclaredMethod("isSigned").returnType)
        VelocityClaimCommands(loader, { false }, { _, _ -> }).close()
    }

    @Test
    fun `unknown native runtime or channel resolver produces a generic error without its cause`() {
        val broken = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.velocitypowered.proxy.connection.MinecraftConnection") throw ClassNotFoundException("SENTINEL-LOADER")
                return super.loadClass(name, resolve)
            }
        }
        val failure = assertFailsWith<IllegalStateException> { VelocityClaimCommands(broken, { true }, { _, _ -> }) }
        assertEquals("Private command interception unavailable", failure.message)
        assertNull(failure.cause)
        val commands = VelocityClaimCommands(loader, { true }, { _, _ -> }) { error("SENTINEL-RESOLVER") }
        try { assertRefused(commands.install(player())) } finally { commands.close() }
    }

    private inner class Fixture(
        predicate: (String) -> Boolean = { it.removePrefix("/").startsWith("ka claim ") },
        receiver: (Player, String) -> Unit = { _, _ -> },
    ) : AutoCloseable {
        val channel = EmbeddedChannel()
        val player = player()
        val received = mutableListOf<Pair<Player, String>>()
        val downstream = mutableListOf<Any>()
        val errors = mutableListOf<Throwable>()
        val commands = VelocityClaimCommands(loader, predicate, { p, text -> receiver(p, text); received += p to text }) { channel }

        init {
            channel.pipeline().addLast("handler", nativeConnection(channel))
            val installed = commands.install(player)
            channel.runPendingTasks()
            installed.join()
            channel.pipeline().addBefore("handler", "spy", object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) { downstream += msg }
                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) { errors += cause }
            })
        }

        override fun close() {
            commands.close()
            channel.runPendingTasks()
            downstream.forEach { ReferenceCountUtil.release(it) }
            downstream.clear()
            channel.finishAndReleaseAll()
        }
    }

    private fun assertRefused(result: CompletableFuture<Unit>) {
        val failure = assertFailsWith<CompletionException> { result.join() }
        assertEquals("Private command interception unavailable", failure.cause?.message)
        assertNull(failure.cause?.cause)
    }

    private fun textPacket(type: String, field: String, text: String): Any {
        val packet = native("com.velocitypowered.proxy.protocol.packet.$type").getConstructor().newInstance()
        setField(packet, field, text)
        return packet
    }

    private fun setField(packet: Any, field: String, value: Any) {
        packet.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(packet, value)
    }

    private fun nativeConnection(channel: Channel): ChannelHandler {
        val type = native("com.velocitypowered.proxy.connection.MinecraftConnection")
        val server = native("com.velocitypowered.proxy.VelocityServer")
        return type.getConstructor(Channel::class.java, server, UUID::class.java)
            .newInstance(channel, null, UUID.randomUUID()) as ChannelHandler
    }

    private fun unsigned(command: String): Any {
        val type = native("com.velocitypowered.proxy.protocol.packet.chat.session.UnsignedPlayerCommandPacket")
        val packet = type.getConstructor().newInstance()
        type.superclass.getDeclaredField("command").apply { isAccessible = true }.set(packet, command)
        return packet
    }

    private fun native(name: String): Class<*> = Class.forName(name, true, loader)

    private fun player(): Player = Proxy.newProxyInstance(loader, arrayOf(Player::class.java)) { self, method, args ->
        when (method.name) {
            "equals" -> self === args?.get(0)
            "hashCode" -> System.identityHashCode(self)
            "toString" -> "TestPlayer"
            "isActive" -> true
            else -> error("Unexpected Player access")
        }
    } as Player
}

/** Always runs: the same public installation and ownership logic without a cached native runtime. */
class VelocityClaimCommandsEmbeddedTest {
    @Test
    fun `unsigned private packet is consumed once and unrelated reference-counted packet is transferred`() {
        Harness().use { h ->
            val privateFrame = Frame("ka claim SENTINEL-EMBEDDED")
            h.channel.writeInbound(privateFrame)
            assertEquals(listOf("ka claim SENTINEL-EMBEDDED"), h.received)
            assertEquals(0, privateFrame.refCnt())
            assertEquals(1, privateFrame.deallocations)
            assertTrue(h.downstream.isEmpty())
            val ordinary = Frame("help")
            h.channel.writeInbound(ordinary)
            assertSame(ordinary, h.downstream.single())
            assertEquals(1, ordinary.refCnt())
            assertTrue(h.channel.isOpen)
        }
    }

    @Test
    fun `private completion is consumed but signed request closes without delivering either`() {
        Harness().use { h ->
            val completion = Frame("ka claim SENTINEL-TAB", VelocityClaimCommands.Kind.COMPLETION)
            h.channel.writeInbound(completion)
            assertEquals(0, completion.refCnt())
            assertTrue(h.channel.isOpen)
            val signed = Frame("ka claim SENTINEL-SIGNED", VelocityClaimCommands.Kind.SIGNED_FORMAT)
            h.channel.writeInbound(signed)
            assertEquals(0, signed.refCnt())
            assertFalse(h.channel.isOpen)
            assertTrue(h.received.isEmpty())
            assertTrue(h.downstream.isEmpty())
            assertTrue(h.errors.isEmpty())
            assertNull(h.channel.readOutbound<Any>())
        }
    }

    @Test
    fun `throwing predicate or receiver releases exactly once without leaking the exception`() {
        listOf(true, false).forEach { predicateFails ->
            Harness(
                sensitive = { if (predicateFails) error("SENTINEL-PREDICATE") else true },
                receive = { error("SENTINEL-RECEIVER") },
            ).use { h ->
                val frame = Frame("ka claim SENTINEL-FAILURE")
                h.channel.writeInbound(frame)
                h.channel.checkException()
                assertEquals(0, frame.refCnt())
                assertEquals(1, frame.deallocations)
                assertFalse(h.channel.isOpen)
                assertTrue(h.errors.isEmpty())
                assertTrue(h.downstream.isEmpty())
                assertNull(h.channel.readOutbound<Any>())
            }
        }
    }

    @Test
    fun `close keeps the guard until asynchronous transport close so later secrets cannot reach dispatch`() {
        Harness().use { h ->
            val delayed = DeferredClose()
            h.channel.pipeline().addLast("deferred-close", delayed)
            val native = h.channel.pipeline().get("test-native")
            val spy = h.channel.pipeline().get("spy")
            h.commands.close()
            h.channel.runPendingTasks()
            // Simulate a real transport/other shutdown work that has not closed the socket yet.
            assertTrue(h.channel.isOpen)
            assertTrue(h.channel.pipeline().get("kyokouauth-private-claim") != null)
            assertSame(native, h.channel.pipeline().get("test-native"))
            assertSame(spy, h.channel.pipeline().get("spy"))
            val late = Frame("ka claim SENTINEL-DURING-UNLOAD")
            h.channel.writeInbound(late)
            assertEquals(0, late.refCnt())
            assertEquals(1, late.deallocations)
            assertTrue(h.received.isEmpty())
            assertTrue(h.downstream.isEmpty())
            assertTrue(h.errors.isEmpty())
            delayed.complete()
            h.channel.runPendingTasks()
            assertFalse(h.channel.isOpen)
            assertNull(h.channel.pipeline().get("kyokouauth-private-claim"))
        }
    }

    private class DeferredClose : ChannelOutboundHandlerAdapter() {
        private val pending = mutableListOf<Pair<ChannelHandlerContext, ChannelPromise>>()
        override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) { pending += ctx to promise }
        fun complete() {
            val held = pending.toList()
            pending.clear()
            held.forEach { (ctx, promise) -> ctx.close(promise) }
        }
    }

    @Test
    fun `closing shuts its original transport cleans the guard and refuses future installation`() {
        Harness().use { h ->
            h.commands.close()
            h.channel.runPendingTasks()
            assertNull(h.channel.pipeline().get("kyokouauth-private-claim"))
            assertFalse(h.channel.isOpen)
            val error = assertFailsWith<CompletionException> { h.commands.install(h.player).join() }
            assertEquals("Private command interception unavailable", error.cause?.message)
            assertNull(error.cause?.cause)
        }
    }

    private class Frame(
        val text: String,
        val kind: VelocityClaimCommands.Kind = VelocityClaimCommands.Kind.UNSIGNED,
    ) : AbstractReferenceCounted() {
        var deallocations = 0
            private set
        override fun deallocate() { deallocations++ }
        override fun touch(hint: Any?): ReferenceCounted = this
    }

    private class NativeConnection(val channel: Channel) : ChannelInboundHandlerAdapter() {
        val session = UUID.randomUUID()
    }

    private class Harness(
        sensitive: (String) -> Boolean = { it.startsWith("ka claim ") },
        receive: (String) -> Unit = {},
    ) : AutoCloseable {
        val channel = EmbeddedChannel()
        val received = mutableListOf<String>()
        val downstream = mutableListOf<Any>()
        val errors = mutableListOf<Throwable>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { self, method, args ->
            when (method.name) {
                "equals" -> self === args?.get(0)
                "hashCode" -> System.identityHashCode(self)
                "isActive" -> true
                "toString" -> "EmbeddedPlayer"
                else -> error("Unexpected Player access")
            }
        } as Player
        private val native = NativeConnection(channel)
        private val access = object : VelocityClaimCommands.NativeAccess {
            override val handlerName = "test-native"
            override fun isConnection(handler: ChannelHandler, channel: Channel): Boolean =
                handler.javaClass === NativeConnection::class.java && (handler as NativeConnection).channel === channel
            override fun session(handler: ChannelHandler): UUID = (handler as NativeConnection).session
            override fun read(message: Any): VelocityClaimCommands.PacketText? =
                (message as? Frame)?.let { VelocityClaimCommands.PacketText(it.text, it.kind) }
        }
        val commands = VelocityClaimCommands(access, sensitive, { _, text -> receive(text); received += text }, { channel })

        init {
            channel.pipeline().addLast("test-native", native)
            commands.install(player).join()
            channel.pipeline().addBefore("test-native", "spy", object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) { downstream += msg }
                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) { errors += cause }
            })
        }

        override fun close() {
            commands.close()
            channel.runPendingTasks()
            downstream.forEach { ReferenceCountUtil.release(it) }
            downstream.clear()
            channel.finishAndReleaseAll()
        }
    }
}
