package moe.ryuu.kyokouauth.bukkit.limbo

import moe.ryuu.kyokouauth.bukkit.KyokouAuthPlugin
import moe.ryuu.kyokouauth.bukkit.Messages
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps an unverified player in the world but unable to do anything.
 *
 * AuthMe's approach, adopted because the alternative - kicking - makes the verification link
 * unclickable. The player is frozen in place rather than moved to a separate world: there is no
 * limbo world to render, so nothing has to be generated, backed up or explained to the operator.
 *
 * Every restriction here is a cancel in a listener. They are deliberately blunt: a frozen player
 * cannot walk, cannot be damaged, cannot open an inventory, cannot right-click, cannot chat, cannot
 * run a command and cannot pick anything up. Anything that slips through is a hole in the login, so
 * the list errs on the side of breaking gameplay rather than permitting it.
 */
class LimboManager(
    private val plugin: KyokouAuthPlugin,
    private val messages: Messages,
    private val settings: () -> moe.ryuu.kyokouauth.bukkit.Configuration.LimboSettings,
) {

    private val held = ConcurrentHashMap<UUID, HeldState>()

    private class HeldState(
        val returnLocation: Location,
        val gameMode: GameMode,
        val allowFlight: Boolean,
        val flying: Boolean,
        val flashTask: BukkitTask?,
    )

    fun isHeld(uuid: UUID): Boolean = held.containsKey(uuid)

    fun heldPlayers(): Set<UUID> = held.keys

    fun hold(player: Player) {
        if (held.containsKey(player.uniqueId)) return

        val state = HeldState(
            returnLocation = player.location.clone(),
            gameMode = player.gameMode,
            allowFlight = player.allowFlight,
            flying = player.isFlying,
            flashTask = if (settings().titleEnabled) startTitleFlash(player) else null,
        )
        held[player.uniqueId] = state

        player.walkSpeed = 0f
        player.setAllowFlight(true)
        player.isFlying = true
        // Invisible to mobs: a frozen player must not be killed by a zombie while reading a link.
        player.isInvulnerable = true
        player.fireTicks = 0
    }

    fun release(player: Player) {
        val state = held.remove(player.uniqueId) ?: return

        state.flashTask?.cancel()
        player.isInvulnerable = false
        player.walkSpeed = 0.2f
        player.setAllowFlight(state.allowFlight)
        player.isFlying = state.flying || state.allowFlight

        if (settings().teleportEnabled) {
            player.teleport(state.returnLocation)
        }
    }

    fun releaseAll() {
        plugin.server.onlinePlayers.forEach { release(it) }
    }

    private fun startTitleFlash(player: Player): BukkitTask =
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val playerRef = plugin.server.getPlayer(player.uniqueId) ?: return@Runnable
            plugin.messenger.showTitle(
                playerRef,
                messages.parsed("limbo.title"),
                messages.parsed("limbo.subtitle"),
            )
        }, 0L, 60L)
}
