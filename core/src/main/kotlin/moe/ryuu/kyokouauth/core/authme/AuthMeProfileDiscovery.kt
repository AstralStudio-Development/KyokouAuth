package moe.ryuu.kyokouauth.core.authme

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import moe.ryuu.kyokouauth.core.identity.GameName
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import java.util.zip.GZIPInputStream

/** Operator-selected frozen server backup metadata, never an identity proof or a name-derived UUID. */
data class HistoricalProfile(val name: String, val profileUuid: UUID)
data class HistoricalProfileScan(val profiles: List<HistoricalProfile>, val invalidFiles: Int)

object AuthMeProfileDiscovery {
    /** Reads actual player NBT and optional usercache; never modifies the backup or scans plugin databases. */
    fun discover(serverFolder: Path, maxFiles: Int = 100_000, current: () -> Boolean = { true }): HistoricalProfileScan {
        try {
            require(maxFiles in 1..100_000)
            check(current())
            val root = serverFolder.toAbsolutePath().normalize()
            require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root))
            val profiles = linkedSetOf<HistoricalProfile>()
            val actual = hashSetOf<UUID>()
            var invalid = 0
            var count = 0
            var visited = 0
            var totalBytes = 0L
            Files.walk(root, 5).use { paths ->
                paths.peek { if (!current() || ++visited > 500_000) throw ScanLimit() }
                    .filter { it.parent?.fileName?.toString() == "playerdata" && it.fileName.toString().endsWith(".dat") }
                    .forEach { path ->
                        require(++count <= maxFiles) { "Historical profile scan limit exceeded" }
                        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) { invalid++; return@forEach }
                        val uuid = parseUuid(path.fileName.toString().removeSuffix(".dat"))
                        if (uuid == null || uuid.version() != 3 || uuid.variant() != 2) { invalid++; return@forEach }
                        try {
                            require(Files.size(path) <= MAX_NBT_BYTES)
                            val raw = Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                                GZIPInputStream(input).use { it.readNBytes(MAX_NBT_BYTES + 1) }
                            }
                            totalBytes += raw.size
                            if (totalBytes > 512L * 1024 * 1024) throw ScanLimit()
                            require(raw.size <= MAX_NBT_BYTES)
                            val nbt = Nbt(raw)
                            nbt.read()
                            require(nbt.uuid == uuid)
                            actual += uuid
                            nbt.name?.let { name ->
                                require(GameName.parse(name) != null)
                                profiles += HistoricalProfile(name, uuid)
                            }
                        } catch (limit: ScanLimit) { throw limit }
                        catch (_: Exception) { invalid++ }
                    }
            }
            val cache = root.resolve("usercache.json")
            if (Files.exists(cache, NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(cache, NOFOLLOW_LINKS) && Files.size(cache) <= MAX_CACHE_BYTES)
                val bytes = Files.newInputStream(cache, NOFOLLOW_LINKS).use { it.readNBytes(MAX_CACHE_BYTES + 1) }
                require(bytes.size <= MAX_CACHE_BYTES)
                JsonReader(StringReader(bytes.toString(Charsets.UTF_8))).use { reader ->
                    reader.beginArray()
                    var cacheEntries = 0
                    while (reader.hasNext()) {
                        check(current())
                        require(++cacheEntries <= maxFiles)
                        reader.beginObject()
                        val item = HashMap<String, String?>()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            require(!item.containsKey(key))
                            item[key] = when (reader.peek()) {
                                JsonToken.STRING -> reader.nextString()
                                JsonToken.NULL -> { reader.nextNull(); null }
                                else -> error("Invalid cache scalar")
                            }
                        }
                        reader.endObject()
                        val name = item["name"]
                        val uuid = item["uuid"]?.let(::parseUuid)
                        if (name == null || GameName.parse(name) == null || uuid == null) invalid++
                        else if (uuid in actual) profiles += HistoricalProfile(name, uuid)
                    }
                    reader.endArray()
                    require(reader.peek() == JsonToken.END_DOCUMENT)
                }
            }
            return HistoricalProfileScan(profiles.toList(), invalid)
        } catch (_: Exception) {
            // Filesystem and parser exceptions may contain administrator paths or untrusted content.
            throw IllegalStateException("Historical backup unavailable, invalid or over scan limits")
        }
    }

    private fun parseUuid(value: String): UUID? = if (UUID_FORMAT.matches(value))
        runCatching { UUID.fromString(value) }.getOrNull() else null

    /** Bounded NBT traversal; only root UUID and bukkit.lastKnownName are retained. */
    private class Nbt(bytes: ByteArray) {
        private val input = DataInputStream(ByteArrayInputStream(bytes))
        private var entries = 0
        private var most: Long? = null
        private var least: Long? = null
        var uuid: UUID? = null
            private set
        var name: String? = null
            private set
        fun read() {
            require(input.readUnsignedByte() == 10)
            input.readUTF()
            payload(10, "", 0)
            val legacy = most?.let { high -> least?.let { low -> UUID(high, low) } }
            require(legacy == null || uuid == null || legacy == uuid)
            if (uuid == null) uuid = legacy
            require(input.available() == 0)
        }
        private fun payload(type: Int, path: String, depth: Int) {
            require(depth <= 32 && ++entries <= 200_000)
            when (type) {
                1 -> input.readByte()
                2 -> input.readShort()
                3 -> input.readInt()
                4 -> {
                    val value = input.readLong()
                    if (path == "UUIDMost") { require(most == null); most = value }
                    if (path == "UUIDLeast") { require(least == null); least = value }
                }
                5 -> input.readFloat()
                6 -> input.readDouble()
                7 -> skip(1)
                8 -> {
                    val value = input.readUTF()
                    if (path == "bukkit.lastKnownName") { require(name == null); name = value }
                }
                9 -> {
                    val child = input.readUnsignedByte()
                    val size = input.readInt()
                    require(size in 0..200_000 && (child in 1..12 || child == 0 && size == 0))
                    repeat(size) { payload(child, "[]", depth + 1) }
                }
                10 -> {
                    val keys = hashSetOf<String>()
                    while (true) {
                        val child = input.readUnsignedByte()
                        if (child == 0) break
                        val key = input.readUTF()
                        require(keys.add(key))
                        payload(child, if (path.isEmpty()) key else "$path.$key", depth + 1)
                    }
                }
                11 -> if (path == "UUID") {
                    require(uuid == null && input.readInt() == 4)
                    val a = input.readInt().toLong() shl 32 or (input.readInt().toLong() and 0xffffffffL)
                    val b = input.readInt().toLong() shl 32 or (input.readInt().toLong() and 0xffffffffL)
                    uuid = UUID(a, b)
                } else skip(4)
                12 -> skip(8)
                else -> error("Unsupported NBT tag")
            }
        }
        private fun skip(width: Int) {
            val count = input.readInt()
            require(count >= 0 && count.toLong() * width <= input.available())
            input.skipNBytes(count.toLong() * width)
        }
    }

    private class ScanLimit : IllegalStateException()

    private const val MAX_NBT_BYTES = 4 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
    private val UUID_FORMAT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
}
