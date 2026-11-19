package moe.ryuu.kyokouauth.core.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.representer.Representer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFileAttributeView
import java.util.logging.Logger

/** Installs the current template, inserting only missing keys into an existing document. */
object YamlDefaults {
    private val log = Logger.getLogger(YamlDefaults::class.java.name)

    fun sync(file: Path, template: ByteArray, aliases: Map<String, String> = emptyMap()) {
        if (Files.isSymbolicLink(file)) {
            log.warning("Skipping YAML upgrade for symlink: ${file.fileName}")
            return
        }
        if (Files.notExists(file)) {
            replace(file, template, preservePermissions = false)
            return
        }
        require(Files.isRegularFile(file)) { "YAML configuration must be a regular file" }
        val original = Files.readAllBytes(file)
        val updated = insertDefaults(original, template, aliases) ?: return
        try {
            check(Files.isWritable(file) && Files.readAllBytes(file).contentEquals(original))
            replace(file.resolveSibling("${file.fileName}.pre-upgrade.bak"), original, preservePermissions = false)
            check(Files.readAllBytes(file).contentEquals(original))
            replace(file, updated)
        } catch (_: Exception) {
            // No sensitive values or parser diagnostics in the console. Load the unchanged file.
            log.warning("Could not save missing YAML fields to ${file.fileName}; keeping the existing file")
        }
    }

    private fun insertDefaults(original: ByteArray, template: ByteArray, aliases: Map<String, String>): ByteArray? {
        val raw = original.toString(Charsets.UTF_8)
        val source = raw.removePrefix("\uFEFF")
        val prefix = if (source.length == raw.length) "" else "\uFEFF"
        val newline = if ("\r\n" in source) "\r\n" else "\n"
        val yaml = yaml()
        val current = yaml.load<Any?>(source) ?: emptyMap<String, Any?>()
        require(current is Map<*, *>) { "YAML configuration must be a mapping" }
        val defaults = yaml.load<Map<String, Any?>>(template.inputStream())
        val existingPaths = linkedSetOf<String>()
        fun record(map: Map<*, *>, parent: String) {
            for ((key, value) in map) {
                val path = "$parent$key"
                existingPaths += path
                if (value is Map<*, *>) record(value, "$path.")
            }
        }
        record(current, "")

        val edits = mutableMapOf<Int, String>()
        val root = yaml.compose(source.reader())
        fun walk(nodes: MappingNode?, values: Map<*, *>, defaultsHere: Map<*, *>, parent: String) {
            val additions = linkedMapOf<String, Any?>()
            for ((rawKey, value) in defaultsHere) {
                val key = rawKey as String
                val path = "$parent$key"
                val actual = values[key]
                if (!values.containsKey(key) && path !in existingPaths && aliases[path] !in existingPaths) {
                    // Keep literal dotted overrides instead of adding a nested key with the same effective path.
                    val filtered = if (value is Map<*, *>) missingTree(value, "$path.", existingPaths, aliases) else value
                    if (filtered !is Map<*, *> || filtered.isNotEmpty()) additions[key] = filtered
                } else if (value is Map<*, *> && actual is Map<*, *>) {
                    // An anchor/alias may be shared by other sections; do not edit its source in place.
                    val child = nodes?.value?.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }?.valueNode as? MappingNode
                    if (child != null && child.anchor == null) walk(child, actual, value, "$path.")
                }
            }
            if (additions.isEmpty()) return
            if (nodes == null && root == null) {
                edits[source.length] = (if (source.isNotEmpty() && !source.endsWith('\n')) newline else "") +
                    yaml.dump(additions).replace("\n", newline)
            } else if (nodes != null && nodes.flowStyle == DumperOptions.FlowStyle.FLOW) {
                val start = source.offsetByCodePoints(0, nodes.startMark.index)
                if (source.getOrNull(start) != '{') return
                val entries = yaml(DumperOptions.FlowStyle.FLOW).dump(additions).trim().removeSurrounding("{", "}")
                edits[start + 1] = entries + if (nodes.value.isEmpty()) "" else ", "
            } else if (nodes != null) {
                val mark = nodes.value.first().keyNode.startMark
                val keyStart = source.offsetByCodePoints(0, mark.index)
                val lineStart = keyStart - mark.column
                if (!source.substring(lineStart, keyStart).all { it == ' ' }) return
                val indent = " ".repeat(mark.column)
                edits[lineStart] = yaml.dump(additions).trimEnd('\n').lineSequence().joinToString(newline, postfix = newline) { indent + it }
            }
        }
        walk(root as? MappingNode, current, defaults, "")
        if (edits.isEmpty()) return null
        val changed = StringBuilder(source)
        edits.toSortedMap(reverseOrder()).forEach { (offset, text) -> changed.insert(offset, text) }
        val result = (prefix + changed).toByteArray(Charsets.UTF_8)
        val before = YamlDocument.load(original)
        val after = YamlDocument.load(result)
        check(before.all { (key, value) -> after[key] == value }) { "YAML upgrade would alter an existing value" }
        return result
    }

    private fun missingTree(template: Map<*, *>, parent: String, paths: Set<String>, aliases: Map<String, String>): Map<String, Any?> =
        buildMap {
            for ((rawKey, value) in template) {
                val key = rawKey as String
                val path = "$parent$key"
                if (path in paths || aliases[path] in paths) continue
                val missing = if (value is Map<*, *>) missingTree(value, "$path.", paths, aliases) else value
                if (missing !is Map<*, *> || missing.isNotEmpty()) put(key, missing)
            }
        }

    private fun yaml(style: DumperOptions.FlowStyle = DumperOptions.FlowStyle.BLOCK): Yaml {
        val options = DumperOptions().apply { defaultFlowStyle = style; splitLines = false }
        val loader = LoaderOptions().apply { isAllowDuplicateKeys = false }
        return Yaml(SafeConstructor(loader), Representer(options), options, loader)
    }

    private fun replace(path: Path, bytes: ByteArray, preservePermissions: Boolean = true) {
        val target = path.toAbsolutePath()
        val temp = Files.createTempFile(target.parent, ".kyokou-yaml-", ".part")
        try {
            Files.write(temp, bytes)
            if (preservePermissions && Files.getFileAttributeView(target, PosixFileAttributeView::class.java) != null) {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target))
            }
            Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
