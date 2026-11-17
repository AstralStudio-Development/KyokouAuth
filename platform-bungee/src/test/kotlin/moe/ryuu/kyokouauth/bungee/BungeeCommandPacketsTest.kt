package moe.ryuu.kyokouauth.bungee

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.suggestion.Suggestion
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.tree.ArgumentCommandNode
import com.mojang.brigadier.tree.RootCommandNode
import java.lang.reflect.Array as ReflectArray
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.embedded.EmbeddedChannel
import net.md_5.bungee.protocol.DefinedPacket
import net.md_5.bungee.protocol.MinecraftEncoder
import net.md_5.bungee.protocol.PacketWrapper
import net.md_5.bungee.protocol.Protocol
import net.md_5.bungee.protocol.ProtocolConstants
import net.md_5.bungee.protocol.packet.Commands
import net.md_5.bungee.protocol.packet.TabCompleteRequest
import net.md_5.bungee.protocol.packet.TabCompleteResponse
import org.junit.jupiter.api.Test
import kotlin.test.*

class BungeeCommandPacketsTest {
    private class Wire(blocked: String? = null) : AutoCloseable {
        val auth = BungeeCommandFixture(blocked)
        val encoder = MinecraftEncoder(Protocol.GAME, true, 777)
        val channel = EmbeddedChannel(encoder)
        val packets = BungeeCommandPackets(auth.guard, auth.log) { channel }
        init { packets.install(auth.fixture.player); channel.runPendingTasks() }
        override fun close() { packets.close(); channel.runPendingTasks(); channel.finishAndReleaseAll(); auth.guard.close() }
    }

    /** 2111 deliberately serializes integer arguments only as its own opaque forwarding model. */
    @Suppress("UNCHECKED_CAST")
    private fun nativeInteger(minimum: Int, maximum: Int): ArgumentType<Any?> {
        val registryName = Commands::class.java.name + "\$ArgumentRegistry"
        val registry = Class.forName(registryName)
        val serializer = registry.getDeclaredField("INTEGER_RANGE").also { check(it.trySetAccessible()) }.get(null)
        val ids = registry.getDeclaredField("IDS_26_3").also { check(it.trySetAccessible()) }.get(null)
        val key = (0 until ReflectArray.getLength(ids)).single { ReflectArray.get(ids, it) === serializer }
        val serializerType = Class.forName(registryName + "\$ArgumentSerializer")
        val dummy = Class.forName(registryName + "\$DummyType")
            .getDeclaredConstructor(Any::class.java, serializerType, Any::class.java).also { check(it.trySetAccessible()) }
        return dummy.newInstance(key, serializer, IntegerArgumentType.integer(minimum, maximum)) as ArgumentType<Any?>
    }

    private fun assertIntegerPage(packet: Commands) {
        val page = assertIs<ArgumentCommandNode<*, *>>(packet.root.getChild("help").getChild("page"))
        val model = page.type
        val value = model.javaClass.getDeclaredField("value").also { check(it.trySetAccessible()) }.get(model)
        val range = assertIs<IntegerArgumentType>(value)
        assertEquals(1, range.minimum)
        assertEquals(3, range.maximum)
        assertSame(Commands.SuggestionRegistry.ASK_SERVER, page.customSuggestions)
    }

    private fun root(): RootCommandNode<Any?> = RootCommandNode<Any?>().also {
        it.addChild(LiteralArgumentBuilder.literal<Any?>("server").executes { 0 }.build())
        it.addChild(LiteralArgumentBuilder.literal<Any?>("help").executes { 0 }.then(
            RequiredArgumentBuilder.argument<Any?, Any?>("page", nativeInteger(1, 3))
                .suggests(Commands.SuggestionRegistry.ASK_SERVER).executes { 0 },
        ).build())
    }

    /** The actual fixed Bungee encoder generates the bytes and IDs; the test writes no packet fields. */
    private fun encode(tree: RootCommandNode<*>): ByteBuf {
        val encoder = EmbeddedChannel(MinecraftEncoder(Protocol.GAME, true, 777))
        // A released adaptive/pooled source may be recycled as the filter's new encoder output.
        // Keep the source object independent, so refCnt=0 proves ownership rather than pool reuse.
        encoder.config().allocator = UnpooledByteBufAllocator.DEFAULT
        try {
            assertTrue(encoder.writeOutbound(Commands(tree)))
            return assertNotNull(encoder.readOutbound<ByteBuf>())
        } finally { encoder.finishAndReleaseAll() }
    }

    private fun commands(bytes: ByteBuf): Commands = try {
        DefinedPacket.readVarInt(bytes)
        Commands().also {
            it.read(bytes, ProtocolConstants.Direction.TO_CLIENT, 777)
            assertFalse(bytes.isReadable)
        }
    } finally { bytes.release() }

    private fun completion(bytes: ByteBuf): TabCompleteResponse = try {
        DefinedPacket.readVarInt(bytes)
        TabCompleteResponse().also {
            it.read(bytes, ProtocolConstants.Direction.TO_CLIENT, 777)
            assertFalse(bytes.isReadable)
        }
    } finally { bytes.release() }

    private fun response(id: Int, vararg values: String): TabCompleteResponse {
        val range = StringRange.at(1)
        return TabCompleteResponse(id, Suggestions(range, values.map { Suggestion(range, it) }))
    }

    @Test fun `fixed native command signatures are accepted without accessing player credentials`() {
        val h = BungeeCommandFixture()
        BungeeCommandPackets(h.guard, h.log).close()
    }

    @Test fun `typed native tree is hidden before authentication and fresh backend tree restores it afterwards`() {
        Wire().use { h ->
            val original = root()
            assertTrue(h.channel.writeOutbound(Commands(original)))
            val held = commands(assertNotNull(h.channel.readOutbound<ByteBuf>()))
            assertNull(held.root.getChild("server"))
            assertNotNull(held.root.getChild("help"))
            assertIntegerPage(held)
            assertNotNull(original.getChild("server"), "Never mutate a root that another connection/backend may reuse")
            h.auth.authenticate()
            assertTrue(h.channel.writeOutbound(Commands(original)))
            val restored = commands(assertNotNull(h.channel.readOutbound<ByteBuf>()))
            assertEquals(setOf("server", "help"), restored.root.children.map { it.name }.toSet())
            assertIntegerPage(restored)
            assertNull(restored.root.getChild("other-plugin-denied"), "Restoration only forwards nodes in the new backend tree")
        }
    }

    @Test fun `raw native pass-through command buffer is re-encoded by the native codec and released once`() {
        Wire().use { h ->
            val raw = encode(root())
            assertTrue(h.channel.writeOutbound(raw))
            assertEquals(0, raw.refCnt())
            val filtered = commands(assertNotNull(h.channel.readOutbound<ByteBuf>()))
            assertNull(filtered.root.getChild("server"))
            assertNotNull(filtered.root.getChild("help"))
        }
    }

    @Test fun `wrapper command buffer replacement has exactly one owner and cannot double release`() {
        Wire().use { h ->
            val tree = root()
            val raw = encode(tree)
            val wrapper = PacketWrapper(Commands(tree), raw, Protocol.GAME)
            assertTrue(h.channel.writeOutbound(wrapper))
            assertEquals(0, raw.refCnt())
            wrapper.trySingleRelease()
            assertEquals(0, raw.refCnt())
            assertNull(commands(assertNotNull(h.channel.readOutbound<ByteBuf>())).root.getChild("server"))
        }
    }

    @Test fun `unchanged wrapper preserves native bytes and releases its transfer reference once`() {
        Wire("[]").use { h ->
            val tree = root()
            val raw = encode(tree)
            val wrapper = PacketWrapper(Commands(tree), raw, Protocol.GAME)
            assertTrue(h.channel.writeOutbound(wrapper))
            assertEquals(1, raw.refCnt())
            assertNotNull(commands(assertNotNull(h.channel.readOutbound<ByteBuf>())).root.getChild("server"))
            wrapper.trySingleRelease()
            assertEquals(0, raw.refCnt())
        }
    }

    @Test fun `malformed raw command payload fails closed without forwarding or leaking a buffer`() {
        Wire().use { h ->
            val encoded = encode(root())
            val truncated = encoded.copy(encoded.readerIndex(), encoded.readableBytes() - 1)
            encoded.release()
            runCatching { h.channel.writeOutbound(truncated) }
            assertFalse(h.channel.isActive)
            assertEquals(0, truncated.refCnt())
            assertNull(h.channel.readOutbound<Any>())
        }
    }

    @Test fun `non-game raw buffer never uses the play command ID or codec`() {
        Wire().use { h ->
            h.encoder.protocol = Protocol.CONFIGURATION
            val raw = encode(root())
            val expected = ByteArray(raw.readableBytes()).also { raw.getBytes(raw.readerIndex(), it) }
            assertTrue(h.channel.writeOutbound(raw))
            val forwarded = assertNotNull(h.channel.readOutbound<ByteBuf>())
            val actual = ByteArray(forwarded.readableBytes()).also { forwarded.readBytes(it) }
            assertContentEquals(expected, actual)
            forwarded.release()
            assertEquals(0, raw.refCnt())
            assertTrue(h.channel.isActive)
        }
    }

    @Test fun `modern blocked request is consumed before provider dispatch and its late response cannot revive`() {
        Wire().use { h ->
            val storage = Unpooled.buffer().writeZero(8) // Ownership sentinel, not a wire-format fixture.
            val request = PacketWrapper(TabCompleteRequest(7, "/server hidden-argument"), storage, Protocol.GAME)
            assertFalse(h.channel.writeInbound(request))
            assertEquals(0, storage.refCnt())
            request.trySingleRelease()
            assertNull(h.channel.readInbound<Any>())
            h.auth.authenticate()
            assertFalse(h.channel.writeOutbound(response(7, "backend")))
            assertNull(h.channel.readOutbound<Any>())
            assertTrue(h.channel.writeInbound(TabCompleteRequest(8, "/server ")))
            assertNotNull(h.channel.readInbound<TabCompleteRequest>())
            assertTrue(h.channel.writeOutbound(response(8, "backend")))
            assertEquals(listOf("backend"), completion(assertNotNull(h.channel.readOutbound<ByteBuf>())).suggestions.list.map { it.text })
        }
    }

    @Test fun `modern transaction IDs distinguish root completion from unrelated argument values`() {
        Wire().use { h ->
            assertTrue(h.channel.writeInbound(TabCompleteRequest(1, "/help ")))
            h.channel.readInbound<TabCompleteRequest>()
            assertTrue(h.channel.writeInbound(TabCompleteRequest(2, "/se")))
            h.channel.readInbound<TabCompleteRequest>()
            assertTrue(h.channel.writeOutbound(response(1, "server", "help")))
            assertEquals(listOf("server", "help"), completion(assertNotNull(h.channel.readOutbound<ByteBuf>())).suggestions.list.map { it.text })
            assertTrue(h.channel.writeOutbound(response(2, "server", "help")))
            assertEquals(listOf("help"), completion(assertNotNull(h.channel.readOutbound<ByteBuf>())).suggestions.list.map { it.text })
        }
    }

    @Test fun `completion correlation is bounded and handler removal clears it`() {
        Wire().use { h ->
            for (id in 0 until 1_000) assertTrue(h.auth.guard.beginCompletion(h.auth.fixture.player, id, "/help argument-$id"))
            assertEquals(64, (0 until 1_000).count { h.auth.guard.takeCompletion(h.auth.fixture.player, it) != null })
            h.auth.guard.beginCompletion(h.auth.fixture.player, 1_001, "/help ")
            h.packets.remove(h.auth.fixture.player)
            h.channel.runPendingTasks()
            assertNull(h.auth.guard.takeCompletion(h.auth.fixture.player, 1_001))
        }
    }

    @Test fun `an old native channel never borrows authentication from a same-UUID replacement`() {
        Wire().use { h ->
            h.auth.authenticate()
            val old = h.auth.reconnect()
            h.auth.authenticate()
            assertEquals(old.uuid, h.auth.fixture.uuid)
            assertTrue(h.channel.writeOutbound(Commands(root())))
            assertNull(commands(assertNotNull(h.channel.readOutbound<ByteBuf>())).root.getChild("server"))
        }
    }

    @Test fun `another plugins cancelled completion is not resurrected by later authentication`() {
        Wire().use { h ->
            assertTrue(h.channel.writeInbound(TabCompleteRequest(9, "/help ")))
            h.channel.readInbound<TabCompleteRequest>()
            val cancelled = net.md_5.bungee.api.event.TabCompleteEvent(h.auth.fixture.player, null, "/help ", mutableListOf<String>())
            cancelled.isCancelled = true
            h.auth.guard.onTab(cancelled)
            h.auth.authenticate()
            assertFalse(h.channel.writeOutbound(response(9, "must-stay-denied")))
            assertNull(h.channel.readOutbound<Any>())
        }
    }

    @Test fun `untracked modern response is dropped only while exact authentication restrictions apply`() {
        Wire().use { held ->
            assertFalse(held.channel.writeOutbound(response(123, "must-not-leak", "server")))
            assertNull(held.channel.readOutbound<Any>())
            assertTrue(held.channel.isActive)
        }
        Wire("[]").use { open ->
            assertTrue(open.channel.writeOutbound(response(123, "must-not-leak", "server")))
            assertEquals(listOf("must-not-leak", "server"), completion(assertNotNull(open.channel.readOutbound<ByteBuf>())).suggestions.list.map { it.text })
        }
        Wire().use { authenticated ->
            authenticated.auth.authenticate()
            assertTrue(authenticated.channel.writeOutbound(response(123, "must-not-leak", "server")))
            assertEquals(listOf("must-not-leak", "server"), completion(assertNotNull(authenticated.channel.readOutbound<ByteBuf>())).suggestions.list.map { it.text })
        }
    }

    @Test fun `legacy response without a modern transaction is filtered rather than dropped`() {
        Wire().use { h ->
            h.encoder.protocolVersion = ProtocolConstants.MINECRAFT_1_12_2
            assertTrue(h.channel.writeOutbound(TabCompleteResponse(listOf("server", "help"))))
            val bytes = assertNotNull(h.channel.readOutbound<ByteBuf>())
            try {
                DefinedPacket.readVarInt(bytes)
                val legacy = TabCompleteResponse().also {
                    it.read(bytes, ProtocolConstants.Direction.TO_CLIENT, ProtocolConstants.MINECRAFT_1_12_2)
                }
                assertEquals(listOf("help"), legacy.commands)
                assertFalse(bytes.isReadable)
            } finally { bytes.release() }
        }
    }

    @Test fun `empty list preserves modern native root suggestions and tree contents`() {
        Wire("[]").use { h ->
            assertTrue(h.channel.writeInbound(TabCompleteRequest(3, "/")))
            h.channel.readInbound<TabCompleteRequest>()
            assertTrue(h.channel.writeOutbound(response(3, "server", "help")))
            assertEquals(listOf("server", "help"), completion(assertNotNull(h.channel.readOutbound<ByteBuf>())).suggestions.list.map { it.text })
            assertTrue(h.channel.writeOutbound(Commands(root())))
            assertNotNull(commands(assertNotNull(h.channel.readOutbound<ByteBuf>())).root.getChild("server"))
        }
    }
}
