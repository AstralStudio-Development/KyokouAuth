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
import moe.ryuu.kyokouauth.proxy.PrivateClaimCommands
import moe.ryuu.kyokouauth.proxy.ProfileClaimFlow
import net.md_5.bungee.api.event.ChatEvent
import net.md_5.bungee.api.event.TabCompleteEvent
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
import java.util.concurrent.atomic.AtomicInteger

/** Test-only alternative bootstrap. Never packaged in the production plugin or enabled by config. */
class BungeeE2ePlugin : Plugin(), Listener {
    private val scenario = System.getProperty("kyokou.e2e.scenario", "held")
    private val stable = UUID(0L, System.getProperty("kyokou.e2e.xuid", "2535412345678901").toLong())
    private val trusted = AtomicBoolean(false)
    private val claimsScenario = scenario == "claim-authme"
    private val historical = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")
    private var privateClaims: BungeeClaimCommands? = null
    private val javaWrites = AtomicInteger()
    private val io = Executors.newFixedThreadPool(2)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var accepting = false
    private val bedrockScenario = scenario.startsWith("bedrock")
    private var floodgate: BungeeFloodgate? = null
    private var bedrockProbe: BungeeE2eBedrockProbe? = null
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
            platform = BungeePlatform(proxy, BungeeViaVersion(lookup = {
                proxy.pluginManager.getPlugin("ViaVersion")
            }))
            authority = PrivateTicketAuthority()
            val profiles = BungeeProfileBridge(javaClass.classLoader, bedrock = { pending ->
                platform.find(pending)?.currentBedrockProof()
            })
            platform.installProfiles(profiles)
            val config = ProxyConfig(mapOf("post-login.server" to "backend",
                "bedrock.enabled" to (bedrockScenario && scenario != "bedrock-disabled").toString(), "limbo.keep-alive-seconds" to "2",
                "limbo.keep-alive-timeout-seconds" to "10", "limbo.join-timeout-seconds" to "5",
                "migration.enabled" to claimsScenario.toString(),
                "verification.poll-interval-seconds" to "1", "verification.blocked-commands" to
                    if (scenario == "commands") "server\n${BungeeE2eCommandProbe.PROBE}" else "server"), dataFolder)
            if (claimsScenario) {
                val languages = File(dataFolder, "messages").apply { mkdirs() }
                File(languages, "en_US.yml").writeText("migration:\n  offer-title: CLAIM_OFFER\n  confirm-old-title: CLAIM_SOURCE\n  confirm-bind-title: CLAIM_BIND\n  claim-rejected: CLAIM_BAD_PROOF\nverification:\n  reconnect: CLAIM_RECONNECT\n")
            }
            val messages = MessageCatalogue("en_US", File(dataFolder, "messages")).also { it.load() }
            val log = ProxyLog { severity, message, error ->
                logger.log(if (severity == ProxyLog.Level.INFO) java.util.logging.Level.INFO else java.util.logging.Level.WARNING, message, error)
            }
            val floodgate = BungeeFloodgate(config.bedrockEnabled, { proxy.pluginManager.getPlugin("floodgate") })
                .also { this.floodgate = it }
            val services = object : LoginGate.Services {
                override fun allow(key: String) = true
                override fun decide(name: GameName, address: String): AuthCoordinator.Decision {
                    javaOnly("decide")
                    if (claimsScenario && !trusted.get()) return AuthCoordinator.Decision.Verify(
                        AuthCoordinator.provisionalUuid(name), AuthCoordinator.Reason.FIRST_BIND, true, null)
                    val identity = Identity.Microsoft(name, Xuid(stable.leastSignificantBits), "Controlled profile", if (claimsScenario) historical else stable)
                    val record = BoundIdentity.of(identity, 1L)
                    if (scenario == "trusted" || trusted.get()) return AuthCoordinator.Decision.Admit(identity, record)
                    return AuthCoordinator.Decision.Verify(if (scenario == "first-bind") AuthCoordinator.provisionalUuid(name) else stable,
                        if (scenario == "first-bind") AuthCoordinator.Reason.FIRST_BIND else AuthCoordinator.Reason.SESSION_EXPIRED,
                        scenario == "first-bind", if (scenario == "first-bind") null else record)
                }
                override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome {
                    javaOnly("bindPremium", write = true)
                    error("No external premium test session")
                }
                override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome {
                    javaOnly("bindMicrosoft", write = true)
                    return AuthCoordinator.ClaimOutcome.Bound(BoundIdentity.of(Identity.Microsoft(name, xuid, gamertag), 1L))
                }
                override fun grantSession(uuid: UUID, address: String) {
                    javaOnly("grantSession", write = true)
                    check(uuid == stable); trusted.set(true); logger.info("BUNGEE_E2E_SESSION $uuid")
                }
                override fun submit(task: () -> Unit) { io.execute(task) }
                override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*> =
                    scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
                override fun begin(): DeviceCodeFlow.Challenge {
                    javaOnly("begin")
                    return DeviceCodeFlow.Challenge("E2E-CODE", "https://microsoft.com/link",
                        "https://microsoft.com/link?otc=E2E-CODE", 300, 1, "test-only-no-network")
                }
                override fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult =
                    if (scenario in setOf("stable", "first-bind", "claim-authme") ||
                        (scenario == "commands" && commandProbe?.mayAuthorize() == true)) DeviceCodeFlow.PollResult.Authorized("synthetic", null, 300)
                    else DeviceCodeFlow.PollResult.Pending
                override fun exchange(accessToken: String) = XboxLive.XstsToken("synthetic", stable.leastSignificantBits,
                    if (claimsScenario) "SyntheticMappedXbox" else "Controlled profile", "synthetic")
                override fun authMeClaimsAvailable() = claimsScenario
                override fun permitsMappedProfile(record: BoundIdentity) = claimsScenario && record.uuid == historical || super.permitsMappedProfile(record)
                override fun proveAuthMeProfile(legacyName: GameName, password: CharArray, session: UUID, name: GameName,
                                                current: () -> Boolean): ProfileClaimFlow.PreparedClaim? = try {
                    check(claimsScenario && current() && legacyName.value == "OldSteve")
                    if (!password.contentEquals("PassWord1".toCharArray())) null else ProfileClaimFlow.PreparedClaim(
                        ProfileClaimFlow.ClaimToken(session.toString()), historical, legacyName, historical, System.currentTimeMillis() + 30_000L)
                } finally { password.fill('\u0000') }
                override fun commitLegacyProfile(token: ProfileClaimFlow.ClaimToken, session: UUID, name: GameName, xuid: Xuid,
                                                 gamertag: String, current: () -> Boolean): BoundIdentity {
                    check(claimsScenario && current() && token.value == session.toString() && trusted.compareAndSet(false, true))
                    return BoundIdentity.of(Identity.Microsoft(name, xuid, gamertag, historical), 1L)
                }
            }
            gate = LoginGate(platform, config, messages, services, log, isolateProvisionalNames = true)
            privateClaims = BungeeClaimCommands(javaClass.classLoader, gate::onPrivateClaim)
            adapter = BungeeLoginGate(this, platform, profiles, BungeeTickets(platform, profiles, authority), gate,
                log, services::submit, services::schedule, { accepting }, privateClaims = privateClaims,
                prepareTemporary = { player, profile -> profiles.prepareVerification(player.pending, profile) }, bedrock = { player ->
                    floodgate.probe(player).also { result ->
                        if (bedrockScenario) {
                            logger.info("BUNGEE_E2E_BEDROCK_SAMPLE result=${result.javaClass.simpleName}")
                            val kind = when (result) {
                                is moe.ryuu.kyokouauth.proxy.BedrockLogin.Rejected -> result.reason.name
                                is moe.ryuu.kyokouauth.proxy.BedrockLogin.Verified -> "VERIFIED"
                                moe.ryuu.kyokouauth.proxy.BedrockLogin.Java -> "JAVA"
                            }
                            logger.info("BUNGEE_E2E_BEDROCK_PROBE kind=$kind")
                        }
                    }
                })
            proxy.pluginManager.registerListener(this, adapter)
            if (bedrockScenario) bedrockProbe = BungeeE2eBedrockProbe(this, floodgate, profiles, scenario,
                authenticated = { native -> gate.isAuthenticated(platform.find(native)) }, javaWrites = javaWrites::get,
            ).also { proxy.pluginManager.registerListener(this, it) }
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
                override fun onRoleSelect(player: LimboPlayer, generation: UUID, optionId: String) {
                    synchronized(handles) { handles[player] }?.let { gate.onRoleSelect(it, generation, optionId) }
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
            platform.installPrivateProtocols()
            proveViaPrivateProtocols()
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

    private fun proveViaPrivateProtocols() {
        val plugin = proxy.pluginManager.getPlugin("ViaVersion") ?: return
        val via = Class.forName("com.viaversion.viaversion.api.Via", true, plugin.javaClass.classLoader)
        val platform = via.getMethod("proxyPlatform").invoke(null)
        fun call(receiver: Any, method: String): Any = receiver.javaClass.getMethod(method).invoke(receiver)
        val detector = call(platform, "protocolDetectorService")
        val config = call(call(platform, "getBungeeConfig"), "getBungeeServerProtocols") as Map<*, *>
        val uncache = detector.javaClass.getMethod("uncacheProtocolVersion", String::class.java)
        val version = detector.javaClass.getMethod("serverProtocolVersion", String::class.java)
        // ViaBungee periodically evicts private names. Correctness must survive without that cache.
        for (protocol in 767..777) {
            val name = "kyokouauth-limbo-$protocol"
            uncache.invoke(detector, name)
            check((call(version.invoke(detector, name), "getVersion") as Number).toInt() == protocol)
            check(config[name] == protocol && proxy.getServerInfo(name) == null)
        }
        logger.info("BUNGEE_E2E_VIA_PRIVATE_PROTOCOLS private=11 cache=evicted default=${config["default"]}")
    }
    private fun javaOnly(operation: String, write: Boolean = false) {
        if (write) javaWrites.incrementAndGet()
        if (bedrockScenario) {
            logger.warning("BUNGEE_E2E_BEDROCK_FORBIDDEN operation=$operation")
            error("Bedrock entered a forbidden Java path")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun refuse(event: PreLoginEvent) { if (!accepting) { event.isCancelled = true; event.reason = unavailable().toBungee() } }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun refuse(event: ServerConnectEvent) { if (!accepting) { event.isCancelled = true; event.player.disconnect(unavailable().toBungee()) } }
    @EventHandler(priority = EventPriority.LOWEST)
    fun observePrivateCommand(event: ChatEvent) {
        if (claimsScenario && PrivateClaimCommands.isSensitive(event.message)) logger.severe("BUNGEE_E2E_PRIVATE_LEAK_CHAT")
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun observePrivateCompletion(event: TabCompleteEvent) {
        if (claimsScenario && PrivateClaimCommands.isSensitive(event.cursor)) logger.severe("BUNGEE_E2E_PRIVATE_LEAK_TAB")
    }
    fun isTemporaryConnection(connection: net.md_5.bungee.api.connection.PendingConnection): Boolean {
        if (!accepting || !::platform.isInitialized || !::gate.isInitialized) return true
        return platform.find(connection)?.let(gate::isTemporaryConnection) ?: true
    }

    fun isAuthenticated(player: net.md_5.bungee.api.connection.ProxiedPlayer): Boolean {
        if (!accepting || !::platform.isInitialized || !::gate.isInitialized) return false
        return platform.find(player)?.let(gate::isAuthenticated) == true
    }

    override fun onDisable() {
        accepting = false
        commandProbe?.close(); commandProbe = null
        if (::adapter.isInitialized) adapter.close()
        privateClaims?.close(); privateClaims = null
        floodgate?.close(); floodgate = null
        bedrockProbe?.close(); bedrockProbe = null
        commandGuard?.close(); commandGuard = null
        if (::gate.isInitialized) gate.deactivate()
        limbo?.close()
        if (::platform.isInitialized) platform.unregisterEngines()
        if (::authority.isInitialized) authority.close()
        io.shutdownNow(); scheduler.shutdownNow()
    }
}
