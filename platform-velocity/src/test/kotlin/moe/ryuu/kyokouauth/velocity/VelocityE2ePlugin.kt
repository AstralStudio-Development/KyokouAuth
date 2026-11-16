package moe.ryuu.kyokouauth.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerInfo
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
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Test-only bootstrap for the real native adapter; never included in a production artifact. */
class VelocityE2ePlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val folder: Path,
) {
    private val scenario = System.getProperty("kyokou.e2e.scenario", "held")
    private val stable = UUID(0L, 2535412345678901L)
    private val trusted = AtomicBoolean(false)
    private val io = Executors.newFixedThreadPool(2)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val handles = IdentityHashMap<LimboPlayer, LimboPlayer>()
    private val registrations = mutableListOf<ServerInfo>()
    private var gate: LoginGate? = null
    private var limbo: LimboServer? = null
    @Volatile private var accepting = false

    @Subscribe(order = PostOrder.LAST)
    fun unavailable(event: PreLoginEvent) {
        if (!accepting) event.result = PreLoginEvent.PreLoginComponentResult.denied(Component.text("Authentication unavailable"))
    }

    @Subscribe(order = PostOrder.LAST)
    fun unavailable(event: ServerPreConnectEvent) {
        if (!accepting) event.result = ServerPreConnectEvent.ServerResult.denied()
    }

    @Subscribe(order = PostOrder.FIRST)
    fun observeBackendAttempt(event: ServerPreConnectEvent) {
        if (accepting && scenario == "server" && event.originalServer.serverInfo.name == "backend") {
            logger.info("VELOCITY_E2E_TARGET_OBSERVED backend")
        }
    }

    @Subscribe
    fun initialize(event: ProxyInitializeEvent) {
        try {
            val config = VelocityConfig(mapOf("post-login.server" to "backend", "limbo.keep-alive-seconds" to "2",
                "limbo.keep-alive-timeout-seconds" to "10", "limbo.join-timeout-seconds" to "5"), folder.toFile())
            val messages = MessageCatalogue("en_US", folder.resolve("messages").toFile()).also { it.load() }
            val services = object : LoginGate.Services {
                override fun allow(key: String) = true
                override fun decide(name: GameName, address: String): AuthCoordinator.Decision {
                    val identity = Identity.Microsoft(name, Xuid(stable.leastSignificantBits), "Controlled profile")
                    val record = BoundIdentity.of(identity, 1L)
                    if (scenario == "trusted" || trusted.get()) return AuthCoordinator.Decision.Admit(identity, record)
                    val first = scenario == "first-bind"
                    return AuthCoordinator.Decision.Verify(if (first) AuthCoordinator.provisionalUuid(name) else stable,
                        if (first) AuthCoordinator.Reason.FIRST_BIND else AuthCoordinator.Reason.SESSION_EXPIRED,
                        first, record.takeUnless { first })
                }
                override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome = error("No external premium session in test")
                override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome =
                    AuthCoordinator.ClaimOutcome.Bound(BoundIdentity.of(Identity.Microsoft(name, xuid, gamertag), 1L))
                override fun grantSession(uuid: UUID, address: String) {
                    check(uuid == stable); trusted.set(true); logger.info("VELOCITY_E2E_SESSION {}", uuid)
                }
                override fun submit(task: () -> Unit) { io.execute(task) }
                override fun schedule(delayMillis: Long, task: () -> Unit) = scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
                override fun scheduleAtFixedRate(periodMillis: Long, task: () -> Unit) =
                    scheduler.scheduleAtFixedRate(task, periodMillis, periodMillis, TimeUnit.MILLISECONDS)
                override fun begin() = DeviceCodeFlow.Challenge("E2E-CODE", "https://microsoft.com/link",
                    "https://microsoft.com/link?otc=E2E-CODE", 300, 1, "test-only-no-network")
                override fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult =
                    if (scenario in setOf("stable", "first-bind")) DeviceCodeFlow.PollResult.Authorized("synthetic", null, 300)
                    else DeviceCodeFlow.PollResult.Pending
                override fun exchange(accessToken: String) = XboxLive.XstsToken("synthetic", stable.leastSignificantBits, "Controlled profile", "synthetic")
            }
            val gate = LoginGate(proxy, config, messages, services, logger).also { this.gate = it }
            proxy.eventManager.register(this, gate)
            val listener = object : LimboListener {
                override fun onJoin(player: LimboPlayer) {
                    val recording = object : LimboPlayer by player {
                        override fun showTitle(title: TextComponent, subtitle: TextComponent?, fadeIn: Int, stay: Int, fadeOut: Int) {
                            logger.info("VELOCITY_E2E_TITLE {} stay={}", player.name, stay)
                            player.showTitle(title, subtitle, fadeIn, stay, fadeOut)
                        }
                        override fun clearTitle() { logger.info("VELOCITY_E2E_CLEAR {}", player.name); player.clearTitle() }
                    }
                    synchronized(handles) { handles[player] = recording }
                    gate.onJoin(recording)
                    logger.info("VELOCITY_E2E_READY {} {}", player.name, player.uuid)
                    if (scenario == "server") scheduler.schedule({
                        proxy.getPlayer(player.uuid).ifPresent { frontend ->
                            logger.info("VELOCITY_E2E_UNAUTHORIZED_SERVER_ATTEMPT {}", frontend.username)
                            proxy.commandManager.executeAsync(frontend, "server backend")
                        }
                    }, 1, TimeUnit.SECONDS)
                }
                override fun onLeave(player: LimboPlayer) {
                    synchronized(handles) { handles.remove(player) }?.let(gate::onLeave)
                    logger.info("VELOCITY_E2E_LEFT {}", player.name)
                }
            }
            val mode = proxy.configuration.javaClass.getMethod("getPlayerInfoForwardingMode").invoke(proxy.configuration) as Enum<*>
            check(mode.name == "MODERN")
            val secret = proxy.configuration.javaClass.getMethod("getForwardingSecret").invoke(proxy.configuration) as ByteArray
            val limbo = LimboServer(settings = config.limbo, listener = listener,
                logger = LimboLogger { message, failure -> if (failure == null) logger.info(message) else logger.warn(message, failure) },
                forwardingSecret = secret).also { this.limbo = it }
            val overridePort = System.getProperty("kyokou.e2e.enginePort")?.toInt()
            limbo.startAll().forEach { (protocol, address) ->
                val actual = if (protocol == 777 && overridePort != null) java.net.InetSocketAddress("127.0.0.1", overridePort) else address
                val info = ServerInfo("${LoginGate.LIMBO_SERVER_NAME}-$protocol", actual)
                proxy.registerServer(info); registrations += info
            }
            gate.activate()
            accepting = true
            logger.info("VELOCITY_E2E_STARTED {}", scenario)
        } catch (failure: Exception) { logger.error("VELOCITY_E2E_START_FAILED", failure) }
    }

    @Subscribe
    fun close(event: ProxyShutdownEvent) {
        accepting = false
        gate?.deactivate()
        limbo?.close()
        registrations.forEach(proxy::unregisterServer)
        registrations.clear()
        io.shutdownNow(); scheduler.shutdownNow()
    }
}
