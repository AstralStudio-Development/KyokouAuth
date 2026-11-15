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
 * `AsyncPlayerPreLoginEvent#setPlayerProfile` is the only supported way to choose a UUID, and it
 * exists on Paper 1.12 and newer but **not** on 1.8-1.11. Referencing `PlayerProfile` from a class
 * that also has to load on those versions makes the whole class unloadable there, so the behaviour
 * lives behind this interface and a server without the capability never loads the implementation.
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
 * Refusing to start is the honest answer: the plugin cannot deliver its own contract on 1.8-1.11.
 */
interface ProfileApplier {

    /**
     * Admits this connection under [uuid].
     *
     * Called from the async pre-login thread; must not touch the world.
     */
    fun apply(
        event: AsyncPlayerPreLoginEvent,
        uuid: UUID,
        name: GameName,
        record: BoundIdentity?,
    )

    companion object {
        /** The first version whose `AsyncPlayerPreLoginEvent` accepts a profile. */
        const val MINIMUM_MENTIONED = "1.12"

        /**
         * @return the applier this server can run, or null when it is too old - in which case the
         *   caller must refuse to enable rather than fall back to a name-derived UUID.
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
