package moe.ryuu.kyokouauth.core.text

import moe.ryuu.kyokouauth.core.config.YamlDocument
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The message catalogue every platform shares.
 *
 * Every message a player can see lives in a file, including the ones produced by the login layer,
 * because an owner who has translated the plugin will otherwise meet half of it in English at
 * exactly the moment a player is being rejected.
 *
 * Files are written out on first start rather than read from inside the jar: an operator who wants to
 * change one line should not have to extract a file first. The bundled copies stay as the source for
 * that extraction and as the fallback for any key an operator's file has not caught up with - the
 * alternative is showing a player the *key*, which is what happens when a translation predates a
 * release.
 *
 * Platforms differ in how a message reaches a player (a chat packet, a kick screen, a title), so this
 * class only resolves and parses. Rendering belongs to the platform.
 */
class MessageCatalogue(
    private val defaultLocale: String,
    private val directory: File,
    private val resources: ClassLoader = MessageCatalogue::class.java.classLoader,
) {

    /** Every locale this installation can serve, keyed by its normalised tag (`zh_CN`). */
    private val bundles = ConcurrentHashMap<String, Map<String, String>>()

    /** The jar's own copies, consulted when an operator's file predates a key. */
    private val bundledCopies = ConcurrentHashMap<String, Map<String, String>>()

    @Volatile
    private var fallbackEn: Map<String, String> = emptyMap()

    @Volatile
    private var defaultBundle: Map<String, String> = emptyMap()

    /**
     * Templates with no placeholders, already parsed.
     *
     * A limbo title is re-sent every few seconds per held player, and the markup parser walks every
     * character of it each time. Keyed by the template text rather than the key, so an edited line is
     * a cache miss instead of a stale answer.
     */
    private val fixed = ConcurrentHashMap<String, RichText.Parsed>()

    fun load() {
        directory.mkdirs()

        extract("en_US")
        extract(defaultLocale)

        bundles.clear()
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".yml") }
            ?.forEach { file ->
                read(file)?.let { bundles[file.name.removeSuffix(".yml")] = it }
            }

        fallbackEn = bundles["en_US"] ?: bundled("en_US") ?: emptyMap()
        defaultBundle = bundles[defaultLocale] ?: fallbackEn
        fixed.clear()
        bundledCopies.clear()
    }

    /** The message in the server's configured locale. */
    fun parsed(key: String, vararg placeholders: Pair<String, String>): RichText.Parsed =
        parsed(key, null, *placeholders)

    /**
     * The message resolved and parsed.
     *
     * @param locale the player's client locale (`zh_cn`); null means the server's configured one.
     */
    fun parsed(key: String, locale: String?, vararg placeholders: Pair<String, String>): RichText.Parsed {
        val template = raw(key, locale)

        // A message with placeholders is parsed every time: substitution happens before parsing, so
        // the result differs per player and cannot be cached as one value.
        if (placeholders.isNotEmpty()) {
            return RichText.parse(RichText.substitute(template, placeholders.toMap()))
        }

        val tag = if (locale == null) defaultLocale else normalise(locale)
        return fixed.getOrPut("$tag\u0000$template") { RichText.parse(template) }
    }

    /** The message as plain text: for logging, and for keys consulted before a player exists. */
    fun plain(key: String, vararg placeholders: Pair<String, String>): String =
        parsed(key, null, *placeholders).plainText

    fun plain(key: String, locale: String?, vararg placeholders: Pair<String, String>): String =
        parsed(key, locale, *placeholders).plainText

    private fun raw(key: String, locale: String?): String {
        bundleFor(locale)[key]?.let { return it }

        val tag = if (locale == null) defaultLocale else normalise(locale)

        return bundled(tag)?.get(key)
            ?: bundled("en_US")?.get(key)
            ?: fallbackEn[key]
            ?: key
    }

    private fun bundleFor(locale: String?): Map<String, String> {
        if (locale == null) return defaultBundle
        return bundles[normalise(locale)] ?: defaultBundle
    }

    private fun bundled(tag: String): Map<String, String>? {
        bundledCopies[tag]?.let { return it }
        val loaded = readBundled(tag) ?: return null
        bundledCopies[tag] = loaded
        return loaded
    }

    /** Copies the bundled file to disk unless the operator already has one. */
    private fun extract(tag: String) {
        val target = File(directory, "$tag.yml")
        if (target.isFile) return

        val resource = resources.getResourceAsStream("messages/$tag.yml") ?: return
        try {
            resource.use { target.writeBytes(it.readBytes()) }
        } catch (_: Throwable) {
            // A read-only data folder is the operator's to notice, not a reason to fail startup: the
            // bundled copy is still read directly.
        }
    }

    private fun readBundled(tag: String): Map<String, String>? =
        resources.getResourceAsStream("messages/$tag.yml")?.use { stream ->
            flatten(stream.readBytes())
        }

    private fun read(file: File): Map<String, String>? = runCatching {
        flatten(file.readBytes())
    }.getOrNull()

    private fun flatten(bytes: ByteArray): Map<String, String> = YamlDocument.load(bytes)

    private fun normalise(clientLocale: String): String {
        val parts = clientLocale.split('_')
        if (parts.size != 2) return clientLocale
        return "${parts[0].lowercase()}_${parts[1].uppercase()}"
    }
}
