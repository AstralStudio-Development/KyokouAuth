package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.proxy.Player
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Fixed Velocity 4.3.1 decoded-packet boundary, before MinecraftConnection and its event dispatch.
 * It deliberately does not parse aliases, rewrite signed packets or implement a second codec.
 * Install must finish before resuming LoginEvent; receive must only enqueue bounded work.
 */
internal class VelocityClaimCommands internal constructor(
    private val access: NativeAccess,
    private val sensitive: (String) -> Boolean,
    private val receive: (Player, String) -> Unit,
    private val channels: (Player) -> Channel,
) : AutoCloseable {
    constructor(
        loader: ClassLoader,
        sensitive: (String) -> Boolean,
        receive: (Player, String) -> Unit,
        channels: (Player) -> Channel = VelocityChannels(loader)::resolve,
    ) : this(verified(loader), sensitive, receive, channels)

    // The ClassLoader constructor always resolves the fixed native metadata exactly once.
    // NativeAccess is an explicit internal seam for EmbeddedChannel tests without a cached proxy.
    private val lock = Any()
    private val sessions = IdentityHashMap<Player, Binding>()
    @Volatile private var closed = false

    fun install(player: Player): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        val channel = try {
            check(!closed)
            channels(player)
        } catch (_: Throwable) {
            result.completeExceptionally(refused())
            return result
        }
        val task = Runnable {
            var binding: Binding? = null
            try {
                check(channel.eventLoop().inEventLoop())
                synchronized(lock) {
                    check(!closed && channel.isOpen && channel.isActive && player.isActive)
                    val pipeline = channel.pipeline()
                    val native = pipeline.get(access.handlerName)
                    check(native != null && access.isConnection(native, channel))
                    val session = access.session(native) ?: throw refused()
                    check(!sessions.containsKey(player) && pipeline.get(HANDLER_NAME) == null)
                    val installed = Binding(player, channel, native, session)
                    binding = installed
                    sessions[player] = installed
                    pipeline.addBefore(access.handlerName, HANDLER_NAME, installed.handler)
                    channel.closeFuture().addListener(installed.onClosed)
                    check(installed.active && channel.isActive && pipeline.get(HANDLER_NAME) === installed.handler)
                }
                result.complete(Unit)
            } catch (_: Throwable) {
                binding?.let { remove(it) }
                reject(channel)
                result.completeExceptionally(refused())
            }
        }
        try {
            if (channel.eventLoop().inEventLoop()) task.run() else channel.eventLoop().execute(task)
        } catch (_: Throwable) {
            reject(channel)
            result.completeExceptionally(refused())
        }
        return result
    }

    private inner class Binding(
        val player: Player,
        val channel: Channel,
        val native: ChannelHandler,
        val session: UUID,
    ) {
        @Volatile var active = true
        val onClosed = ChannelFutureListener { remove(this) }
        val handler = object : ChannelInboundHandlerAdapter() {
            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                var consumed = false
                try {
                    check(ctx.channel() === channel && channel.eventLoop().inEventLoop())
                    check(active && !closed && channel.isActive)
                    check(synchronized(lock) { sessions[player] === this@Binding })
                    check(channel.pipeline().get(access.handlerName) === native)
                    check(access.session(native) == session)
                    val packet = access.read(msg)
                    if (packet != null && sensitive(packet.text)) {
                        // Ownership never crosses the boundary for a consumed packet. Decoded native
                        // packets are not reference counted, but release also handles future owners.
                        consumed = true
                        ReferenceCountUtil.release(msg)
                        when (packet.kind) {
                            Kind.UNSIGNED -> synchronized(lock) {
                                check(active && !closed && channel.isActive && sessions[player] === this@Binding)
                                receive(player, packet.text)
                            }
                            Kind.COMPLETION -> Unit
                            // Even an empty argument-signature list may carry LastSeen state. We do
                            // not skip its counters or strip signatures: reject this exact channel.
                            Kind.SIGNED_FORMAT -> reject(channel)
                        }
                        return
                    }
                } catch (_: Throwable) {
                    // No native exception, object.toString(), password or callback message escapes.
                    // A callback throws only after ownership was already released.
                    if (!consumed) {
                        try { ReferenceCountUtil.release(msg) } catch (_: Throwable) { /* Refuse below. */ }
                    }
                    reject(channel)
                    return
                }
                // Transfer the original object exactly once. Do not catch downstream failures and
                // then release it again: ownership is theirs after fireChannelRead.
                ctx.fireChannelRead(msg)
            }

            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                reject(channel)
            }

            override fun handlerRemoved(ctx: ChannelHandlerContext) {
                forget(this@Binding)
                if (!closed && channel.isActive) reject(channel)
            }
        }
    }

    private fun forget(binding: Binding) {
        binding.active = false
        synchronized(lock) {
            if (sessions[binding.player] === binding) sessions.remove(binding.player)
        }
        binding.channel.closeFuture().removeListener(binding.onClosed)
    }

    private fun remove(binding: Binding) {
        try {
            forget(binding)
            val pipeline = binding.channel.pipeline()
            if (pipeline.context(binding.handler) != null) pipeline.remove(binding.handler)
        } catch (_: Throwable) {
            // Cleanup failure cannot be sent to Netty's exception logger or reopen the boundary.
            reject(binding.channel)
        }
    }

    override fun close() {
        val installed = synchronized(lock) {
            if (closed) return
            closed = true
            sessions.values.toList().also { sessions.clear() }
        }
        var failed = false
        installed.forEach { binding ->
            try {
                val loop = binding.channel.eventLoop()
                // Keep both the guard and its close listener until the original transport is
                // actually closed. A close request may be asynchronous: removing now would let
                // later private input reach ordinary dispatch during plugin shutdown.
                // onClosed removes only this binding after channel.closeFuture completes.
                if (loop.inEventLoop()) binding.channel.close() else loop.execute { reject(binding.channel) }
            } catch (_: Throwable) {
                // Leave the guard installed on failure; closed=true still refuses every packet.
                reject(binding.channel)
                failed = true
            }
        }
        if (failed) throw refused()
    }

    internal enum class Kind { UNSIGNED, SIGNED_FORMAT, COMPLETION }
    internal class PacketText(val text: String, val kind: Kind)
    private class PacketAccess(val text: Method, val kind: Kind)

    internal interface NativeAccess {
        val handlerName: String
        fun isConnection(handler: ChannelHandler, channel: Channel): Boolean
        fun session(handler: ChannelHandler): UUID?
        fun read(message: Any): PacketText?
    }

    /** No arbitrary member search, private-field traversal, packet IDs or handwritten layouts. */
    private class Access(private val loader: ClassLoader) : NativeAccess {
        private fun native(name: String): Class<*> = Class.forName(name, false, loader).also {
            check(it.`package`.implementationVersion == "4.3.1")
        }
        val connection = native("com.velocitypowered.proxy.connection.MinecraftConnection").also {
            check(it.superclass === ChannelInboundHandlerAdapter::class.java)
        }
        private val connections = native("com.velocitypowered.proxy.network.Connections")
        override val handlerName = connections.getDeclaredField("HANDLER").let {
            check(it.type === String::class.java && Modifier.isPublic(it.modifiers) &&
                Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers))
            (it.get(null) as String).also { name -> check(name == "handler") }
        }
        private val channelMethod = getter(connection, "getChannel", Channel::class.java)
        private val sessionMethod = getter(connection, "getSessionId", UUID::class.java)
        private val packet = native("com.velocitypowered.proxy.protocol.MinecraftPacket")
        private val command = native("com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerCommandPacket")
        private val unsigned = native("com.velocitypowered.proxy.protocol.packet.chat.session.UnsignedPlayerCommandPacket")
        private val sessionChat = native("com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerChatPacket")
        private val keyedCommand = native("com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedPlayerCommandPacket")
        private val keyedChat = native("com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedPlayerChatPacket")
        private val legacy = native("com.velocitypowered.proxy.protocol.packet.chat.legacy.LegacyChatPacket")
        private val completion = native("com.velocitypowered.proxy.protocol.packet.TabCompleteRequestPacket")
        private val types: Map<Class<*>, PacketAccess>

        init {
            check(unsigned.superclass === command)
            listOf(command, unsigned, sessionChat, keyedCommand, keyedChat, legacy, completion).forEach {
                check(it.classLoader === connection.classLoader && packet.isAssignableFrom(it))
            }
            // Verify the native signing contract even though signed-format requests are refused.
            getter(command, "isSigned", Boolean::class.javaPrimitiveType!!)
            getter(unsigned, "isSigned", Boolean::class.javaPrimitiveType!!)
            getter(sessionChat, "isSigned", Boolean::class.javaPrimitiveType!!)
            getter(keyedCommand, "isUnsigned", Boolean::class.javaPrimitiveType!!)
            getter(keyedChat, "isUnsigned", Boolean::class.javaPrimitiveType!!)
            val commandText = getter(command, "getCommand", String::class.java)
            check(unsigned.getMethod("getCommand") == commandText)
            types = mapOf(
                unsigned to PacketAccess(commandText, Kind.UNSIGNED),
                command to PacketAccess(commandText, Kind.SIGNED_FORMAT),
                sessionChat to PacketAccess(getter(sessionChat, "getMessage", String::class.java), Kind.SIGNED_FORMAT),
                keyedCommand to PacketAccess(getter(keyedCommand, "getCommand", String::class.java), Kind.SIGNED_FORMAT),
                keyedChat to PacketAccess(getter(keyedChat, "getMessage", String::class.java), Kind.SIGNED_FORMAT),
                legacy to PacketAccess(getter(legacy, "getMessage", String::class.java), Kind.UNSIGNED),
                completion to PacketAccess(getter(completion, "getCommand", String::class.java), Kind.COMPLETION),
            )
        }

        override fun isConnection(handler: ChannelHandler, channel: Channel): Boolean =
            handler.javaClass === connection && channelMethod.invoke(handler) === channel

        override fun session(handler: ChannelHandler): UUID? = sessionMethod.invoke(handler) as? UUID

        override fun read(message: Any): PacketText? {
            val type = types[message.javaClass] ?: return null
            return PacketText(type.text.invoke(message) as String, type.kind)
        }

        private fun getter(owner: Class<*>, name: String, returns: Class<*>): Method =
            owner.getDeclaredMethod(name).also {
                check(it.declaringClass === owner && it.returnType === returns &&
                    Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers))
            }
    }

    private companion object {
        const val HANDLER_NAME = "kyokouauth-private-claim"
        fun verified(loader: ClassLoader): NativeAccess =
            try { Access(loader) } catch (_: Throwable) { throw refused() }
        fun refused() = IllegalStateException("Private command interception unavailable")
        fun reject(channel: Channel) {
            try { channel.close() } catch (_: Throwable) { /* Never expose native error text. */ }
        }
    }
}
