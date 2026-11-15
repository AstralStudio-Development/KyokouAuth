package moe.ryuu.kyokouauth.bukkit.inject

import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelFuture

/**
 * Attaches a handler to every incoming connection, on any server generation.
 *
 * ## Two mechanisms, one interface
 *
 * Paper exposes `ChannelInitializeListenerHolder`, which is the clean way and is used when present.
 * Spigot has no such hook, so the fallback reaches the netty pipeline the way every cross-version
 * plugin does: find the server's listening channels, and wrap the `ChannelInitializer` they hand to
 * new connections.
 *
 * ## Why field scanning rather than names
 *
 * The NMS classes holding the channel list are renamed on every Minecraft generation
 * (`v1_8_R3.ServerConnection`, `v1_16_R5.ServerConnection`, `net.minecraft.server.network.ServerConnectionListener`, ...)
 * and their members are obfuscated. Matching on *shape* - a field whose type is named like a server
 * connection, holding a list of channel futures - survives renames that a hard-coded path does not.
 * Every step fails soft: if the shape is not found, the plugin runs without the hook instead of not
 * at all.
 */
internal object ChannelHook {

    private const val HOLDER = "io.papermc.paper.network.ChannelInitializeListenerHolder"
    private const val LISTENER = "io.papermc.paper.network.ChannelInitializeListener"

    class Handle(private val unregister: () -> Unit) {
        fun remove() = unregister()
    }

    /** @return a handle to unregister with, or null when no mechanism worked on this server. */
    fun install(pluginKey: String, onChannel: (Channel) -> Unit): Handle? =
        installPaper(pluginKey, onChannel) ?: installSpigot(onChannel)

    // ---- Paper ----

    private fun installPaper(pluginKey: String, onChannel: (Channel) -> Unit): Handle? {
        val holder = runCatching { Class.forName(HOLDER) }.getOrNull() ?: return null
        val listenerType = runCatching { Class.forName(LISTENER) }.getOrNull() ?: return null
        val keyClass = runCatching { Class.forName("net.kyori.adventure.key.Key") }.getOrNull() ?: return null

        val key = runCatching {
            keyClass.getMethod("key", String::class.java, String::class.java)
                .invoke(null, "kyokouauth", pluginKey)
        }.getOrNull() ?: return null

        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            listenerType.classLoader,
            arrayOf(listenerType),
        ) { _, method, args ->
            when (method.name) {
                "afterInitChannel" -> {
                    (args?.getOrNull(0) as? Channel)?.let(onChannel)
                    null
                }

                "toString" -> "KyokouAuthChannelListener"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> null
            }
        }

        return runCatching {
            holder.getMethod("addListener", keyClass, listenerType).invoke(null, key, proxy)
            Handle { runCatching { holder.getMethod("removeListener", keyClass).invoke(null, key) } }
        }.getOrNull()
    }

    // ---- Spigot ----

    private fun installSpigot(onChannel: (Channel) -> Unit): Handle? {
        val channels = runCatching { findServerChannels() }.getOrNull().orEmpty()
        if (channels.isEmpty()) return null

        val restore = mutableListOf<() -> Unit>()
        channels.forEach { channel ->
            runCatching { wrapChildHandler(channel, onChannel) }?.getOrNull()?.let { restore += it }
        }
        if (restore.isEmpty()) return null

        return Handle { restore.forEach { runCatching(it) } }
    }

    /**
     * Finds the server's listening channels by shape.
     *
     * `CraftServer#getServer` is the one stable entry point - it is present from the first CraftBukkit
     * to current Paper. Everything below it is searched structurally.
     */
    private fun findServerChannels(): List<Channel> {
        val craftServer = org.bukkit.Bukkit.getServer()

        val minecraftServer = craftServer.javaClass.methods
            .firstOrNull { it.name == "getServer" && it.parameterCount == 0 }
            ?.invoke(craftServer) ?: return emptyList()

        val connection = minecraftServer.javaClass.declaredFields
            .filter { it.type.simpleName.contains("ServerConnection") }
            .firstOrNull { it.isAccessible = true; it.get(minecraftServer) != null }
            ?.let { it.isAccessible = true; it.get(minecraftServer) }
            ?: return emptyList()

        val listField = connection.javaClass.declaredFields
            .firstOrNull { List::class.java.isAssignableFrom(it.type) }
            ?: return emptyList()
        listField.isAccessible = true

        val futures = listField.get(connection) as? List<*> ?: return emptyList()
        return futures.mapNotNull { (it as? ChannelFuture)?.channel() }
    }

    /**
     * Replaces the acceptor's child handler with one that runs the original and then ours.
     *
     * The acceptor is netty's own `ServerBootstrap$ServerBootstrapAcceptor`, whose `childHandler`
     * field is what builds each new connection's pipeline. Wrapping it is mapping-independent
     * because it never touches a Minecraft class.
     */
    private fun wrapChildHandler(serverChannel: Channel, onChannel: (Channel) -> Unit): (() -> Unit)? {
        val acceptor = serverChannel.pipeline().toMap().values.firstOrNull {
            it.javaClass.name.startsWith("io.netty.bootstrap.ServerBootstrap")
        } ?: return null

        val field = acceptor.javaClass.declaredFields
            .firstOrNull { ChannelHandler::class.java.isAssignableFrom(it.type) }
            ?: return null
        field.isAccessible = true

        val original = field.get(acceptor) as? ChannelHandler ?: return null
        if (original is WrappingInitializer) return null

        field.set(acceptor, WrappingInitializer(original, onChannel))
        return { runCatching { field.set(acceptor, original) }.let { } }
    }

    /**
     * Runs the original child handler, then the callback.
     *
     * `initChannel` is invoked once per accepted connection; the callback runs after the original has
     * finished building the pipeline, which is the only point at which `addAfter("splitter", ...)`
     * can succeed.
     */
    private class WrappingInitializer(
        private val delegate: ChannelHandler,
        private val onChannel: (Channel) -> Unit,
    ) : ChannelInitializer<Channel>() {

        override fun initChannel(channel: Channel) {
            // `ChannelInitializer.initChannel` is protected, so the delegate cannot be invoked
            // directly. Handing it back to the pipeline is the supported route: netty calls its
            // `handlerAdded`, which runs the initialisation and then removes it.
            channel.pipeline().addLast(delegate)

            runCatching { onChannel(channel) }
        }

        override fun handlerAdded(ctx: ChannelHandlerContext) {
            // A ChannelInitializer removes itself after the first connection; this wrapper must not,
            // because it serves every connection the acceptor accepts.
        }
    }
}
