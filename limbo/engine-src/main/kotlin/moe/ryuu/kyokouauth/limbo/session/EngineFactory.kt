package moe.ryuu.kyokouauth.limbo.session

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelOption
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.concurrent.GlobalEventExecutor
import moe.ryuu.kyokouauth.limbo.*
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/** Compiled separately against every fixed codec and relocated with that engine, never conflict-resolved together. */
class EngineFactory : LimboEngineFactory {
    override val protocolVersion get() = GeneratedCodec.PROTOCOL
    override val versionName get() = GeneratedCodec.VERSION
    override fun biomeKeys() = RegistrySnapshot.load().biomeKeys
    override fun create(context: EngineContext): LimboEngine = Engine(context)
}

internal class Engine(private val context: EngineContext) : LimboEngine {
    private val connections = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    private var server: Channel? = null
    private var boss: NioEventLoopGroup? = null
    private var workers: NioEventLoopGroup? = null
    private var started = false

    override fun start(): InetSocketAddress {
        check(!started)
        started = true
        require(context.bind.address?.isLoopbackAddress == true)
        val registry = RegistrySnapshot.load()
        val world = LimboWorld(context.settings, registry)
        boss = NioEventLoopGroup(1)
        workers = NioEventLoopGroup(2)
        try {
            server = ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel::class.java)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(libraryInitializer(context, registry, world, connections))
                .bind(context.bind).sync().channel()
            return server!!.localAddress() as InetSocketAddress
        } catch (failure: Throwable) { close(); throw failure }
    }

    override fun connectionCount(): Int = connections.size

    override fun close() {
        server?.close()?.syncUninterruptibly()
        server = null
        connections.close().awaitUninterruptibly()
        workers?.shutdownGracefully(0, 5, TimeUnit.SECONDS)?.syncUninterruptibly()
        boss?.shutdownGracefully(0, 5, TimeUnit.SECONDS)?.syncUninterruptibly()
        workers = null
        boss = null
    }
}
