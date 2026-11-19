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

/** Immutable initial world: chunk sections and packet metadata are built once per engine. */
internal class LimboWorld(private val settings: LimboSettings, private val registry: RegistrySnapshot) {
    private val sectionCount = registry.height / 16
    private val biome = registry.biomeId(requireNotNull(settings.biomeKey) { "Facade must resolve the biome resource key" })
    private val emptySection = serialize { Wire.writeSection(it, newSection()) }
    private val emptyChunk = ByteArray(emptySection.size * sectionCount).also { bytes ->
        repeat(sectionCount) { emptySection.copyInto(bytes, it * emptySection.size) }
    }
    private val light = run {
        val mask = BitSet(sectionCount + 2).apply { set(0, sectionCount + 2) }
        LightUpdateData(mask, BitSet(), BitSet(), mask.clone() as BitSet,
            List(sectionCount + 2) { ByteArray(2048) { 0xff.toByte() } }, emptyList())
    }
    private val heightBits = 32 - Integer.numberOfLeadingZeros(registry.height)
    private val heightsPerWord = 64 / heightBits
    private val emptyHeightmap = LongArray((256 + heightsPerWord - 1) / heightsPerWord)
    val chunkX = Math.floorDiv(floor(settings.spawn.x).toInt(), 16)
    val chunkZ = Math.floorDiv(floor(settings.spawn.z).toInt(), 16)
    val chunkCount = (settings.viewDistance * 2 + 1).let { it * it }
    private val chunkXs = chunkX - settings.viewDistance..chunkX + settings.viewDistance
    private val chunkZs = chunkZ - settings.viewDistance..chunkZ + settings.viewDistance
    private val populated = settings.map?.let(::prepareMap).orEmpty()
    private val initialPackets = buildList {
        ProtocolPackets.server() // Validate fixed upstream compatibility before any connection.
        require(settings.spawn.y >= registry.minY && settings.spawn.y < registry.minY + registry.height)
        add(ProtocolPackets.join(settings, registry))
        add(ClientboundPlayerAbilitiesPacket(true, true, true, false, 0f, 0f))
        add(ClientboundGameEventPacket(GameEvent.LEVEL_CHUNKS_LOAD_START, null))
        add(ClientboundSetChunkCacheRadiusPacket(settings.viewDistance))
        add(ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ))
        add(ClientboundChunkBatchStartPacket())
        for (x in chunkXs) for (z in chunkZs) add(chunk(x, z))
        add(ClientboundChunkBatchFinishedPacket(chunkCount))
    }

    fun joinPackets(teleportId: Int): List<Packet> = initialPackets + ProtocolPackets.teleport(settings, teleportId)

    fun chunk(x: Int, z: Int): Packet = populated[x to z]
        ?: ProtocolPackets.chunk(x, z, emptyChunk, emptyHeightmap, light)

    private fun prepareMap(layout: LimboSettings.MapLayout): Map<Pair<Int, Int>, Packet> {
        val schematic = layout.schematic
        val endX = layout.x.toLong() + schematic.width - 1
        val endZ = layout.z.toLong() + schematic.length - 1
        val endY = layout.y.toLong() + schematic.height - 1
        require(layout.x.toLong() >= chunkXs.first.toLong() * 16 && endX < (chunkXs.last.toLong() + 1) * 16 &&
            layout.z.toLong() >= chunkZs.first.toLong() * 16 && endZ < (chunkZs.last.toLong() + 1) * 16) {
            "Schematic must fit in the initial chunks around limbo.spawn (limbo.view-distance)"
        }
        require(layout.y >= registry.minY && endY < registry.minY + registry.height) { "Schematic exceeds the Limbo dimension's Y bounds" }
        val states = SchematicStates.load(layout)
        val populated = HashMap<Pair<Int, Int>, Packet>()
        val startX = Math.floorDiv(layout.x, 16)
        val startZ = Math.floorDiv(layout.z, 16)
        for (x in startX..Math.floorDiv(endX.toInt(), 16)) for (z in startZ..Math.floorDiv(endZ.toInt(), 16)) {
            val sections = arrayOfNulls<org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection>(sectionCount)
            val fluidCounts = IntArray(sectionCount)
            val heights = IntArray(256)
            for (y in 0 until schematic.height) {
                val localY = layout.y + y - registry.minY
                val sectionId = localY ushr 4
                for (worldZ in maxOf(layout.z, z * 16)..minOf(endZ.toInt(), z * 16 + 15)) {
                    for (worldX in maxOf(layout.x, x * 16)..minOf(endX.toInt(), x * 16 + 15)) {
                        val index = schematic.paletteIndex(worldX - layout.x, y, worldZ - layout.z)
                        val state = states.id(index)
                        if (state == 0) continue
                        val section = sections[sectionId] ?: newSection(states.registrySize).also { sections[sectionId] = it }
                        section.setBlock(worldX and 15, localY and 15, worldZ and 15, state)
                        if (states.hasFluid(index)) fluidCounts[sectionId]++
                        if (states.blocksMotion(index)) heights[(worldZ and 15) * 16 + (worldX and 15)] = localY + 1
                    }
                }
            }
            if (sections.all { it == null }) continue
            val bytes = serialize { out ->
                sections.forEachIndexed { i, section ->
                    if (section == null) out.writeBytes(emptySection)
                    else Wire.writeSection(out, ProtocolPackets.withFluidCount(section, fluidCounts[i]))
                }
            }
            val heightmap = LongArray(emptyHeightmap.size)
            heights.forEachIndexed { i, height ->
                val word = i / heightsPerWord
                heightmap[word] = heightmap[word] or (height.toLong() shl ((i % heightsPerWord) * heightBits))
            }
            populated[x to z] = ProtocolPackets.chunk(x, z, bytes, heightmap, light)
        }
        return populated
    }

    private fun newSection(blockStateCount: Int = 1) = ProtocolPackets.section(biome, registry.biomeCount, blockStateCount)

    private inline fun serialize(write: (io.netty.buffer.ByteBuf) -> Unit): ByteArray {
        val buffer = Unpooled.buffer()
        try {
            write(buffer)
            return ByteArray(buffer.readableBytes()).also { buffer.readBytes(it) }
        } finally { buffer.release() }
    }
}
