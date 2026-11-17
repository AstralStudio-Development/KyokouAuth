package moe.ryuu.kyokouauth.bungee

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.RootCommandNode
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import moe.ryuu.kyokouauth.proxy.LoginGate
import net.md_5.bungee.UserConnection
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.event.ChatEvent
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.protocol.DefinedPacket
import net.md_5.bungee.protocol.MinecraftEncoder
import net.md_5.bungee.protocol.PacketWrapper
import net.md_5.bungee.protocol.Protocol
import net.md_5.bungee.protocol.ProtocolConstants
import net.md_5.bungee.protocol.packet.Commands
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Test-jar-only probe: actual event bus/dispatcher and final native encoder output, one frontend. */
internal class BungeeE2eCommandProbe(
    private val plugin: Plugin,
    private val platform: BungeePlatform,
    private val gate: LoginGate,
    private val scheduler: ScheduledExecutorService,
) : AutoCloseable {
    private val executions = AtomicInteger()
    private val started = AtomicBoolean()
    private val heldTyped = AtomicBoolean()
    private val heldRaw = AtomicBoolean()
    private val authenticatedTyped = AtomicBoolean()
    private val authenticatedRaw = AtomicBoolean()
    private val heldDispatchDenied = AtomicBoolean()
    private val complete = AtomicBoolean()
    private var native: UserConnection? = null
    private var expected: BungeePlayer? = null
    private var awaiting: ScheduledFuture<*>? = null
    private val command = object : Command(PROBE, "kyokouauth.e2e.probe", ALIAS) {
        override fun execute(sender: CommandSender, args: Array<out String>) { executions.incrementAndGet() }
    }

    init { plugin.proxy.pluginManager.registerCommand(plugin, command) }

    fun mayAuthorize(): Boolean = heldDispatchDenied.get() && heldTyped.get() && heldRaw.get()

    fun ready(frontend: BungeePlayer) {
        check(started.compareAndSet(false, true)) { "Command probe frontend was replaced" }
        expected = frontend
        val player = checkNotNull(frontend.native) as UserConnection
        native = player
        player.setPermission("kyokouauth.e2e.probe", true)
        player.ch.handle.eventLoop().execute {
            safely {
                check(platform.find(player) === frontend && !gate.isAuthenticated(frontend))
                val encoder = checkNotNull(player.ch.handle.pipeline().context(MinecraftEncoder::class.java))
                player.ch.handle.pipeline().addBefore(encoder.name(), CAPTURE, Capture(player))
                val event = plugin.proxy.pluginManager.callEvent(ChatEvent(player, player.server, "/$ALIAS"))
                if (!event.isCancelled) plugin.proxy.pluginManager.dispatchCommand(player, ALIAS)
                check(event.isCancelled && executions.get() == 0) { "Held command reached native dispatcher" }
                heldDispatchDenied.set(true)
                emit(player)
                check(mayAuthorize()) { "Held native command output was not filtered" }
                plugin.logger.info("BUNGEE_E2E_COMMANDS_HELD execution_count=0 typed_hidden=true raw_hidden=true")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                awaiting = scheduler.scheduleAtFixedRate({
                    player.ch.handle.eventLoop().execute {
                        safely authorized@{
                            if (complete.get()) return@authorized
                            check(System.nanoTime() < deadline) { "Command probe authorization deadline" }
                            check(platform.find(player) === frontend && player.isConnected)
                            if (!gate.isAuthenticated(frontend) || player.ch.encodeProtocol != Protocol.GAME) return@authorized
                            awaiting?.cancel(false)
                            emit(player)
                            val allowed = plugin.proxy.pluginManager.callEvent(ChatEvent(player, player.server, "/$ALIAS"))
                            check(!allowed.isCancelled) { "Authenticated command entry was not restored" }
                            check(plugin.proxy.pluginManager.dispatchCommand(player, ALIAS))
                            check(executions.get() == 1 && authenticatedTyped.get() && authenticatedRaw.get()) {
                                "Authenticated native command tree or execution did not recover"
                            }
                            if (complete.compareAndSet(false, true)) plugin.logger.info(
                                "BUNGEE_E2E_COMMANDS_PASS held_execution_count=0 authenticated_execution_count=1 physical_frontend_unchanged=true typed_and_raw_restored=true")
                        }
                    }
                }, 50, 50, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun emit(player: UserConnection) {
        check(player.ch.encodeProtocol == Protocol.GAME)
        player.unsafe().sendPacket(Commands(tree(TYPED)))
        val root = tree(RAW)
        val bytes = encode(root, player.pendingConnection.version)
        val wrapper = PacketWrapper(Commands(root), bytes, Protocol.GAME)
        try { player.sendPacket(wrapper) } finally { wrapper.trySingleRelease() }
    }

    private inner class Capture(private val player: UserConnection) : ChannelOutboundHandlerAdapter() {
        private val commandsId = encode(tree(TYPED), player.pendingConnection.version).let { bytes ->
            try { DefinedPacket.readVarInt(bytes) } finally { bytes.release() }
        }
        override fun write(ctx: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
            if (message is ByteBuf && message.isReadable && player.ch.encodeProtocol == Protocol.GAME) {
                val view = message.duplicate()
                if (DefinedPacket.readVarInt(view) == commandsId) {
                    val decoded = Commands().also {
                        it.read(view, ProtocolConstants.Direction.TO_CLIENT, player.pendingConnection.version)
                    }
                    check(!view.isReadable)
                    val root = decoded.root
                    val typed = root.getChild(TYPED) != null
                    val raw = root.getChild(RAW) != null
                    if (typed || raw) {
                        check(platform.find(player) === expected)
                        val authenticated = gate.isAuthenticated(expected)
                        val visible = root.getChild(PROBE) != null && root.getChild(ALIAS) != null &&
                            root.getChild("server") != null && root.getChild("shortcut") != null
                        val hidden = root.getChild(PROBE) == null && root.getChild(ALIAS) == null &&
                            root.getChild("server") == null && root.getChild("shortcut") == null
                        check(if (authenticated) visible else hidden) { "Final encoded native command tree has wrong visibility" }
                        check(root.getChild("help") != null)
                        if (authenticated) (if (typed) authenticatedTyped else authenticatedRaw).set(true)
                        else (if (typed) heldTyped else heldRaw).set(true)
                        plugin.logger.info("BUNGEE_E2E_COMMAND_TREE stage=${if (authenticated) "authenticated" else "held"} route=${if (typed) "typed" else "raw"} visible=$visible hidden=$hidden final_encoded=true")
                    }
                }
            }
            ctx.write(message, promise)
        }
    }

    private fun tree(marker: String): RootCommandNode<Any?> = RootCommandNode<Any?>().also { root ->
        val probe = LiteralArgumentBuilder.literal<Any?>(PROBE).executes { 0 }.build()
        root.addChild(probe)
        for (name in listOf(ALIAS, "server", "help", marker)) {
            root.addChild(LiteralArgumentBuilder.literal<Any?>(name).executes { 0 }.build())
        }
        root.addChild(LiteralArgumentBuilder.literal<Any?>("shortcut").redirect(probe).build())
    }

    private fun encode(root: RootCommandNode<*>, version: Int): ByteBuf {
        val channel = EmbeddedChannel(MinecraftEncoder(Protocol.GAME, true, version))
        try {
            check(channel.writeOutbound(Commands(root)))
            return checkNotNull(channel.readOutbound<ByteBuf>())
        } finally { channel.finishAndReleaseAll() }
    }

    private fun safely(action: () -> Unit) {
        try { action() } catch (failure: Throwable) {
            awaiting?.cancel(false)
            plugin.logger.warning("BUNGEE_E2E_COMMANDS_FAIL kind=${failure.javaClass.simpleName}")
            native?.disconnect(unavailable().toBungee())
        }
    }

    override fun close() {
        awaiting?.cancel(false)
        plugin.proxy.pluginManager.unregisterCommand(command)
        native?.ch?.handle?.let { channel ->
            channel.eventLoop().execute { if (channel.pipeline().get(CAPTURE) != null) channel.pipeline().remove(CAPTURE) }
        }
    }

    companion object {
        const val PROBE = "kyokouauthprobe"
        private const val ALIAS = "kaprobe"
        private const val TYPED = "kyokouauth_typed_probe_marker"
        private const val RAW = "kyokouauth_raw_probe_marker"
        private const val CAPTURE = "kyokouauth-e2e-final-command-capture"
    }
}
