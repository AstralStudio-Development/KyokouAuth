package moe.ryuu.kyokouauth.bungee

import io.netty.channel.Channel
import io.netty.util.AttributeKey
import moe.ryuu.kyokouauth.proxy.BedrockFailure
import moe.ryuu.kyokouauth.proxy.BedrockLogin
import moe.ryuu.kyokouauth.proxy.FloodgateConnection
import moe.ryuu.kyokouauth.proxy.FloodgateProvider
import moe.ryuu.kyokouauth.proxy.FloodgateRuntime
import net.md_5.bungee.api.connection.PendingConnection
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.plugin.Plugin
import java.lang.reflect.Modifier

/** Optional native provider/channel boundary. Floodgate policy and SDK reflection stay in Runtime. */
internal class BungeeFloodgate(
    private val enabled: Boolean,
    private val lookup: () -> Plugin?,
    private val allowLinked: Boolean = false,
    private val guardRoleSkin: (java.util.UUID) -> Boolean = { allowLinked },
    private val channelFactory: () -> FloodgateChannels = { BungeeFloodgateChannels(BungeeFloodgate::class.java.classLoader) },
) : AutoCloseable {
    @Volatile private var closed = false
    private val installed = lookup()
    val provider: FloodgateProvider? = installed?.let { plugin ->
        FloodgateProvider(plugin, plugin.description.version, plugin.javaClass.classLoader) {
            !closed && lookup() === plugin
        }
    }
    private val runtime = FloodgateRuntime(enabled, allowLinked, guardRoleSkin) { provider }
    private val channels by lazy(channelFactory)

    fun probe(player: BungeePlayer): BedrockLogin {
        if (closed) return BedrockLogin.Rejected(BedrockFailure.STALE)
        return try {
            // A default Java-only installation must not load an SDK or initialize native channels.
            if (!enabled && installed == null && lookup() == null) BedrockLogin.Java
            else runtime.probe(connection(player))
        } catch (_: Exception) {
            BedrockLogin.Rejected(BedrockFailure.UNAVAILABLE)
        }
    }

    private fun connection(player: BungeePlayer): FloodgateConnection {
        val channel = channels.pending(player.pending)
        fun sameSource(): Boolean = channels.pending(player.pending) === channel &&
            (player.native?.let { channels.native(it) === channel } != false)
        check(sameSource()) { "Native pending and player Channels differ" }
        return FloodgateConnection(channel,
            active = { !closed && player.isActive && channel.isActive && runCatching(::sameSource).getOrDefault(false) },
            attribute = { name -> channel.attr(AttributeKey.valueOf<Any>(name)).get() },
        )
    }

    override fun close() {
        closed = true
        runtime.close()
    }
}

/** InitialHandler and UserConnection must expose one exact physical frontend, not UUID API state. */
internal interface FloodgateChannels {
    fun pending(connection: PendingConnection): Channel
    fun native(player: ProxiedPlayer): Channel
}

/** Fixed official-2111 seam shared by native login lifetime watches and optional Floodgate proof. */
internal class BungeeFloodgateChannels(loader: ClassLoader) : FloodgateChannels {
    private val handler = Class.forName("net.md_5.bungee.connection.InitialHandler", false, loader)
    private val user = Class.forName("net.md_5.bungee.UserConnection", false, loader)
    private val wrapper = Class.forName("net.md_5.bungee.netty.ChannelWrapper", false, loader)
    private val pendingChannel = handler.getDeclaredField("ch")
    private val nativeChannel = user.getMethod("getCh")
    private val handle = wrapper.getMethod("getHandle")

    init {
        check(handler.`package`.implementationVersion?.endsWith("55e1b0b:2111") == true &&
            user.`package`.implementationVersion?.endsWith("55e1b0b:2111") == true) {
            "Floodgate channel proof requires the verified official Bungee 2111 implementation"
        }
        check(PendingConnection::class.java.isAssignableFrom(handler) && ProxiedPlayer::class.java.isAssignableFrom(user))
        check(pendingChannel.type == wrapper && !Modifier.isStatic(pendingChannel.modifiers))
        check(nativeChannel.returnType == wrapper && handle.returnType == Channel::class.java)
        check(pendingChannel.trySetAccessible()) { "Bungee pending Channel is inaccessible" }
    }

    /** LOGIN disconnects do not emit PlayerDisconnectEvent; watch their actual frontend instead. */
    fun watchClosed(player: BungeePlayer, onClosed: () -> Unit): AutoCloseable {
        check(player.isActive) { "Native login connection closed" }
        val channel = pending(player.pending)
        check(player.native?.let { native(it) === channel } != false) { "Native pending and player Channels differ" }
        val listener = io.netty.channel.ChannelFutureListener { onClosed() }
        channel.closeFuture().addListener(listener)
        return AutoCloseable { channel.closeFuture().removeListener(listener) }
    }

    override fun pending(connection: PendingConnection): Channel {
        check(connection.javaClass == handler) { "Unsupported Bungee pending connection" }
        return handle.invoke(checkNotNull(pendingChannel.get(connection))) as Channel
    }

    override fun native(player: ProxiedPlayer): Channel {
        check(player.javaClass == user) { "Unsupported Bungee native connection" }
        return handle.invoke(checkNotNull(nativeChannel.invoke(player))) as Channel
    }
}
