package moe.ryuu.kyokouauth.bukkit

import moe.ryuu.kyokouauth.core.text.RichText
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Message catalogue.
 *
 * Every message the player can see lives in a file, including the ones produced by the login layer,
 * because a server owner who has translated the plugin will otherwise find half of it in English at
 * exactly the moment a player is being rejected.
 *
 * This class only *resolves* and *parses*; delivery belongs to [moe.ryuu.kyokouauth.bukkit.message.Messenger],
 * because what a message can become depends on the server - Adventure on modern Paper, BungeeChat on
 * Spigot, plain §-codes when neither is present.
 */
class Messages(private val locale: String, private val dataFolder: File) {

    private var bundle: YamlConfiguration = YamlConfiguration()
    private var fallback: YamlConfiguration = YamlConfiguration()

    fun load() {
        dataFolder.mkdirs()
        fallback = readBundled("en_US") ?: YamlConfiguration()
        bundle = readBundled(locale) ?: YamlConfiguration()
        // Let server owners override individual keys by dropping a file next to config.yml.
        readExternal(locale)?.let { override ->
            override.getKeys(false).forEach { key -> bundle.set(key, override.get(key)) }
        }
    }

    private fun readBundled(tag: String): YamlConfiguration? =
        javaClass.classLoader.getResourceAsStream("messages/messages_$tag.yml")?.use { stream ->
            YamlConfiguration.loadConfiguration(InputStreamReader(stream, StandardCharsets.UTF_8))
        }

    private fun readExternal(tag: String): YamlConfiguration? {
        val file = File(dataFolder, "messages_$tag.yml")
        return if (file.isFile) YamlConfiguration.loadConfiguration(file) else null
    }

    private fun raw(key: String): String = bundle.getString(key) ?: fallback.getString(key) ?: key

    /** The message resolved and parsed, ready for whichever backend this server has. */
    fun parsed(key: String, vararg placeholders: Pair<String, String>): RichText.Parsed =
        RichText.parse(RichText.substitute(raw(key), placeholders.toMap()))

    /**
     * The message as plain text.
     *
     * Used where nothing can be rendered - chiefly logging, and message keys that are consulted
     * before a player exists.
     */
    fun plain(key: String, vararg placeholders: Pair<String, String>): String =
        parsed(key, *placeholders).plainText
}
