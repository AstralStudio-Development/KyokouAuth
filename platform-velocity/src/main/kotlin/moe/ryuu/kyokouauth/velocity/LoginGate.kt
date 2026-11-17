package moe.ryuu.kyokouauth.velocity

import com.mojang.brigadier.tree.CommandNode
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.command.CommandExecuteEvent
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent
import com.velocitypowered.api.event.player.TabCompleteEvent
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.util.GameProfile
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.proxy.AuthService
import moe.ryuu.kyokouauth.proxy.CommandNames
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.limbo.LimboListener
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import org.slf4j.Logger
import java.util.UUID
import moe.ryuu.kyokouauth.proxy.LoginGate as SharedGate
import moe.ryuu.kyokouauth.proxy.PreLoginEvent as PortPreLogin
import moe.ryuu.kyokouauth.proxy.LoginEvent as PortLogin
import moe.ryuu.kyokouauth.proxy.GameProfileRequestEvent as PortProfile
import moe.ryuu.kyokouauth.proxy.GameProfile as PortGameProfile

/** Only native event/result conversion lives here; both proxies execute the same connection gate. */
class LoginGate internal constructor(
    private val server: ProxyServer,
    config: ProxyConfig,
    private val messages: MessageCatalogue,
    services: SharedGate.Services,
    logger: Logger,
    clock: () -> Long = System::currentTimeMillis,
) : LimboListener {
    internal interface Services : SharedGate.Services
    internal val ports = VelocityPorts(server)
    internal val common = SharedGate(ports, config, messages, services, logger.toProxyLog(), clock)

    constructor(server: ProxyServer, config: ProxyConfig, messages: MessageCatalogue, auth: AuthService, logger: Logger) :
        this(server, config, messages, SharedGate.liveServices(auth), logger)

    internal fun activate() = common.activate()
    internal fun deactivate() = common.deactivate()
    fun verifyingCount() = common.verifyingCount()
    fun restartVerification(uuid: UUID) = common.restartVerification(uuid)
    fun restartVerification(player: Player) = common.restartVerification(ports.player(player))

    @Subscribe(order = PostOrder.LAST)
    fun onPreLogin(event: PreLoginEvent) {
        val allowed = event.result.isAllowed
        val port = PortPreLogin(ports.inbound(event.connection), event.username, event.uniqueId)
        if (!allowed) port.result = PortPreLogin.PreLoginComponentResult.denied(messages.parsed("login.internal-error"))
        common.onPreLogin(port)
        if (!allowed) return
        event.result = when {
            !port.result.isAllowed -> PreLoginEvent.PreLoginComponentResult.denied(requireNotNull(port.result.reason).toAdventure())
            port.result.onlineMode == true -> PreLoginEvent.PreLoginComponentResult.forceOnlineMode()
            port.result.onlineMode == false -> PreLoginEvent.PreLoginComponentResult.forceOfflineMode()
            else -> PreLoginEvent.PreLoginComponentResult.allowed()
        }
    }

    @Subscribe(order = PostOrder.LAST)
    fun onGameProfile(event: GameProfileRequestEvent) {
        val profile = event.gameProfile
        val port = PortProfile(ports.inbound(event.connection), PortGameProfile(profile.id, profile.name,
            profile.properties.map { PortGameProfile.Property(it.name, it.value, it.signature ?: "") }))
        common.onGameProfile(port)
        event.gameProfile = profile.withId(port.gameProfile.id).withProperties(port.gameProfile.properties.map {
            GameProfile.Property(it.name, it.value, it.signature)
        })
    }

    @Subscribe(order = PostOrder.LAST)
    fun onLogin(event: LoginEvent) {
        val allowed = event.result.isAllowed
        val port = PortLogin(ports.player(event.player))
        if (!allowed) port.result = moe.ryuu.kyokouauth.proxy.ResultedEvent.ComponentResult.denied(messages.parsed("login.internal-error"))
        common.onLogin(port)
        if (allowed && !port.result.isAllowed) event.result = ResultedEvent.ComponentResult.denied(requireNotNull(port.result.reason).toAdventure())
    }

    @Subscribe(order = PostOrder.LAST)
    fun onCommand(event: CommandExecuteEvent) {
        val player = event.commandSource as? Player ?: return
        if (!event.result.isAllowed && !event.result.isForwardToServer) return
        val port = ports.player(player)
        if (common.isAuthenticated(port)) return
        val commands = listOf(event.command, event.result.command.orElse(event.command))
        val denied = commands.firstNotNullOfOrNull { blockedCommand(port, it) } ?: return
        event.result = CommandExecuteEvent.CommandResult.denied()
        common.allowCommand(port, denied) // Feedback is best-effort; it can never reopen the event.
    }

    @Subscribe(order = PostOrder.LAST)
    fun onAvailableCommands(event: PlayerAvailableCommandsEvent) {
        val player = ports.player(event.player)
        if (common.isAuthenticated(player)) return
        event.rootNode.children.filter { node ->
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<CommandNode<*>, Boolean>())
            var current: CommandNode<*>? = node
            var blocked = false
            while (current != null && seen.add(current)) {
                if (blockedCommand(player, current.name) != null) { blocked = true; break }
                current = current.redirect
            }
            blocked
        }.map { it.name }.forEach(event.rootNode::removeChildByName)
    }

    @Subscribe(order = PostOrder.LAST)
    fun onTabComplete(event: TabCompleteEvent) {
        val player = ports.player(event.player)
        if (common.isAuthenticated(player)) return
        val partial = event.partialMessage.trimStart()
        if (!partial.startsWith('/')) return
        if (blockedCommand(player, partial) != null) {
            event.suggestions.clear()
        } else if (partial.none(Char::isWhitespace)) {
            event.suggestions.removeIf { blockedCommand(player, it) != null }
        }
    }

    private fun blockedCommand(player: moe.ryuu.kyokouauth.proxy.Player, command: String): String? {
        if (common.isCommandBlocked(player, command)) return command
        val root = CommandNames.root(command)
        if (root.isEmpty()) return null
        return server.commandManager.getCommandMeta(root)?.aliases?.firstOrNull { common.isCommandBlocked(player, it) }
    }

    @Subscribe(order = PostOrder.LAST)
    fun onChooseInitialServer(event: PlayerChooseInitialServerEvent) {
        val port = moe.ryuu.kyokouauth.proxy.PlayerChooseInitialServerEvent(ports.player(event.player))
        port.setInitialServer(event.initialServer.orElse(null)?.let(ports::server))
        common.onChooseInitialServer(port)
        event.setInitialServer(port.initialServer?.let(ports::unwrap))
    }

    @Subscribe(order = PostOrder.LAST)
    fun onPreConnect(event: ServerPreConnectEvent) {
        val target = event.result.server.orElse(null)
        val port = moe.ryuu.kyokouauth.proxy.ServerPreConnectEvent(ports.player(event.player), target?.let(ports::server))
        common.onPreConnect(port)
        if (port.result.server.isEmpty) event.result = ServerPreConnectEvent.ServerResult.denied()
    }

    @Subscribe(order = PostOrder.LAST)
    fun onKicked(event: KickedFromServerEvent) {
        val port = moe.ryuu.kyokouauth.proxy.KickedFromServerEvent(ports.player(event.player))
        common.onKicked(port)
        port.result?.let { event.result = KickedFromServerEvent.DisconnectPlayer.create(it.reason.toAdventure()) }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        try { common.onDisconnect(moe.ryuu.kyokouauth.proxy.DisconnectEvent(ports.player(event.player))) }
        finally { ports.forget(event.player) }
    }

    override fun onJoin(player: LimboPlayer) = common.onJoin(player)
    override fun onLeave(player: LimboPlayer) = common.onLeave(player)

    companion object {
        const val LIMBO_SERVER_NAME = SharedGate.LIMBO_SERVER_NAME
        internal const val CONNECTION_PROPERTY = SharedGate.CONNECTION_PROPERTY
    }
}
