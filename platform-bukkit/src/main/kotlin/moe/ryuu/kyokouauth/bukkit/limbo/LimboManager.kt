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
        val gameMode: GameMode,
        val allowFlight: Boolean,
        val flying: Boolean,
        val flashTask: BukkitTask?,
    )

    fun isHeld(uuid: UUID): Boolean = held.containsKey(uuid)

    fun heldPlayers(): Set<UUID> = held.keys

    /**
     * Puts a player who does not need to verify at the configured spawn.
     *
     * A trusted session skips Limbo entirely, so [hold] never runs for them - but "every join lands
     * on the verification platform" is the behaviour an operator expects once they have set one.
     * Only a configured spawn applies here: falling back to the world spawn would teleport every
     * trusted player on every join, which is not what the switch means.
     */
    fun placeAtSpawn(player: Player) {
        settings().spawn?.toLocation()?.let { player.teleport(it) }
    }

    fun hold(player: Player) {
        if (held.containsKey(player.uniqueId)) return

        val state = HeldState(
            gameMode = player.gameMode,
            allowFlight = player.allowFlight,
            flying = player.isFlying,
            flashTask = if (settings().titleEnabled) startTitleFlash(player) else null,
        )
        held[player.uniqueId] = state

        // Placed before the freeze so the player is already standing still when the mask and the
        // title arrive.
        //
        // A configured spawn always wins. Requiring `teleport-to-spawn` to be flipped as well made
        // `/kyokouauth setspawn` look broken: it wrote a position, and nothing read it. That switch
        // now only decides what happens when no spawn has been configured.
        val configured = settings().spawn?.toLocation()
        if (configured != null) {
            player.teleport(configured)
        } else if (settings().teleportEnabled) {
            player.teleport(player.world.spawnLocation)
        }

        player.walkSpeed = 0f
        player.setAllowFlight(true)
        player.isFlying = true
        // Invisible to mobs: a frozen player must not be killed by a zombie while reading a link.
        player.isInvulnerable = true
        player.fireTicks = 0

        if (settings().hidePlayers) mask(player)
    }

    fun release(player: Player) {
        val state = held.remove(player.uniqueId) ?: return

        state.flashTask?.cancel()
        player.isInvulnerable = false
        player.walkSpeed = 0.2f
        player.setAllowFlight(state.allowFlight)
        player.isFlying = state.flying || state.allowFlight

        if (settings().hidePlayers) unmask(player)
    }

    fun releaseAll() {
        plugin.server.onlinePlayers.forEach { release(it) }
    }

    /**
     * Hides everyone from [viewer] while any of them is held, and the held ones from everyone else.
     *
     * A verification screen with the server's population moving behind it is both a distraction and
     * a small information leak: someone who has not proven an account should not be able to read the
     * player list or watch who walks past. Both directions are hidden because the pair is what the
     * client renders.
     */
    private fun mask(player: Player) {
        plugin.server.onlinePlayers.forEach { other ->
            if (other.uniqueId == player.uniqueId) return@forEach
            player.hidePlayer(plugin, other)
            other.hidePlayer(plugin, player)
        }
    }

    private fun unmask(player: Player) {
        plugin.server.onlinePlayers.forEach { other ->
            if (other.uniqueId == player.uniqueId) return@forEach
            player.showPlayer(plugin, other)
            other.showPlayer(plugin, player)
        }
    }

    /**
     * Applies the mask to a player who joined while others were already held.
     *
     * [mask] runs when someone enters Limbo and covers whoever is online at that moment. A player who
     * joins afterwards never saw that call, so without this they would be the one person in the
     * server able to see a held player.
     */
    fun maskFromJoin(viewer: Player) {
        if (!settings().hidePlayers) return
        held.keys.forEach { uuid ->
            plugin.server.getPlayer(uuid)?.let { heldPlayer ->
                if (heldPlayer.uniqueId != viewer.uniqueId) viewer.hidePlayer(plugin, heldPlayer)
            }
        }
    }

    private fun startTitleFlash(player: Player): BukkitTask =
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val playerRef = plugin.server.getPlayer(player.uniqueId) ?: return@Runnable
            val locale = playerRef.locale().toString()
            plugin.messenger.showTitle(
                playerRef,
                messages.parsed("limbo.title", locale),
                messages.parsed("limbo.subtitle", locale),
            )
        }, 0L, 60L)
}
