package moe.ryuu.kyokouauth.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.util.GameProfile
import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.LimboListener
import moe.ryuu.kyokouauth.limbo.LimboLogger
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import moe.ryuu.kyokouauth.limbo.LimboServer
import moe.ryuu.kyokouauth.limbo.RoleSelectorIcon
import moe.ryuu.kyokouauth.limbo.RoleSelectorMenu
import moe.ryuu.kyokouauth.proxy.BedrockLogin
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.proxy.RoleOption
import moe.ryuu.kyokouauth.proxy.RoleOptions
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import moe.ryuu.kyokouauth.proxy.GameProfile as PortProfile

/** Test-only native FG bootstrap. No Java advanced role fixture, avatar, Transfer or cookie flow. */
class RoleSelectionE2ePlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val folder: Path,
) {
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
    private var bedrock: VelocityFloodgate? = null
    private var gate: LoginGate? = null
    private var limbo: LimboServer? = null
    @Volatile private var accepting = false
    private val selected get() = if (chosen == "microsoft") microsoft else premium
    private val jSkin get() = PortProfile.Property("textures",
        checkNotNull(System.getProperty("kyokou.roleE2e.premiumTexture")), "role-e2e-synthetic-signature")

    @Subscribe(order = PostOrder.LAST)
    fun unavailable(event: PreLoginEvent) {
        if (!accepting || !event.connection.remoteAddress.address.isLoopbackAddress)
            event.result = PreLoginEvent.PreLoginComponentResult.denied(Component.text("ROLE_E2E_UNAVAILABLE"))
    }

    /** A NORMAL unrelated property survives; hostile texture noise may never survive Gate LAST. */
    @Subscribe(priority = 1)
    fun profileNoise(event: GameProfileRequestEvent) {
        if (!accepting) return
        val profile = event.gameProfile
        event.gameProfile = GameProfile(profile.id, profile.name,
            profile.properties.filterNot { it.name == "textures" || it.name == "role-e2e:marker" } + listOf(
                GameProfile.Property("role-e2e:marker", "preserved-non-texture-property", ""),
                GameProfile.Property("textures", "must-be-removed-by-last-role-policy", "")))
    }

    @Subscribe(order = PostOrder.LAST)
    fun postLogin(event: PostLoginEvent) = checked {
        val current = checkNotNull(gate)
        val player = event.player
        val authenticated = current.common.isAuthenticated(current.ports.player(player))
        logger.info("ROLE_E2E_NATIVE_SOCKET session={} remotePort={} state={}", player.sessionId,
            player.remoteAddress.port, if (authenticated) "final" else "held")
        if (!authenticated) {
            check(!single && player.uniqueId == premium.uuid && player.username == premium.name.value)
            val textures = player.gameProfileProperties.filter { it.name == "textures" }
            check(textures.size == 1 && textures.single().value == jSkin.value && textures.single().signature == jSkin.signature)
            check(player.gameProfileProperties.single { it.name == "role-e2e:marker" }.value == "preserved-non-texture-property")
            chooserSessions += player.sessionId
            logger.info("ROLE_E2E_NATIVE_HELD session={} uuid={} name={}", player.sessionId, player.uniqueId, player.username)
        } else {
            check(single || chosen == "microsoft") { "J must authorize later on this original socket, not silently preselect" }
            recordFinal(player)
        }
    }

    private fun recordFinal(player: Player) {
        val current = checkNotNull(gate)
        check(current.common.isAuthenticated(current.ports.player(player)))
        check(player.uniqueId == selected.uuid && player.username == selected.name.value && !player.isOnlineMode)
        val textures = player.gameProfileProperties.filter { it.name == "textures" }
        if (chosen == "microsoft") check(textures.isEmpty())
        else check(textures.single().value == jSkin.value && textures.single().signature == jSkin.signature)
        check(player.gameProfileProperties.single { it.name == "role-e2e:marker" }.value == "preserved-non-texture-property")
        check(player.gameProfileProperties.single { it.name == LoginGate.CONNECTION_PROPERTY }.value == player.sessionId.toString())
        if (finalSessions.add(player.sessionId)) logger.info("ROLE_E2E_NATIVE_FINAL session={} role={} uuid={} name={}",
            player.sessionId, chosen, player.uniqueId, player.username)
    }

    /** Real official NORMAL handler runs independently. This LAST observer never changes event.result. */
    @Subscribe(order = PostOrder.LAST)
    fun floodgateMessageBarrier(event: PluginMessageEvent) = checked {
        if (!accepting || event.identifier.id != "floodgate:form") return@checked
        val player = event.source as? Player ?: error("Expected native player-originated probe")
        val data = event.data
        check(data.size == 3 && java.nio.ByteBuffer.wrap(data).short.toInt() == -12345 && data[2] == '0'.code.toByte())
        val current = checkNotNull(gate)
        val authenticated = current.common.isAuthenticated(current.ports.player(player))
        val phase = if (authenticated) "final" else "chooser"
        if (!authenticated) check(player.sessionId in chooserSessions)
        else {
            check(player.sessionId in finalSessions)
            check(player.uniqueId == selected.uuid && player.username == selected.name.value)
        }
        logger.info("ROLE_E2E_FG_MESSAGE_BARRIER session={} phase={} source=PLAYER held={} bytes=3", player.sessionId, phase, !authenticated)
        player.sendPluginMessage(MinecraftChannelIdentifier.from("kyokouauth:role_e2e_fg_ack"), data)
    }

    @Subscribe(order = PostOrder.FIRST)
    fun backendAttempt(event: ServerPreConnectEvent) = checked {
        if (!accepting || event.originalServer.serverInfo.name != "backend") return@checked
        val current = checkNotNull(gate)
        val player = event.player
        check(current.common.isAuthenticated(current.ports.player(player))) { "Old chooser/replay attempted real backend" }
        if (chosen == "premium") check(player.sessionId in chooserSessions) { "Same-role path used another socket" }
        else check(player.sessionId !in chooserSessions) { "Different-role old chooser escaped" }
        recordFinal(player)
        check(claims.get() == if (chosen == "premium") 1 else 0)
        logger.info("ROLE_E2E_AUTHORIZED_BACKEND session={} role={} uuid={} name={}", player.sessionId, chosen, player.uniqueId, player.username)
    }

    @Subscribe(order = PostOrder.LAST)
    fun initialize(event: ProxyInitializeEvent) = checked {
        check(System.getProperty("kyokou.roleE2e.mode") == "floodgate" && chosen in setOf("microsoft", "premium"))
        val isolated = Path.of(checkNotNull(System.getProperty("kyokou.roleE2e.run"))).toRealPath()
        val root = Path.of(checkNotNull(System.getProperty("kyokou.roleE2e.root"))).toRealPath()
        check(isolated.startsWith(root) && isolated != root && Files.isRegularFile(isolated.resolve("isolated-role-fixture.json")))
        check(folder.toAbsolutePath().normalize().startsWith(isolated))
        val config = ProxyConfig(mapOf("post-login.server" to "backend", "verification.poll-interval-seconds" to "1",
            "verification.timeout-seconds" to "25", "limbo.join-timeout-seconds" to "10", "role-selection.enabled" to "true",
            "role-selection.timeout-seconds" to "25", "role-selection.hint-ttl-seconds" to "20", "bedrock.enabled" to "true"), folder.toFile())
        val messagesFolder = folder.resolve("messages")
        Files.createDirectories(messagesFolder)
        Files.writeString(messagesFolder.resolve("en_US.yml"), "role-selection:\n  reconnect: 'ROLE_E2E_ROLE_SELECTED: reconnect normally'\n")
        val messages = MessageCatalogue("en_US", messagesFolder.toFile()).also { it.load() }
        bedrock = VelocityFloodgate(proxy, true, allowLinked = true)
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
            override fun schedule(delayMillis: Long, task: () -> Unit) = scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
            override fun begin(): DeviceCodeFlow.Challenge = error("FG must not begin Java device flow")
            override fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult = error("FG must not poll Java device flow")
            override fun exchange(accessToken: String): XboxLive.XstsToken = error("FG must not exchange a Java token")
            override fun roleOptions(xuid: Xuid, gamertag: String, fallbackBedrockName: String?): RoleOptions {
                check(xuid == this@RoleSelectionE2ePlugin.xuid && gamertag == "R")
                return if (single) RoleOptions(listOf(RoleOption(microsoft, uRecord, null)), null)
                else RoleOptions(listOf(RoleOption(microsoft, uRecord, null), RoleOption(premium, jRecord, jSkin)), premium.uuid)
            }
        }
        val native = LoginGate(proxy, config, messages, services, logger, bedrockProbe = { source ->
            checkNotNull(bedrock).probe(source).also { result ->
                check(result is BedrockLogin.Verified && result.proof.xuid == xuid && result.proof.linkedJavaUuid == if (single) null else premium.uuid)
                val phase = probes.computeIfAbsent(source.sessionId) { AtomicInteger() }.incrementAndGet()
                logger.info("ROLE_E2E_FG_PROOF session={} phase={} linked={}", source.sessionId, phase, !single)
            }
        }).also { gate = it }
        proxy.eventManager.register(this, native)
        val listener = object : LimboListener {
            override fun onJoin(player: LimboPlayer) {
                check(!single)
                val recording = object : LimboPlayer by player {
                    override fun openRoleSelector(menu: RoleSelectorMenu) {
                        check(menu.microsoft.profileUuid == microsoft.uuid && menu.premium.profileUuid == premium.uuid)
                        check(menu.microsoft.icon == RoleSelectorIcon.NAME_TAG && menu.premium.icon == RoleSelectorIcon.DIAMOND)
                        logger.info("ROLE_E2E_MENU session={} slots=27 u=NAME_TAG j=DIAMOND", player.connectionId)
                        player.openRoleSelector(menu)
                    }
                }
                handles[player] = recording
                native.onJoin(recording)
                logger.info("ROLE_E2E_LIMBO_READY session={}", player.connectionId)
            }
            override fun onLeave(player: LimboPlayer) { handles.remove(player)?.let(native::onLeave) }
            override fun onRoleSelect(player: LimboPlayer, generation: UUID, optionId: String) {
                handles[player]?.let { native.onRoleSelect(it, generation, optionId) }
            }
        }
        val forwarding = proxy.configuration.javaClass.getMethod("getForwardingSecret").invoke(proxy.configuration) as ByteArray
        limbo = LimboServer(config.limbo, listener, LimboLogger { message, error -> if (error == null) logger.info(message) else logger.warn(message, error) },
            forwardingSecret = forwarding).also { engine -> engine.startAll().forEach { (protocol, address) -> native.ports.registerEngine(protocol, address) } }
        native.activate()
        accepting = true
        logger.info("ROLE_E2E_STARTED mode=floodgate role={} scenario={} relation=synthetic texture=synthetic-not-rendered", chosen, if (single) "single" else "linked")
    }

    private inline fun checked(action: () -> Unit) {
        try { action() } catch (failure: Throwable) { logger.error("ROLE_E2E_ASSERTION_FAILED", failure); throw failure }
    }

    @Subscribe
    fun shutdown(event: ProxyShutdownEvent) {
        accepting = false
        gate?.deactivate(); limbo?.close(); gate?.ports?.unregisterEngines(); bedrock?.close()
        handles.clear(); io.shutdownNow(); scheduler.shutdownNow()
    }
}
