package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.ForwardingMode
import moe.ryuu.kyokouauth.limbo.LimboListener
import moe.ryuu.kyokouauth.limbo.LimboLogger
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import moe.ryuu.kyokouauth.limbo.LimboServer
import moe.ryuu.kyokouauth.limbo.PrivateTicketAuthority
import moe.ryuu.kyokouauth.limbo.RoleSelectorIcon
import moe.ryuu.kyokouauth.limbo.RoleSelectorMenu
import moe.ryuu.kyokouauth.proxy.BedrockLogin
import moe.ryuu.kyokouauth.proxy.GameProfile
import moe.ryuu.kyokouauth.proxy.LoginGate
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.proxy.ProxyLog
import moe.ryuu.kyokouauth.proxy.RoleOption
import moe.ryuu.kyokouauth.proxy.RoleOptions
import net.md_5.bungee.api.connection.PendingConnection
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.LoginEvent
import net.md_5.bungee.api.event.PluginMessageEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.PreLoginEvent
import net.md_5.bungee.api.event.ServerConnectEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level

/** Test-only native Bungee Floodgate role bootstrap. Never packaged in the production plugin. */
class BungeeRoleSelectionE2ePlugin : Plugin(), Listener {
    private val chosen = System.getProperty("kyokou.roleE2e.role", "premium")
    private val single = System.getProperty("kyokou.roleE2e.scenario", "linked") == "single"
    private val xuid = Xuid(2535412345678901L)
    private val microsoft = Identity.Microsoft(GameName.parseOrThrow("URole"), xuid, "R")
    private val premium = Identity.Premium(GameName.parseOrThrow("JRole"), UUID.fromString("12345678-1234-4234-9234-123456789abc"))
    private val uRecord = BoundIdentity.of(microsoft, 1L)
    @Volatile private var jRecord: BoundIdentity? = null
    private val claims = AtomicInteger()
    private val io = Executors.newFixedThreadPool(2)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val handles = Collections.synchronizedMap(IdentityHashMap<LimboPlayer, LimboPlayer>())
    private val chooserSessions = ConcurrentHashMap.newKeySet<UUID>()
    private val finalSessions = ConcurrentHashMap.newKeySet<UUID>()
    private val probes = ConcurrentHashMap<UUID, AtomicInteger>()
    private var floodgate: BungeeFloodgate? = null
    private lateinit var platform: BungeePlatform
    private lateinit var profiles: BungeeProfileBridge
    private lateinit var authority: PrivateTicketAuthority
    private lateinit var gate: LoginGate
    private lateinit var adapter: BungeeLoginGate
    private var limbo: LimboServer? = null
    @Volatile private var accepting = false
    private val selected get() = if (chosen == "microsoft") microsoft else premium
    private val jSkin get() = GameProfile.Property("textures",
        checkNotNull(System.getProperty("kyokou.roleE2e.premiumTexture")), "role-e2e-synthetic-signature")

    override fun onEnable() {
        proxy.pluginManager.registerListener(this, this)
        checked {
            check(proxy.config.isIpForward)
            check(System.getProperty("kyokou.roleE2e.mode") == "floodgate" && chosen in setOf("microsoft", "premium"))
            val isolated = Path.of(checkNotNull(System.getProperty("kyokou.roleE2e.run"))).toRealPath()
            val root = Path.of(checkNotNull(System.getProperty("kyokou.roleE2e.root"))).toRealPath()
            check(isolated.startsWith(root) && isolated != root && Files.isRegularFile(isolated.resolve("isolated-role-fixture.json")))
            check(dataFolder.toPath().toAbsolutePath().normalize().startsWith(isolated))
            proxy.registerChannel("floodgate:form")
            proxy.registerChannel("kyokouauth:role_e2e_fg_ack")
            platform = BungeePlatform(proxy)
            profiles = BungeeProfileBridge(javaClass.classLoader,
                bedrock = { pending -> platform.find(pending)?.currentBedrockProof() },
                role = { pending -> platform.find(pending)?.currentRoleProfile() })
            platform.installProfiles(profiles)
            authority = PrivateTicketAuthority()
            val roleBridge = BungeeRoleLoginBridge(javaClass.classLoader)
            val config = ProxyConfig(mapOf("post-login.server" to "backend", "verification.poll-interval-seconds" to "1",
                "verification.timeout-seconds" to "25", "limbo.join-timeout-seconds" to "10", "role-selection.enabled" to "true",
                "role-selection.timeout-seconds" to "25", "role-selection.hint-ttl-seconds" to "20", "bedrock.enabled" to "true"), dataFolder)
            val messagesFolder = dataFolder.toPath().resolve("messages")
            Files.createDirectories(messagesFolder)
            Files.writeString(messagesFolder.resolve("en_US.yml"), "role-selection:\n  reconnect: 'ROLE_E2E_ROLE_SELECTED: reconnect normally'\n")
            val messages = MessageCatalogue("en_US", messagesFolder.toFile()).also { it.load() }
            val log = ProxyLog { severity, message, failure ->
                logger.log(when (severity) {
                    ProxyLog.Level.INFO -> Level.INFO
                    ProxyLog.Level.WARN -> Level.WARNING
                    ProxyLog.Level.ERROR -> Level.SEVERE
                }, message, failure)
            }
            val fg = BungeeFloodgate(true, { proxy.pluginManager.getPlugin("floodgate") }, allowLinked = true).also { floodgate = it }
            val services = object : LoginGate.Services {
                override fun allow(key: String) = true
                override fun decide(name: GameName, address: String): AuthCoordinator.Decision = error("Java basic is a separate fixture")
                override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome = error("FG must not call Java device-code bind")
                override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome {
                    check(chosen == "premium" && uuid == premium.uuid && name == premium.name && claims.incrementAndGet() == 1)
                    return AuthCoordinator.ClaimOutcome.Bound(BoundIdentity.of(premium, 1L).also { jRecord = it })
                }
                override fun grantSession(uuid: UUID, address: String) = error("FG must not mint Java/IP sessions")
                override fun submit(task: () -> Unit) { io.execute(task) }
                override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*> = scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
                override fun begin(): DeviceCodeFlow.Challenge = error("FG must not begin Java device flow")
                override fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult = error("FG must not poll Java device flow")
                override fun exchange(accessToken: String): XboxLive.XstsToken = error("FG must not exchange a Java token")
                override fun roleOptions(xuid: Xuid, gamertag: String, fallbackBedrockName: String?): RoleOptions {
                    check(xuid == this@BungeeRoleSelectionE2ePlugin.xuid && gamertag == "R")
                    return if (single) RoleOptions(listOf(RoleOption(microsoft, uRecord, null)), null)
                    else RoleOptions(listOf(RoleOption(microsoft, uRecord, null), RoleOption(premium, jRecord, jSkin)), premium.uuid)
                }
            }
            gate = LoginGate(platform, config, messages, services, log)
            adapter = BungeeLoginGate(this, platform, profiles, BungeeTickets(platform, profiles, authority), gate, log,
                submit = services::submit, schedule = services::schedule, accepting = { accepting },
                bedrock = { player ->
                    fg.probe(player).also { result ->
                        check(result is BedrockLogin.Verified && result.proof.xuid == xuid &&
                            result.proof.linkedJavaUuid == if (single) null else premium.uuid)
                        val phase = probes.computeIfAbsent(player.sessionId) { AtomicInteger() }.incrementAndGet()
                        logger.info("ROLE_E2E_FG_PROOF session=${player.sessionId} phase=$phase linked=${!single}")
                    }
                },
                rolesEnabled = true,
                prepareRole = { player, profile ->
                    val proof = player.currentBedrockProof()
                    roleBridge.prepare(player.pending, profile.id, profile.name, proof)
                    player.rememberRoleProfile(profile)
                    profiles.prepareRole(player.pending, profile)
                    injectProfileNoise(player.pending)
                },
            )
            proxy.pluginManager.registerListener(this, adapter)
            val listener = object : LimboListener {
                override fun onJoin(player: LimboPlayer) {
                    check(!single)
                    logger.info("ROLE_E2E_PRIVATE_LIMBO_AUTHORITY session=${player.connectionId} consumed=true target=private-engine")
                    val recording = object : LimboPlayer by player {
                        override fun openRoleSelector(menu: RoleSelectorMenu) {
                            check(menu.microsoft.profileUuid == microsoft.uuid && menu.premium.profileUuid == premium.uuid)
                            check(menu.microsoft.icon == RoleSelectorIcon.NAME_TAG && menu.premium.icon == RoleSelectorIcon.DIAMOND)
                            logger.info("ROLE_E2E_MENU session=${player.connectionId} slots=27 u=NAME_TAG j=DIAMOND")
                            player.openRoleSelector(menu)
                        }
                    }
                    handles[player] = recording
                    gate.onJoin(recording)
                    logger.info("ROLE_E2E_LIMBO_READY session=${player.connectionId}")
                }
                override fun onLeave(player: LimboPlayer) { handles.remove(player)?.let(gate::onLeave) }
                override fun onRoleSelect(player: LimboPlayer, generation: UUID, optionId: String) {
                    handles[player]?.let { gate.onRoleSelect(it, generation, optionId) }
                }
            }
            limbo = LimboServer(settings = config.limbo, listener = listener,
                logger = LimboLogger { message, failure -> if (failure == null) logger.info(message) else logger.log(Level.WARNING, message, failure) },
                forwardingMode = ForwardingMode.PrivateTicket(authority),
            ).also { engine -> engine.startAll().forEach { (protocol, address) -> platform.registerEngine(protocol, address) } }
            scheduler.scheduleAtFixedRate({ adapter.sweepClosed() }, 1, 1, TimeUnit.SECONDS)
            gate.activate()
            accepting = true
            logger.info("ROLE_E2E_STARTED mode=floodgate role=$chosen scenario=${if (single) "single" else "linked"} relation=synthetic texture=synthetic-not-rendered")
        }
    }

    /** Injects one unrelated property and hostile texture noise before Gate's LAST profile check. */
    private fun injectProfileNoise(pending: PendingConnection) {
        val current = profiles.read(pending)
        profiles.apply(pending, current.withProperties(
            current.properties.filterNot { it.name == "textures" || it.name == "role-e2e:marker" } + listOf(
                GameProfile.Property("role-e2e:marker", "preserved-non-texture-property", ""),
                GameProfile.Property("textures", "must-be-removed-by-last-role-policy", ""))))
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun unavailablePreLogin(event: PreLoginEvent) {
        val remote = event.connection.socketAddress as? java.net.InetSocketAddress
        if (!accepting || remote?.address?.isLoopbackAddress != true) {
            event.isCancelled = true
            event.reason = unavailable().toBungee()
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun unavailableLogin(event: LoginEvent) {
        if (!accepting && !event.isCancelled) {
            event.isCancelled = true
            event.reason = unavailable().toBungee()
        }
    }

    // Priority 65 runs strictly after native HIGHEST (64), observing the settled PostLogin result.
    @EventHandler(priority = 65)
    fun postLogin(event: PostLoginEvent) = checked {
        val player = checkNotNull(platform.find(event.player.pendingConnection))
        val authenticated = gate.isAuthenticated(player)
        logger.info("ROLE_E2E_NATIVE_SOCKET session=${player.sessionId} remotePort=${player.remoteAddress.port} state=${if (authenticated) "final" else "held"}")
        val profile = profiles.read(player.pending)
        if (!authenticated) {
            check(!single && player.uniqueId == premium.uuid && player.username == premium.name.value)
            val textures = profile.properties.filter { it.name == "textures" }
            check(textures.size == 1 && textures.single().value == jSkin.value && textures.single().signature == jSkin.signature)
            check(profile.properties.single { it.name == "role-e2e:marker" }.value == "preserved-non-texture-property")
            chooserSessions += player.sessionId
            logger.info("ROLE_E2E_NATIVE_HELD session=${player.sessionId} uuid=${player.uniqueId} name=${player.username}")
        } else {
            check(single || chosen == "microsoft") { "J must authorize later on this original socket, not silently preselect" }
            recordFinal(player)
        }
    }

    private fun recordFinal(player: BungeePlayer) {
        check(gate.isAuthenticated(player))
        check(player.uniqueId == selected.uuid && player.username == selected.name.value && !player.isOnlineMode)
        val profile = profiles.read(player.pending)
        val textures = profile.properties.filter { it.name == "textures" }
        if (chosen == "microsoft") check(textures.isEmpty())
        else check(textures.single().value == jSkin.value && textures.single().signature == jSkin.signature)
        check(profile.properties.single { it.name == "role-e2e:marker" }.value == "preserved-non-texture-property")
        if (finalSessions.add(player.sessionId)) {
            logger.info("ROLE_E2E_NATIVE_FINAL session=${player.sessionId} role=$chosen uuid=${player.uniqueId} name=${player.username}")
        }
    }

    // Priority 65 runs strictly after BungeeTickets strips private authority and connection-id on real backends.
    @EventHandler(priority = 65)
    fun backendAttempt(event: ServerConnectEvent) = checked {
        if (!accepting || event.isCancelled || event.target.name != "backend") return@checked
        val player = checkNotNull(platform.find(event.player))
        check(gate.isAuthenticated(player)) { "Old chooser/replay attempted real backend" }
        if (chosen == "premium") check(player.sessionId in chooserSessions) { "Same-role path used another socket" }
        else check(player.sessionId !in chooserSessions) { "Different-role old chooser escaped" }
        recordFinal(player)
        check(claims.get() == if (chosen == "premium") 1 else 0)
        val actual = profiles.read(player.pending)
        check(actual.properties.none { it.name == authority.propertyKey || it.name == LoginGate.CONNECTION_PROPERTY }) {
            "Private ticket or connection nonce survived real-backend strip"
        }
        profiles.apply(player.pending, actual.withProperties(
            actual.properties.filterNot { it.name == "role-e2e:test-session" } +
                GameProfile.Property("role-e2e:test-session", player.sessionId.toString(), "")))
        logger.info("ROLE_E2E_AUTHORIZED_BACKEND session=${player.sessionId} role=$chosen uuid=${player.uniqueId} name=${player.username}")
    }

    /** Official Floodgate handler runs at NORMAL. Priority 65 observes without changing isCancelled. */
    @EventHandler(priority = 65)
    fun floodgateMessageBarrier(event: PluginMessageEvent) = checked {
        if (!accepting || event.tag != "floodgate:form") return@checked
        val native = event.sender as? ProxiedPlayer ?: error("Expected native player-originated probe")
        val player = checkNotNull(platform.find(native))
        val data = event.data
        check(data.size == 3 && ByteBuffer.wrap(data).short.toInt() == -12345 && data[2] == '0'.code.toByte())
        val authenticated = gate.isAuthenticated(player)
        val phase = if (authenticated) "final" else "chooser"
        if (!authenticated) check(player.sessionId in chooserSessions)
        else {
            check(player.sessionId in finalSessions)
            check(player.uniqueId == selected.uuid && player.username == selected.name.value)
        }
        logger.info("ROLE_E2E_FG_MESSAGE_BARRIER session=${player.sessionId} phase=$phase source=PLAYER held=${!authenticated} bytes=3")
        native.sendData("kyokouauth:role_e2e_fg_ack", data)
    }

    private inline fun checked(action: () -> Unit) {
        try { action() } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "ROLE_E2E_ASSERTION_FAILED", failure)
            throw failure
        }
    }

    override fun onDisable() {
        accepting = false
        if (::adapter.isInitialized) adapter.close()
        if (::gate.isInitialized) gate.deactivate()
        limbo?.close()
        if (::platform.isInitialized) platform.unregisterEngines()
        if (::authority.isInitialized) authority.close()
        floodgate?.close()
        handles.clear()
        io.shutdownNow()
        scheduler.shutdownNow()
    }
}
