package moe.ryuu.kyokouauth.limbo

/** Link the provided transport before opening any admission endpoint. No proxy event loop is borrowed. */
internal object NettyRuntimeGuard {
    @Volatile private var checked = false

    @Synchronized
    fun check(loader: ClassLoader) {
        if (checked) return
        try {
            fun type(name: String) = Class.forName("io.netty.$name", false, loader)
            val buffer = type("buffer.ByteBuf")
            buffer.getMethod("readableBytes")
            buffer.getMethod("skipBytes", Int::class.javaPrimitiveType)
            val group = type("channel.EventLoopGroup")
            type("bootstrap.ServerBootstrap").getMethod("group", group, group)
            type("channel.nio.NioEventLoopGroup").getConstructor(Int::class.javaPrimitiveType)
            type("channel.socket.nio.NioServerSocketChannel").getConstructor()
            type("handler.codec.ByteToMessageDecoder")
            val versions = type("util.Version").getMethod("identify").invoke(null) as Map<*, *>
            val core = versions.filterKeys { it in setOf("netty-common", "netty-buffer", "netty-transport", "netty-codec-base", "netty-handler") }
            core.values.forEach { version ->
                val value = requireNotNull(version).javaClass.getMethod("artifactVersion").invoke(version) as String
                require(value.startsWith("4.2.")) { "Provided Netty 4.2 is required, found $value" }
            }
            checked = true
        } catch (failure: Throwable) {
            throw IllegalStateException("The proxy does not provide the required Netty 4.2 transport; authentication remains closed", failure)
        }
    }
}
