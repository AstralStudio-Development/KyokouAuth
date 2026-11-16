package moe.ryuu.kyokouauth.limbo.session

import io.netty.buffer.ByteBuf
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes
import org.geysermc.mcprotocollib.network.packet.PacketRegistry

internal object Wire {
    fun writeSection(out: ByteBuf, section: org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection) = MinecraftTypes.writeChunkSection(out, section)
    fun readVarInt(input: ByteBuf) = MinecraftTypes.readVarInt(input)
    fun readString(input: ByteBuf) = MinecraftTypes.readString(input)
    fun readUUID(input: ByteBuf) = MinecraftTypes.readUUID(input)
    fun readProperty(input: ByteBuf) = MinecraftTypes.readProperty(input)
    fun packet(registry: PacketRegistry, id: Int, input: ByteBuf) = registry.createClientboundPacket(id, input)
}
