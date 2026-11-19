package moe.ryuu.kyokouauth.limbo.session

import moe.ryuu.kyokouauth.limbo.LimboSettings
import java.util.zip.GZIPInputStream

/** Maps only the schematic's palette into the current release; the full table never stays in memory. */
internal class SchematicStates private constructor(val registrySize: Int, private val encoded: IntArray) {
    fun id(index: Int) = encoded[index] ushr 4
    fun blocksMotion(index: Int) = encoded[index] and 2 != 0
    fun hasFluid(index: Int) = encoded[index] and 4 != 0

    companion object {
        fun load(layout: LimboSettings.MapLayout): SchematicStates {
            val protocol = GeneratedCodec.PROTOCOL
            val keys = layout.schematic.palette
            val indices = keys.withIndex().associate { it.value to it.index }
            val states = IntArray(keys.size) { -1 }
            val defaults = IntArray(keys.size) { -1 }
            val resource = requireNotNull(SchematicStates::class.java.getResourceAsStream("/limbo/block-states-$protocol.txt.gz"))
            val count = GZIPInputStream(resource).bufferedReader(Charsets.UTF_8).use { reader ->
                val header = reader.readLine().split('\t', limit = 2)
                require(header.size == 2 && header[0] == protocol.toString() && header[1].length == 64)
                var id = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    // Avoid splitting every state into a list. Only retain matching states.
                    val separator = line.lastIndexOf('\t')
                    val flags = line.substring(separator + 1).toInt()
                    val key = line.substring(0, separator)
                    val encoded = if (flags and 1 != 0) 0 else (id shl 4) or flags
                    indices[key]?.let { states[it] = encoded }
                    if (flags and 8 != 0) indices[key.substringBefore('[')]?.let { defaults[it] = encoded }
                    id++
                }
                id
            }
            for (index in states.indices) {
                if (states[index] == -1) states[index] = defaults[index]
                if (states[index] == -1) {
                    require(layout.unknownBlocksAsAir) { "Schematic block state ${keys[index]} is not present in protocol $protocol" }
                    states[index] = 0
                }
            }
            return SchematicStates(count, states)
        }
    }
}
