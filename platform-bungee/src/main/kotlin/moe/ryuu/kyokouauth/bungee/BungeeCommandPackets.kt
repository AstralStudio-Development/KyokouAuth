package moe.ryuu.kyokouauth.bungee

import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.tree.RootCommandNode
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import moe.ryuu.kyokouauth.proxy.ProxyLog
import net.md_5.bungee.UserConnection
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.netty.ChannelWrapper
import net.md_5.bungee.protocol.DefinedPacket
import net.md_5.bungee.protocol.MinecraftEncoder
import net.md_5.bungee.protocol.PacketWrapper
import net.md_5.bungee.protocol.Protocol
import net.md_5.bungee.protocol.ProtocolConstants
import net.md_5.bungee.protocol.packet.Commands
import net.md_5.bungee.protocol.packet.TabCompleteRequest
import net.md_5.bungee.protocol.packet.TabCompleteResponse
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Fixed-2111 boundary. DownstreamBridge may send Commands as an object or forward its original
 * PacketWrapper; ChannelWrapper.write unwraps the latter into an unframed ByteBuf. Both routes
 * reach this handler immediately before the native encoder. Only the native codec parses/writes
 * the command graph: this adapter never implements node flags, argument properties or packet IDs.
 */
internal class BungeeCommandPackets(
    private val guard: BungeeCommandGuard,
    private val logger: ProxyLog,
    private val channelOf: (ProxiedPlayer) -> Channel = Fixed2111Channels()::channel,
) : AutoCloseable {
    private val channels = IdentityHashMap<ProxiedPlayer, Channel>()
    private val commandIds = ConcurrentHashMap<Int, Int>()
    @Volatile private var closed = false

    @Synchronized fun install(native: ProxiedPlayer) {
        check(!closed) { "Native command boundary is closed" }
        val channel = channelOf(native)
        if (channels[native] === channel) return
        check(channels[native] == null) { "Native command channel changed" }
        channels[native] = channel
        val attach = Runnable {
            check(channel.isActive && !closed) { "Frontend closed before command boundary installation" }
            val encoderContext = checkNotNull(channel.pipeline().context(MinecraftEncoder::class.java)) { "Native command encoder is unavailable" }
            val encoder = encoderContext.handler() as MinecraftEncoder
            check(channel.pipeline().get(HANDLER) == null) { "Native command handler name is occupied" }
            channel.pipeline().addAfter(encoderContext.name(), HANDLER, Filter(native, encoder))
        }
        if (channel.eventLoop().inEventLoop()) attach.run()
        else channel.eventLoop().submit(attach).addListener { future ->
            if (!future.isSuccess) { guard.forget(native); native.disconnect(unavailable().toBungee()) }
        }
    }

    @Synchronized fun remove(native: ProxiedPlayer) {
        val channel = channels.remove(native) ?: return
        val detach = Runnable {
            val handler = channel.pipeline().get(HANDLER)
            if (handler is Filter && handler.native === native) channel.pipeline().remove(handler)
        }
        if (channel.eventLoop().inEventLoop()) detach.run() else channel.eventLoop().execute(detach)
    }

    override fun close() {
        closed = true
        synchronized(this) { channels.keys.toList() }.forEach(::remove)
        commandIds.clear()
    }

    private fun filter(native: ProxiedPlayer, packet: Commands): Commands {
        if (!guard.shouldFilter(native)) return packet
        val root = checkNotNull(packet.root) { "Native command tree has no root" }
        val filtered = BungeeCommandTree.filter(root) { guard.isBlocked(native, it) }
        return if (filtered === root) packet else Commands(filtered)
    }

    private fun filter(native: ProxiedPlayer, packet: TabCompleteResponse): TabCompleteResponse? {
        val context = guard.takeCompletion(native, packet.transactionId)
        if (context?.cancelled == true) return null
        if (!guard.shouldFilter(native) || context?.command == false) return packet
        val denyArguments = context?.restricted == true
        val filterRoots = context == null || context.root
        packet.commands?.let { legacy ->
            val kept = if (denyArguments) emptyList() else if (filterRoots) legacy.filterNot { guard.isBlocked(native, it) } else legacy
            return if (kept.size == legacy.size) packet else TabCompleteResponse(kept)
        }
        if (context == null) return null // Modern IDs must be correlated while exact authentication restrictions apply.
        val suggestions = checkNotNull(packet.suggestions) { "Native completion has no suggestions" }
        val kept = if (denyArguments) emptyList() else if (filterRoots)
            suggestions.list.filterNot { guard.isBlocked(native, it.text) } else suggestions.list
        return if (kept.size == suggestions.list.size) packet else
            TabCompleteResponse(packet.transactionId, Suggestions(suggestions.range, kept))
    }

    private inner class Filter(val native: ProxiedPlayer, private val encoder: MinecraftEncoder) : ChannelDuplexHandler() {
        override fun channelRead(ctx: ChannelHandlerContext, message: Any) {
            val request = when (message) {
                is PacketWrapper -> message.packet as? TabCompleteRequest
                is TabCompleteRequest -> message
                else -> null
            }
            try {
                if (request != null && !guard.beginCompletion(native, request.transactionId, request.cursor)) {
                    release(message)
                    return // Before UpstreamBridge invokes any native TabExecutor or forwards the request.
                }
            } catch (failure: Throwable) {
                try { release(message) } finally { failed(ctx, failure) }
                return
            }
            ctx.fireChannelRead(message)
        }

        override fun write(ctx: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
            var inputOwned = true
            var outgoing: Any? = null
            try {
                outgoing = when (message) {
                    is Commands -> filter(native, message)
                    is TabCompleteResponse -> filter(native, message)
                    is PacketWrapper -> if (message.packet is Commands) {
                        check(message.protocol == Protocol.GAME) { "Native command wrapper has the wrong protocol state" }
                        val original = message.packet as Commands
                        val filtered = filter(native, original)
                        if (filtered === original) message.buf.retainedDuplicate() else filtered
                    } else message
                    is ByteBuf -> filterRaw(ctx, message)
                    else -> message
                }
                if (outgoing == null) {
                    release(message)
                    inputOwned = false
                    promise.trySuccess()
                    return
                }
                if (outgoing !== message) release(message)
                inputOwned = false
                ctx.write(outgoing, promise)
                outgoing = null // Ownership passed to the remaining native pipeline.
            } catch (failure: Throwable) {
                promise.tryFailure(failure)
                try { if (inputOwned) release(message) else outgoing?.let(::release) }
                finally { failed(ctx, failure) }
            }
        }

        private fun filterRaw(ctx: ChannelHandlerContext, buffer: ByteBuf): Any {
            if (!guard.shouldFilter(native) || encoder.protocol != Protocol.GAME || !buffer.isReadable) return buffer
            val version = encoder.protocolVersion
            if (version < ProtocolConstants.MINECRAFT_1_13) return buffer
            val view = buffer.duplicate()
            val id = DefinedPacket.readVarInt(view)
            val commandId = commandIds.computeIfAbsent(version) { CommandsIdProbe(it).id(ctx) }
            if (id != commandId) return buffer
            val decoded = Commands()
            decoded.read(view, ProtocolConstants.Direction.TO_CLIENT, version)
            check(!view.isReadable) { "Native command codec did not consume the full packet" }
            val filtered = filter(native, decoded)
            return if (filtered === decoded) buffer else filtered
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            guard.forget(native)
            synchronized(this@BungeeCommandPackets) { channels.remove(native) }
            ctx.fireChannelInactive()
        }

        override fun handlerRemoved(ctx: ChannelHandlerContext) { guard.forget(native) }

        private fun failed(ctx: ChannelHandlerContext, failure: Throwable) {
            // Never include exception bodies, command strings, cursors or argument values in logs.
            guard.forget(native)
            ctx.close()
            runCatching { logger.warn("Native command filtering failed ({}); frontend closed", failure.javaClass.simpleName) }
        }
    }

    private fun release(message: Any) {
        if (message is PacketWrapper) message.trySingleRelease() else ReferenceCountUtil.release(message)
    }

    /** Derive the ID with Bungee's own encoder, not a copied mapping or private DirectionData API. */
    private class CommandsIdProbe(version: Int) : MinecraftEncoder(Protocol.GAME, true, version) {
        fun id(ctx: ChannelHandlerContext): Int {
            val buffer = ctx.alloc().buffer(32)
            try {
                super.encode(ctx, Commands(RootCommandNode<Any?>()), buffer)
                return DefinedPacket.readVarInt(buffer)
            } finally { buffer.release() }
        }
    }

    private class Fixed2111Channels {
        init {
            BungeeRuntime.requireCompatible()
            check(UserConnection::class.java.getMethod("getCh").returnType == ChannelWrapper::class.java)
            check(ChannelWrapper::class.java.getMethod("getHandle").returnType == Channel::class.java)
            check(Commands::class.java.getMethod("getRoot").returnType == RootCommandNode::class.java)
            Commands::class.java.getConstructor(RootCommandNode::class.java)
            val codec = MinecraftEncoder::class.java.getDeclaredMethod("encode", ChannelHandlerContext::class.java,
                DefinedPacket::class.java, ByteBuf::class.java)
            check(Modifier.isProtected(codec.modifiers) && codec.returnType == Void.TYPE)
            check(Commands::class.java.getMethod("read", ByteBuf::class.java, ProtocolConstants.Direction::class.java,
                Int::class.javaPrimitiveType).returnType == Void.TYPE)
        }

        fun channel(native: ProxiedPlayer): Channel {
            check(native.javaClass == UserConnection::class.java) { "Unsupported native frontend connection" }
            return (native as UserConnection).ch.handle
        }
    }

    private companion object { const val HANDLER = "kyokouauth-command-boundary" }
}
