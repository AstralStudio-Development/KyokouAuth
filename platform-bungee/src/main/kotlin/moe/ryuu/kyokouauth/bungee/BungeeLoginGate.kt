package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.proxy.DisconnectEvent
import moe.ryuu.kyokouauth.proxy.GameProfileRequestEvent
import moe.ryuu.kyokouauth.proxy.KickedFromServerEvent
import moe.ryuu.kyokouauth.proxy.LoginGate
import moe.ryuu.kyokouauth.proxy.PlayerChooseInitialServerEvent
import moe.ryuu.kyokouauth.proxy.ProxyLog
import moe.ryuu.kyokouauth.proxy.ServerPreConnectEvent
import moe.ryuu.kyokouauth.proxy.PreLoginEvent as CommonPreLogin
import moe.ryuu.kyokouauth.proxy.LoginEvent as CommonLogin
import net.md_5.bungee.api.event.*
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

/** AsyncEvent intent is registered synchronously and completed exactly once on every terminal path. */
internal class BungeeIntent(private val complete: () -> Unit) {
    private var done = false
    private var timer: ScheduledFuture<*>? = null
    @Synchronized fun timer(task: ScheduledFuture<*>) { if (done) task.cancel(false) else timer = task }
    @Synchronized fun finish(action: () -> Unit) {
        if (done) return
        done = true
        timer?.cancel(false)
        try { action() } finally { complete() }
    }
}

/** Native event translation only; all identity/device-code decisions remain in the shared Gate. */
internal class BungeeLoginGate(
    private val plugin: Plugin,
    private val platform: BungeePlatform,
    private val profiles: ProfileAccess,
    private val tickets: BungeeTickets,
    private val gate: LoginGate,
    private val logger: ProxyLog,
    private val submit: (() -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> ScheduledFuture<*>,
    private val accepting: () -> Boolean,
    private val intentTimeoutMillis: Long = 35_000L,
) : Listener {
    private data class Work(val player: BungeePlayer, val cancel: () -> Unit)
    private val intents = ConcurrentHashMap<BungeeIntent, Work>()

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPreLogin(event: PreLoginEvent) {
        if (!accepting() || event.isCancelled || platform.find(event.connection) != null) {
            event.isCancelled = true
            event.reason = unavailable().toBungee()
            return
        }
        val player = try { platform.pending(event.connection) } catch (failure: Exception) {
            event.isCancelled = true
            event.reason = unavailable().toBungee()
            return
        }
        event.registerIntent(plugin)
        val common = CommonPreLogin(player, event.connection.name, null)
        work(player, { event.completeIntent(plugin) }, { event.isCancelled = true; event.reason = unavailable().toBungee() }, {
            gate.onPreLogin(common)
        }) {
            if (!common.result.isAllowed || !accepting() || !player.isActive || event.isCancelled) {
                event.isCancelled = true
                event.reason = (common.result.reason ?: unavailable()).toBungee()
                abandon(player)
            } else {
                val online = checkNotNull(common.result.onlineMode) { "Gate did not choose an authentication mode" }
                player.requestedOnline = online
                event.connection.isOnlineMode = online
                if (!online) {
                    val profile = GameProfileRequestEvent(player, profiles.read(event.connection))
                    gate.onGameProfile(profile)
                    // Official setters are legal only while this PreLogin intent holds USERNAME.
                    event.connection.uniqueId = profile.gameProfile.id
                    profiles.apply(event.connection, profile.gameProfile)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onLogin(event: LoginEvent) {
        val player = platform.find(event.connection)
        if (!accepting() || player == null || player.requestedOnline == null || player.loggedIn) {
            event.isCancelled = true
            event.reason = unavailable().toBungee()
            player?.let(::abandon)
            return
        }
        event.registerIntent(plugin)
        val common = CommonLogin(player)
        if (event.isCancelled) common.result = moe.ryuu.kyokouauth.proxy.ResultedEvent.ComponentResult.denied(unavailable())
        work(player, { event.completeIntent(plugin) }, { event.isCancelled = true; event.reason = unavailable().toBungee() }, {
            check(player.requestedOnline == event.connection.isOnlineMode) { "Native authentication mode changed" }
            // Online LoginEvent is reached only after this physical connection's session handshake.
            // Its profile must match the authenticated native UUID/name, then Gate checks expected UUID
            // and atomically accepts the premium binding. Never set a UUID here or fall back offline.
            val profile = profiles.read(event.connection)
            check(profile.id == player.uniqueId && profile.name == player.username) { "Native login profile mismatch" }
            if (player.requestedOnline == true) {
                val request = GameProfileRequestEvent(player, profile)
                gate.onGameProfile(request)
                profiles.apply(event.connection, request.gameProfile)
            }
            gate.onLogin(common)
        }) {
            if (!common.result.isAllowed || !accepting() || !player.isActive || event.isCancelled) {
                event.isCancelled = true
                event.reason = (common.result.reason ?: unavailable()).toBungee()
                abandon(player)
            } else player.loggedIn = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPostLogin(event: PostLoginEvent) {
        val player = platform.find(event.player.pendingConnection)
        try {
            check(accepting() && player != null && player.loggedIn)
            player.bind(event.player)
            val common = PlayerChooseInitialServerEvent(player)
            gate.onChooseInitialServer(common)
            // Null from Gate means authenticated: keep the proxy's native forced/default target.
            common.initialServer?.let { event.target = checkNotNull(platform.nativeServer(it)) }
            check(player.isActive) { "Initial admission was refused" }
        } catch (failure: Exception) {
            player?.let(::abandon)
            event.player.disconnect(unavailable().toBungee())
            logger.warn("Refused incomplete Bungee PostLogin", failure)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onServerConnect(event: ServerConnectEvent) {
        val player = platform.find(event.player)
        try {
            check(accepting() && player != null && player.loggedIn && player.isActive && !event.isCancelled)
            val target = checkNotNull(platform.commonServer(event.target)) { "Unregistered/replaced target" }
            val common = ServerPreConnectEvent(player, target)
            gate.onPreConnect(common)
            check(common.result.server.orElse(null) === target) { "Gate refused backend/fallback" }
            // Covers JOIN_PROXY, LOBBY_FALLBACK, /server and every plugin reason. No recursive redirect.
            tickets.prepare(player, target)
        } catch (failure: Exception) {
            event.isCancelled = true
            player?.let(::abandon)
            event.player.disconnect(unavailable().toBungee())
            logger.warn("Refused unsafe Bungee server connection (reason={})", event.reason, failure)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onKick(event: ServerKickEvent) {
        val player = platform.find(event.player)
        if (player == null || !accepting()) {
            event.isCancelled = false
            event.reason = unavailable().toBungee()
            event.player.disconnect(event.reason)
            return
        }
        val common = KickedFromServerEvent(player)
        gate.onKicked(common)
        common.result?.let { result ->
            event.isCancelled = false
            event.reason = result.reason.toBungee()
            abandon(player)
            event.player.disconnect(event.reason)
        }
    }

    @EventHandler
    fun onDisconnect(event: PlayerDisconnectEvent) { platform.find(event.player)?.let(::abandon) }

    /** Also observes half-logins, which never receive PlayerDisconnectEvent. */
    fun sweepClosed() { platform.connections().filterNot { it.isActive }.forEach(::abandon) }

    fun close() {
        intents.keys.toList().forEach { intent -> intent.finish { intents[intent]?.let { it.cancel(); abandon(it.player) } } }
        platform.connections().forEach { player -> player.disconnect(unavailable()); abandon(player) }
    }

    private fun abandon(player: BungeePlayer) {
        player.markClosed()
        tickets.revoke(player)
        gate.onDisconnect(DisconnectEvent(player))
        platform.remove(player)
        intents.entries.filter { it.value.player === player }.forEach { (intent, work) -> intent.finish { work.cancel() } }
    }

    private fun work(
        player: BungeePlayer, complete: () -> Unit, cancel: () -> Unit, task: () -> Unit, apply: () -> Unit,
    ) {
        lateinit var intent: BungeeIntent
        intent = BungeeIntent { intents.remove(intent); complete() }
        intents[intent] = Work(player, cancel)
        try {
            intent.timer(schedule(intentTimeoutMillis) {
                intent.finish { cancel(); player.disconnect(unavailable()); abandon(player) }
            })
            submit {
                try {
                    task()
                    intent.finish {
                        try { apply() } catch (failure: Exception) {
                            cancel(); player.disconnect(unavailable()); abandon(player)
                            logger.warn("Bungee admission/profile application failed", failure)
                        }
                    }
                } catch (failure: Exception) {
                    intent.finish { cancel(); player.disconnect(unavailable()); abandon(player) }
                    logger.warn("Bungee admission task failed", failure)
                }
            }
        } catch (failure: Exception) {
            intent.finish { cancel(); player.disconnect(unavailable()); abandon(player) }
            logger.warn("Bungee admission scheduling failed", failure)
        }
    }
}
