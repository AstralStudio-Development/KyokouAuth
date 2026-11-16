package moe.ryuu.kyokouauth.limbo.session

import io.netty.buffer.Unpooled
import net.kyori.adventure.key.Key
import org.cloudburstmc.nbt.NbtMap
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.data.ProtocolState
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundUpdateTagsPacket
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundUpdateEnabledFeaturesPacket
import java.security.MessageDigest
import java.util.Properties

/** A validated data snapshot, decoded into library packets rather than replayed as opaque traffic. */
internal class RegistrySnapshot private constructor(
    val registries: List<ClientboundRegistryDataPacket>,
    val tags: ClientboundUpdateTagsPacket,
) {
    private val byName = registries.associateBy { it.registry }
    val dimensionId = id("dimension_type", "overworld")
    private val dimension = byName.getValue(Key.key("minecraft:dimension_type")).entries[dimensionId].data as NbtMap
    val minY = dimension.getInt("min_y")
    val height = dimension.getInt("height")
    val biomeKeys = byName.getValue(Key.key("minecraft:worldgen/biome")).entries.map { it.id.asString() }
    val biomeCount = biomeKeys.size
    val plainsId = id("worldgen/biome", "plains")
    fun biomeId(key: String): Int = biomeKeys.indexOf(key).also { require(it >= 0) { "Biome $key is absent in protocol ${GeneratedCodec.PROTOCOL}" } }

    init {
        require(height in 16..512 && height % 16 == 0 && minY % 16 == 0)
        tags.tags.forEach { (key, entries) ->
            byName[key]?.let { registry ->
                entries.values.forEach { ids -> require(ids.all { it in registry.entries.indices }) { "Tag id outside $key" } }
            }
        }
    }

    private fun id(registry: String, name: String): Int = byName.getValue(Key.key("minecraft:$registry"))
        .entries.indexOfFirst { it.id == Key.key("minecraft:$name") }.also { require(it >= 0) }

    companion object {
        fun load(): RegistrySnapshot {
            val protocol = GeneratedCodec.PROTOCOL
            val base = "/limbo/configuration-$protocol"
            val meta = Properties().apply {
                RegistrySnapshot::class.java.getResourceAsStream("$base.properties")
                    ?.use { load(it) } ?: error("Missing configuration provenance for $protocol")
            }
            require(meta.getProperty("protocol") == protocol.toString())
            require(meta.getProperty("server-sha256").matches(Regex("[0-9a-f]{64}")))
            val bytes = RegistrySnapshot::class.java.getResourceAsStream("$base.bin")
                ?.use { it.readNBytes(4 * 1024 * 1024 + 1) } ?: error("Missing configuration fixture for $protocol")
            require(bytes.size <= 4 * 1024 * 1024)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(hash == meta.getProperty("sha256")) { "Configuration snapshot checksum mismatch" }
            return decode(bytes).also { snapshot ->
                require(snapshot.registries.size == meta.getProperty("registries").toInt())
                require(snapshot.registries.sumOf { it.entries.size } == meta.getProperty("entries").toInt())
                require(snapshot.tags.tags.size == meta.getProperty("tag-registries").toInt())
                require(snapshot.tags.tags.values.sumOf { it.size } == meta.getProperty("tags").toInt())
            }
        }

        fun decode(bytes: ByteArray): RegistrySnapshot {
            val input = Unpooled.wrappedBuffer(bytes)
            val codec = GeneratedCodec.CODEC.getCodec(ProtocolState.CONFIGURATION)
            val registries = mutableListOf<ClientboundRegistryDataPacket>()
            var tags: ClientboundUpdateTagsPacket? = null
            var features = false
            var brand = false
            try {
                while (input.isReadable) {
                    val length = Wire.readVarInt(input)
                    require(length in 1..input.readableBytes()) { "Truncated configuration frame" }
                    val frame = input.readSlice(length)
                    val packet: Packet = Wire.packet(codec, Wire.readVarInt(frame), frame)
                    require(!frame.isReadable) { "Trailing configuration bytes" }
                    when (packet) {
                        is ClientboundCustomPayloadPacket -> {
                            require(!brand && !features && registries.isEmpty() && packet.channel == Key.key("minecraft:brand"))
                            brand = true // Capture metadata only; never advertised to clients.
                        }
                        is ClientboundUpdateEnabledFeaturesPacket -> {
                            require(!features && registries.isEmpty())
                            require(packet.features.contentEquals(arrayOf(Key.key("minecraft:vanilla"))))
                            features = true
                        }
                        is ClientboundRegistryDataPacket -> {
                            require(features && tags == null && registries.none { it.registry == packet.registry })
                            require(packet.entries.isNotEmpty() && packet.entries.all { it.data != null })
                            require(packet.entries.map { it.id }.distinct().size == packet.entries.size)
                            registries += packet
                        }
                        is ClientboundUpdateTagsPacket -> {
                            require(tags == null && registries.isNotEmpty())
                            tags = packet
                        }
                        else -> error("Unexpected packet in configuration data")
                    }
                }
                require(features && registries.isNotEmpty())
                return RegistrySnapshot(registries.toList(), requireNotNull(tags))
            } finally { input.release() }
        }
    }
}
