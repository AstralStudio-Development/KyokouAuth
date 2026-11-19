package moe.ryuu.kyokouauth.limbo

import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Static block volume in Sponge order (X changes fastest, then Z, then Y). */
class Schematic private constructor(
    val width: Int, val height: Int, val length: Int,
    val palette: List<String>, private val indices: IntArray,
) {
    fun paletteIndex(x: Int, y: Int, z: Int): Int = indices[(y * length + z) * width + x]

    companion object {
        private const val MAX_BLOCKS = 1_048_576
        private const val MAX_BYTES = 16 * 1024 * 1024
        private const val MAX_PALETTE = 8192
        private val namespacedKey = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
        private val property = Regex("[a-z0-9_]+=[a-z0-9_-]+")

        /** Reads a gzip-compressed Sponge v2 or v3 schematic. Entities, biomes and metadata are ignored. */
        fun read(source: InputStream): Schematic {
            val bytes = GZIPInputStream(source).use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes.size <= MAX_BYTES) { "Schematic exceeds 16 MiB after decompression" }
            return Reader(DataInputStream(bytes.inputStream())).read()
        }

        private fun canonicalState(raw: String): String {
            val name = raw.substringBefore('[')
            require(raw.length <= 512 && namespacedKey.matches(name)) { "Invalid schematic block state" }
            if (name.length == raw.length) return name
            require(raw.endsWith(']')) { "Invalid schematic block state" }
            val properties = raw.substring(name.length + 1, raw.length - 1).split(',')
            require(properties.all(property::matches) && properties.distinctBy { it.substringBefore('=') }.size == properties.size) {
                "Invalid schematic block properties"
            }
            return properties.sorted().joinToString(",", "$name[", "]")
        }
    }

    private class Reader(private val input: DataInputStream) {
        private class Fields {
            var version = 0
            var width = 0
            var height = 0
            var length = 0
            var palette: Array<String?>? = null
            var data: ByteArray? = null
            var blocks: Fields? = null
        }

        fun read(): Schematic {
            require(input.readUnsignedByte() == 10) { "Schematic root must be an NBT compound" }
            ignoreString()
            var nested: Fields? = null
            val root = fields(0) { type ->
                require(type == 10 && nested == null) { "Invalid Schematic compound" }
                nested = fields(1)
            }
            require(input.read() == -1) { "Trailing schematic NBT data" }
            val selected = nested ?: root
            val blocks = when {
                nested == null && selected.version == 2 -> selected
                nested != null && selected.version == 3 -> requireNotNull(selected.blocks) { "Missing Blocks compound" }
                else -> throw IllegalArgumentException("Only Sponge schematic v2 and v3 are supported")
            }
            val w = selected.width
            val h = selected.height
            val l = selected.length
            val volume = w.toLong() * h * l
            require(w in 1..272 && l in 1..272 && h in 1..384 && volume in 1..MAX_BLOCKS.toLong()) {
                "Schematic exceeds Limbo map dimensions or 1,048,576 blocks"
            }
            val wirePalette = requireNotNull(blocks.palette) { "Missing schematic palette" }
            val data = requireNotNull(blocks.data) { "Missing schematic block data" }
            val keys = wirePalette.filterNotNull().distinct()
            val indexByKey = keys.withIndex().associate { it.value to it.index }
            val remap = IntArray(wirePalette.size) { wirePalette[it]?.let(indexByKey::getValue) ?: -1 }
            var cursor = 0
            val indices = IntArray(volume.toInt()) {
                var value = 0
                var shift = 0
                do {
                    require(cursor < data.size && shift <= 7) { "Invalid schematic block index" }
                    val octet = data[cursor++].toInt() and 0xff
                    value = value or ((octet and 0x7f) shl shift)
                    shift += 7
                } while (octet and 0x80 != 0)
                require(value < remap.size && remap[value] >= 0) { "Schematic uses an undefined palette entry" }
                remap[value]
            }
            require(cursor == data.size) { "Extra schematic block data" }
            return Schematic(w, h, l, java.util.List.copyOf(keys), indices)
        }

        private fun fields(depth: Int, schematic: ((Int) -> Unit)? = null): Fields {
            val result = Fields()
            val seen = HashSet<String>()
            compound { type, key ->
                val known = when (key) {
                    "Schematic" -> schematic != null
                    "Data", "BlockData", "Palette", "Blocks", "Version", "Width", "Height", "Length" -> true
                    else -> false
                }
                if (!known) skip(type, depth + 1)
                else {
                    require(seen.add(key)) { "Duplicate schematic field" }
                    when (key) {
                        "Schematic" -> schematic!!(type)
                        "Version" -> { require(type == 3); result.version = input.readInt() }
                        "Width", "Height", "Length" -> {
                            require(type == 2)
                            val value = input.readUnsignedShort()
                            when (key) {
                                "Width" -> result.width = value
                                "Height" -> result.height = value
                                else -> result.length = value
                            }
                        }
                        "Blocks" -> {
                            require(type == 10 && depth < 2)
                            result.blocks = fields(depth + 1)
                        }
                        "Data", "BlockData" -> {
                            require(type == 7 && result.data == null)
                            val size = input.readInt()
                            require(size in 1..(2 * MAX_BLOCKS) && size <= input.available())
                            result.data = ByteArray(size).also(input::readFully)
                        }
                        "Palette" -> {
                            require(type == 10)
                            val palette = arrayOfNulls<String>(MAX_PALETTE)
                            compound { entryType, state ->
                                require(entryType == 3)
                                val id = input.readInt()
                                require(id in palette.indices && palette[id] == null) { "Invalid schematic palette ID" }
                                palette[id] = canonicalState(state)
                            }
                            result.palette = palette
                        }
                    }
                }
            }
            return result
        }

        private inline fun compound(entry: (Int, String) -> Unit) {
            while (true) {
                val type = input.readUnsignedByte()
                if (type == 0) return
                entry(type, input.readUTF())
            }
        }

        private fun ignoreString() = input.skipNBytes(input.readUnsignedShort().toLong())

        private fun skip(type: Int, depth: Int) {
            require(depth <= 32) { "Schematic NBT is too deeply nested" }
            when (type) {
                1, 2, 3, 4, 5, 6 -> input.skipNBytes(when (type) { 1 -> 1; 2 -> 2; 3, 5 -> 4; else -> 8 })
                7, 11, 12 -> {
                    val count = input.readInt().toLong()
                    require(count >= 0)
                    input.skipNBytes(count * when (type) { 7 -> 1; 11 -> 4; else -> 8 })
                }
                8 -> ignoreString()
                9 -> {
                    val element = input.readUnsignedByte()
                    val count = input.readInt()
                    require(count in 0..input.available() && (count == 0 || element in 1..12))
                    repeat(count) { skip(element, depth + 1) }
                }
                10 -> while (true) {
                    val element = input.readUnsignedByte()
                    if (element == 0) break
                    ignoreString()
                    skip(element, depth + 1)
                }
                else -> error("Invalid schematic NBT tag")
            }
        }
    }
}
