package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import moe.ryuu.kyokouauth.proxy.DisconnectEvent
import moe.ryuu.kyokouauth.proxy.GameProfile
import moe.ryuu.kyokouauth.proxy.GameProfileRequestEvent
import moe.ryuu.kyokouauth.proxy.LoginGate
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.proxy.ProxyLog
import moe.ryuu.kyokouauth.proxy.LoginEvent as CommonLogin
import moe.ryuu.kyokouauth.proxy.PreLoginEvent as CommonPreLogin
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.plugin.Command
import java.io.File
import java.net.InetSocketAddress
import java.util.Locale
import kotlin.test.*

/** Runs the real shared authentication state machine; only network/account operations are fake. */
internal class BungeeCommandFixture(blocked: String? = null) {
    val proxy = TestProxy()
    val platform = BungeePlatform(proxy)
    val services = TestServices()
    val commands = linkedMapOf<String, Command>()
    val config = ProxyConfig(buildMap {
        put("post-login.server", "backend")
        // YamlDocument's neutral representation is newline-separated roots; [] becomes empty text.
        blocked?.let { put("verification.blocked-commands", when (it) { "[]" -> ""; "[*]" -> "*"; else -> it }) }
    }, File("build/bungee-command-tests"))
    val log = ProxyLog { _, _, _ -> }
    val gate = LoginGate(platform, config, MessageCatalogue("en_US", File("build/bungee-command-tests/messages")), services, log)
    val guard = BungeeCommandGuard(platform, gate, config.blockedCommands.isNotEmpty()) { commands.entries }
    var fixture = PendingFixture()
        private set
    var player: BungeePlayer
        private set

    init {
        val backend = proxy.constructServerInfo("backend", InetSocketAddress("127.0.0.1", 29001), "test", false)
        proxy.registered[backend.name] = backend
        gate.activate()
        player = connect(fixture)
    }

    private fun connect(connection: PendingFixture): BungeePlayer {
        val current = platform.pending(connection.pending)
        current.bind(connection.player)
        val pre = CommonPreLogin(current, connection.name, null)
        gate.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
        val profile = GameProfileRequestEvent(current, GameProfile(current.uniqueId, connection.name, emptyList()))
        gate.onGameProfile(profile)
        connection.uuid = profile.gameProfile.id
        val login = CommonLogin(current)
        gate.onLogin(login)
        assertTrue(login.result.isAllowed)
        proxy.online += connection.player
        return current
    }

    fun authenticate() {
        services.authorize = true
        gate.onJoin(object : LimboPlayer {
            override val name get() = player.username
            override val uuid get() = player.uniqueId
            override val connectionId get() = player.sessionId
            override val address get() = player.remoteAddress.address.hostAddress
            override fun sendMessage(message: TextComponent) = Unit
            override fun showTitle(title: TextComponent, subtitle: TextComponent?, fadeIn: Int, stay: Int, fadeOut: Int) = Unit
            override fun clearTitle() = Unit
            override fun disconnect(reason: TextComponent) = Unit
            override fun close() = Unit
        })
        services.drain()
        services.tasks.last().fire()
        services.drain()
        assertTrue(gate.isAuthenticated(player), "Controlled verification must authenticate the exact frontend")
    }

    fun reconnect(): PendingFixture {
        val old = fixture
        old.connected = false
        gate.onDisconnect(DisconnectEvent(player))
        platform.remove(player)
        proxy.online.remove(old.player)
        fixture = PendingFixture(old.name, old.protocol)
        player = connect(fixture)
        return old
    }

    fun alias(name: String, vararg aliases: String) {
        val command = object : Command(name, null, *aliases) {
            override fun execute(sender: CommandSender, args: Array<out String>) = Unit
        }
        (listOf(name) + aliases).forEach { commands[it.lowercase(Locale.ROOT)] = command }
    }
}
