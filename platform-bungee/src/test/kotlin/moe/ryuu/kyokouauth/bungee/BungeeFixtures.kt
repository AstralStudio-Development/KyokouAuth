package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.proxy.GameProfile
import moe.ryuu.kyokouauth.proxy.LoginGate
import net.md_5.bungee.api.*
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.config.*
import net.md_5.bungee.api.connection.PendingConnection
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.plugin.PluginManager
import net.md_5.bungee.api.scheduler.TaskScheduler
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

internal inline fun <reified T> nativeStub(crossinline call: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "test-${T::class.java.simpleName}"
            else -> call(method.name, args ?: emptyArray())
        }
    } as T

internal class PendingFixture(val name: String = "OracleLocal", val protocol: Int = 777) {
    var uuid: UUID? = BungeeProfileBridge.offlineId(name)
    var online = false
    var connected = true
    var disconnects = 0
    val connects = mutableListOf<ServerInfo>()
    val setterOrder = mutableListOf<String>()
    var profile = GameProfile(checkNotNull(uuid), name, listOf(GameProfile.Property("textures", "preserved", "signed")))
    val pending = nativeStub<PendingConnection> { method, args -> when (method) {
        "getName" -> name
        "getVersion" -> protocol
        "getSocketAddress", "getAddress" -> InetSocketAddress("127.0.0.1", 43123)
        "getUniqueId" -> uuid
        "setUniqueId" -> { check(!online); setterOrder += "uuid"; uuid = args[0] as UUID; null }
        "isOnlineMode" -> online
        "setOnlineMode" -> { online = args[0] as Boolean; setterOrder += "online:$online"; null }
        "isConnected" -> connected
        "disconnect" -> { connected = false; disconnects++; null }
        else -> error("Unexpected PendingConnection.$method")
    } }
    val player = nativeStub<ProxiedPlayer> { method, args -> when (method) {
        "getPendingConnection" -> pending
        "getName" -> name
        "getUniqueId" -> uuid
        "isConnected" -> connected
        "getLocale" -> Locale.SIMPLIFIED_CHINESE
        "getServer" -> null
        "hasPermission" -> true
        "sendMessage" -> null
        "disconnect" -> { connected = false; disconnects++; null }
        "connect" -> {
            connects += args[0] as ServerInfo
            @Suppress("UNCHECKED_CAST")
            (args.getOrNull(1) as? Callback<Boolean>)?.done(true, null); null
        }
        else -> error("Unexpected ProxiedPlayer.$method")
    } }
}

internal class TestProfiles : ProfileAccess {
    private val values = java.util.IdentityHashMap<PendingConnection, GameProfile>()
    var failApply = false
    fun add(fixture: PendingFixture) { values[fixture.pending] = fixture.profile }
    override fun read(pending: PendingConnection): GameProfile = checkNotNull(values[pending])
    override fun apply(pending: PendingConnection, profile: GameProfile) {
        check(!failApply) { "injected profile failure" }
        check(profile.id == pending.uniqueId)
        values[pending] = profile
    }
}

internal class TestProxy : ProxyServer() {
    val registered = linkedMapOf<String, ServerInfo>()
    val online = mutableListOf<ProxiedPlayer>()
    private val configuration = nativeStub<ProxyConfig> { method, _ -> when (method) {
        "getListeners" -> emptyList<ListenerInfo>()
        "isIpForward" -> true
        else -> error("Unexpected ProxyConfig.$method")
    } }
    override fun constructServerInfo(name: String, address: InetSocketAddress, motd: String, restricted: Boolean): ServerInfo =
        nativeStub { method, _ -> when (method) {
            "getName" -> name
            "getAddress", "getSocketAddress" -> address
            "getMotd" -> motd
            "isRestricted" -> restricted
            else -> error("Unexpected ServerInfo.$method")
        } }
    override fun constructServerInfo(name: String, address: SocketAddress, motd: String, restricted: Boolean): ServerInfo =
        constructServerInfo(name, address as InetSocketAddress, motd, restricted)
    override fun getName() = "Bungee test"
    override fun getVersion() = "2111"
    override fun getTranslation(name: String, vararg args: Any?) = name
    override fun getLogger(): Logger = Logger.getLogger("Bungee test")
    override fun getPlayers(): Collection<ProxiedPlayer> = online
    override fun getPlayer(name: String): ProxiedPlayer? = online.find { it.name == name }
    override fun getPlayer(uuid: UUID): ProxiedPlayer? = online.find { it.uniqueId == uuid }
    override fun getServers(): MutableMap<String, ServerInfo> = registered
    override fun getServerInfo(name: String): ServerInfo? = registered[name]
    override fun getConfig(): ProxyConfig = configuration
    override fun getGameVersion() = "26.3"
    override fun getProtocolVersion() = 777
    override fun getOnlineCount() = online.size
    override fun getDisabledCommands(): Collection<String> = emptyList()
    override fun matchPlayer(name: String): Collection<ProxiedPlayer> = online.filter { it.name.contains(name) }
    override fun getChannels(): Collection<String> = emptyList()
    override fun getPluginsFolder(): File = error("unused")
    override fun getPluginManager(): PluginManager = error("unused")
    override fun getConfigurationAdapter(): ConfigurationAdapter = error("unused")
    override fun setConfigurationAdapter(adapter: ConfigurationAdapter) = Unit
    override fun getReconnectHandler(): ReconnectHandler = error("unused")
    override fun setReconnectHandler(handler: ReconnectHandler?) = Unit
    override fun stop() = Unit
    override fun stop(reason: String) = Unit
    override fun registerChannel(channel: String) = Unit
    override fun unregisterChannel(channel: String) = Unit
    override fun getConsole(): CommandSender = error("unused")
    override fun getScheduler(): TaskScheduler = error("unused")
    override fun broadcast(message: String) = Unit
    override fun broadcast(vararg message: BaseComponent) = Unit
    override fun broadcast(message: BaseComponent) = Unit
    override fun createTitle(): Title = error("unused")
    override fun unsafe(): ProxyServer.Unsafe = error("unused")
}

internal class TestTask(val task: () -> Unit) : ScheduledFuture<Unit> {
    var cancelled = false
    fun fire() { if (!cancelled) task() }
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean { cancelled = true; return true }
    override fun isCancelled() = cancelled
    override fun isDone() = cancelled
    override fun get() = Unit
    override fun get(timeout: Long, unit: TimeUnit) = Unit
    override fun getDelay(unit: TimeUnit): Long = 0
    override fun compareTo(other: Delayed): Int = 0
}

internal class TestServices : LoginGate.Services {
    val stableUuid: UUID = UUID(0L, 2535412345678901L)
    var decision: AuthCoordinator.Decision = AuthCoordinator.Decision.Verify(stableUuid,
        AuthCoordinator.Reason.SESSION_EXPIRED, false, null)
    var premiumBindings = 0
    var authorize = false
    val granted = mutableListOf<UUID>()
    val queue = ArrayDeque<() -> Unit>()
    val tasks = mutableListOf<TestTask>()
    fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
    override fun allow(key: String) = true
    override fun decide(name: GameName, address: String): AuthCoordinator.Decision = decision
    override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome {
        premiumBindings++
        return AuthCoordinator.ClaimOutcome.Refused(AuthCoordinator.RejectReason.INTERNAL_ERROR, "test refusal")
    }
    override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome =
        AuthCoordinator.ClaimOutcome.Bound(moe.ryuu.kyokouauth.core.storage.BoundIdentity.of(
            moe.ryuu.kyokouauth.core.identity.Identity.Microsoft(name, xuid, gamertag), 1L))
    override fun grantSession(uuid: UUID, address: String) { granted += uuid }
    override fun submit(task: () -> Unit) { queue += task }
    override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*> = TestTask(task).also { tasks += it }
    override fun scheduleAtFixedRate(periodMillis: Long, task: () -> Unit) = schedule(periodMillis, task)
    override fun begin() = DeviceCodeFlow.Challenge("TEST-CODE", "https://microsoft.com/link",
        "https://microsoft.com/link?otc=TEST-CODE", 300, 5, "synthetic-never-sent")
    override fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult =
        if (authorize) DeviceCodeFlow.PollResult.Authorized("synthetic", null, 300) else DeviceCodeFlow.PollResult.Pending
    override fun exchange(accessToken: String) = XboxLive.XstsToken("synthetic", stableUuid.leastSignificantBits, "Controlled", "synthetic")
}
