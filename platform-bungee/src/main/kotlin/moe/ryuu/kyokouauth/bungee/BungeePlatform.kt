package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.core.text.RichText
import moe.ryuu.kyokouauth.proxy.BedrockProof
import moe.ryuu.kyokouauth.proxy.CommandSource
import moe.ryuu.kyokouauth.proxy.ConnectionRequestBuilder
import moe.ryuu.kyokouauth.proxy.GameProfile
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
    @Volatile private var bedrockProof: BedrockProof? = null
    @Volatile private var roleProfile: GameProfile? = null
    val hasRoleProfile: Boolean get() = roleProfile != null
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

    /** Native profile interpretation only; authorization remains exclusively in LoginGate. */
    fun rememberBedrock(proof: BedrockProof) {
        check(proof.isCurrent() && !isOnlineMode && pending.uniqueId == proof.uniqueId && pending.name == proof.username)
        bedrockProof = proof
    }

    /** Gate-selected identity is distinct from the unmodified native Floodgate proof. */
    @Synchronized fun rememberRoleProfile(profile: GameProfile) {
        check(isActive && native == null && !isOnlineMode && matchesIdentity(profile.id, profile.name))
        val previous = roleProfile
        check(previous == null || previous.id == profile.id && previous.name == profile.name) { "Prepared role identity changed" }
        check(bedrockProof?.isCurrent() != false) { "Role source proof expired" }
        roleProfile = profile.copy(properties = profile.properties.toList())
    }

    fun currentRoleProfile(): GameProfile? = roleProfile?.takeIf {
        isActive && !isOnlineMode && matchesIdentity(it.id, it.name) && bedrockProof?.isCurrent() != false
    }

    fun currentBedrockProof(): BedrockProof? {
        val proof = bedrockProof ?: return null
        val expected = roleProfile
        return proof.takeIf {
            isActive && !isOnlineMode && it.isCurrent() &&
                matchesIdentity(expected?.id ?: it.uniqueId, expected?.name ?: it.username)
        }
    }

    private fun matchesIdentity(id: UUID, name: String): Boolean = pending.uniqueId == id && pending.name == name &&
        (native?.let { it.uniqueId == id && it.name == name } != false)

    @Synchronized fun markClosed() { closed = true; bedrockProof = null; roleProfile = null }
    override fun disconnect(message: RichText.Parsed) {
        markClosed()
        (native ?: pending).disconnect(message.toBungee())
    }
    override fun applyRoleProfile(profile: GameProfile): CompletableFuture<Unit> = try {
        synchronized(this) {
            check(platform.find(pending) === this && isActive && loggedIn && native != null &&
                matchesIdentity(profile.id, profile.name)) { "Role properties require the exact live Player identity" }
            check(bedrockProof?.isCurrent() != false) { "Role source proof expired" }
            val profiles = platform.profiles()
            val actual = profiles.read(pending)
            check(actual.id == profile.id && actual.name == profile.name) { "Native role profile identity changed" }
            val managed = setOf("textures", "kyokouauth:connection-id")
            val merged = actual.withProperties(actual.properties.filterNot { it.name in managed } +
                profile.properties.filter { it.name in managed })
            profiles.apply(pending, merged)
            check(isActive && matchesIdentity(profile.id, profile.name) && bedrockProof?.isCurrent() != false) {
                "Role connection or source changed during property application"
            }
            // A late native skin guard must now restore this selection, never the old chooser.
            roleProfile = merged.copy(properties = merged.properties.toList())
        }
        CompletableFuture.completedFuture(Unit)
    } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
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

internal class BungeePlatform(val proxy: NativeProxy, private val viaVersion: BungeeViaVersion? = null) : ProxyServer {
    @Volatile private var profileAccess: ProfileAccess? = null
    @Synchronized fun installProfiles(profiles: ProfileAccess) {
        check(profileAccess == null || profileAccess === profiles) { "Native profile bridge already installed" }
        profileAccess = profiles
    }
    fun profiles(): ProfileAccess = checkNotNull(profileAccess) { "Native profile bridge unavailable" }
    private val players = IdentityHashMap<PendingConnection, BungeePlayer>()
    private val servers = IdentityHashMap<ServerInfo, RegisteredServer>()
    private val nativeServers = IdentityHashMap<RegisteredServer, ServerInfo>()
    private val internalServers = IdentityHashMap<RegisteredServer, Int>()
    private val privateServers = HashMap<String, RegisteredServer>()
    // Keep reserved names after removal: an expired private handle must never become public.
    private val privateServerNames = HashSet<String>()

    @Synchronized fun pending(connection: PendingConnection): BungeePlayer = players.getOrPut(connection) { BungeePlayer(connection, this) }
    @Synchronized fun find(connection: PendingConnection): BungeePlayer? = players[connection]
    @Synchronized fun find(player: ProxiedPlayer): BungeePlayer? = players[player.pendingConnection]?.takeIf { it.native === player }
    @Synchronized fun connections(): List<BungeePlayer> = players.values.toList()
    @Synchronized fun remove(player: BungeePlayer) { if (players[player.pending] === player) players.remove(player.pending) }

    @Synchronized fun commonServer(info: ServerInfo): RegisteredServer? {
        val key = serverKey(info.name)
        if (key in privateServerNames) {
            val common = privateServers[key] ?: return null
            return common.takeIf { nativeServer(it) === info }
        }
        if (proxy.servers[info.name] !== info) return null
        return servers.getOrPut(info) { RegisteredServer(info.name).also { nativeServers[it] = info } }
    }
    @Synchronized fun nativeServer(server: RegisteredServer): ServerInfo? {
        val native = nativeServers[server] ?: return null
        val key = serverKey(native.name)
        return if (key in privateServerNames) native.takeIf {
            privateServers[key] === server && servers[it] === server &&
                internalServers.containsKey(server) && !hasPublicServer(it.name)
        } else native.takeIf { proxy.servers[it.name] === it }
    }
    @Synchronized fun registerEngine(protocol: Int, address: InetSocketAddress) {
        require(protocol in 767..777) { "Unsupported private engine protocol" }
        val name = "kyokouauth-limbo-$protocol"
        val key = serverKey(name)
        privateServerNames += key
        check(key !in privateServers) { "Private engine '$name' is already registered" }
        check(!hasPublicServer(name)) { "Reserved internal server '$name' is publicly registered" }
        val info = proxy.constructServerInfo(name, address, "KyokouAuth private verification engine", true)
        check(!hasPublicServer(name)) { "Private engine construction raced a public registration" }
        val common = RegisteredServer(name)
        viaVersion?.register(name, protocol)
        servers[info] = common
        nativeServers[common] = info
        internalServers[common] = protocol
        privateServers[key] = common
    }
    @Synchronized fun engineProtocol(server: RegisteredServer): Int? = internalServers[server]
    @Synchronized fun installPrivateProtocols() { viaVersion?.install() }
    @Synchronized fun prepareEngineConnection(server: RegisteredServer) {
        val protocol = internalServers[server] ?: return
        check(nativeServer(server) != null) { "Private engine handle is no longer current" }
        viaVersion?.prepare(server.name, protocol)
    }
    @Synchronized fun unregisterEngines() {
        try { viaVersion?.close() } finally {
            internalServers.keys.toList().forEach { common ->
                nativeServers.remove(common)?.let(servers::remove)
                privateServers.remove(serverKey(common.name), common)
            }
            internalServers.clear()
        }
    }
    @Synchronized override fun getServer(name: String): Optional<RegisteredServer> {
        val key = serverKey(name)
        if (key in privateServerNames) {
            return Optional.ofNullable(privateServers[key]?.takeIf { nativeServer(it) != null })
        }
        return Optional.ofNullable(proxy.getServerInfo(name)?.let(::commonServer))
    }

    private fun serverKey(name: String): String = name.lowercase(Locale.ROOT)
    private fun hasPublicServer(name: String): Boolean = proxy.getServerInfo(name) != null ||
        proxy.servers.any { (key, server) -> key.equals(name, ignoreCase = true) || server.name.equals(name, ignoreCase = true) }
    override fun getPlayer(name: String): Optional<Player> = Optional.ofNullable(proxy.getPlayer(name)?.let(::find))
    override fun getPlayer(uuid: UUID): Optional<Player> = Optional.ofNullable(proxy.getPlayer(uuid)?.let(::find))
    override val allPlayers: Collection<Player> get() = connections().filter { it.native != null && it.isActive }
    override val configuration: ProxyConfiguration = object : ProxyConfiguration {
        override val attemptConnectionOrder: List<String> get() = proxy.config.listeners.flatMap { it.serverPriority }.distinct()
    }
    fun source(sender: CommandSender): CommandSource = (sender as? ProxiedPlayer)?.let(::find) ?: object : CommandSource {
        override val isConsole get() = sender === proxy.console
        override fun hasPermission(permission: String) = sender.hasPermission(permission)
        override fun sendMessage(message: RichText.Parsed) { sender.sendMessage(message.toBungee()) }
    }
}
