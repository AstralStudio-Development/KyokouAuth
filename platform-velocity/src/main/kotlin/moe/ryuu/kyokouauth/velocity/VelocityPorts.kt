package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ConnectionRequestBuilder
import com.velocitypowered.api.proxy.server.RegisteredServer
import moe.ryuu.kyokouauth.core.text.RichText
import moe.ryuu.kyokouauth.proxy.ProxyLog
import moe.ryuu.kyokouauth.proxy.CommandSource
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import moe.ryuu.kyokouauth.proxy.Player as PortPlayer
import moe.ryuu.kyokouauth.proxy.RegisteredServer as PortServer
import moe.ryuu.kyokouauth.proxy.InboundConnection as PortConnection
import moe.ryuu.kyokouauth.proxy.ConnectionRequestBuilder as PortRequest

/** One wrapper per actual Player, so shared-flow identity checks remain exact-reference checks. */
internal class VelocityPorts(private val proxy: ProxyServer) : moe.ryuu.kyokouauth.proxy.ProxyServer {
    private data class ServerBinding(val native: RegisteredServer, val port: PortServer)
    private val servers = ConcurrentHashMap<String, ServerBinding>()
    private val players = ConcurrentHashMap<UUID, VelocityPlayer>()

    fun player(native: Player): PortPlayer = players.compute(native.sessionId) { _, previous ->
        previous?.takeIf { it.native === native } ?: VelocityPlayer(native)
    }!!

    fun inbound(native: InboundConnection): PortConnection = if (native is Player) player(native) else object : PortConnection {
        override val sessionId get() = native.sessionId
        override val isActive get() = native.isActive
        override val remoteAddress get() = native.remoteAddress
        override fun disconnect(message: RichText.Parsed) {
            native.javaClass.getMethod("disconnect", Component::class.java).invoke(native, message.toAdventure())
        }
    }

    fun forget(native: Player) {
        players.computeIfPresent(native.sessionId) { _, previous -> previous.takeUnless { it.native === native } }
    }

    fun commandSource(native: com.velocitypowered.api.command.CommandSource): CommandSource =
        if (native is Player) player(native) else object : CommandSource {
            override fun hasPermission(permission: String) = native.hasPermission(permission)
            override fun sendMessage(message: RichText.Parsed) { native.sendMessage(message.toAdventure()) }
        }

    fun server(native: RegisteredServer): PortServer = servers.compute(native.serverInfo.name) { _, previous ->
        previous?.takeIf { it.native === native } ?: ServerBinding(native, PortServer(native.serverInfo.name))
    }!!.port

    fun unwrap(port: PortServer): RegisteredServer = servers[port.name]?.takeIf { it.port === port }?.native
        ?: error("Unknown or stale proxy server registration")

    override fun getServer(name: String) = proxy.getServer(name).map { server(it) }
    override fun getPlayer(name: String) = proxy.getPlayer(name).map { player(it) }
    override fun getPlayer(uuid: UUID) = proxy.getPlayer(uuid).map { player(it) }
    override val allPlayers get() = proxy.allPlayers.map { player(it) }
    override val configuration = object : moe.ryuu.kyokouauth.proxy.ProxyConfiguration {
        override val attemptConnectionOrder get() = proxy.configuration.attemptConnectionOrder
    }

    private inner class VelocityPlayer(val native: Player) : PortPlayer {
        override val sessionId get() = native.sessionId
        override val isActive get() = native.isActive
        override val remoteAddress get() = native.remoteAddress
        override val uniqueId get() = native.uniqueId
        override val username get() = native.username
        override val isOnlineMode get() = native.isOnlineMode
        override val effectiveLocale get() = native.effectiveLocale ?: Locale.ENGLISH
        override val protocolVersion get() = native.protocolVersion?.protocol ?: -1
        override fun hasPermission(permission: String) = native.hasPermission(permission)
        override fun sendMessage(message: RichText.Parsed) { native.sendMessage(message.toAdventure()) }
        override fun disconnect(message: RichText.Parsed) { native.disconnect(message.toAdventure()) }
        override fun createConnectionRequest(target: PortServer) = object : PortRequest {
            override fun connect() = native.createConnectionRequest(unwrap(target)).connect().thenApply { result ->
                PortRequest.Result(when (result.status) {
                    ConnectionRequestBuilder.Status.SUCCESS -> PortRequest.Status.SUCCESS
                    ConnectionRequestBuilder.Status.ALREADY_CONNECTED -> PortRequest.Status.ALREADY_CONNECTED
                    else -> PortRequest.Status.FAILED
                })
            }
        }
    }
}

internal fun Logger.toProxyLog() = ProxyLog { level, message, failure ->
    when (level) {
        ProxyLog.Level.INFO -> if (failure == null) info(message) else info(message, failure)
        ProxyLog.Level.WARN -> if (failure == null) warn(message) else warn(message, failure)
        ProxyLog.Level.ERROR -> if (failure == null) error(message) else error(message, failure)
    }
}
