package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.proxy.BedrockLogin
import moe.ryuu.kyokouauth.proxy.DisconnectEvent
import moe.ryuu.kyokouauth.proxy.GameProfileRequestEvent
import moe.ryuu.kyokouauth.proxy.GameProfile
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
    private val bedrock: (BungeePlayer) -> BedrockLogin = { BedrockLogin.Java },
    private val rolesEnabled: Boolean = false,
    private val roleTransfer: BungeeRoleTransfer? = null,
    private val prepareRole: ((BungeePlayer, GameProfile) -> Unit)? = null,
) : Listener {
    private data class Work(val player: BungeePlayer, val cancel: () -> Unit)
    private val intents = ConcurrentHashMap<BungeeIntent, Work>()
    @Volatile private var closed = false
    private fun isAccepting() = !closed && accepting()

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPreLogin(event: PreLoginEvent) {
        if (event.isCancelled) return
        if (!isAccepting() || platform.find(event.connection) != null) {
            event.isCancelled = true
            event.reason = unavailable().toBungee()
            return
        }
        val player = try { platform.pending(event.connection) } catch (failure: Exception) {
            event.isCancelled = true
            event.reason = unavailable().toBungee()
            return
        }
        // Floodgate LOWEST has already processed kick/offline/UUID/name. Capture this exact
        // Channel proof synchronously, before transferring work off the native event thread.
        val source = try { bedrock(player) } catch (failure: Exception) {
            event.isCancelled = true
            event.reason = unavailable().toBungee()
            abandon(player)
            logger.warn("Bungee native proof read failed", failure)
            return
        }
        event.registerIntent(plugin)
        val loginName = event.connection.name
        var common = CommonPreLogin(player, loginName, null, source)
        work(player, { event.completeIntent(plugin) }, {
            if (!event.isCancelled) { event.isCancelled = true; event.reason = unavailable().toBungee() }
        }, {
            if (!event.isCancelled) {
                // Only Java uses a cookie. FG resumption must obtain a fresh exact-channel proof;
                // its server-side choice hint is never a cookie or an authorization credential.
                val cookie = if (rolesEnabled && source is BedrockLogin.Java)
                    checkNotNull(roleTransfer) { "Native role cookie bridge unavailable" }.retrieve(event.connection).join() else null
                check(isAccepting() && player.isActive && !event.isCancelled) { "Prelogin closed while retrieving role cookie" }
                common = CommonPreLogin(player, loginName, null, source, cookie)
                gate.onPreLogin(common)
            }
        }) {
            if (!common.result.isAllowed || !isAccepting() || !player.isActive || event.isCancelled) {
                if (!event.isCancelled) {
                    val reason = common.result.reason ?: unavailable()
                    event.isCancelled = true
                    event.reason = reason.toBungee()
                    player.disconnect(reason)
                }
                abandon(player)
            } else {
                val online = checkNotNull(common.result.onlineMode) { "Gate did not choose an authentication mode" }
                player.requestedOnline = online
                if (source is BedrockLogin.Java) event.connection.isOnlineMode = online
                else check(!online && !event.connection.isOnlineMode) { "Floodgate native authentication mode changed" }
                if (source is BedrockLogin.Verified) player.rememberBedrock(source.proof)
                if (!online) {
                    val original = profiles.read(event.connection)
                    val fresh = bedrock(player)
                    var profile = GameProfileRequestEvent(player, original, fresh)
                    val role = gate.requiresRoleProfile(player)
                    gate.onPrepareGameProfile(profile)
                    check(player.isActive && !event.isCancelled) { "Prepared profile admission was refused" }
                    if (role) {
                        check(rolesEnabled && prepareRole != null) { "Native role preparation unavailable" }
                        prepareRole.invoke(player, profile.gameProfile)
                        check(player.hasRoleProfile && player.currentRoleProfile() != null) { "Role profile was not bound to this connection" }
                        profile = GameProfileRequestEvent(player, profiles.read(event.connection), bedrock(player))
                    }
                    // LAST semantic check: the actual newly built container, not a proposed UUID.
                    gate.onGameProfile(profile)
                    check(player.isActive && !event.isCancelled) { "Profile admission was refused" }
                    if (role) {
                        check(event.connection.uniqueId == profile.gameProfile.id && event.connection.name == profile.gameProfile.name)
                        profiles.apply(event.connection, profile.gameProfile)
                        player.rememberRoleProfile(profile.gameProfile)
                    } else if (source is BedrockLogin.Java && fresh is BedrockLogin.Java) {
                        // Official setters are legal only while this PreLogin intent holds USERNAME.
                        event.connection.uniqueId = profile.gameProfile.id
                        profiles.apply(event.connection, profile.gameProfile)
                    } else {
                        // Feature-off FG identity stays read-only, including its absent container.
                        check(profile.gameProfile == original && event.connection.uniqueId == original.id &&
                            event.connection.name == original.name) { "Floodgate native profile changed" }
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onLogin(event: LoginEvent) {
        val player = platform.find(event.connection)
        if (event.isCancelled) { player?.let(::abandon); return }
        if (!isAccepting() || player == null || player.requestedOnline == null || player.loggedIn) {
            event.isCancelled = true
            event.reason = unavailable().toBungee()
            player?.let(::abandon)
            return
        }
        val source = try { bedrock(player) } catch (failure: Exception) {
            event.isCancelled = true
            event.reason = unavailable().toBungee()
            abandon(player)
            logger.warn("Bungee native login proof read failed", failure)
            return
        }
        event.registerIntent(plugin)
        val common = CommonLogin(player, source)
        work(player, { event.completeIntent(plugin) }, {
            if (!event.isCancelled) { event.isCancelled = true; event.reason = unavailable().toBungee() }
        }, {
            if (event.isCancelled) {
                common.result = moe.ryuu.kyokouauth.proxy.ResultedEvent.ComponentResult.denied(unavailable())
            } else {
                check(player.requestedOnline == event.connection.isOnlineMode) { "Native authentication mode changed" }
                // LoginEvent is already FINISHING: recheck actual identity and role textures here,
                // but never set UUID/name or repair a mismatch with an offline fallback.
                val profile = profiles.read(event.connection)
                check(profile.id == player.uniqueId && profile.name == player.username) { "Native login profile mismatch" }
                if (player.requestedOnline == true || player.hasRoleProfile) {
                    if (player.hasRoleProfile) checkNotNull(player.currentRoleProfile()) { "Role identity or source proof changed before Login" }
                    val request = GameProfileRequestEvent(player, profile, bedrock(player))
                    gate.onGameProfile(request)
                    check(player.isActive && !event.isCancelled && request.gameProfile.id == player.uniqueId &&
                        request.gameProfile.name == player.username) { "Final native profile admission was refused" }
                    profiles.apply(event.connection, request.gameProfile)
                    if (player.hasRoleProfile) player.rememberRoleProfile(request.gameProfile)
                }
            }
            gate.onLogin(common)
        }) {
            if (!common.result.isAllowed || !isAccepting() || !player.isActive || event.isCancelled) {
                if (!event.isCancelled) {
                    val reason = common.result.reason ?: unavailable()
                    event.isCancelled = true
                    event.reason = reason.toBungee()
                    player.disconnect(reason)
                }
                abandon(player)
            } else player.loggedIn = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPostLogin(event: PostLoginEvent) {
        val player = platform.find(event.player.pendingConnection)
        try {
            check(isAccepting() && player != null && player.loggedIn)
            player.bind(event.player)
            if (player.hasRoleProfile) enforceRoleProperties(player)
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
            check(isAccepting() && player != null && player.loggedIn && player.isActive && !event.isCancelled)
            val target = checkNotNull(platform.commonServer(event.target)) { "Unregistered/replaced target" }
            val common = ServerPreConnectEvent(player, target)
            gate.onPreConnect(common)
            check(common.result.server.orElse(null) === target) { "Gate refused backend/fallback" }
            // Covers JOIN_PROXY, LOBBY_FALLBACK, /server and every plugin reason. No recursive redirect.
            if (player.hasRoleProfile) enforceRoleProperties(player)
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
        if (player == null || !isAccepting()) {
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
        if (closed) return
        closed = true
        roleTransfer?.close()
        intents.keys.toList().forEach { intent -> intent.finish { intents[intent]?.let { it.cancel(); abandon(it.player) } } }
        platform.connections().forEach { player -> player.disconnect(unavailable()); abandon(player) }
    }

    /** FG/SR PostLogin may replace textures, or create FG's legitimate skin-only container. */
    private fun enforceRoleProperties(player: BungeePlayer) {
        val expected = checkNotNull(player.currentRoleProfile()) { "Prepared role identity or proof is no longer current" }
        val actual = profiles.read(player.pending)
        check(actual.id == expected.id && actual.name == expected.name) { "Native role identity changed after Login" }
        val managed = setOf("textures", "kyokouauth:connection-id")
        val retained = actual.properties.filterNot { it.name in managed }
        val retainedNames = retained.mapTo(HashSet()) { it.name }
        val missing = expected.properties.filterNot { it.name in managed || it.name in retainedNames }
        profiles.apply(player.pending, actual.withProperties(missing + retained + expected.properties.filter { it.name in managed }))
    }

    private fun abandon(player: BungeePlayer) {
        roleTransfer?.cancel(player.pending)
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
