package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.ForwardingMode
import moe.ryuu.kyokouauth.limbo.LimboLogger
import moe.ryuu.kyokouauth.limbo.LimboServer
import moe.ryuu.kyokouauth.limbo.PrivateTicketAuthority
import moe.ryuu.kyokouauth.proxy.AuthService
import moe.ryuu.kyokouauth.proxy.GameProfile
import moe.ryuu.kyokouauth.proxy.KyokouAuthCommand
import moe.ryuu.kyokouauth.proxy.LoginGate
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.proxy.ProxyLog
import moe.ryuu.kyokouauth.proxy.RoleSkinService
import net.md_5.bungee.api.event.LoginEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.PreLoginEvent
import net.md_5.bungee.api.event.ServerConnectEvent
import net.md_5.bungee.api.event.ServerKickEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.scheduler.ScheduledTask
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/** Native lifecycle only. A permanent refusal listener survives every partial startup failure. */
class KyokouAuthBungee : Plugin(), Listener {
    @Volatile private var accepting = false
    private var platform: BungeePlatform? = null
    private var auth: AuthService? = null
    private var gate: LoginGate? = null
    private var adapter: BungeeLoginGate? = null
    private var floodgate: BungeeFloodgate? = null
    private var commandGuard: BungeeCommandGuard? = null
    private var authority: PrivateTicketAuthority? = null
    private var limbo: LimboServer? = null
    private var sweep: ScheduledTask? = null
    private var roleTransfer: BungeeRoleTransfer? = null
    private var roleSkinSource: BungeeRoleSkins? = null
    private var roleSkins: RoleSkinService? = null

    override fun onEnable() {
        accepting = false
        proxy.pluginManager.registerListener(this, this)
        val log = ProxyLog { severity, message, failure ->
            logger.log(when (severity) {
                ProxyLog.Level.INFO -> Level.INFO
                ProxyLog.Level.WARN -> Level.WARNING
                ProxyLog.Level.ERROR -> Level.SEVERE
            }, message, failure)
        }
        try {
            check(proxy.config.isIpForward) { "KyokouAuth-Bungee requires ip_forward: true" }
            check(proxy.getServerInfo(LoginGate.LIMBO_SERVER_NAME) == null) { "Reserved internal server name is already registered" }
            val config = ProxyConfig.load(dataFolder)
            val floodgate = BungeeFloodgate(config.bedrockEnabled, { proxy.pluginManager.getPlugin("floodgate") }, config.rolesEnabled)
                .also { this.floodgate = it }
            val messages = MessageCatalogue(config.locale, File(dataFolder, "messages")).also { it.load() }
            // No role-specific native signature/field access is initialized by default Java-only installs.
            val transfer = if (config.rolesEnabled) BungeeRoleTransfer().also { roleTransfer = it } else null
            val roleBridge = if (config.rolesEnabled) BungeeRoleLoginBridge(javaClass.classLoader) else null
            val platform = BungeePlatform(proxy, transfer).also { this.platform = it }
            val profiles = BungeeProfileBridge(javaClass.classLoader, bedrock = { pending ->
                platform.find(pending)?.currentBedrockProof()
            }, role = { pending -> platform.find(pending)?.currentRoleProfile() })
            val authority = PrivateTicketAuthority().also { this.authority = it }
            val skinSource = if (config.rolesEnabled) BungeeRoleSkins {
                proxy.pluginManager.getPlugin("SkinsRestorer") ?: proxy.pluginManager.getPlugin("skinsrestorer")
            }.also { roleSkinSource = it } else null
            val skins = RoleSkinService { skinSource?.provider() }.also { roleSkins = it }
            val auth = AuthService(config, log, skins).also { this.auth = it }
            val gate = LoginGate(platform, config, messages, auth, log).also { this.gate = it }
            val adapter = BungeeLoginGate(this, platform, profiles, BungeeTickets(platform, profiles, authority), gate, log,
                submit = auth::execute, schedule = auth::schedule, accepting = { accepting },
                intentTimeoutMillis = (config.sessionHandshakeTimeoutSeconds + 5L) * 1000L,
                bedrock = floodgate::probe,
                rolesEnabled = config.rolesEnabled, roleTransfer = transfer,
                prepareRole = roleBridge?.let { bridge -> { player: BungeePlayer, profile: GameProfile ->
                    val proof = player.currentBedrockProof()
                    bridge.prepare(player.pending, profile.id, profile.name, proof)
                    player.rememberRoleProfile(profile)
                    profiles.prepareRole(player.pending, profile)
                } },
            ).also { this.adapter = it }
            proxy.pluginManager.registerListener(this, adapter)
            val commands = BungeeCommandGuard(platform, gate, config.blockedCommands.isNotEmpty()).also {
                it.enablePacketFilter(log)
                this.commandGuard = it
            }
            proxy.pluginManager.registerListener(this, commands)
            val limbo = LimboServer(settings = config.limbo, listener = gate,
                logger = LimboLogger { message, failure -> if (failure == null) log.info(message) else log.warn(message, failure) },
                forwardingMode = ForwardingMode.PrivateTicket(authority),
            ).also { this.limbo = it }
            val endpoints = limbo.startAll()
            check(endpoints.keys == (767..777).toSet()) { "Incomplete private engine directory: ${endpoints.keys}" }
            endpoints.toSortedMap().forEach { (protocol, address) -> platform.registerEngine(protocol, address) }
            val command = KyokouAuthCommand(platform, config, messages, auth, gate) { messages.load() }
            proxy.pluginManager.registerCommand(this, BungeeCommand(platform, command))
            sweep = proxy.scheduler.schedule(this, { adapter.sweepClosed() }, 1, 1, TimeUnit.SECONDS)
            gate.activate()
            accepting = true
            log.info("KyokouAuth-Bungee accepting connections; private engines={}", endpoints.keys.sorted())
        } catch (failure: Exception) {
            log.error("Could not initialize KyokouAuth-Bungee; all logins remain refused", failure)
            closeServices()
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun refusePreLogin(event: PreLoginEvent) {
        if (!accepting && !event.isCancelled) { event.isCancelled = true; event.reason = unavailable().toBungee() }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun refuseLogin(event: LoginEvent) {
        if (!accepting && !event.isCancelled) { event.isCancelled = true; event.reason = unavailable().toBungee() }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun refusePostLogin(event: PostLoginEvent) { if (!accepting) event.player.disconnect(unavailable().toBungee()) }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun refuseConnect(event: ServerConnectEvent) {
        if (!accepting) { event.isCancelled = true; event.player.disconnect(unavailable().toBungee()) }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun refuseKick(event: ServerKickEvent) {
        if (!accepting) { event.isCancelled = false; event.reason = unavailable().toBungee(); event.player.disconnect(event.reason) }
    }

    override fun onDisable() { closeServices() }

    private fun closeServices() {
        accepting = false
        fun release(name: String, action: () -> Unit) {
            runCatching(action).onFailure { logger.log(Level.WARNING, "Could not close $name", it) }
        }
        release("connection watcher") { sweep?.cancel() }; sweep = null
        release("Bungee admission") { adapter?.close() }; adapter = null
        release("native role transfer") { roleTransfer?.close() }; roleTransfer = null
        release("role skin provider generation") { roleSkinSource?.close() }; roleSkinSource = null
        release("Floodgate proof source") { floodgate?.close() }; floodgate = null
        release("native command boundary") { commandGuard?.close() }; commandGuard = null
        release("shared authentication gate") { gate?.deactivate() }; gate = null
        release("private login engines") { limbo?.close() }; limbo = null
        release("internal server registrations") { platform?.unregisterEngines() }; platform = null
        release("private tickets") { authority?.close() }; authority = null
        release("authentication storage") { auth?.close() }; auth = null
        release("role skins") { roleSkins?.close() }; roleSkins = null
    }
}
