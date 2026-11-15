package moe.ryuu.kyokouauth.bukkit.api

import moe.ryuu.kyokouauth.bukkit.KyokouAuthPlugin
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * The `%kyokouauth_*%` placeholders.
 *
 * This class extends PlaceholderAPI's abstract `PlaceholderExpansion`, which means it can only be
 * loaded on a server that has PlaceholderAPI - the plugin therefore constructs it only after
 * confirming the class is present. An earlier version tried to stand in with a `java.lang.reflect`
 * Proxy and failed at runtime with "PlaceholderExpansion is not an interface"; the fix is a real
 * subclass and a compile-time dependency, not more reflection.
 *
 * ## Reading from the cache
 *
 * These are queried from the main thread by scoreboard and tab plugins, so they must not touch the
 * database. The answer comes from a cache filled at pre-login; a bound identity does not change
 * during a session, so it cannot go meaningfully stale.
 */
internal class KyokouPlaceholderExpansion(
    private val plugin: KyokouAuthPlugin,
) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "kyokouauth"

    override fun getAuthor(): String = "Ryuu"

    override fun getVersion(): String = plugin.description.version

    /** Kept across `/papi reload`, which would otherwise drop it. */
    override fun persist(): Boolean = true

    override fun canRegister(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String {
        if (player == null) return ""

        return when (params.lowercase()) {
            "verified" -> if (plugin.limbo.isHeld(player.uniqueId)) "false" else "true"
            "pending" -> if (plugin.limbo.isHeld(player.uniqueId)) "true" else "false"

            "bound" -> if (plugin.core.identities.cached(player.uniqueId) != null) "true" else "false"

            "kind" -> when (plugin.core.identities.cached(player.uniqueId)?.kind) {
                moe.ryuu.kyokouauth.core.storage.BoundIdentity.Kind.MOJANG -> "premium"
                moe.ryuu.kyokouauth.core.storage.BoundIdentity.Kind.XUID -> "microsoft"
                null -> ""
            }

            "gamertag" -> plugin.core.identities.cached(player.uniqueId)?.gamertag ?: ""
            "subject" -> plugin.core.identities.cached(player.uniqueId)?.subject ?: ""
            else -> ""
        }
    }
}

/**
 * Registers the expansion only when PlaceholderAPI is actually installed and enabled.
 *
 * Deliberately a separate object: it references the expansion class only from inside a method body,
 * so a server without PlaceholderAPI can load this object without ever loading a class that extends
 * an absent abstract one.
 */
internal object PlaceholderRegistrar {

    fun register(plugin: KyokouAuthPlugin): Boolean {
        val placeholderApi = plugin.server.pluginManager.getPlugin("PlaceholderAPI") ?: return false
        if (!placeholderApi.isEnabled) return false

        return runCatching {
            placeholderApi.description.version
            KyokouPlaceholderExpansion(plugin).register()
        }.getOrElse { failure ->
            plugin.logger.warning("PlaceholderAPI is installed but the expansion failed to register: ${failure.message}")
            false
        }
    }
}

