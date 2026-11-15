package moe.ryuu.kyokouauth.bukkit.listener

import moe.ryuu.kyokouauth.bukkit.KyokouAuthPlugin
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.player.PlayerToggleSprintEvent

/**
 * Everything a frozen player must not be able to do.
 *
 * This is the largest surface in the project and it is intentionally exhaustive rather than
 * selective: a single unguarded event is a way to act on the world without having proven an
 * identity. Each handler is a cancel and nothing else - there is no state here to get wrong.
 */
class LimboListener(private val plugin: KyokouAuthPlugin) : Listener {

    private fun held(player: Player): Boolean = plugin.limbo.isHeld(player.uniqueId)

    private fun held(player: Player, event: org.bukkit.event.Cancellable) {
        if (held(player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onMove(event: PlayerMoveEvent) {
        if (!held(event.player)) return
        // Rotating the head is fine: it costs nothing and stops the view from feeling broken.
        val from = event.from
        val to = event.to
        if (from.x != to.x || from.y != to.y || from.z != to.z) {
            event.to = from.clone().apply {
                yaw = to.yaw
                pitch = to.pitch
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (event.cause == PlayerTeleportEvent.TeleportCause.PLUGIN) return
        held(event.player, event)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPortal(event: PlayerPortalEvent) = held(event.player, event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onSneak(event: PlayerToggleSneakEvent) = held(event.player, event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onSprint(event: PlayerToggleSprintEvent) = held(event.player, event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onFlight(event: PlayerToggleFlightEvent) = held(event.player, event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (!held(event.player)) return
        // An empty-hand right click on air is the client's "I opened a menu" - harmless, let it pass
        // so that the inventory key does not appear broken. Everything else is blocked.
        if (event.action == Action.RIGHT_CLICK_AIR && event.item?.type == Material.AIR) return
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteractEntity(event: PlayerInteractEntityEvent) = held(event.player, event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBreak(event: BlockBreakEvent) = held(event.player, event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlace(event: BlockPlaceEvent) = held(event.player, event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) = held(event.player, event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBucketFill(event: PlayerBucketFillEvent) = held(event.player, event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrop(event: PlayerDropItemEvent) = held(event.player, event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        if (held(event.player as Player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (held(event.whoClicked as Player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        held(player, event)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDamageByEntity(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        held(player, event)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onMobTarget(event: EntityTargetLivingEntityEvent) {
        if (event.target is Player && held(event.target as Player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onHunger(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        held(player, event)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBed(event: PlayerBedEnterEvent) = held(event.player, event)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        if (!held(player)) return

        val root = event.message.removePrefix("/").substringBefore(' ').lowercase()
        if (root in ALLOWED_COMMANDS) {
            // The only thing a frozen player may run is the status command, so they can re-read the
            // link without waiting for the title to flash again.
            return
        }
        event.isCancelled = true
        plugin.tell(player, "limbo.command-blocked")
    }

    private companion object {
        val ALLOWED_COMMANDS = setOf("kyokouauth", "ka", "auth")
    }
}

/**
 * Chat is handled separately because the event class differs by server.
 *
 * Modern Paper fires [io.papermc.paper.event.player.AsyncChatEvent]; everything older fires
 * [org.bukkit.event.player.AsyncPlayerChatEvent]. Both live in their own class so that a server
 * missing one of them can still register the other - a single class referencing both could not be
 * loaded on either.
 */
class LimboChatListener(private val plugin: KyokouAuthPlugin) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onChat(event: io.papermc.paper.event.player.AsyncChatEvent) {
        if (plugin.limbo.isHeld(event.player.uniqueId)) event.isCancelled = true
    }
}

/** The chat listener for server versions that predate Paper's chat event. */
class LimboLegacyChatListener(private val plugin: KyokouAuthPlugin) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onChat(event: org.bukkit.event.player.AsyncPlayerChatEvent) {
        if (plugin.limbo.isHeld(event.player.uniqueId)) event.isCancelled = true
    }
}

/**
 * The restrictions that only exist on 1.9 and later.
 *
 * `PlayerSwapHandItemsEvent` and item pickup events arrived with the off-hand slot; referencing them
 * from the main listener would make that whole class unloadable on 1.8, taking every other
 * restriction with it.
 */
class LimboModernListener(private val plugin: KyokouAuthPlugin) : Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        if (plugin.limbo.isHeld(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (plugin.limbo.isHeld(player.uniqueId)) event.isCancelled = true
    }
}
