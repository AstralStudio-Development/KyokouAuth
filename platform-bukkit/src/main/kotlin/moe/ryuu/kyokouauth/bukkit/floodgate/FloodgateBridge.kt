package moe.ryuu.kyokouauth.bukkit.floodgate

import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Recognises Bedrock players that Floodgate has already authenticated.
 *
 * ## Why they are admitted without verification
 *
 * A player arriving through Geyser + Floodgate has already completed a Microsoft sign-in — Geyser
 * does it for them, and Floodgate hands the server a UUID derived from their XUID. Asking them to do
 * a device-code sign-in on top would be asking the same person to prove the same fact twice.
 *
 * ## Why they are not bound
 *
 * Floodgate players are admitted without touching the name table at all. That is deliberate: their
 * name carries Floodgate's prefix (`.Steve` by default) while the same Microsoft account joining from
 * Java would use `Steve` for the same UUID. Binding the Bedrock name would make the Java name look
 * like a second name for one identity — which the domain forbids — and the two entry points would
 * fight over one account. Leaving the table alone means the same person is simply admitted from both
 * doors and occupies one UUID, which is what Floodgate's own derivation guarantees.
 *
 * ## Why reflection
 *
 * The alternative is a `compileOnly` dependency on a SNAPSHOT artifact from Floodgate's own
 * repository, versioned against a specific Minecraft generation. The surface actually needed here is
 * two methods, and a plugin whose entire value proposition is "runs on everything from 1.16.5 to
 * 26.3" should not couple its build to a third party's snapshot line for that.
 */
class FloodgateBridge private constructor(
    private val isFloodgatePlayer: (UUID) -> Boolean,
    private val namePrefix: String,
) {

    /** True when this UUID belongs to a player Floodgate authenticated. Never true for a Java connection. */
    fun isBedrockPlayer(uuid: UUID): Boolean = try {
        isFloodgatePlayer(uuid)
    } catch (_: Throwable) {
        // Floodgate is present but answered with something unexpected: fall back to "not Bedrock",
        // which sends the player through normal verification. Failing that way round means a broken
        // integration costs an extra sign-in, not an authentication bypass.
        false
    }

    val floodgateNamePrefix: String get() = namePrefix

    companion object {
        /**
         * @return a working bridge when Floodgate is installed and its API can be reached, otherwise
         *   null - in which case nothing about the login flow changes.
         */
        fun create(
            plugin: Plugin,
            onDetected: (version: String) -> Unit,
            onBroken: (error: String) -> Unit,
        ): FloodgateBridge? {
            val floodgate = plugin.server.pluginManager.getPlugin("floodgate") ?: return null

            return try {
                val type = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
                val instance = type.getMethod("getInstance").invoke(null)
                val isPlayer = type.getMethod("isFloodgatePlayer", UUID::class.java)

                val prefix = runCatching {
                    type.getMethod("getPlayerPrefix").invoke(instance) as? String
                }.getOrNull() ?: "."

                onDetected(floodgate.description.version)
                FloodgateBridge(
                    isFloodgatePlayer = { uuid -> isPlayer.invoke(instance, uuid) as? Boolean ?: false },
                    namePrefix = prefix,
                )
            } catch (failure: Throwable) {
                // Installed but unreachable is a configuration problem the operator needs to see:
                // silently ignoring it would leave Bedrock players stuck at a sign-in that makes no
                // sense to them.
                onBroken(failure.javaClass.simpleName + ": " + (failure.message ?: ""))
                null
            }
        }
    }
}
