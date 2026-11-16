package moe.ryuu.kyokouauth.limbo.session

import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.group.ChannelGroup
import io.netty.handler.timeout.ReadTimeoutHandler
import moe.ryuu.kyokouauth.limbo.EngineContext
import org.geysermc.mcprotocollib.network.tcp.*
import java.util.concurrent.Executor

/** 768's TcpSession takes an executor; upstream still owns every transport codec handler. */
internal fun libraryInitializer(context: EngineContext, registry: RegistrySnapshot, world: LimboWorld, channels: ChannelGroup): ChannelInitializer<Channel> =
    object : ChannelInitializer<Channel>() {
        override fun initChannel(channel: Channel) {
            channels.add(channel)
            val protocol = ProtocolPackets.server().apply { resetStates() }
            val helper = protocol.createHelper()
            val remote = channel.remoteAddress() as java.net.InetSocketAddress
            val transport = object : TcpSession(remote.address.hostAddress, remote.port, protocol, Executor { it.run() }) {
                override fun getCodecHelper() = helper
            }
            transport.addListener(LimboConnection(transport, context.settings, registry, world, context.forwardingMode,
                context.listener, context.logger, context.executor, { channels.size }))
            channel.pipeline().apply {
                addLast("read-timeout", ReadTimeoutHandler((context.settings.keepAliveSeconds + context.settings.keepAliveTimeoutSeconds).toInt()))
                addLast("encryption", TcpPacketEncryptor())
                addLast("sizer", TcpPacketSizer(protocol.packetHeader, helper))
                addLast("compression", TcpPacketCompression(helper))
                addLast("flow-control", TcpFlowControlHandler())
                addLast("codec", TcpPacketCodec(transport, false))
                addLast("flush-handler", FlushHandler())
                addLast("manager", transport)
            }
        }
    }
