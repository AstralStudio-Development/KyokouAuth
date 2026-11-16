package moe.ryuu.kyokouauth.limbo.session

import io.netty.buffer.Unpooled
import moe.ryuu.kyokouauth.limbo.LimboSettings
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerAbilitiesPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.*
import java.util.BitSet
import kotlin.math.floor

/** World semantics shared by engines; library constructors/serializers own each version's layout. */
internal class LimboWorld(private val settings: LimboSettings, private val registry: RegistrySnapshot) {
    private val sectionCount = registry.height / 16
    private val chunkData: ByteArray
    private val light: LightUpdateData
    val chunkX = Math.floorDiv(floor(settings.spawn.x).toInt(), 16)
    val chunkZ = Math.floorDiv(floor(settings.spawn.z).toInt(), 16)
    val chunkCount = (settings.viewDistance * 2 + 1).let { it * it }

    init {
        ProtocolPackets.server() // Validate version-specific compatibility registrations before any write.
        val biome = registry.biomeId(requireNotNull(settings.biomeKey) { "Facade must resolve the biome resource key" })
        require(settings.spawn.y >= registry.minY && settings.spawn.y < registry.minY + registry.height)
        val buffer = Unpooled.buffer()
        try {
            val section = ProtocolPackets.section(biome, registry.biomeCount)
            repeat(sectionCount) { Wire.writeSection(buffer, section) }
            chunkData = ByteArray(buffer.readableBytes()).also { buffer.readBytes(it) }
        } finally { buffer.release() }
        val mask = BitSet(sectionCount + 2).apply { set(0, sectionCount + 2) }
        light = LightUpdateData(mask, BitSet(), BitSet(), mask.clone() as BitSet,
            List(sectionCount + 2) { ByteArray(2048) { 0xff.toByte() } }, emptyList())
    }

    fun joinPackets(teleportId: Int): List<Packet> = buildList {
        add(ProtocolPackets.join(settings, registry))
        add(ClientboundPlayerAbilitiesPacket(true, true, true, false, 0f, 0f))
        add(ClientboundGameEventPacket(GameEvent.LEVEL_CHUNKS_LOAD_START, null))
        add(ClientboundSetChunkCacheRadiusPacket(settings.viewDistance))
        add(ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ))
        add(ClientboundChunkBatchStartPacket())
        for (x in chunkX - settings.viewDistance..chunkX + settings.viewDistance) {
            for (z in chunkZ - settings.viewDistance..chunkZ + settings.viewDistance) add(chunk(x, z))
        }
        add(ClientboundChunkBatchFinishedPacket(chunkCount))
        add(ProtocolPackets.teleport(settings, teleportId))
    }

    fun chunk(x: Int, z: Int): Packet {
        val bits = 32 - Integer.numberOfLeadingZeros(registry.height)
        val words = (256 + (64 / bits) - 1) / (64 / bits)
        return ProtocolPackets.chunk(x, z, chunkData, LongArray(words), light)
    }
}
