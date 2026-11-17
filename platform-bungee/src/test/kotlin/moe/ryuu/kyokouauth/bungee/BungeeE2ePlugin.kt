package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.*
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import moe.ryuu.kyokouauth.proxy.LoginGate
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.proxy.ProxyLog
import net.md_5.bungee.api.event.PreLoginEvent
import net.md_5.bungee.api.event.ServerConnectEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import java.io.File
import java.net.InetSocketAddress
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Test-only alternative bootstrap. Never packaged in the production plugin or enabled by config. */
class BungeeE2ePlugin : Plugin(), Listener {
    private val scenario = System.getProperty("kyokou.e2e.scenario", "held")
    private val stable = UUID(0L, 2535412345678901L)
    private val trusted = AtomicBoolean(false)
    private val io = Executors.newFixedThreadPool(2)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var accepting = false
    private lateinit var platform: BungeePlatform
    private lateinit var gate: LoginGate
    private lateinit var adapter: BungeeLoginGate
    private var commandGuard: BungeeCommandGuard? = null
    private var commandProbe: BungeeE2eCommandProbe? = null
    private lateinit var authority: PrivateTicketAuthority
    private var limbo: LimboServer? = null
    private val handles = IdentityHashMap<LimboPlayer, LimboPlayer>()

    override fun onEnable() {
        proxy.pluginManager.registerListener(this, this)
        try {
            check(proxy.config.isIpForward)
            platform = BungeePlatform(proxy)
            authority = PrivateTicketAuthority()
            val profiles = BungeeProfileBridge(javaClass.classLoader)
            val config = ProxyConfig(mapOf("post-login.server" to "backend", "limbo.keep-alive-seconds" to "2",
                "limbo.keep-alive-timeout-seconds" to "10", "limbo.join-timeout-seconds" to "5",
                "verification.poll-interval-seconds" to "1", "verification.blocked-commands" to
                    if (scenario == "commands") "server\n${BungeeE2eCommandProbe.PROBE}" else "server"), dataFolder)
            val messages = MessageCatalogue("en_US", File(dataFolder, "messages")).also { it.load() }
            val log = ProxyLog { severity, message, error ->
                logger.log(if (severity == ProxyLog.Level.INFO) java.util.logging.Level.INFO else java.util.logging.Level.WARNING, message, error)
            }
            val services = object : LoginGate.Services {
                override fun allow(key: String) = true
                override fun decide(name: GameName, address: String): AuthCoordinator.Decision {
                    val identity = Identity.Microsoft(name, Xuid(stable.leastSignificantBits), "Controlled profile")
                    val record = BoundIdentity.of(identity, 1L)
                    if (scenario == "trusted" || trusted.get()) return AuthCoordinator.Decision.Admit(identity, record)
                    return AuthCoordinator.Decision.Verify(if (scenario == "first-bind") AuthCoordinator.provisionalUuid(name) else stable,
                        if (scenario == "first-bind") AuthCoordinator.Reason.FIRST_BIND else AuthCoordinator.Reason.SESSION_EXPIRED,
                        scenario == "first-bind", if (scenario == "first-bind") null else record)
                }
                override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome = error("No external premium test session")
                override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome =
                    AuthCoordinator.ClaimOutcome.Bound(BoundIdentity.of(Identity.Microsoft(name, xuid, gamertag), 1L))
                override fun grantSession(uuid: UUID, address: String) {
                    check(uuid == stable); trusted.set(true); logger.info("BUNGEE_E2E_SESSION $uuid")
                }
                override fun submit(task: () -> Unit) { io.execute(task) }
                override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*> =
                    scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
                override fun begin() = DeviceCodeFlow.Challenge("E2E-CODE", "https://microsoft.com/link",
                    "https://microsoft.com/link?otc=E2E-CODE", 300, 1, "test-only-no-network")
                override fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult =
                    if (scenario in setOf("stable", "first-bind") ||
                        (scenario == "commands" && commandProbe?.mayAuthorize() == true)) DeviceCodeFlow.PollResult.Authorized("synthetic", null, 300)
                    else DeviceCodeFlow.PollResult.Pending
                override fun exchange(accessToken: String) = XboxLive.XstsToken("synthetic", stable.leastSignificantBits, "Controlled profile", "synthetic")
            }
            gate = LoginGate(platform, config, messages, services, log)
            adapter = BungeeLoginGate(this, platform, profiles, BungeeTickets(platform, profiles, authority), gate,
                log, services::submit, services::schedule, { accepting })
            proxy.pluginManager.registerListener(this, adapter)
            commandGuard = BungeeCommandGuard(platform, gate, config.blockedCommands.isNotEmpty()).also {
                it.enablePacketFilter(log)
                proxy.pluginManager.registerListener(this, it)
            }
            if (scenario == "commands") commandProbe = BungeeE2eCommandProbe(this, platform, gate, scheduler)
            val listener = object : LimboListener {
                override fun onJoin(player: LimboPlayer) {
                    val recording = object : LimboPlayer by player {
                        override fun showTitle(title: TextComponent, subtitle: TextComponent?, fadeIn: Int, stay: Int, fadeOut: Int) {
                            logger.info("BUNGEE_E2E_TITLE ${player.name} stay=$stay")
                            player.showTitle(title, subtitle, fadeIn, stay, fadeOut)
                        }
                        override fun clearTitle() { logger.info("BUNGEE_E2E_CLEAR ${player.name}"); player.clearTitle() }
                    }
                    synchronized(handles) { handles[player] = recording }
                    gate.onJoin(recording)
                    logger.info("BUNGEE_E2E_READY ${player.name} ${player.uuid}")
                    if (scenario == "commands") {
                        val frontend = platform.connections().single { it.sessionId == player.connectionId && it.isActive }
                        commandProbe?.ready(frontend)
                    }
                    if (scenario == "server") scheduler.schedule({
                        val frontend = checkNotNull(platform.connections().single { it.sessionId == player.connectionId && it.isActive }.native)
                        frontend.setPermission("bungeecord.command.server", true)
                        logger.info("BUNGEE_E2E_UNAUTHORIZED_SERVER_ATTEMPT ${player.name}")
                        val attempt = proxy.pluginManager.callEvent(net.md_5.bungee.api.event.ChatEvent(frontend, frontend.server, "/server backend"))
                        if (!attempt.isCancelled) proxy.pluginManager.dispatchCommand(frontend, "server backend")
                        check(attempt.isCancelled) { "Native command entry was not denied" }
                        logger.info("BUNGEE_E2E_COMMAND_ENTRY_BLOCKED server=true")
                        frontend.disconnect(unavailable().toBungee())
                    }, 1, TimeUnit.SECONDS)
                }
                override fun onLeave(player: LimboPlayer) {
                    synchronized(handles) { handles.remove(player) }?.let(gate::onLeave)
                    logger.info("BUNGEE_E2E_LEFT ${player.name}")
                }
            }
            limbo = LimboServer(settings = config.limbo, listener = listener,
                logger = LimboLogger { message, error -> logger.log(java.util.logging.Level.INFO, message, error) },
                forwardingMode = ForwardingMode.PrivateTicket(authority))
            val overridePort = System.getProperty("kyokou.e2e.enginePort")?.toInt()
            limbo!!.startAll().forEach { (protocol, address) ->
                platform.registerEngine(protocol, if (protocol == 777 && overridePort != null) InetSocketAddress("127.0.0.1", overridePort) else address)
            }
            for (protocol in 767..777) {
                val name = "kyokouauth-limbo-$protocol"
                val common = platform.getServer(name).orElseThrow()
                val native = checkNotNull(platform.nativeServer(common))
                check(proxy.getServerInfo(name) == null && proxy.servers.keys.none { it.equals(name, ignoreCase = true) }) {
                    "Private engine leaked into the public server directory: $name"
                }
                check(proxy.servers.values.none { it === native } && platform.commonServer(native) === common) {
                    "Private engine identity or directory isolation changed: $name"
                }
            }
            logger.info("BUNGEE_E2E_PRIVATE_DIRECTORY public=0 internal=11")
            scheduler.scheduleAtFixedRate({ adapter.sweepClosed() }, 1, 1, TimeUnit.SECONDS)
            gate.activate()
            accepting = true
            logger.info("BUNGEE_E2E_STARTED $scenario")
        } catch (failure: Exception) { logger.log(java.util.logging.Level.SEVERE, "BUNGEE_E2E_START_FAILED", failure) }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun refuse(event: PreLoginEvent) { if (!accepting) { event.isCancelled = true; event.reason = unavailable().toBungee() } }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun refuse(event: ServerConnectEvent) { if (!accepting) { event.isCancelled = true; event.player.disconnect(unavailable().toBungee()) } }
    override fun onDisable() {
        accepting = false
        commandProbe?.close(); commandProbe = null
        if (::adapter.isInitialized) adapter.close()
        commandGuard?.close(); commandGuard = null
        if (::gate.isInitialized) gate.deactivate()
        limbo?.close()
        if (::platform.isInitialized) platform.unregisterEngines()
        if (::authority.isInitialized) authority.close()
        io.shutdownNow(); scheduler.shutdownNow()
    }
}
