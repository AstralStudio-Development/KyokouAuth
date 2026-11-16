package moe.ryuu.kyokouauth.bukkit

import moe.ryuu.kyokouauth.core.text.RichText
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Message catalogue.
 *
 * Every message the player can see lives in a file, including the ones produced by the login layer,
 * because a server owner who has translated the plugin will otherwise find half of it in English at
 * exactly the moment a player is being rejected.
 *
 * The files are written out on first start rather than read from inside the jar: an operator who
 * wants to change one line should not have to extract and place a file first. The bundled copies
 * remain as the source for that extraction and as the fallback for any key an operator removed.
 *
 * ## Which language a player sees
 *
 * A player gets the file matching their own client language when one exists, and the server's
 * configured locale otherwise. An operator drops `messages_de_DE.yml` into the folder and German
 * clients start seeing it on the next `/kyokouauth reload`; nobody else is affected. The server
 * locale is also the fallback for every key a translation left out.
 *
 * This class only *resolves* and *parses*; delivery belongs to
 * [moe.ryuu.kyokouauth.bukkit.message.Messenger].
 */
class Messages(private val defaultLocale: String, private val dataFolder: File) {

    /** Every locale this installation can serve, keyed by its normalised tag (`zh_CN`). */
    private val bundles = ConcurrentHashMap<String, YamlConfiguration>()

    /**
     * The jar's own copies, consulted when an operator's file predates a key.
     *
     * A language file is written out once and then belongs to the operator. An upgrade that adds a
     * message therefore finds their file missing that key - and without this fallback the player is
     * shown the key name, which is how `command.setspawn-player-only` ended up on someone's screen.
     */
    private val bundledCopies = ConcurrentHashMap<String, YamlConfiguration>()

    private var fallbackEn: YamlConfiguration = YamlConfiguration()
    private var defaultBundle: YamlConfiguration = YamlConfiguration()

    /**
     * Templates with no placeholders, already parsed, keyed by locale and text.
     *
     * The markup parser walks every character, and the messages that hit it most often are the ones
     * that never change: a Limbo title is re-sent every three seconds per held player, and
     * `/kyokouauth usage` on every mistyped command. Keyed by the template text rather than the key,
     * so an edited line is a cache miss instead of a stale answer; [load] clears it anyway.
     */
    private val fixed = ConcurrentHashMap<String, RichText.Parsed>()

    /** Operator-editable copies: `<dataFolder>/messages/messages_<tag>.yml`. */
    val directory: File get() = File(dataFolder, "messages")

    fun load() {
        directory.mkdirs()

        extract("en_US")
        extract(defaultLocale)

        // Loaded from the folder rather than on demand: a player joining with a locale nobody has a
        // file for would otherwise hit the disk once per message, on the thread delivering it.
        bundles.clear()
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".yml") }
            ?.forEach { file ->
                val tag = file.name.removeSuffix(".yml")
                readFile(file)?.let { bundles[tag] = it }
            }

        fallbackEn = bundles["en_US"] ?: readBundled("en_US") ?: YamlConfiguration()
        defaultBundle = bundles[defaultLocale] ?: fallbackEn
        fixed.clear()
        bundledCopies.clear()
    }

    /** Copies the bundled file to disk unless the operator already has one. */
    private fun extract(tag: String) {
        val target = File(directory, "$tag.yml")
        if (target.isFile) return

        // Two earlier layouts: the file first lived in the data folder itself, then in this folder
        // under a `messages_` prefix that repeated the directory's own name. Both are carried over
        // so an existing translation is not silently reverted to the bundled text.
        listOf(File(dataFolder, "messages_$tag.yml"), File(directory, "messages_$tag.yml"))
            .forEach { legacy ->
                if (legacy.isFile) runCatching { legacy.renameTo(target) }
            }
        if (target.isFile) return

        val resource = javaClass.classLoader.getResourceAsStream("messages/$tag.yml") ?: return
        try {
            resource.use { target.writeBytes(it.readBytes()) }
        } catch (_: Throwable) {
            // A read-only data folder is the operator's problem to notice, not a reason to fail
            // startup: the bundled copy is still read directly below.
        }
    }

    private fun readBundled(tag: String): YamlConfiguration? =
        javaClass.classLoader.getResourceAsStream("messages/$tag.yml")?.use { stream ->
            YamlConfiguration.loadConfiguration(InputStreamReader(stream, StandardCharsets.UTF_8))
        }

    private fun readFile(file: File): YamlConfiguration? = runCatching {
        YamlConfiguration.loadConfiguration(InputStreamReader(file.inputStream(), StandardCharsets.UTF_8))
    }.getOrNull()

    /**
     * `zh_cn` -> `zh_CN`.
     *
     * Minecraft reports the client locale lower-cased (`zh_cn`, `en_us`); the file names follow the
     * Java convention (`zh_CN`, `en_US`). A locale that does not have two parts is returned as given.
     */
    private fun normalise(clientLocale: String): String {
        val parts = clientLocale.split('_')
        if (parts.size != 2) return clientLocale
        return "${parts[0].lowercase()}_${parts[1].uppercase()}"
    }

    private fun bundleFor(locale: String?): YamlConfiguration {
        if (locale == null) return defaultBundle
        return bundles[normalise(locale)] ?: defaultBundle
    }

    private fun bundledFor(tag: String): YamlConfiguration? {
        bundledCopies[tag]?.let { return it }
        val loaded = readBundled(tag) ?: return null
        bundledCopies[tag] = loaded
        return loaded
    }

    private fun raw(key: String, locale: String?): String {
        bundleFor(locale).getString(key)?.let { return it }

        val tag = if (locale == null) defaultLocale else normalise(locale)

        // The operator's file predates this key. The jar's copy is the current text, so a feature
        // added after they last regenerated their translation still speaks instead of printing a key.
        return bundledFor(tag)?.getString(key)
            ?: bundledFor("en_US")?.getString(key)
            ?: fallbackEn.getString(key)
            ?: key
    }

    /** The message in the server's configured locale. */
    fun parsed(key: String, vararg placeholders: Pair<String, String>): RichText.Parsed =
        parsed(key, null, *placeholders)

    /**
     * The message resolved and parsed.
     *
     * @param locale the player's client locale (`zh_cn`); null means the server's configured one.
     *
     * A message with placeholders is parsed every time: substitution happens before parsing, so the
     * result is different for every player. A message without them is parsed once and kept.
     */
    fun parsed(
        key: String,
        locale: String?,
        vararg placeholders: Pair<String, String>,
    ): RichText.Parsed {
        val template = raw(key, locale)

        if (placeholders.isNotEmpty()) {
            return RichText.parse(RichText.substitute(template, placeholders.toMap()))
        }

        val tag = if (locale == null) defaultLocale else normalise(locale)
        return fixed.getOrPut("$tag\u0000$template") { RichText.parse(template) }
    }

    /**
     * The message as plain text.
     *
     * Used where nothing can be rendered - chiefly logging, and message keys that are consulted
     * before a player exists.
     */
    fun plain(key: String, vararg placeholders: Pair<String, String>): String =
        plain(key, null, *placeholders)

    fun plain(key: String, locale: String?, vararg placeholders: Pair<String, String>): String =
        parsed(key, locale, *placeholders).plainText
}
