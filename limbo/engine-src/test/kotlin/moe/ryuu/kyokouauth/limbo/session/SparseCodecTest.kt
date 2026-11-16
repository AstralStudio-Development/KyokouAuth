package moe.ryuu.kyokouauth.limbo.session

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.network.packet.PacketRegistry
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec
import org.geysermc.mcprotocollib.protocol.data.ProtocolState
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.util.function.Supplier
import kotlin.test.*

/** Complete upstream codec is a test-only oracle; production roots use GeneratedCodec. */
class SparseCodecTest {
    private val full = MinecraftCodec.CODEC
    private val sparse = GeneratedCodec.CODEC

    private fun definitions(registry: PacketRegistry, direction: String): List<Any> {
        val field = registry.javaClass.getDeclaredField(direction).apply { isAccessible = true }
        return (field.get(registry) as Map<*, *>).values.filterNotNull()
    }

    private fun id(definition: Any) = definition.javaClass.getMethod("getId").invoke(definition) as Int
    private fun type(definition: Any) = definition.javaClass.getMethod("getPacketClass").invoke(definition) as Class<*>

    private fun receive(state: ProtocolState, id: Int, payload: ByteBuf): Packet {
        val registry = sparse.getCodec(state)
        val method = registry.javaClass.methods.single { it.name == "createServerboundPacket" }
        return try {
            if (method.parameterCount == 2) method.invoke(registry, id, payload) as Packet
            else {
                val supplier = sparse.javaClass.getMethod("getHelperFactory").invoke(sparse) as Supplier<*>
                method.invoke(registry, id, payload, supplier.get()) as Packet
            }
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    @Test fun `known ignored game payload consumes only the current upstream frame and cannot be sent`() {
        val registry = sparse.getCodec(ProtocolState.GAME)
        val ignored = definitions(full.getCodec(ProtocolState.GAME), "serverbound")
            .filter { runCatching { registry.getServerboundClass(id(it)) }.getOrNull() == IgnoredPlayPacket::class.java }
        assertTrue(ignored.isNotEmpty())
        ignored.forEach { definition ->
            val transport = Unpooled.wrappedBuffer(byteArrayOf(-1, 0, 127, 42))
            try {
                val frame = transport.readSlice(3)
                val packet = receive(ProtocolState.GAME, id(definition), frame)
                assertIs<IgnoredPlayPacket>(packet)
                assertFalse(packet.isTerminal)
                assertFalse(frame.isReadable)
                assertEquals(42, transport.readUnsignedByte().toInt(), "next frame was not consumed")
                val serialize = packet.javaClass.methods.single { it.name == "serialize" }
                val failure = assertFailsWith<InvocationTargetException> {
                    if (serialize.parameterCount == 1) serialize.invoke(packet, frame)
                    else serialize.invoke(packet, frame, (sparse.javaClass.getMethod("getHelperFactory").invoke(sparse) as Supplier<*>).get())
                }
                assertIs<UnsupportedOperationException>(failure.targetException)
            } finally { transport.release() }
        }
    }

    @Test fun `unknown game id and terminal configuration transition are rejected rather than ignored`() {
        val registry = full.getCodec(ProtocolState.GAME)
        val terminal = definitions(registry, "serverbound").single { type(it).simpleName == "ServerboundConfigurationAcknowledgedPacket" }
        val unknown = definitions(registry, "serverbound").maxOf(::id) + 1
        listOf(unknown, id(terminal)).forEach { packetId ->
            val payload = Unpooled.buffer()
            try { assertFailsWith<IllegalArgumentException> { receive(ProtocolState.GAME, packetId, payload) } }
            finally { payload.release() }
        }
    }

    @Test fun `required handshake status login config and readiness packets retain original class and ids`() {
        assertEquals(full.protocolVersion, GeneratedCodec.PROTOCOL)
        assertEquals(full.minecraftVersion, GeneratedCodec.VERSION)
        val required = mapOf(
            ProtocolState.HANDSHAKE to setOf("ClientIntentionPacket"),
            ProtocolState.STATUS to setOf("ServerboundStatusRequestPacket", "ServerboundPingRequestPacket"),
            ProtocolState.LOGIN to setOf("ServerboundHelloPacket", "ServerboundCustomQueryAnswerPacket", "ServerboundLoginAcknowledgedPacket"),
            ProtocolState.CONFIGURATION to setOf("ServerboundFinishConfigurationPacket", "ServerboundSelectKnownPacks", "ServerboundClientInformationPacket", "ServerboundCustomPayloadPacket", "ServerboundPongPacket"),
            ProtocolState.GAME to buildSet {
                addAll(setOf("ServerboundKeepAlivePacket", "ServerboundChunkBatchReceivedPacket", "ServerboundAcceptTeleportationPacket", "ServerboundPingRequestPacket"))
                if (GeneratedCodec.PROTOCOL >= 769) add("ServerboundPlayerLoadedPacket")
                else add("ServerboundMovePlayerPosRotPacket")
            },
        )
        required.forEach { (state, names) ->
            val upstream = definitions(full.getCodec(state), "serverbound").filter { type(it).simpleName in names }
            assertEquals(names, upstream.map { type(it).simpleName }.toSet())
            upstream.forEach { definition -> assertSame(type(definition), sparse.getCodec(state).getServerboundClass(id(definition))) }
        }
        val game = sparse.getCodec(ProtocolState.GAME)
        definitions(full.getCodec(ProtocolState.GAME), "clientbound").forEach { definition ->
            try { assertSame(type(definition), game.getClientboundClass(id(definition))) }
            catch (_: IllegalArgumentException) { /* Outbound packets not used by Limbo have no production codec root. */ }
        }
    }

    @Test fun `thin server protocol owns both initializers without session listeners or auth flags`() {
        val server = SparseMinecraftProtocol()
        assertSame(sparse, server.codec)
        SparseMinecraftProtocol::class.java.declaredMethods.filter { it.name in setOf("newClientSession", "newServerSession") }.forEach { method ->
            val args = arrayOfNulls<Any>(method.parameterCount)
            if (method.parameterCount == 2 && method.parameterTypes[1] == Boolean::class.javaPrimitiveType) args[1] = false
            method.invoke(server, *args)
        }
        assertEquals(ProtocolState.HANDSHAKE, server.inboundState)
        assertEquals(ProtocolState.HANDSHAKE, server.outboundState)
    }
}
