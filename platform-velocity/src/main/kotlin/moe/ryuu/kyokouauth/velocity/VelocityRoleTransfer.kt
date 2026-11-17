package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import net.kyori.adventure.key.Key
import java.lang.reflect.Modifier
import java.net.InetSocketAddress
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Transport, never authentication: the Gate validates/redeems every returned untrusted cookie. */
internal class VelocityRoleTransfer internal constructor(
    private val access: VelocityCookieAccess,
    private val timeoutMillis: Long = 3_000,
) : AutoCloseable {
    constructor(proxy: ProxyServer, timeoutMillis: Long = 3_000) :
        this(VelocityNativeCookies(proxy.javaClass.classLoader), timeoutMillis)

    private val requests = IdentityHashMap<Channel, Request>()
    private val sequence = AtomicLong()
    @Volatile private var closed = false

    init { require(timeoutMillis in 1..30_000) }

    /** The caller must hold its PreLogin EventTask until this future completes. */
    fun retrieve(connection: InboundConnection): CompletableFuture<ByteArray?> = try {
        check(!closed && connection.isActive) { "Role connection closed" }
        require(connection !is Player) { "Role cookies must be retrieved before Player construction" }
        require(connection.protocolVersion.protocol in 767..777) { "Unsupported role cookie protocol" }
        val channel = access.channel(connection)
        val request = Request(connection, channel)
        synchronized(requests) {
            check(!closed && requests.size < 4096 && !requests.containsKey(channel)) { "Role cookie request unavailable" }
            requests[channel] = request
        }
        request.result.whenComplete { _, _ -> request.execute { request.finish(null, IllegalStateException("Role cookie request cancelled")) } }
        request.execute { request.start() }
        request.result
    } catch (error: Exception) { CompletableFuture.failedFuture(error) }

    /** Read-only lifecycle watch survives cookie completion while bounded auth work is queued. */
    fun watchClosed(connection: InboundConnection, onClosed: () -> Unit): AutoCloseable {
        val channel = synchronized(requests) {
            check(!closed && connection.isActive) { "Role connection closed" }
            access.channel(connection)
        }
        val listener = ChannelFutureListener { onClosed() }
        channel.closeFuture().addListener(listener)
        return AutoCloseable { channel.closeFuture().removeListener(listener) }
    }

    /** Completes after native store/transfer submission, not after the new connection authenticates. */
    fun transfer(player: Player, host: String, port: Int, cookie: ByteArray?): CompletableFuture<Unit> = try {
        synchronized(requests) {
            check(!closed && player.isActive) { "Role connection closed" }
            require(player.protocolVersion.protocol in 767..777) { "Unsupported role transfer protocol" }
            require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it.isISOControl() } && port in 1..65535)
            require(cookie == null || cookie.size in 1..MAX_COOKIE_BYTES) { "Invalid role cookie size" }
            if (cookie != null) player.storeCookie(COOKIE_KEY, cookie.copyOf())
            player.transferToHost(InetSocketAddress.createUnresolved(host, port))
        }
        CompletableFuture.completedFuture(Unit)
    } catch (error: Exception) { CompletableFuture.failedFuture(error) }

    fun cancel(connection: InboundConnection) {
        synchronized(requests) { requests.values.filter { it.connection === connection } }.forEach {
            it.execute { it.finish(null, IllegalStateException("Role connection closed")) }
        }
    }

    override fun close() {
        val pending = synchronized(requests) {
            if (closed) return
            closed = true
            requests.values.toList()
        }
        pending.forEach { it.execute { it.finish(null, IllegalStateException("Role cookie bridge closed")) } }
    }

    private inner class Request(val connection: InboundConnection, val channel: Channel) : ChannelInboundHandlerAdapter() {
        val result = CompletableFuture<ByteArray?>()
        private val name = "kyokouauth-role-cookie-${sequence.incrementAndGet()}"
        private var done = false
        private var timeout: ScheduledFuture<*>? = null
        private val disconnect = ChannelFutureListener { finish(null, IllegalStateException("Role connection closed")) }

        fun execute(task: () -> Unit) {
            try {
                if (channel.eventLoop().inEventLoop()) task() else channel.eventLoop().execute(task)
            } catch (error: Exception) {
                synchronized(requests) { if (requests[channel] === this) requests.remove(channel) }
                result.completeExceptionally(error)
            }
        }

        fun start() {
            if (done) return
            if (closed || !channel.isActive || !connection.isActive || result.isDone) {
                finish(null, IllegalStateException("Role connection closed")); return
            }
            try {
                check(channel.pipeline().context("minecraft-decoder") != null) { "Velocity native decoder missing" }
                channel.pipeline().addAfter("minecraft-decoder", name, this)
                channel.closeFuture().addListener(disconnect)
                timeout = channel.eventLoop().schedule({ finish(null, java.util.concurrent.TimeoutException("Role cookie request timed out")) },
                    timeoutMillis, TimeUnit.MILLISECONDS)
                channel.writeAndFlush(access.request()).addListener(ChannelFutureListener { write: ChannelFuture ->
                    if (!write.isSuccess) finish(null, write.cause() ?: IllegalStateException("Role cookie request write failed"))
                })
            } catch (error: Exception) { finish(null, error) }
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            try {
                val response = access.response(msg)
                if (ctx.channel() !== channel || response == null || response.key != COOKIE_KEY) {
                    ctx.fireChannelRead(msg); return
                }
                try {
                    when {
                        closed || !channel.isActive || !connection.isActive -> finish(null, IllegalStateException("Role connection closed"))
                        response.bytes != null && response.bytes.size > MAX_COOKIE_BYTES -> finish(null, IllegalArgumentException("Role cookie exceeds limit"))
                        else -> finish(response.bytes?.copyOf(), null)
                    }
                } finally { ReferenceCountUtil.release(msg) }
            } catch (error: Exception) { ReferenceCountUtil.release(msg); finish(null, error) }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            finish(null, IllegalStateException("Role connection closed"))
            ctx.fireChannelInactive()
        }

        override fun handlerRemoved(ctx: ChannelHandlerContext) {
            if (!done) finish(null, IllegalStateException("Role cookie interceptor removed"))
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            finish(null, cause)
            ctx.fireExceptionCaught(cause)
        }

        /** Always remove before completing: completion resumes the native login future. */
        fun finish(bytes: ByteArray?, error: Throwable?) {
            if (done) return
            done = true
            timeout?.cancel(false)
            channel.closeFuture().removeListener(disconnect)
            if (channel.pipeline().context(this) != null) channel.pipeline().remove(this)
            synchronized(requests) { if (requests[channel] === this) requests.remove(channel) }
            if (error == null) result.complete(bytes) else result.completeExceptionally(error)
        }
    }

    companion object {
        val COOKIE_KEY: Key = Key.key("kyokouauth:role_transfer")
        const val MAX_COOKIE_BYTES = 512
    }
}

/** A narrow native seam allows lifecycle tests without bundling or mocking a proxy implementation. */
internal interface VelocityCookieAccess {
    fun channel(connection: InboundConnection): Channel
    fun request(): Any
    fun response(packet: Any): VelocityCookieResponse?
}
internal data class VelocityCookieResponse(val key: Key, val bytes: ByteArray?)

/** Fixed 4.3.1 LOGIN codec. No packet IDs, protocol-state writes or native session replacement. */
private class VelocityNativeCookies(loader: ClassLoader) : VelocityCookieAccess {
    private val login = Class.forName("com.velocitypowered.proxy.connection.client.LoginInboundConnection", false, loader)
    private val initial = Class.forName("com.velocitypowered.proxy.connection.client.InitialInboundConnection", false, loader)
    private val player = Class.forName("com.velocitypowered.proxy.connection.client.ConnectedPlayer", false, loader)
    private val native = Class.forName("com.velocitypowered.proxy.connection.MinecraftConnection", false, loader)
    private val request = Class.forName("com.velocitypowered.proxy.protocol.packet.ClientboundCookieRequestPacket", false, loader)
    private val response = Class.forName("com.velocitypowered.proxy.protocol.packet.ServerboundCookieResponsePacket", false, loader)
    private val delegate = login.getDeclaredField("delegate")
    private val connection = initial.getMethod("getConnection")
    private val playerConnection = player.getMethod("getConnection")
    private val channel = native.getMethod("getChannel")
    private val state = native.getMethod("getState")
    private val requestConstructor = request.getConstructor(Key::class.java)
    private val getKey = response.getMethod("getKey")
    private val getPayload = response.getMethod("getPayload")

    init {
        check(native.`package`.implementationVersion == "4.3.1") { "Role selection requires the verified Velocity 4.3.1 implementation" }
        check(delegate.type === initial && Modifier.isPrivate(delegate.modifiers) && Modifier.isFinal(delegate.modifiers) && !Modifier.isStatic(delegate.modifiers))
        check(connection.returnType === native && playerConnection.returnType === native && channel.returnType === Channel::class.java &&
            state.returnType.name == "com.velocitypowered.proxy.protocol.StateRegistry" && state.returnType.isEnum)
        check(getKey.returnType === Key::class.java && getPayload.returnType === ByteArray::class.java)
        check(delegate.trySetAccessible()) { "Velocity login delegate inaccessible" }
    }

    override fun channel(connection: InboundConnection): Channel {
        val nativeConnection = when (connection.javaClass) {
            login -> this.connection.invoke(delegate.get(connection))
            initial -> this.connection.invoke(connection)
            player -> playerConnection.invoke(connection)
            else -> error("Role cookie lifecycle requires a verified native login connection")
        }
        check(nativeConnection != null && nativeConnection.javaClass === native)
        check((state.invoke(nativeConnection) as Enum<*>).name == "LOGIN") { "Role cookie retrieval must occur in LOGIN" }
        return channel.invoke(nativeConnection) as Channel
    }

    override fun request(): Any = requestConstructor.newInstance(VelocityRoleTransfer.COOKIE_KEY)
    override fun response(packet: Any): VelocityCookieResponse? = if (packet.javaClass !== response) null else
        VelocityCookieResponse(getKey.invoke(packet) as Key, getPayload.invoke(packet) as ByteArray?)
}
