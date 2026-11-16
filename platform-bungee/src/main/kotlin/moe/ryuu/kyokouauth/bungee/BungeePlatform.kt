package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.core.text.RichText
import moe.ryuu.kyokouauth.proxy.CommandSource
import moe.ryuu.kyokouauth.proxy.ConnectionRequestBuilder
import moe.ryuu.kyokouauth.proxy.Player
import moe.ryuu.kyokouauth.proxy.ProxyConfiguration
import moe.ryuu.kyokouauth.proxy.ProxyServer
import moe.ryuu.kyokouauth.proxy.RegisteredServer
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.ProxyServer as NativeProxy
import net.md_5.bungee.api.config.ServerInfo
import net.md_5.bungee.api.connection.PendingConnection
import net.md_5.bungee.api.connection.ProxiedPlayer
import java.net.InetSocketAddress
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** An object belongs to a physical frontend connection, never a username/identity UUID lookup. */
internal class BungeePlayer(val pending: PendingConnection, private val platform: BungeePlatform) : Player {
    override val sessionId: UUID = UUID.randomUUID()
    override val remoteAddress: InetSocketAddress = pending.socketAddress as? InetSocketAddress
        ?: error("Only IP connections are supported")
    @Volatile var native: ProxiedPlayer? = null
        private set
    @Volatile private var closed = false
    @Volatile var loggedIn = false
    @Volatile var requestedOnline: Boolean? = null
    // Kept until departure or the first real-backend connection, even after atomic redemption.
    @Volatile var engineAttempt: RegisteredServer? = null
    override val isActive get() = !closed && pending.isConnected && (native?.isConnected != false)
    override val uniqueId: UUID get() = native?.uniqueId ?: pending.uniqueId ?: BungeeProfileBridge.offlineId(username)
    override val username: String get() = native?.name ?: pending.name
    override val isOnlineMode: Boolean get() = pending.isOnlineMode
    override val effectiveLocale: Locale get() = native?.locale ?: Locale.ROOT
    override val protocolVersion: Int get() = pending.version

    @Synchronized fun bind(player: ProxiedPlayer) {
        check(isActive && player.pendingConnection === pending && (native == null || native === player)) {
            "PostLogin changed physical frontend connection"
        }
        check(player.uniqueId == pending.uniqueId && player.name == pending.name)
        native = player
    }

    fun markClosed() { closed = true }
    override fun disconnect(message: RichText.Parsed) {
        closed = true
        (native ?: pending).disconnect(message.toBungee())
    }
    override fun hasPermission(permission: String): Boolean = native?.hasPermission(permission) ?: false
    override fun sendMessage(message: RichText.Parsed) { native?.sendMessage(message.toBungee()) }
    override fun createConnectionRequest(target: RegisteredServer): ConnectionRequestBuilder = object : ConnectionRequestBuilder {
        override fun connect(): CompletableFuture<ConnectionRequestBuilder.Result> {
            val result = CompletableFuture<ConnectionRequestBuilder.Result>()
            val player = native
            val info = platform.nativeServer(target)
            if (!isActive || player == null || info == null) {
                result.complete(ConnectionRequestBuilder.Result(ConnectionRequestBuilder.Status.FAILED))
            } else if (player.server?.info === info) {
                result.complete(ConnectionRequestBuilder.Result(ConnectionRequestBuilder.Status.ALREADY_CONNECTED))
            } else try {
                player.connect(info, { success, error ->
                    // This is TCP connection completion, never a trustworthy Limbo READY signal.
                    result.complete(ConnectionRequestBuilder.Result(if (success == true && error == null)
                        ConnectionRequestBuilder.Status.SUCCESS else ConnectionRequestBuilder.Status.FAILED))
                })
            } catch (failure: Exception) { result.completeExceptionally(failure) }
            return result
        }
    }
}

internal class BungeePlatform(val proxy: NativeProxy) : ProxyServer {
    private val players = IdentityHashMap<PendingConnection, BungeePlayer>()
    private val servers = IdentityHashMap<ServerInfo, RegisteredServer>()
    private val nativeServers = IdentityHashMap<RegisteredServer, ServerInfo>()
    private val internalServers = IdentityHashMap<RegisteredServer, Int>()

    @Synchronized fun pending(connection: PendingConnection): BungeePlayer = players.getOrPut(connection) { BungeePlayer(connection, this) }
    @Synchronized fun find(connection: PendingConnection): BungeePlayer? = players[connection]
    @Synchronized fun find(player: ProxiedPlayer): BungeePlayer? = players[player.pendingConnection]?.takeIf { it.native === player }
    @Synchronized fun connections(): List<BungeePlayer> = players.values.toList()
    @Synchronized fun remove(player: BungeePlayer) { if (players[player.pending] === player) players.remove(player.pending) }

    @Synchronized fun commonServer(info: ServerInfo): RegisteredServer? {
        if (proxy.servers[info.name] !== info) return null
        return servers.getOrPut(info) { RegisteredServer(info.name).also { nativeServers[it] = info } }
    }
    @Synchronized fun nativeServer(server: RegisteredServer): ServerInfo? = nativeServers[server]?.takeIf { proxy.servers[it.name] === it }
    @Synchronized fun registerEngine(protocol: Int, address: InetSocketAddress) {
        val name = "kyokouauth-limbo-$protocol"
        check(proxy.getServerInfo(name) == null) { "Reserved internal server '$name' is already registered" }
        val info = proxy.constructServerInfo(name, address, "KyokouAuth private verification engine", true)
        check(proxy.servers.putIfAbsent(name, info) == null) { "Internal registration raced another plugin" }
        val common = checkNotNull(commonServer(info))
        internalServers[common] = protocol
    }
    @Synchronized fun engineProtocol(server: RegisteredServer): Int? = internalServers[server]
    @Synchronized fun unregisterEngines() {
        internalServers.keys.toList().forEach { common ->
            nativeServers.remove(common)?.let { native ->
                proxy.servers.remove(native.name, native)
                servers.remove(native)
            }
        }
        internalServers.clear()
    }
    override fun getServer(name: String): Optional<RegisteredServer> = Optional.ofNullable(proxy.getServerInfo(name)?.let(::commonServer))
    override fun getPlayer(name: String): Optional<Player> = Optional.ofNullable(proxy.getPlayer(name)?.let(::find))
    override fun getPlayer(uuid: UUID): Optional<Player> = Optional.ofNullable(proxy.getPlayer(uuid)?.let(::find))
    override val allPlayers: Collection<Player> get() = connections().filter { it.native != null && it.isActive }
    override val configuration: ProxyConfiguration = object : ProxyConfiguration {
        override val attemptConnectionOrder: List<String> get() = proxy.config.listeners.flatMap { it.serverPriority }.distinct()
    }
    fun source(sender: CommandSender): CommandSource = (sender as? ProxiedPlayer)?.let(::find) ?: object : CommandSource {
        override fun hasPermission(permission: String) = sender.hasPermission(permission)
        override fun sendMessage(message: RichText.Parsed) { sender.sendMessage(message.toBungee()) }
    }
}
