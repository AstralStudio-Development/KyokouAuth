package moe.ryuu.kyokouauth.limbo.session

import io.netty.buffer.ByteBuf
import org.geysermc.mcprotocollib.network.packet.PacketRegistry

internal object Wire {
    val helper = GeneratedCodec.CODEC.helperFactory.get()
    fun writeSection(out: ByteBuf, section: org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection) = helper.writeChunkSection(out, section)
    fun readVarInt(input: ByteBuf) = helper.readVarInt(input)
    fun readString(input: ByteBuf) = helper.readString(input)
    fun readUUID(input: ByteBuf) = helper.readUUID(input)
    fun readProperty(input: ByteBuf) = helper.readProperty(input)
    fun packet(registry: PacketRegistry, id: Int, input: ByteBuf) = registry.createClientboundPacket(id, input, helper)
}
