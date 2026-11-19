package moe.ryuu.kyokouauth.limbo.session

import io.netty.buffer.Unpooled
import moe.ryuu.kyokouauth.limbo.LimboSettings
import net.kyori.adventure.key.Key
import org.cloudburstmc.nbt.NbtMap
import org.geysermc.mcprotocollib.auth.GameProfile
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket
import org.junit.jupiter.api.Test
import java.lang.reflect.Constructor
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier
import kotlin.system.measureNanoTime
import kotlin.test.*

class PacketLookupTest {
    private val protocol = GeneratedCodec.PROTOCOL
    private val settings = LimboSettings(biomeKey = "minecraft:plains")
    private val registry = RegistrySnapshot.load()
    private val profile = GameProfile(UUID(1, 2), "LookupPlayer")
    private val light = LightUpdateData(BitSet(), BitSet(), BitSet(), BitSet(), emptyList(), emptyList())
    private val heightmap = LongArray(37)
    @Volatile private var sink: Any? = null

    @Test fun `all pinned handles resolve before use and warm construction never resolves again`() {
        ProtocolPackets.server()
        val initial = ProtocolPackets.lookupCounts
        assertEquals(PacketLookup.Counts(
            classes = when { protocol == 767 -> 7; protocol <= 769 -> 8; protocol == 777 -> 11; else -> 9 },
            constructors = when { protocol == 777 -> 8; protocol >= 775 -> 7; else -> 6 },
            methods = when { protocol < 773 -> 2; protocol >= 775 -> 1; else -> 0 },
            enums = if (protocol >= 770) 1 else 0,
            arrays = if (protocol >= 768) 2 else 1,
            fields = if (protocol == 777) 1 else 0,
        ), initial)
        repeat(100) { currentBatch() }
        assertEquals(initial, ProtocolPackets.lookupCounts)
        println("LOOKUP protocol=$protocol startup=$initial warmBatches=100 warmAdditionalResolutions=0")
    }

    @Test fun `exact primitive signatures accept boxed invocation and failures identify protocol and member`() {
        val lookup = PacketLookup(protocol)
        val constructor = lookup.constructor(BitSet::class.java.name, BitSet::class.java, Int::class.java)
        assertEquals(128, constructor.newInstance(65).size())
        fun fails(member: String, action: () -> Unit) {
            val failure = assertFailsWith<IllegalStateException>(block = action)
            assertContains(failure.message.orEmpty(), "Pinned protocol $protocol")
            assertContains(failure.message.orEmpty(), member)
            assertNotNull(failure.cause)
        }
        fails("constructor java.util.BitSet") {
            lookup.constructor(BitSet::class.java.name, BitSet::class.java, Int::class.javaObjectType)
        }
        fails("constructor java.util.BitSet") {
            lookup.constructor(BitSet::class.java.name, BitSet::class.java, Int::class.java, Int::class.java)
        }
        fails("method ${ChunkSection::class.java.name}.missingPinnedMethod") {
            lookup.method(ChunkSection::class.java, "missingPinnedMethod", Int::class.java)
        }
        fails("method java.lang.String.length") {
            lookup.method(String::class.java, "length", Long::class.java)
        }
        fails("enum java.lang.Thread\$State.MISSING") { lookup.enumConstant(Thread.State::class.java.name, "MISSING") }
        fails("empty array missing.PinnedComponent[]") { lookup.emptyArray("missing.PinnedComponent") }
    }

    @Test fun `cached accessors preserve previous upstream bytes including air palette and 263 login fix`() {
        ProtocolPackets.server()
        val previous = PreviousPackets()
        val section = ProtocolPackets.section(registry.plainsId, registry.biomeCount)
        val oldSection = previous.section(registry.plainsId, registry.biomeCount)
        fun sectionBytes(value: ChunkSection): ByteArray {
            val buffer = Unpooled.buffer()
            return try { Wire.writeSection(buffer, value); ByteArray(buffer.readableBytes()).also { buffer.readBytes(it) } }
            finally { buffer.release() }
        }
        assertContentEquals(sectionBytes(oldSection), sectionBytes(section))
        assertEquals(0, section.getBlock(0, 0, 0))
        val bytes = sectionBytes(section)
        val packets = listOf(
            previous.join() to ProtocolPackets.join(settings, registry),
            previous.teleport() to ProtocolPackets.teleport(settings, 7),
            previous.chunk(bytes) to ProtocolPackets.chunk(0, 0, bytes, heightmap, light),
        )
        packets.forEach { (before, after) ->
            assertSame(before.javaClass, after.javaClass)
            assertContentEquals(encode(before), encode(after), after.javaClass.simpleName)
        }
        val login = ProtocolPackets.loginFinished(profile)
        var oldLogin = previous.loginFinished()
        if (protocol >= 776) {
            assertSame(profile, login.javaClass.getMethod("getProfile").invoke(login))
            val sessionId = login.javaClass.getMethod("getSessionId").invoke(login)
            oldLogin = oldLogin.javaClass.getMethod("withSessionId", UUID::class.java).invoke(oldLogin, sessionId) as Packet
        }
        assertContentEquals(encode(oldLogin), encode(login))
        if (protocol == 777) assertEquals("Login263", packets.first().second.javaClass.simpleName)
    }

    @Test fun `record actual previous lookup work and warmed construction timing without timing assertions`() {
        ProtocolPackets.server()
        val previous = PreviousPackets()
        previousBatch(previous) // Populate the old constructor-list cache before counting the hot path.
        previous.resetCounts()
        repeat(100) { previousBatch(previous) }
        assertEquals(3000, previous.selections)
        assertEquals(if (protocol < 773) 200 else 0, previous.methods)
        assertEquals(if (protocol >= 770) 2500 else 0, previous.enums)
        assertEquals(2500 + (if (protocol >= 768) 100 else 0), previous.arrays)
        assertEquals(2500 + (if (protocol >= 768) 100 else 0) +
            (if (protocol >= 770) 2500 else 0) + (if (protocol == 777) 100 else 0), previous.classes)
        assertEquals(if (protocol == 777) 100 else 0, previous.constructors)
        assertTrue(previous.parameterArrays >= 6000)
        val work = "classes=${previous.classes},constructorSelections=${previous.selections},parameterArrays=${previous.parameterArrays}," +
            "constructors=${previous.constructors},methods=${previous.methods},enums=${previous.enums},arrays=${previous.arrays}"
        val resolved = ProtocolPackets.lookupCounts
        repeat(300) { previousBatch(previous); currentBatch() }
        val before = ArrayList<Long>()
        val after = ArrayList<Long>()
        repeat(5) { sample ->
            if (sample % 2 == 0) {
                before += measureNanoTime { repeat(200) { previousBatch(previous) } }
                after += measureNanoTime { repeat(200) { currentBatch() } }
            } else {
                after += measureNanoTime { repeat(200) { currentBatch() } }
                before += measureNanoTime { repeat(200) { previousBatch(previous) } }
            }
        }
        assertEquals(resolved, ProtocolPackets.lookupCounts)
        assertNotNull(sink)
        println("LOOKUP_BENCH protocol=$protocol batches=100 previous=[$work] currentAdditionalResolutions=0 " +
            "samples=5 batchesPerSample=200 packetsPerBatch=28 selectionCallsPerBatch=30 previousMedianNs=${before.sorted()[2]} currentMedianNs=${after.sorted()[2]}")
    }

    private fun currentBatch() {
        sink = ProtocolPackets.loginFinished(profile)
        sink = ProtocolPackets.join(settings, registry)
        sink = ProtocolPackets.section(registry.plainsId, registry.biomeCount)
        sink = ProtocolPackets.teleport(settings, 7)
        repeat(25) { sink = ProtocolPackets.chunk(it, 0, byteArrayOf(), heightmap, light) }
    }

    private fun previousBatch(previous: PreviousPackets) {
        sink = previous.loginFinished()
        sink = previous.join()
        sink = previous.section(registry.plainsId, registry.biomeCount)
        sink = previous.teleport()
        repeat(25) { sink = previous.chunk(byteArrayOf(), it) }
    }

    private fun encode(packet: Packet): ByteArray {
        val buffer = Unpooled.buffer()
        return try {
            val serialize = packet.javaClass.methods.single { it.name == "serialize" }
            if (serialize.parameterCount == 1) serialize.invoke(packet, buffer)
            else serialize.invoke(packet, buffer, (GeneratedCodec.CODEC.javaClass.getMethod("getHelperFactory").invoke(GeneratedCodec.CODEC) as Supplier<*>).get())
            ByteArray(buffer.readableBytes()).also { buffer.readBytes(it) }
        } finally { buffer.release() }
    }

    /** Exact pre-change API-selection path, retained only as a byte/count/timing comparison. */
    private inner class PreviousPackets {
        private val cached = ConcurrentHashMap<String, List<Constructor<*>>>()
        var classes = 0
        var selections = 0
        var parameterArrays = 0
        var constructors = 0
        var methods = 0
        var enums = 0
        var arrays = 0
        fun resetCounts() { classes = 0; selections = 0; parameterArrays = 0; constructors = 0; methods = 0; enums = 0; arrays = 0 }
        private fun type(name: String): Class<*> { classes++; return Class.forName(name) }
        private fun construct(name: String, vararg args: Any?): Any {
            val options = cached.computeIfAbsent(ROOT + name) { constructors++; type(it).constructors.toList() }
            selections++
            val constructor = options.singleOrNull { candidate ->
                candidate.parameterTypes.also { parameterArrays++ }.size == args.size &&
                    candidate.parameterTypes.also { parameterArrays++ }.zip(args.asList()).all { (type, value) ->
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
        fun loginFinished(): Packet = when {
            protocol == 767 -> construct("packet.login.clientbound.ClientboundGameProfilePacket", profile, false)
            protocol >= 776 -> construct("packet.login.clientbound.ClientboundLoginFinishedPacket", profile, UUID.randomUUID())
            else -> construct("packet.login.clientbound.ClientboundLoginFinishedPacket", profile)
        } as Packet
        fun join(): Packet {
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
                constructors++
                type("moe.ryuu.kyokouauth.limbo.session.Login263").getConstructor(ClientboundLoginPacket::class.java).newInstance(packet) as Packet
            } else packet
        }
        fun section(biome: Int, biomeCount: Int): ChunkSection {
            if (protocol >= 773) return construct("data.game.chunk.ChunkSection", 0, 1, biome, biomeCount) as ChunkSection
            val section = construct("data.game.chunk.ChunkSection") as ChunkSection
            section.setBlock(0, 0, 0, 0)
            methods++
            val palette = section.javaClass.getMethod("getBiomeData").invoke(section)
            methods++
            val set = palette.javaClass.getMethod("set", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            for (x in 0..3) for (y in 0..3) for (z in 0..3) set.invoke(palette, x, y, z, biome)
            return section
        }
        fun teleport(): Packet {
            val s = settings.spawn
            val name = "packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket"
            return if (protocol == 767) construct(name, s.x, s.y, s.z, s.yaw, s.pitch, 7, emptyList<Any>()) as Packet
            else {
                arrays++
                construct(name, 7, s.x, s.y, s.z, 0.0, 0.0, 0.0, s.yaw, s.pitch,
                    java.lang.reflect.Array.newInstance(type(ROOT + "data.game.entity.player.PositionElement"), 0)) as Packet
            }
        }
        fun chunk(bytes: ByteArray, x: Int = 0): Packet {
            val maps: Any = if (protocol <= 769) NbtMap.builder().putLongArray("MOTION_BLOCKING", heightmap).build()
            else {
                enums++
                val kind = type(ROOT + "data.game.level.HeightmapTypes").enumConstants.first { (it as Enum<*>).name == "MOTION_BLOCKING" }
                mapOf(kind to heightmap)
            }
            arrays++
            val emptyEntities = java.lang.reflect.Array.newInstance(type(ROOT + "data.game.level.block.BlockEntityInfo"), 0)
            return construct("packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket", x, 0, bytes, maps, emptyEntities, light) as Packet
        }
    }

    companion object { private const val ROOT = "org.geysermc.mcprotocollib.protocol." }
}
