package moe.ryuu.kyokouauth.bungee

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import moe.ryuu.kyokouauth.proxy.PrivateClaimCommands
import net.md_5.bungee.netty.HandlerBoss
import net.md_5.bungee.protocol.MinecraftDecoder
import net.md_5.bungee.protocol.PacketWrapper
import net.md_5.bungee.protocol.Protocol
import net.md_5.bungee.protocol.packet.Chat
import net.md_5.bungee.protocol.packet.ClientChat
import net.md_5.bungee.protocol.packet.ClientCommand
import net.md_5.bungee.protocol.packet.TabCompleteRequest
import net.md_5.bungee.protocol.packet.UnsignedClientCommand

/** Fixed Bungee 2111 boundary: decoded frontend input, before HandlerBoss / public command events. */
internal class BungeeClaimCommands(
    loader: ClassLoader,
    private val receive: (BungeePlayer, String) -> Unit,
    private val channels: FloodgateChannels = BungeeFloodgateChannels(loader),
) : AutoCloseable {
    private val installed = HashSet<Channel>()
    @Volatile private var closed = false

    // Called on the original PreLogin event loop, before that event can create a UserConnection.
    @Synchronized fun install(player: BungeePlayer) {
        val channel = channels.pending(player.pending)
        check(!closed && channel.eventLoop().inEventLoop() && channel.isActive && player.isActive)
        val pipeline = channel.pipeline()
        val names = pipeline.names()
        val decoder = checkNotNull(pipeline.context(MinecraftDecoder::class.java))
        val boss = checkNotNull(pipeline.context(HandlerBoss::class.java))
        check(names.indexOf(decoder.name()) in 0 until names.indexOf(boss.name()))
        check(pipeline.get(HANDLER) == null && installed.add(channel))
        try {
            val guard = Guard(player, channel, boss.handler() as HandlerBoss)
            pipeline.addBefore(boss.name(), HANDLER, guard)
            channel.closeFuture().addListener { forget(channel) }
            check(channel.isActive && pipeline.get(HANDLER) === guard)
        } catch (_: Exception) {
            forget(channel)
            channel.close()
            error("Bungee private command boundary unavailable")
        }
    }

    @Synchronized private fun forget(channel: Channel) { installed.remove(channel) }

    override fun close() {
        val active = synchronized(this) {
            if (closed) return
            closed = true
            installed.toList().also { installed.clear() }
        }
        // Keep the handler in place until the actual transport closes, including if close is asynchronous.
        active.forEach { it.close() }
    }

    private inner class Guard(
        private val player: BungeePlayer, private val channel: Channel, private val boss: HandlerBoss,
    ) : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            var owns = true
            try {
                check(!closed && ctx.channel() === channel && channel.isActive && channel.eventLoop().inEventLoop())
                check(channel.pipeline().context(HandlerBoss::class.java)?.handler() === boss)
                val packet = if (msg is PacketWrapper) msg.packet else msg
                val text = when (packet) {
                    is UnsignedClientCommand -> packet.command
                    is ClientCommand -> packet.command
                    is TabCompleteRequest -> packet.cursor
                    is ClientChat -> packet.message?.takeIf { it.startsWith('/') }
                    is Chat -> packet.message?.takeIf { it.startsWith('/') }
                    else -> null
                }
                if (text != null && PrivateClaimCommands.isSensitive(text)) {
                    owns = false
                    release(msg)
                    when (packet) {
                        is UnsignedClientCommand -> {
                            val native = checkNotNull(player.native)
                            check(!closed && player.isActive && player.loggedIn && native.pendingConnection === player.pending &&
                                channels.native(native) === channel && (msg !is PacketWrapper || msg.protocol == Protocol.GAME))
                            receive(player, text)
                        }
                        is TabCompleteRequest -> Unit // Never expose private input to public completion events.
                        else -> ctx.close() // Never strip signatures or bypass LastSeen validation.
                    }
                    return
                }
            } catch (_: Exception) {
                if (owns) runCatching { release(msg) }
                ctx.close() // Never propagate an exception containing the raw packet or command.
                return
            }
            ctx.fireChannelRead(msg)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) { ctx.close() }
        override fun handlerRemoved(ctx: ChannelHandlerContext) {
            if (channel.isActive) channel.close()
        }
    }

    private fun release(message: Any) {
        if (message is PacketWrapper) message.trySingleRelease() else ReferenceCountUtil.release(message)
    }

    private companion object { const val HANDLER = "kyokouauth-private-claim" }
}
