package moe.ryuu.kyokouauth.limbo.session

import moe.ryuu.kyokouauth.limbo.LimboSettings
import net.kyori.adventure.key.Key
import org.cloudburstmc.nbt.NbtMap
import org.geysermc.mcprotocollib.auth.GameProfile
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection
import org.geysermc.mcprotocollib.protocol.data.game.chunk.DataPalette
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.GlobalPos
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket
import java.util.UUID

/** Fixed upstream API differences only. Every byte remains serialized by that engine's own library. */
internal object ProtocolPackets {
    val protocol = GeneratedCodec.PROTOCOL
    val hasPlayerLoaded = protocol >= 769
    private const val ROOT = "org.geysermc.mcprotocollib.protocol."
    init { require(protocol in 767..777) }

    private val lookup = PacketLookup(protocol)
    private val emptyEntities = lookup.emptyArray(ROOT + "data.game.level.block.BlockEntityInfo")
    private val emptyPositions = if (protocol >= 768) lookup.emptyArray(ROOT + "data.game.entity.player.PositionElement") else null
    private val motionBlocking = if (protocol >= 770) lookup.enumConstant(ROOT + "data.game.level.HeightmapTypes", "MOTION_BLOCKING") else null
    private val loginFinishedConstructor = when {
        protocol == 767 -> lookup.constructor(ROOT + "packet.login.clientbound.ClientboundGameProfilePacket", Packet::class.java,
            GameProfile::class.java, Boolean::class.java)
        protocol >= 776 -> lookup.constructor(ROOT + "packet.login.clientbound.ClientboundLoginFinishedPacket", Packet::class.java,
            GameProfile::class.java, UUID::class.java)
        else -> lookup.constructor(ROOT + "packet.login.clientbound.ClientboundLoginFinishedPacket", Packet::class.java, GameProfile::class.java)
    }
    private val spawnConstructor = lookup.constructor(ROOT + "data.game.entity.player.PlayerSpawnInfo", PlayerSpawnInfo::class.java,
        *buildList {
            addAll(listOf(Int::class.java, Key::class.java, Long::class.java,
                GameMode::class.java, GameMode::class.java, Boolean::class.java, Boolean::class.java,
                GlobalPos::class.java, Int::class.java))
            if (protocol >= 768) add(Int::class.java)
        }.toTypedArray())
    private val joinConstructor = lookup.constructor(ROOT + "packet.ingame.clientbound.ClientboundLoginPacket", ClientboundLoginPacket::class.java,
        *buildList {
            addAll(listOf(Int::class.java, Boolean::class.java, Array<Key>::class.java,
                Int::class.java, Int::class.java, Int::class.java,
                Boolean::class.java, Boolean::class.java, Boolean::class.java,
                PlayerSpawnInfo::class.java, Boolean::class.java))
            if (protocol >= 776) add(Boolean::class.java)
        }.toTypedArray())
    private val sectionConstructor = if (protocol >= 773) lookup.constructor(ROOT + "data.game.chunk.ChunkSection", ChunkSection::class.java,
        Int::class.java, Int::class.java, Int::class.java, Int::class.java)
        else lookup.constructor(ROOT + "data.game.chunk.ChunkSection", ChunkSection::class.java)
    private val fluidSectionConstructor = if (protocol >= 775) lookup.constructor(ROOT + "data.game.chunk.ChunkSection", ChunkSection::class.java,
        Int::class.java, Int::class.java, DataPalette::class.java, DataPalette::class.java) else null
    private val blockData = fluidSectionConstructor?.let { lookup.method(it.declaringClass, "getBlockData", DataPalette::class.java) }
    private val biomeData = if (protocol < 773) lookup.method(sectionConstructor.declaringClass, "getBiomeData", DataPalette::class.java) else null
    private val setBiome = biomeData?.let { lookup.method(it.returnType, "set", Int::class.java,
        Int::class.java, Int::class.java, Int::class.java, Int::class.java) }
    private val teleportConstructor = if (protocol == 767) lookup.constructor(ROOT + "packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket", Packet::class.java,
        Double::class.java, Double::class.java, Double::class.java,
        Float::class.java, Float::class.java, Int::class.java, List::class.java)
        else lookup.constructor(ROOT + "packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket", Packet::class.java,
            Int::class.java, Double::class.java, Double::class.java, Double::class.java,
            Double::class.java, Double::class.java, Double::class.java,
            Float::class.java, Float::class.java, emptyPositions!!.javaClass)
    private val chunkConstructor = lookup.constructor(ROOT + "packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket", Packet::class.java,
        Int::class.java, Int::class.java, ByteArray::class.java,
        if (protocol <= 769) NbtMap::class.java else Map::class.java, emptyEntities.javaClass, LightUpdateData::class.java)
    private val login263Constructor = if (protocol == 777) lookup.constructor("moe.ryuu.kyokouauth.limbo.session.Login263", Packet::class.java,
        ClientboundLoginPacket::class.java) else null

    init {
        if (protocol == 777) lookup.singleton("moe.ryuu.kyokouauth.limbo.session.Protocol263")
    }

    internal val lookupCounts get() = lookup.counts()

    fun server(): MinecraftProtocol = SparseMinecraftProtocol()

    fun loginFinished(profile: GameProfile): Packet = when {
        protocol == 767 -> loginFinishedConstructor.newInstance(profile, false)
        protocol >= 776 -> loginFinishedConstructor.newInstance(profile, UUID.randomUUID())
        else -> loginFinishedConstructor.newInstance(profile)
    }

    fun join(settings: LimboSettings, registry: RegistrySnapshot): Packet {
        val world = Key.key("minecraft:overworld")
        val spawn = if (protocol >= 768) spawnConstructor.newInstance(
            registry.dimensionId, world, 0L, GameMode.ADVENTURE, null, false, false, null, 0, 63)
        else spawnConstructor.newInstance(registry.dimensionId, world, 0L, GameMode.ADVENTURE, null, false, false, null, 0)
        val packet = if (protocol >= 776) joinConstructor.newInstance(1, false, arrayOf(world), settings.maxPlayers,
            settings.viewDistance, settings.viewDistance, false, true, false, spawn, false, false)
        else joinConstructor.newInstance(1, false, arrayOf(world), settings.maxPlayers,
            settings.viewDistance, settings.viewDistance, false, true, false, spawn, false)
        return login263Constructor?.newInstance(packet) ?: packet
    }

    fun section(biome: Int, biomeCount: Int, blockStateCount: Int = 1): ChunkSection {
        if (protocol >= 773) return sectionConstructor.newInstance(0, blockStateCount, biome, biomeCount)
        val section = sectionConstructor.newInstance()
        // Older MCPL's default palette is empty, not a singleton-air palette. Register air via its public API.
        section.setBlock(0, 0, 0, 0)
        val palette = biomeData!!.invoke(section)
        for (x in 0..3) for (y in 0..3) for (z in 0..3) setBiome!!.invoke(palette, x, y, z, biome)
        return section
    }

    fun withFluidCount(section: ChunkSection, count: Int): ChunkSection =
        if (count == 0 || fluidSectionConstructor == null) section else fluidSectionConstructor.newInstance(
            section.blockCount, count, blockData!!.invoke(section), section.biomeData)

    fun teleport(settings: LimboSettings, id: Int): Packet {
        val s = settings.spawn
        return if (protocol == 767) teleportConstructor.newInstance(s.x, s.y, s.z, s.yaw, s.pitch, id, emptyList<Any>())
        else teleportConstructor.newInstance(id, s.x, s.y, s.z, 0.0, 0.0, 0.0, s.yaw, s.pitch, emptyPositions)
    }

    fun chunk(x: Int, z: Int, bytes: ByteArray, heightmap: LongArray, light: LightUpdateData): Packet {
        val maps: Any = if (protocol <= 769) NbtMap.builder().putLongArray("MOTION_BLOCKING", heightmap).build()
        else mapOf(motionBlocking!! to heightmap)
        return chunkConstructor.newInstance(x, z, bytes, maps, emptyEntities, light)
    }
}
