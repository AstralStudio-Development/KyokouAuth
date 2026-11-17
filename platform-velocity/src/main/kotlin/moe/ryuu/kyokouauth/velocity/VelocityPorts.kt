package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ConnectionRequestBuilder
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import java.net.InetSocketAddress
import moe.ryuu.kyokouauth.core.text.RichText
import moe.ryuu.kyokouauth.proxy.ProxyLog
import moe.ryuu.kyokouauth.proxy.CommandSource
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import moe.ryuu.kyokouauth.proxy.Player as PortPlayer
import moe.ryuu.kyokouauth.proxy.RegisteredServer as PortServer
import moe.ryuu.kyokouauth.proxy.InboundConnection as PortConnection
import moe.ryuu.kyokouauth.proxy.ConnectionRequestBuilder as PortRequest

/** One wrapper per actual Player, so shared-flow identity checks remain exact-reference checks. */
internal class VelocityPorts(private val proxy: ProxyServer,
                             private val roleTransfer: VelocityRoleTransfer? = null) : moe.ryuu.kyokouauth.proxy.ProxyServer {
    private data class ServerBinding(val native: RegisteredServer, val port: PortServer)
    private val servers = ConcurrentHashMap<String, ServerBinding>()
    private val engines = ConcurrentHashMap<String, ServerBinding>()
    private val privateNames = ConcurrentHashMap.newKeySet<String>()
    private val players = ConcurrentHashMap<UUID, VelocityPlayer>()

    /** Raw targets are usable by connection requests, but absent from native /server and discovery. */
    fun registerEngine(protocol: Int, address: InetSocketAddress): PortServer {
        require(protocol in 767..777)
        val name = "${LoginGate.LIMBO_SERVER_NAME}-$protocol"
        privateNames += name
        check(proxy.getServer(name).isEmpty && !engines.containsKey(name)) { "Reserved internal server '$name' is already in use" }
        val native = proxy.createRawRegisteredServer(ServerInfo(name, address))
        val binding = ServerBinding(native, PortServer(name))
        check(engines.putIfAbsent(name, binding) == null) { "Internal endpoint was already created" }
        return binding.port
    }

    fun unregisterEngines() { engines.clear() }

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

    fun server(native: RegisteredServer): PortServer {
        engines[native.serverInfo.name]?.takeIf { it.native === native }?.let { return it.port }
        return servers.compute(native.serverInfo.name) { _, previous ->
            previous?.takeIf { it.native === native } ?: ServerBinding(native, PortServer(native.serverInfo.name))
        }!!.port
    }

    fun unwrap(port: PortServer): RegisteredServer {
        if (port.name in privateNames) {
            val binding = engines[port.name]
            check(binding != null && binding.port === port && proxy.getServer(port.name).isEmpty) { "Unknown, replaced or stale private endpoint" }
            return binding.native
        }
        return servers[port.name]?.takeIf { it.port === port }?.native
            ?: error("Unknown or stale proxy server registration")
    }

    override fun getServer(name: String): Optional<PortServer> {
        if (name in privateNames) {
            // A retired endpoint cannot be revived by publishing its old object as a public server.
            val binding = engines[name]
            return if (binding != null && proxy.getServer(name).isEmpty) Optional.of(binding.port) else Optional.empty()
        }
        return proxy.getServer(name).map { server(it) }
    }
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
        override fun transferRole(host: String, port: Int, cookie: ByteArray?) =
            roleTransfer?.transfer(native, host, port, cookie)
                ?: java.util.concurrent.CompletableFuture.failedFuture<Unit>(IllegalStateException("Role transfer is unavailable"))
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
