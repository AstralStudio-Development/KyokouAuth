package moe.ryuu.kyokouauth.bukkit

import org.bukkit.configuration.file.YamlConfiguration

/**
 * Rewrites an outdated `config.yml` from the bundled template, carrying the operator's values over.
 *
 * ## Why this is text surgery rather than a config round-trip
 *
 * Bukkit's `YamlConfiguration.save` writes keys and values and nothing else - every comment is gone
 * afterwards. The comments are most of what makes the file usable, so the template's text is the
 * base and only the values are replaced. That also means an option the operator never touched keeps
 * its current default *and* its explanation.
 *
 * ## What it deliberately does not carry
 *
 * A key the template no longer has is dropped, which is how a removed option stops looking live.
 * `config-version` is never carried: it is the one value that must come from the template, or the
 * migration would rewrite the file and leave it still outdated.
 */
internal object ConfigMerger {

    private const val VERSION_KEY = "config-version"

    /**
     * @param template the bundled `config.yml`, comments included
     * @param existing the values currently on disk
     */
    fun merge(template: String, existing: YamlConfiguration): String {
        val out = StringBuilder(template.length + 256)
        val path = ArrayDeque<String>()

        template.lineSequence().forEach { line ->
            val trimmed = line.trimStart()

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                out.appendLine(line)
                return@forEach
            }

            val indent = line.length - trimmed.length
            val depth = indent / 2
            while (path.size > depth) path.removeLast()

            val colon = trimmed.indexOf(':')
            if (colon < 0) {
                out.appendLine(line)
                return@forEach
            }

            val key = trimmed.substring(0, colon)
            val rest = trimmed.substring(colon + 1)

            path.addLast(key)

            // A section header has no value of its own; it stays on the path for its children.
            if (rest.isBlank()) {
                out.appendLine(line)
                return@forEach
            }

            val fullKey = path.joinToString(".")
            path.removeLast()

            val carried = if (fullKey == VERSION_KEY) null else existing.get(fullKey)

            out.appendLine(
                if (carried == null) line else " ".repeat(indent) + key + ": " + scalar(carried),
            )
        }

        return out.toString()
    }

    /** YAML-safe rendering of a scalar read back from the operator's file. */
    private fun scalar(value: Any): String = when (value) {
        is String ->
            if (value.isEmpty() || value.any { it in SPECIAL }) {
                "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            } else {
                value
            }

        else -> value.toString()
    }

    /** Characters that would change how YAML reads the value, or start a comment. */
    private const val SPECIAL = ":#{}[],&*!|>'\"%@`"
}
