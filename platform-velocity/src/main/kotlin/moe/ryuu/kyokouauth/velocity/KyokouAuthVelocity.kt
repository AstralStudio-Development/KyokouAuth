package moe.ryuu.kyokouauth.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.proxy.AuthService
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.limbo.LimboLogger
import moe.ryuu.kyokouauth.limbo.LimboServer
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import java.io.File
import java.nio.file.Path

/**
 * KyokouAuth on Velocity.
 *
 * The plugin owns the whole login path on the proxy: it decides whether a name may enter, keeps an
 * unverified player in a virtual server of its own making, and is the only thing that can send them
 * onward. Nothing here delegates to a backend server, because a backend cannot see - let alone
 * refuse - a `/server` command.
 *
 * The virtual server is a real Minecraft server on loopback, built by the `limbo` module and
 * connected through private raw targets, never published in the proxy's server registry. The proxy
 * needs no knowledge of packets, and the limbo needs no knowledge of proxies.
 */
class KyokouAuthVelocity @Inject constructor(
    private val server: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path,
) {

    private var limbo: LimboServer? = null
    private var auth: AuthService? = null
    private var gate: LoginGate? = null
    private var bedrock: VelocityFloodgate? = null
    @Volatile private var accepting = false

    // These listeners belong to the plugin instance itself, so even config/database/limbo creation
    // failures before LoginGate can be registered keep all new connections closed.
    @Subscribe(order = PostOrder.LAST)
    fun denyUnavailablePreLogin(event: PreLoginEvent) {
        if (!accepting) event.result = PreLoginEvent.PreLoginComponentResult.denied(unavailable())
    }

    @Subscribe(order = PostOrder.LAST)
    fun denyUnavailableLogin(event: LoginEvent) {
        if (!accepting) event.result = ResultedEvent.ComponentResult.denied(unavailable())
    }

    @Subscribe(order = PostOrder.LAST)
    fun denyUnavailableConnect(event: ServerPreConnectEvent) {
        if (!accepting) event.result = ServerPreConnectEvent.ServerResult.denied()
    }

    @Subscribe(order = PostOrder.LAST)
    fun denyUnavailableInitialServer(event: PlayerChooseInitialServerEvent) {
        if (!accepting) {
            event.setInitialServer(null)
            event.player.disconnect(unavailable())
        }
    }

    @Subscribe(order = PostOrder.LAST)
    fun denyUnavailableKick(event: KickedFromServerEvent) {
        if (!accepting) event.result = KickedFromServerEvent.DisconnectPlayer.create(unavailable())
    }

    private fun unavailable(): Component = Component.text("Authentication is unavailable. Please try again later.")

    @Subscribe(order = PostOrder.LAST)
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        accepting = false
        try {
            val dataFolder = dataDirectory.toFile()
            val config = ProxyConfig.load(dataFolder)
            val bedrock = VelocityFloodgate(server, config.bedrockEnabled, config.rolesEnabled).also { this.bedrock = it }
            val messages = MessageCatalogue(config.locale, File(dataFolder, "messages")).also { it.load() }
            val forwardingSecret = forwardingSecret(server.configuration)
            val reservedNames = listOf(LoginGate.LIMBO_SERVER_NAME) + (767..777).map { "${LoginGate.LIMBO_SERVER_NAME}-$it" }
            check(reservedNames.all { server.getServer(it).isEmpty }) { "A reserved limbo server name is already registered" }

            if (config.directoryBudget <= 0) {
                logger.warn("`premium.directory-limit` is 0, so Mojang lookups are not capped locally")
            }
            val skins = moe.ryuu.kyokouauth.proxy.RoleSkinService {
                val container = server.pluginManager.getPlugin("skinsrestorer").orElse(null) ?: return@RoleSkinService null
                val instance = container.instance.orElse(null) ?: return@RoleSkinService null
                moe.ryuu.kyokouauth.proxy.RoleSkinProvider(instance, instance.javaClass.classLoader) {
                    val current = server.pluginManager.getPlugin("skinsrestorer").orElse(null)
                    current === container && current.instance.orElse(null) === instance
                }
            }
            val auth = AuthService(config, logger.toProxyLog(), skins, supportsProfileClaims = true).also { this.auth = it }
            val gate = LoginGate(server, config, messages, auth, logger, bedrock::probe).also { this.gate = it }
            server.eventManager.register(this, gate)
            val limbo = LimboServer(
                settings = config.limbo,
                listener = gate,
                logger = LimboLogger { message, error ->
                    if (error != null) logger.warn(message, error) else logger.info(message)
                },
                forwardingSecret = forwardingSecret,
            ).also { this.limbo = it }
            val addresses = limbo.startAll()
            addresses.forEach { (protocol, address) -> gate.ports.registerEngine(protocol, address) }

            val command = KyokouAuthCommand(
                server = server,
                config = config,
                messages = messages,
                auth = auth,
                gate = gate,
                reloadMessages = { messages.load() },
            )
            server.commandManager.register(
                server.commandManager.metaBuilder(KyokouAuthCommand.NAME)
                    .aliases(KyokouAuthCommand.ALIAS)
                    .plugin(this)
                    .build(),
                command,
            )
            gate.activate()
            accepting = true
            logger.info("Login limbo supports protocols 767..777 on {} private loopback endpoints", addresses.size)
            logger.info("Official name lookup sources: services then legacy; network={}", config.directoryNetwork)
            logger.info("Microsoft auto-rename: {}; premium failure backoff: {}..{} seconds", config.autoRenameMicrosoft,
                config.sessionFailureBackoffSeconds, config.sessionFailureMaxBackoffSeconds)
        } catch (failure: Exception) {
            logger.error("Could not initialize KyokouAuth; all logins will be refused", failure)
            closeServices()
        }
    }

    /** Velocity 4.3.1 exposes these public getters on its actual config, but not on ProxyConfig. */
    internal fun forwardingSecret(configuration: Any): ByteArray {
        val mode = configuration.javaClass.getMethod("getPlayerInfoForwardingMode").invoke(configuration) as? Enum<*>
        check(mode?.name == "MODERN") { "KyokouAuth requires Velocity modern forwarding" }
        val secret = configuration.javaClass.getMethod("getForwardingSecret").invoke(configuration) as? ByteArray
        check(secret != null && secret.isNotEmpty()) { "Velocity's modern forwarding secret is unavailable" }
        return secret.copyOf()
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        closeServices()
    }

    private fun closeServices() {
        accepting = false
        bedrock?.close()
        bedrock = null
        runCatching { gate?.deactivate() }.onFailure { logger.warn("Could not close login gate", it) }
        // Closing one resource must not prevent the others from being released after partial startup.
        runCatching { limbo?.close() }.onFailure { logger.warn("Could not close login limbo", it) }
        limbo = null
        gate?.ports?.unregisterEngines()
        runCatching { auth?.close() }.onFailure { logger.warn("Could not close authentication storage", it) }
        auth = null
    }
}
