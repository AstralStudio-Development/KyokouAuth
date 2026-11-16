package moe.ryuu.kyokouauth.limbo.session

import io.netty.buffer.ByteBuf
import moe.ryuu.kyokouauth.limbo.LimboSettings
import net.kyori.adventure.key.Key
import org.cloudburstmc.nbt.NbtMap
import org.geysermc.mcprotocollib.auth.GameProfile
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket
import java.lang.reflect.Constructor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Fixed upstream API differences only. Every byte remains serialized by that engine's own library. */
internal object ProtocolPackets {
    val protocol = GeneratedCodec.PROTOCOL
    val hasPlayerLoaded = protocol >= 769
    private const val ROOT = "org.geysermc.mcprotocollib.protocol."
    private val constructors = ConcurrentHashMap<String, List<Constructor<*>>>()

    init { require(protocol in 767..777) }

    private fun construct(name: String, vararg args: Any?): Any {
        val full = ROOT + name
        val options = constructors.computeIfAbsent(full) { Class.forName(it).constructors.toList() }
        val constructor = options.singleOrNull { candidate ->
            candidate.parameterTypes.size == args.size && candidate.parameterTypes.zip(args.asList()).all { (type, value) ->
                if (value == null) !type.isPrimitive else boxed(type).isInstance(value)
            }
        } ?: error("Pinned protocol $protocol has unexpected upstream constructor: $name/${args.size}")
        return constructor.newInstance(*args)
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        else -> type
    }

    fun server(): MinecraftProtocol {
        if (protocol == 777) Class.forName("moe.ryuu.kyokouauth.limbo.session.Protocol263").getField("INSTANCE").get(null)
        return SparseMinecraftProtocol()
    }

    fun loginFinished(profile: GameProfile): Packet = when {
        protocol == 767 -> construct("packet.login.clientbound.ClientboundGameProfilePacket", profile, false)
        protocol >= 776 -> construct("packet.login.clientbound.ClientboundLoginFinishedPacket", profile, UUID.randomUUID())
        else -> construct("packet.login.clientbound.ClientboundLoginFinishedPacket", profile)
    } as Packet

    fun join(settings: LimboSettings, registry: RegistrySnapshot): Packet {
        val world = Key.key("minecraft:overworld")
        val spawnArgs = mutableListOf<Any?>(registry.dimensionId, world, 0L, GameMode.ADVENTURE, null, false, false, null, 0)
        if (protocol >= 768) spawnArgs += 63
        val spawn = construct("data.game.entity.player.PlayerSpawnInfo", *spawnArgs.toTypedArray())
        val args = mutableListOf<Any?>(1, false, arrayOf(world), settings.maxPlayers, settings.viewDistance,
            settings.viewDistance, false, true, false, spawn)
        if (protocol >= 776) args += false
        args += false
        val packet = construct("packet.ingame.clientbound.ClientboundLoginPacket", *args.toTypedArray()) as ClientboundLoginPacket
        return if (protocol == 777) {
            val fix = Class.forName("moe.ryuu.kyokouauth.limbo.session.Login263")
            fix.getConstructor(ClientboundLoginPacket::class.java).newInstance(packet) as Packet
        } else packet
    }

    fun section(biome: Int, biomeCount: Int): ChunkSection {
        if (protocol >= 773) return construct("data.game.chunk.ChunkSection", 0, 1, biome, biomeCount) as ChunkSection
        val section = construct("data.game.chunk.ChunkSection") as ChunkSection
        // Older MCPL's default palette is empty, not a singleton-air palette. Register air via its public API.
        section.setBlock(0, 0, 0, 0)
        val palette = section.javaClass.getMethod("getBiomeData").invoke(section)
        val set = palette.javaClass.getMethod("set", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        for (x in 0..3) for (y in 0..3) for (z in 0..3) set.invoke(palette, x, y, z, biome)
        return section
    }

    fun teleport(settings: LimboSettings, id: Int): Packet {
        val s = settings.spawn
        val name = "packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket"
        return if (protocol == 767) construct(name, s.x, s.y, s.z, s.yaw, s.pitch, id, emptyList<Any>()) as Packet
        else construct(name, id, s.x, s.y, s.z, 0.0, 0.0, 0.0, s.yaw, s.pitch,
            java.lang.reflect.Array.newInstance(Class.forName(ROOT + "data.game.entity.player.PositionElement"), 0)) as Packet
    }

    fun chunk(x: Int, z: Int, bytes: ByteArray, heightmap: LongArray, light: LightUpdateData): Packet {
        val maps: Any = if (protocol <= 769) NbtMap.builder().putLongArray("MOTION_BLOCKING", heightmap).build()
        else {
            val type = Class.forName(ROOT + "data.game.level.HeightmapTypes").enumConstants.first { (it as Enum<*>).name == "MOTION_BLOCKING" }
            mapOf(type to heightmap)
        }
        val emptyEntities = java.lang.reflect.Array.newInstance(Class.forName(ROOT + "data.game.level.block.BlockEntityInfo"), 0)
        return construct("packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket", x, z, bytes, maps, emptyEntities, light) as Packet
    }
}
