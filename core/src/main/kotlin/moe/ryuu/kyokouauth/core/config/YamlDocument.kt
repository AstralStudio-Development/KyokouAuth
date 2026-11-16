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
 * Only scalars are expected as leaves. A list becomes its items joined with newlines, which is what a
 * message file wants and what a configuration value would never legitimately be.
 */
object YamlDocument {

    fun load(file: File): Map<String, String> = flatten(Yaml().load<Any?>(file.reader(StandardCharsets.UTF_8)))

    fun load(bytes: ByteArray): Map<String, String> =
        flatten(Yaml().load<Any?>(InputStreamReader(bytes.inputStream(), StandardCharsets.UTF_8)))

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

/**
 * Reads one value with a default, so a missing key is a default rather than a crash.
 *
 * A configuration file that has not caught up with a release must not stop the plugin from starting:
 * the alternative is that an upgrade refuses to boot until an operator hand-edits YAML.
 */
fun Map<String, String>.string(key: String, default: String): String = this[key] ?: default

fun Map<String, String>.int(key: String, default: Int): Int = this[key]?.trim()?.toIntOrNull() ?: default

fun Map<String, String>.long(key: String, default: Long): Long = this[key]?.trim()?.toLongOrNull() ?: default

fun Map<String, String>.bool(key: String, default: Boolean): Boolean =
    this[key]?.trim()?.lowercase()?.let { it == "true" || it == "yes" || it == "on" } ?: default
