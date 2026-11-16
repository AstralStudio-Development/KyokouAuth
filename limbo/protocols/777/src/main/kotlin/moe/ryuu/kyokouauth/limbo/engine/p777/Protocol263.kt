package moe.ryuu.kyokouauth.limbo.session

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacketSerializer
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes
import org.geysermc.mcprotocollib.protocol.data.ProtocolState
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket

/** Pinned upstream compatibility fix, independently checked against Mojang's 26.3 codec.
 * MCProtocolLib 26.3-4 still encodes absent previous mode as the old BYTE -1, not OPTIONAL_VAR_INT 0.
 * All other serialization is delegated unchanged; do not replace null with a fictional game mode.
 */
internal class Login263(original: ClientboundLoginPacket) : ClientboundLoginPacket(
    original.entityId, original.isHardcore, original.worldNames, original.maxPlayers,
    original.viewDistance, original.simulationDistance, original.isReducedDebugInfo,
    original.isEnableRespawnScreen, original.isDoLimitedCrafting, original.commonPlayerSpawnInfo,
    original.isOnlineMode, original.isEnforcesSecureChat,
) {
    override fun serialize(out: ByteBuf) {
        val spawn = Unpooled.buffer()
        try {
            MinecraftTypes.writePlayerSpawnInfo(spawn, commonPlayerSpawnInfo)
            MinecraftTypes.readVarInt(spawn)
            MinecraftTypes.readResourceLocation(spawn)
            spawn.readLong()
            spawn.readByte()
            val previousOffset = spawn.readerIndex()
            val start = out.writerIndex()
            super.serialize(out)
            val fieldOffset = out.writerIndex() - 2 - spawn.writerIndex() + previousOffset
            check(fieldOffset >= start && out.getByte(fieldOffset) == spawn.getByte(previousOffset))
            out.setByte(fieldOffset, commonPlayerSpawnInfo.previousGamemode?.ordinal?.plus(1) ?: 0)
        } finally { spawn.release() }
    }
}

internal object Protocol263 {
    init {
        val registry = GeneratedCodec.CODEC.getCodec(ProtocolState.GAME)
        val id = registry.getClientboundId(ClientboundLoginPacket::class.java)
        val original = registry.getClientboundDefinition(id)
        registry.registerClientbound(id, Login263::class.java, MinecraftPacketSerializer { input ->
            Login263(ClientboundLoginPacket(input))
        })
        // Preserve normal client decoding; the subclass only supplies our server's corrected output.
        registry.registerClientbound(original)
    }
    fun ensureReady() = Unit
    fun server() = SparseMinecraftProtocol()
}
