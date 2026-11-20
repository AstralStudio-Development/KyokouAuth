package moe.ryuu.kyokouauth.velocity

import com.mojang.brigadier.tree.CommandNode
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.EventTask
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
import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.Player
import moe.ryuu.kyokouauth.proxy.BedrockLogin
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
import java.util.concurrent.CompletableFuture
import moe.ryuu.kyokouauth.proxy.LoginGate as SharedGate
import moe.ryuu.kyokouauth.proxy.PreLoginEvent as PortPreLogin
import moe.ryuu.kyokouauth.proxy.LoginEvent as PortLogin
import moe.ryuu.kyokouauth.proxy.GameProfileRequestEvent as PortProfile
import moe.ryuu.kyokouauth.proxy.GameProfile as PortGameProfile

/** Only native event/result conversion lives here; both proxies execute the same connection gate. */
class LoginGate internal constructor(
    private val server: ProxyServer,
    private val config: ProxyConfig,
    private val messages: MessageCatalogue,
    private val services: SharedGate.Services,
    logger: Logger,
    clock: () -> Long = System::currentTimeMillis,
    private val bedrockProbe: (InboundConnection) -> BedrockLogin = { BedrockLogin.Java },
    private val watchClosed: (InboundConnection, () -> Unit) -> AutoCloseable =
        VelocityChannels(server.javaClass.classLoader)::watchClosed,
    private val roleEvents: VelocityRoleEventTasks? = if (config.rolesEnabled)
        VelocityRoleEventTasks(config.pendingLoginLimit, services::submit) else null,
) : LimboListener {
    internal interface Services : SharedGate.Services
    internal val ports = VelocityPorts(server)
    internal val common = SharedGate(ports, config, messages, services, logger.toProxyLog(), clock,
        isolateProvisionalNames = true)
    private val privateClaimLock = Any()
    private var privateClaimsClosed = false
    private var privateClaims: VelocityClaimCommands? = if (config.profileClaimsEnabled) createClaimShield() else null

    private fun createClaimShield() = VelocityClaimCommands(server.javaClass.classLoader,
        moe.ryuu.kyokouauth.proxy.PrivateClaimCommands::isSensitive,
        receive = { player, command -> common.onPrivateClaim(ports.player(player), command) })

    internal constructor(server: ProxyServer, config: ProxyConfig, messages: MessageCatalogue, auth: AuthService,
                         logger: Logger, bedrockProbe: (InboundConnection) -> BedrockLogin) :
        this(server, config, messages, SharedGate.liveServices(auth), logger, bedrockProbe = bedrockProbe)

    internal fun activate() = common.activate()
    internal fun deactivate() {
        try { common.deactivate() }
        finally {
            val shield = synchronized(privateClaimLock) { privateClaimsClosed = true; privateClaims }
            try { shield?.close() } finally { roleEvents?.close() }
        }
    }
    fun verifyingCount() = common.verifyingCount()
    fun restartVerification(uuid: UUID) = common.restartVerification(uuid)
    fun restartVerification(player: Player) = common.restartVerification(ports.player(player))

    @Subscribe(order = PostOrder.LAST)
    fun onPreLogin(event: PreLoginEvent): EventTask? {
        if (!config.rolesEnabled || !event.result.isAllowed) {
            applyPreLogin(event, computePreLogin(event, bedrockProbe(event.connection)))
            return null
        }
        return checkNotNull(roleEvents).task(event.connection.sessionId,
            active = { event.connection.isActive && event.result.isAllowed },
            watchClosed = { action -> watchClosed(event.connection, action) },
            source = { CompletableFuture.completedFuture(bedrockProbe(event.connection)) },
            compute = { source -> computePreLogin(event, source) },
            apply = { result -> applyPreLogin(event, result) },
            refuse = {
                val reason = messages.parsed("login.internal-error")
                try { if (event.result.isAllowed) event.result = PreLoginEvent.PreLoginComponentResult.denied(reason.toAdventure()) }
                finally { ports.inbound(event.connection).disconnect(reason) }
            },
        )
    }

    /** Common may perform blocking IO; role-enabled callers execute this only through auth submit. */
    private fun computePreLogin(event: PreLoginEvent, source: BedrockLogin): PortPreLogin {
        val port = PortPreLogin(ports.inbound(event.connection), event.username, event.uniqueId, source)
        if (!event.result.isAllowed) port.result = PortPreLogin.PreLoginComponentResult.denied(messages.parsed("login.internal-error"))
        common.onPreLogin(port)
        return port
    }

    /** Short native result application only; an existing native refusal can never be reopened. */
    private fun applyPreLogin(event: PreLoginEvent, port: PortPreLogin) {
        if (!event.result.isAllowed) return
        event.result = when {
            !port.result.isAllowed -> PreLoginEvent.PreLoginComponentResult.denied(requireNotNull(port.result.reason).toAdventure())
            port.result.onlineMode == true -> PreLoginEvent.PreLoginComponentResult.forceOnlineMode()
            port.result.onlineMode == false -> PreLoginEvent.PreLoginComponentResult.forceOfflineMode()
            else -> PreLoginEvent.PreLoginComponentResult.allowed()
        }
    }

    /** After native Floodgate EARLY, but before NORMAL skin storage resolves the selected UUID. */
        @Subscribe(priority = 2)
        fun onPrepareJavaProfile(event: GameProfileRequestEvent) {
            if (config.rolesEnabled) applyProfile(event, preparing = true)
            else if (bedrockProbe(event.connection) === BedrockLogin.Java) onGameProfile(event)
        }

    @Subscribe(order = PostOrder.LAST)
        fun onGameProfile(event: GameProfileRequestEvent) = applyProfile(event, preparing = false)

        private fun applyProfile(event: GameProfileRequestEvent, preparing: Boolean) {
            val profile = event.gameProfile
            val original = PortGameProfile(profile.id, profile.name,
                profile.properties.map { PortGameProfile.Property(it.name, it.value, it.signature ?: "") })
            val connection = ports.inbound(event.connection)
            val port = PortProfile(connection, original, bedrockProbe(event.connection))
            if (preparing) common.onPrepareGameProfile(port) else common.onGameProfile(port)
            if (port.gameProfile === original) return
            if (common.requiresMappedProfile(connection) || common.requiresRoleProfile(connection) &&
                (profile.id != port.gameProfile.id || profile.name != port.gameProfile.name)) {
                try { VelocityRoleLoginKeys.verify(event.connection, port.gameProfile.id) }
                catch (_: Exception) {
                    connection.disconnect(messages.parsed("login.internal-error"))
                    return
                }
            }
            event.gameProfile = GameProfile(port.gameProfile.id, port.gameProfile.name, port.gameProfile.properties.map {
                GameProfile.Property(it.name, it.value, it.signature)
            })
        }

    /** Install before native login completes, so credentials never become ordinary command events. */
    @Subscribe(order = PostOrder.FIRST)
    fun onProtectPrivateClaims(event: LoginEvent): EventTask? {
        if (!config.profileClaimsEnabled || !event.result.isAllowed) return null
        val shield = try {
            synchronized(privateClaimLock) {
                check(!privateClaimsClosed)
                privateClaims ?: createClaimShield().also { privateClaims = it }
            }
        } catch (_: Throwable) {
            event.result = ResultedEvent.ComponentResult.denied(messages.parsed("migration.claim-ingress-unavailable").toAdventure())
            return null
        }
        val installed = shield.install(event.player).handle { _, failure ->
            if (failure != null || !event.player.isActive) {
                event.result = ResultedEvent.ComponentResult.denied(messages.parsed("migration.claim-ingress-unavailable").toAdventure())
            } else common.onPrivateClaimIngressReady(event.player.sessionId)
            Unit
        }
        return EventTask.resumeWhenComplete(installed)
    }

    @Subscribe(order = PostOrder.LAST)
    fun onLogin(event: LoginEvent): EventTask? {
        val player = ports.player(event.player)
        if (!config.rolesEnabled || !event.result.isAllowed || !common.requiresRoleProfile(player)) {
            applyLogin(event, computeLogin(event, bedrockProbe(event.player)))
            return null
        }
        // Final role admission rechecks current roleOptions/relations over bounded auth IO.
        return checkNotNull(roleEvents).task(event.player.sessionId,
            active = { event.player.isActive && event.result.isAllowed },
            watchClosed = { action -> watchClosed(event.player, action) },
            source = { CompletableFuture.completedFuture(bedrockProbe(event.player)) },
            compute = { source -> computeLogin(event, source) },
            apply = { result -> applyLogin(event, result) },
            refuse = {
                val reason = messages.parsed("login.internal-error")
                try { if (event.result.isAllowed) event.result = ResultedEvent.ComponentResult.denied(reason.toAdventure()) }
                finally { player.disconnect(reason) }
            },
        )
    }

    private fun computeLogin(event: LoginEvent, source: BedrockLogin): PortLogin {
        val port = PortLogin(ports.player(event.player), source)
        if (!event.result.isAllowed) port.result = moe.ryuu.kyokouauth.proxy.ResultedEvent.ComponentResult.denied(messages.parsed("login.internal-error"))
        common.onLogin(port)
        return port
    }

    private fun applyLogin(event: LoginEvent, port: PortLogin) {
        if (event.result.isAllowed && !port.result.isAllowed)
            event.result = ResultedEvent.ComponentResult.denied(requireNotNull(port.result.reason).toAdventure())
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
        try {
            enforceRoleProperties(event.player)
            event.setInitialServer(port.initialServer?.let(ports::unwrap))
        } catch (_: Exception) {
            event.setInitialServer(null)
            event.player.disconnect(messages.parsed("login.internal-error").toAdventure())
        }
    }

    @Subscribe(order = PostOrder.LAST)
    fun onPreConnect(event: ServerPreConnectEvent) {
        val target = event.result.server.orElse(null)
        val port = moe.ryuu.kyokouauth.proxy.ServerPreConnectEvent(ports.player(event.player), target?.let(ports::server))
        common.onPreConnect(port)
        if (port.result.server.isEmpty) event.result = ServerPreConnectEvent.ServerResult.denied()
        else try { enforceRoleProperties(event.player) }
        catch (_: Exception) {
            event.result = ServerPreConnectEvent.ServerResult.denied()
            event.player.disconnect(messages.parsed("login.internal-error").toAdventure())
        }
    }

    /** Floodgate/skin plugins may replace textures after Login; restore only Gate-managed fields. */
    private fun enforceRoleProperties(native: Player) {
        val player = ports.player(native)
        if (!common.requiresRoleProfile(player)) return
        val expected = checkNotNull(common.currentRoleProfile(player)) { "Gate role profile is no longer current" }
        player.applyRoleProfile(expected).join()
    }

    @Subscribe(order = PostOrder.LAST)
    fun onKicked(event: KickedFromServerEvent) {
        val port = moe.ryuu.kyokouauth.proxy.KickedFromServerEvent(ports.player(event.player))
        common.onKicked(port)
        port.result?.let { event.result = KickedFromServerEvent.DisconnectPlayer.create(it.reason.toAdventure()) }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        roleEvents?.cancel(event.player.sessionId)
        try { common.onDisconnect(moe.ryuu.kyokouauth.proxy.DisconnectEvent(ports.player(event.player))) }
        finally { ports.forget(event.player) }
    }

    override fun onJoin(player: LimboPlayer) = common.onJoin(player)
    override fun onLeave(player: LimboPlayer) = common.onLeave(player)
    override fun onRoleSelect(player: LimboPlayer, generation: UUID, optionId: String) =
        common.onRoleSelect(player, generation, optionId)

    companion object {
        const val LIMBO_SERVER_NAME = SharedGate.LIMBO_SERVER_NAME
        internal const val CONNECTION_PROPERTY = SharedGate.CONNECTION_PROPERTY
    }
}
