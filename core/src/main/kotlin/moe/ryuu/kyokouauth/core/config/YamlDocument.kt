package moe.ryuu.kyokouauth.core.config

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * A YAML file flattened into dotted keys.
 *
 * Both the configuration and the message files are written as nested YAML, because that is what an
 * operator can read, but they are *looked up* by flat path (`database.pool-size`,
 * `login.premium-name`). Flattening once at load time keeps every later read a map access, which
 * matters because messages are resolved on the thread that delivers them.
 *
 * Leaves are scalars or lists. Lists become newline-separated values for multi-line messages and
 * command-root settings; the consumer validates the meaning of each resulting value.
 */
object YamlDocument {

    fun load(file: File): Map<String, String> =
        file.reader(StandardCharsets.UTF_8).use { flatten(Yaml().load<Any?>(it)) }

    fun load(bytes: ByteArray): Map<String, String> =
        InputStreamReader(bytes.inputStream(), StandardCharsets.UTF_8).use { flatten(Yaml().load<Any?>(it)) }

    // SnakeYaml reads ahead but does not close this reader; the supplied stream stays caller-owned.
    fun load(stream: InputStream): Map<String, String> =
        flatten(Yaml().load<Any?>(InputStreamReader(stream, StandardCharsets.UTF_8)))

    private fun flatten(root: Any?): Map<String, String> {
        val flat = LinkedHashMap<String, String>()
        collect("", root, flat)
        return flat
    }

    private fun collect(prefix: String, node: Any?, into: MutableMap<String, String>) {
        when (node) {
            is Map<*, *> -> node.forEach { (key, value) ->
                collect(if (prefix.isEmpty()) "$key" else "$prefix.$key", value, into)
            }

            null -> Unit

            is List<*> -> into[prefix] = node.joinToString("\n") { it.toString() }

            else -> into[prefix] = node.toString()
        }
    }
}
