package moe.ryuu.kyokouauth.limbo.session

import moe.ryuu.kyokouauth.limbo.*
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.geysermc.mcprotocollib.network.Session
import org.geysermc.mcprotocollib.network.event.session.*
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.data.ProtocolState
import org.geysermc.mcprotocollib.protocol.data.game.KnownPack
import org.geysermc.mcprotocollib.protocol.data.handshake.HandshakeIntent
import org.geysermc.mcprotocollib.protocol.data.status.*
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.*
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundKeepAlivePacket
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.*
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.*
import org.geysermc.mcprotocollib.protocol.packet.handshake.serverbound.ClientIntentionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.*
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.*
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.*
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.*
import org.geysermc.mcprotocollib.protocol.packet.status.clientbound.ClientboundStatusResponsePacket
import org.geysermc.mcprotocollib.protocol.packet.status.serverbound.ServerboundStatusRequestPacket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import moe.ryuu.kyokouauth.limbo.lifecycle.ConnectionLifecycle
import moe.ryuu.kyokouauth.limbo.lifecycle.ConnectionLifecycle.Phase

/** Owns transitions and timers; packet layout and framing belong exclusively to MCProtocolLib. */
internal class LimboConnection(
    private val session: Session,
    private val settings: LimboSettings,
    private val registry: RegistrySnapshot,
    private val world: LimboWorld,
    private val forwardingMode: ForwardingMode,
    private val listener: LimboListener,
    private val logger: LimboLogger,
    private val executor: ScheduledExecutorService,
    private val onlineCount: () -> Int,
) : SessionAdapter(), LimboPlayer {
    private val lifecycle = ConnectionLifecycle()
    private val queryId = RANDOM.nextInt(Int.MAX_VALUE)
    private val teleportId = RANDOM.nextInt(Int.MAX_VALUE - 1) + 1
    private var loginName = ""
    private var hostname = ""
    private var legacyActivity = false
    private val protocolVersion = GeneratedCodec.PROTOCOL
    private var identity: ForwardedIdentity? = null
    private var announced = false
    private var leaveAnnounced = false
    private var joinTimeout: ScheduledFuture<*>? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var selector: RoleSelectorMenu? = null
    private val selectorGenerations = HashSet<UUID>() // At most 127 openings before the connection fails closed.
    private var selectorWindow = 0
    private var lastSelectorWindow = 0
    private var selectorState = 0
    private var selectorVisible = false
    private var selectorResyncAt = 0L
    private var selectorReopen: ScheduledFuture<*>? = null
    private var selectorResync: ScheduledFuture<*>? = null
    override val name get() = requireNotNull(identity).profile.name!!
    override val uuid get() = requireNotNull(identity).profile.id!!
    override val address get() = requireNotNull(identity).address
    override val connectionId get() = requireNotNull(identity).connectionId

    override fun connected(event: ConnectedEvent) = execute {
        joinTimeout = executor.schedule({
            if (lifecycle.phase != Phase.READY && lifecycle.phase != Phase.CLOSED) disconnect(TextComponent("Login screen did not finish loading."))
        }, settings.joinTimeoutSeconds, TimeUnit.SECONDS)
    }

    override fun packetReceived(session: Session, packet: Packet) = execute {
        try { receive(packet) }
        catch (failure: Exception) {
            invalidateRoleSelector(false)
            logger.log("Limbo rejected ${packet.javaClass.simpleName} during ${lifecycle.phase}", null)
            disconnect(TextComponent("Invalid login-screen connection."))
        }
    }

    private fun receive(packet: Packet) {
        val protocol = session.packetProtocol as MinecraftProtocol
        when (lifecycle.phase) {
            Phase.HANDSHAKE -> {
                require(packet is ClientIntentionPacket)
                if (packet.intent == HandshakeIntent.STATUS) {
                    lifecycle.move(Phase.HANDSHAKE, Phase.STATUS)
                    protocol.setOutboundState(ProtocolState.STATUS)
                    session.switchInboundState { protocol.setInboundState(ProtocolState.STATUS) }
                } else {
                    require(packet.intent == HandshakeIntent.LOGIN)
                    lifecycle.move(Phase.HANDSHAKE, Phase.HELLO)
                    protocol.setOutboundState(ProtocolState.LOGIN)
                    session.switchInboundState { protocol.setInboundState(ProtocolState.LOGIN) }
                    hostname = packet.hostname
                    if (packet.protocolVersion != protocolVersion) disconnect(settings.messages.unsupportedVersion)
                }
            }
            Phase.STATUS -> when (packet) {
                is ServerboundStatusRequestPacket -> session.send(ClientboundStatusResponsePacket(ServerStatusInfo(
                    settings.motdComponent?.toLibrary() ?: Component.text(settings.motd), PlayerInfo(settings.maxPlayers, onlineCount(), emptyList()),
                    VersionInfo(GeneratedCodec.VERSION, protocolVersion), null, false)))
                is LimboPingRequest -> session.send(LimboPongResponse(packet.pingTime)) { close() }
                else -> error("Unexpected status packet")
            }
            Phase.HELLO -> {
                require(packet is ServerboundHelloPacket)
                loginName = packet.username
                // This only admits a candidate name into the forwarding handshake. HMAC/ticket
                // verification still binds the exact reserved alias to its trusted placeholder UUID.
                require(VALID_NAME.matches(loginName) ||
                    moe.ryuu.kyokouauth.limbo.VerificationProfileName.isCandidate(loginName))
                when (val mode = forwardingMode) {
                    is ForwardingMode.VelocityModern -> {
                        require('\u0000' !in hostname) { "Legacy forwarding is forbidden in modern mode" }
                        lifecycle.move(Phase.HELLO, Phase.FORWARDING)
                        session.send(ClientboundCustomQueryPacket(queryId, Key.key("velocity:player_info"), byteArrayOf(1)))
                    }
                    is ForwardingMode.PrivateTicket -> {
                        identity = ForwardedIdentity.redeem(hostname, mode.authority, loginName, protocolVersion)
                        lifecycle.move(Phase.HELLO, Phase.LOGIN_ACK)
                        session.send(ProtocolPackets.loginFinished(requireNotNull(identity).profile))
                    }
                }
            }
            Phase.FORWARDING -> {
                require(packet is ServerboundCustomQueryAnswerPacket && packet.transactionId == queryId)
                val modern = forwardingMode as ForwardingMode.VelocityModern
                identity = ForwardedIdentity.verify(requireNotNull(packet.data), modern.secret, loginName)
                lifecycle.move(Phase.FORWARDING, Phase.LOGIN_ACK)
                session.send(ProtocolPackets.loginFinished(requireNotNull(identity).profile))
            }
            Phase.LOGIN_ACK -> {
                require(packet is ServerboundLoginAcknowledgedPacket)
                lifecycle.move(Phase.LOGIN_ACK, Phase.KNOWN_PACKS)
                protocol.setOutboundState(ProtocolState.CONFIGURATION)
                session.switchInboundState { protocol.setInboundState(ProtocolState.CONFIGURATION) }
                session.send(ClientboundUpdateEnabledFeaturesPacket(arrayOf(Key.key("minecraft:vanilla"))))
                session.send(ClientboundSelectKnownPacks(emptyList()))
            }
            Phase.KNOWN_PACKS -> when (packet) {
                is ServerboundSelectKnownPacks -> {
                    require(packet.knownPacks.isEmpty()) { "Unadvertised known pack" }
                    lifecycle.move(Phase.KNOWN_PACKS, Phase.CONFIG_ACK)
                    registry.registries.forEach(session::send)
                    session.send(registry.tags)
                    session.send(ClientboundFinishConfigurationPacket())
                }
                else -> requireConfigurationAuxiliary(packet)
            }
            Phase.CONFIG_ACK -> when (packet) {
                is ServerboundFinishConfigurationPacket -> {
                    lifecycle.move(Phase.CONFIG_ACK, Phase.JOINING)
                    protocol.setOutboundState(ProtocolState.GAME)
                    session.switchInboundState { protocol.setInboundState(ProtocolState.GAME) }
                    world.joinPackets(teleportId).forEach(session::send)
                    heartbeat = executor.scheduleAtFixedRate({ tick() }, 0, settings.keepAliveSeconds, TimeUnit.SECONDS)
                }
                else -> requireConfigurationAuxiliary(packet)
            }
            Phase.JOINING, Phase.READY -> when (packet) {
                is ServerboundKeepAlivePacket -> lifecycle.pong(packet.pingId)
                is ServerboundChunkBatchReceivedPacket -> lifecycle.acknowledgeBatch(packet.desiredChunksPerTick)
                is ServerboundAcceptTeleportationPacket -> lifecycle.acknowledgeTeleport(packet.id, teleportId)
                is LimboPingRequest -> session.send(LimboPongResponse(packet.pingTime))
                is ServerboundContainerClickPacket -> if (lifecycle.phase == Phase.READY) clickRole(packet)
                is ServerboundContainerClosePacket -> if (lifecycle.phase == Phase.READY) closeRoleFromClient(packet.containerId)
                else -> when {
                    ProtocolPackets.hasPlayerLoaded && packet.javaClass.simpleName == "ServerboundPlayerLoadedPacket" -> ready()
                    !ProtocolPackets.hasPlayerLoaded && packet.javaClass.simpleName.startsWith("ServerboundMovePlayer") -> {
                        if (lifecycle.teleportAcknowledged && lifecycle.batchAcknowledged) legacyActivity = true
                        if (legacyActivity) ready()
                    }
                    packet is IgnoredPlayPacket -> Unit // Only generated known nonterminal GAME payloads bypass field decoding.
                    else -> error("Unexpected typed play packet")
                }
            }
            Phase.CLOSED -> Unit
        }
    }

    private fun ready() {
        if (lifecycle.loaded()) {
            joinTimeout?.cancel(false)
            settings.sidebar?.let { SidebarPackets.show(session, it) }
            announced = true
            logger.log("Limbo ready for $name ($connectionId), protocol $protocolVersion", null)
            listener.onJoin(this)
        }
    }

    private fun requireConfigurationAuxiliary(packet: Packet) {
        require(packet.javaClass.simpleName in setOf("ServerboundClientInformationPacket", "ServerboundCustomPayloadPacket", "ServerboundPongPacket"))
    }

    private fun tick() {
        if (lifecycle.phase != Phase.JOINING && lifecycle.phase != Phase.READY) return
        try {
            val now = System.nanoTime()
            if (lifecycle.needsPing(now, TimeUnit.SECONDS.toNanos(settings.keepAliveTimeoutSeconds))) {
                val id = RANDOM.nextLong()
                lifecycle.ping(id, now)
                session.send(ClientboundKeepAlivePacket(id))
            }
        } catch (_: Exception) { disconnect(TextComponent("Login screen keepalive timed out.")) }
    }

    override fun sendMessage(message: TextComponent) = execute {
        if (lifecycle.phase == Phase.READY) session.send(ClientboundSystemChatPacket(message.toLibrary(), false))
    }
    override fun showTitle(title: TextComponent, subtitle: TextComponent?, fadeIn: Int, stay: Int, fadeOut: Int) = execute {
        if (lifecycle.phase == Phase.READY) {
            require(fadeIn >= 0 && stay >= 0 && fadeOut >= 0)
            session.send(ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut))
            subtitle?.let { session.send(ClientboundSetSubtitleTextPacket(it.toLibrary())) }
            session.send(ClientboundSetTitleTextPacket(title.toLibrary()))
        }
    }
    override fun clearTitle() = execute {
        if (lifecycle.phase == Phase.READY) session.send(ClientboundClearTitlesPacket(true))
    }
    override fun openRoleSelector(menu: RoleSelectorMenu) {
        require(menu.microsoft.optionId.isNotBlank() && menu.microsoft.optionId.length <= 128)
        require(menu.premium.optionId.isNotBlank() && menu.premium.optionId.length <= 128)
        require(menu.microsoft.optionId != menu.premium.optionId)
        for (item in listOf(menu.microsoft, menu.premium)) {
            require(VALID_NAME.matches(item.profileName))
            require((item.textureValue?.length ?: 0) <= 8192 && (item.textureSignature?.length ?: 0) <= 8192)
            require(item.textureSignature == null || item.textureValue != null)
            require(item.lore.size <= 8)
        }
        val snapshot = menu.copy(microsoft = menu.microsoft.copy(lore = menu.microsoft.lore.toList()),
            premium = menu.premium.copy(lore = menu.premium.lore.toList()))
        execute {
            if (lifecycle.phase != Phase.READY || !session.isConnected) return@execute
            // A refresh must not make an already queued callback valid under a changed option map.
            if (!selectorGenerations.add(snapshot.generation)) return@execute
            try {
                invalidateRoleSelector(true)
                selector = snapshot
                showRoleSelector()
            } catch (_: Exception) {
                invalidateRoleSelector(false)
                logger.log("Limbo role selector could not be opened", null)
                disconnect(TextComponent("Role selector unavailable. Please reconnect."))
            }
        }
    }

    override fun closeRoleSelector() = execute { invalidateRoleSelector(true) }

    private fun showRoleSelector() {
        val menu = selector ?: return
        if (lifecycle.phase != Phase.READY || !session.isConnected) return
        // 767 sends a signed byte for Click.containerId. Never wrap/reuse an ID on this connection.
        if (lastSelectorWindow >= 127) {
            disconnect(TextComponent("Role selector was closed too often. Please reconnect."))
            return
        }
        selectorWindow = ++lastSelectorWindow
        selectorState = 0
        selectorVisible = true
        selectorResyncAt = 0L
        session.send(RoleSelectorPackets.open(selectorWindow, menu))
        syncRoleSelector(false)
        selectorResyncAt = 0L // Allow the first rejected prediction to receive an immediate correction.
    }

    private fun syncRoleSelector(throttle: Boolean = true) {
        val menu = selector ?: return
        if (!selectorVisible || !session.isConnected) return
        val now = System.nanoTime()
        if (throttle && now < selectorResyncAt) {
            if (selectorResync == null) {
                val window = selectorWindow
                selectorResync = executor.schedule({
                    selectorResync = null
                    if (selector === menu && selectorVisible && selectorWindow == window && lifecycle.phase == Phase.READY) {
                        try { syncRoleSelector(false) }
                        catch (_: Exception) {
                            invalidateRoleSelector(false)
                            disconnect(TextComponent("Role selector unavailable. Please reconnect."))
                        }
                    }
                }, selectorResyncAt - now, TimeUnit.NANOSECONDS)
            }
            return
        }
        selectorResync?.cancel(false)
        selectorResync = null
        selectorResyncAt = now + TimeUnit.MILLISECONDS.toNanos(100)
        if (selectorState == 0) selectorState = 1
        // The immutable inventory has not changed: corrections retain its state ID, never accept client state.
        // Includes menu, player inventory and cursor; changedSlots/carriedItem are only predictions.
        session.send(RoleSelectorPackets.playerInventory(selectorState))
        session.send(RoleSelectorPackets.content(selectorWindow, selectorState, menu))
    }

    private fun clickRole(packet: ServerboundContainerClickPacket) {
        val menu = selector ?: return
        if (!session.isConnected) { invalidateRoleSelector(false); return }
        if (!selectorVisible) return
        if (packet.containerId != selectorWindow) { syncRoleSelector(); return }
        if (packet.stateId != selectorState || packet.action != ContainerActionType.CLICK_ITEM ||
            packet.param != ClickItemAction.LEFT_CLICK && packet.param != ClickItemAction.RIGHT_CLICK) {
            syncRoleSelector()
            return
        }
        val option = when (packet.slot) {
            RoleSelectorPackets.MICROSOFT_SLOT -> menu.microsoft.optionId
            RoleSelectorPackets.PREMIUM_SLOT -> menu.premium.optionId
            else -> { syncRoleSelector(); return }
        }
        // Invalidate before sends as well as the callback: even a failed serializer cannot leave a selectable menu.
        val window = selectorWindow
        val state = selectorState
        invalidateRoleSelector(false)
        session.send(RoleSelectorPackets.playerInventory(state))
        session.send(RoleSelectorPackets.content(window, state, menu))
        session.send(RoleSelectorPackets.close(window))
        if (session.isConnected && lifecycle.phase == Phase.READY) listener.onRoleSelect(this, menu.generation, option)
    }

    private fun closeRoleFromClient(window: Int) {
        val menu = selector ?: return
        if (!selectorVisible || window != selectorWindow) return
        selectorVisible = false
        if (selectorReopen != null) return
        selectorReopen = executor.schedule({
            selectorReopen = null
            if (selector === menu && lifecycle.phase == Phase.READY && session.isConnected) showRoleSelector()
        }, 250, TimeUnit.MILLISECONDS)
    }

    private fun invalidateRoleSelector(sendClose: Boolean) {
        selectorReopen?.cancel(false)
        selectorReopen = null
        selectorResync?.cancel(false)
        selectorResync = null
        val window = if (selectorVisible) selectorWindow else null
        val menu = selector
        val state = selectorState
        selectorVisible = false
        selector = null
        if (sendClose && session.isConnected) {
            if (menu != null) session.send(RoleSelectorPackets.playerInventory(state))
            if (window != null) {
                if (menu != null) session.send(RoleSelectorPackets.content(window, state, menu))
                session.send(RoleSelectorPackets.close(window))
            }
        }
    }

    override fun disconnect(reason: TextComponent) = execute {
        if (lifecycle.phase != Phase.CLOSED) {
            val component = reason.toLibrary()
            val packet = when ((session.packetProtocol as MinecraftProtocol).outboundState) {
                ProtocolState.LOGIN -> ClientboundLoginDisconnectPacket(component)
                ProtocolState.CONFIGURATION, ProtocolState.GAME -> ClientboundDisconnectPacket(component)
                else -> null
            }
            finish()
            if (packet != null && session.isConnected) session.send(packet) { session.channel.close() }
            else session.channel.close()
        }
    }
    override fun close() = execute { finish(); session.channel.close() }
    override fun disconnected(event: DisconnectedEvent) = execute {
        if (event.cause != null) logger.log("Limbo transport closed (${event.cause!!::class.java.simpleName})", null)
        finish()
    }
    private fun finish() {
        // A live Limbo-initiated close can still remove the objective. A proxy switch instead
        // replaces the client's play listener through Configuration, discarding its scoreboard.
        if (lifecycle.phase == Phase.READY && settings.sidebar != null && session.isConnected) {
            runCatching { SidebarPackets.remove(session) }
        }
        invalidateRoleSelector(false)
        selectorGenerations.clear()
        lifecycle.close()
        joinTimeout?.cancel(false)
        heartbeat?.cancel(false)
        if (announced && !leaveAnnounced) {
            leaveAnnounced = true
            try { listener.onLeave(this) } catch (failure: Throwable) { logger.log("Limbo leave callback failed", failure) }
        }
    }
    private fun execute(action: () -> Unit) {
        if (!executor.isShutdown) executor.execute(action)
    }
    companion object {
        private val RANDOM = SecureRandom()
        private val VALID_NAME = Regex("[A-Za-z0-9_]{3,16}")
    }
}
