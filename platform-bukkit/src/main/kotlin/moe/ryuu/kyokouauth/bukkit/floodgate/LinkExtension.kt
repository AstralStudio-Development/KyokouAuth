package moe.ryuu.kyokouauth.bukkit.floodgate

import org.bukkit.plugin.Plugin
import java.io.File

/**
 * Installs the Floodgate linking extension that lets a linked Bedrock account resolve to its Java
 * identity, and reports configuration that would stop it working.
 *
 * ## Why the plugin ships this instead of asking the operator to
 *
 * Floodgate's own `player-link` is the feature that would normally do this, and against the API as
 * it stands it cannot: the global service answers `{}` for `…/v2/link/bedrock/<xuid>` and the real
 * answer only appears at `…/v2/link/bedrock/<xuid>/` — a trailing slash Floodgate does not send.
 * Asking every server owner to download a jar, name it after a config value and restart would move
 * that defect onto them. Instead the extension travels inside this plugin and is written out on
 * start, so installing KyokouAuth remains the only deployment step.
 *
 * ## What the operator still owns
 *
 * Floodgate only loads linking implementations when `player-link.enable-own-linking` is true, and it
 * picks one by matching `player-link.type` against the file name `floodgate-<type>-database.jar`.
 * Both live in Floodgate's own configuration; rewriting someone else's file behind their back is
 * worse than telling them what to change, so this reports instead of edits.
 */
internal object LinkExtension {

    const val FILE_NAME = "floodgate-global-database.jar"
    const val TYPE = "global"

    private const val RESOURCE = "floodgate/$FILE_NAME"

    sealed class Outcome {
        object Installed : Outcome()
        object AlreadyPresent : Outcome()
        class Failed(val reason: String) : Outcome()
    }

    /**
     * Writes the bundled extension into Floodgate's data folder when it is not already there.
     *
     * An existing file is left alone rather than overwritten: an operator who replaced it with a
     * newer build should not have it silently reverted on every restart.
     */
    fun install(floodgate: Plugin): Outcome {
        val target = File(floodgate.dataFolder, FILE_NAME)
        if (target.isFile) return Outcome.AlreadyPresent

        return try {
            val bytes = LinkExtension::class.java.classLoader
                .getResourceAsStream(RESOURCE)
                ?.use { it.readBytes() }
                ?: return Outcome.Failed("bundled resource $RESOURCE is missing")

            floodgate.dataFolder.mkdirs()
            target.writeBytes(bytes)
            Outcome.Installed
        } catch (failure: Throwable) {
            Outcome.Failed(failure.message ?: failure.javaClass.simpleName)
        }
    }

    /** @return a description of what the operator must change, or null when the configuration fits. */
    fun configurationProblem(floodgate: Plugin): String? {
        val config = File(floodgate.dataFolder, "config.yml")
        if (!config.isFile) return null

        val text = runCatching { config.readText() }.getOrNull() ?: return null

        val ownLinking = Regex("^\\s*enable-own-linking:\\s*(\\S+)", RegexOption.MULTILINE)
            .find(text)?.groupValues?.get(1)
        val type = Regex("^\\s*type:\\s*(\\S+)", RegexOption.MULTILINE)
            .find(text)?.groupValues?.get(1)?.trim('"', '\'')

        return when {
            !ownLinking.equals("true", ignoreCase = true) ->
                "player-link.enable-own-linking 需要设为 true（当前为 ${ownLinking ?: "未设置"}）"

            type != null && type != TYPE ->
                "player-link.type 需要设为 $TYPE（当前为 $type）"

            else -> null
        }
    }
}
