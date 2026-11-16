package moe.ryuu.kyokouauth.bukkit.profile

import moe.ryuu.kyokouauth.bukkit.KyokouAuthPlugin
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import java.util.UUID

/**
 * Puts a connection in the world under the UUID its identity owns.
 *
 * ## Why this is an interface rather than a method
 *
 * `AsyncPlayerPreLoginEvent#setPlayerProfile` is the only supported way to choose a UUID, and it is
 * a Paper addition. The plugin's floor is 1.16, so it is always present there - but the check that
 * proves it costs one reflection call at startup, and a server that cannot honour the contract
 * should say so before a player connects rather than throw inside the login path.
 *
 * ## Why an unsupported server is refused, not degraded
 *
 * The fallback would be Bukkit's own `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)`. That is a
 * UUID derived from the *name*, and it breaks two things this plugin exists for:
 *
 *  - a rename moves the player to a different UUID, splitting their data;
 *  - Geyser/Floodgate represent a Bedrock player as `UUID(0, xuid)`, so the same Microsoft account
 *    would be two different players depending on which door they came through.
 *
 * Refusing to start is the honest answer: without a settable profile the plugin cannot deliver its
 * own contract.
 */
interface ProfileApplier {

    /**
     * Admits this connection under [uuid].
     *
     * Called from the async pre-login thread; must not touch the world.
     *
     * @param textures the player's texture property, when the Mojang handshake already returned one.
     *   Passing it avoids a second request to the endpoint that rate-limits this plugin hardest.
     */
    fun apply(
        event: AsyncPlayerPreLoginEvent,
        uuid: UUID,
        name: GameName,
        record: BoundIdentity?,
        textures: Pair<String, String>? = null,
    )

    companion object {
        /**
         * @return the applier this server can run, or null when it cannot set a profile - in which
         *   case the caller must refuse to enable rather than fall back to a name-derived UUID.
         */
        fun create(plugin: KyokouAuthPlugin): ProfileApplier? {
            val supported = runCatching {
                AsyncPlayerPreLoginEvent::class.java.getMethod(
                    "setPlayerProfile",
                    Class.forName("com.destroystokyo.paper.profile.PlayerProfile"),
                )
            }.isSuccess

            return if (supported) PaperProfileApplier(plugin) else null
        }
    }
}
