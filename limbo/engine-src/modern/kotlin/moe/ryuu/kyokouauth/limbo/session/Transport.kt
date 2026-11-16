package moe.ryuu.kyokouauth.limbo.session

import io.netty.channel.Channel
import io.netty.channel.group.ChannelGroup
import moe.ryuu.kyokouauth.limbo.EngineContext
import org.geysermc.mcprotocollib.network.BuiltinFlags
import org.geysermc.mcprotocollib.network.netty.MinecraftChannelInitializer
import org.geysermc.mcprotocollib.network.session.NetworkSession
import java.util.concurrent.Executor

internal fun libraryInitializer(context: EngineContext, registry: RegistrySnapshot, world: LimboWorld, channels: ChannelGroup) =
    MinecraftChannelInitializer({ channel: Channel ->
        channels.add(channel)
        val transport = object : NetworkSession(channel.remoteAddress(), ProtocolPackets.server(), Executor { it.run() }) {}
        transport.setFlag(BuiltinFlags.READ_TIMEOUT, (context.settings.keepAliveSeconds + context.settings.keepAliveTimeoutSeconds).toInt())
        transport.addListener(LimboConnection(transport, context.settings, registry, world, context.forwardingMode,
            context.listener, context.logger, context.executor, { channels.size }))
        transport
    }, false)
