package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.helpers.NOPLogger
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class KyokouAuthVelocityTest {
    @TempDir lateinit var directory: Path
    private val proxy = stub<ProxyServer> { error("Startup must fail before touching a database or proxy: $it") }
    private var disconnected = false
    private val player = stub<Player> { method ->
        when (method) {
            "disconnect" -> { disconnected = true; Unit }
            "getCurrentServer" -> Optional.empty<Any>()
            else -> error(method)
        }
    }
    private val backend = stub<RegisteredServer> { method ->
        when (method) {
            "getServerInfo" -> ServerInfo("lobby", InetSocketAddress("127.0.0.1", 25566))
            else -> error(method)
        }
    }

    @Test
    fun `configuration initialization failure keeps all login and failover paths denied`() {
        val invalidDataDirectory = directory.resolve("not-a-directory")
        Files.writeString(invalidDataDirectory, "not a directory")
        val plugin = KyokouAuthVelocity(proxy, NOPLogger.NOP_LOGGER, invalidDataDirectory)
        plugin.onProxyInitialize(ProxyInitializeEvent())

        val pre = PreLoginEvent(player, "Steve", UUID.randomUUID())
        plugin.denyUnavailablePreLogin(pre)
        assertFalse(pre.result.isAllowed)
        val login = LoginEvent(player, "")
        plugin.denyUnavailableLogin(login)
        assertFalse(login.result.isAllowed)
        val connect = ServerPreConnectEvent(player, backend)
        plugin.denyUnavailableConnect(connect)
        assertFalse(connect.result.isAllowed)
        val choose = PlayerChooseInitialServerEvent(player, backend)
        plugin.denyUnavailableInitialServer(choose)
        assertTrue(choose.initialServer.isEmpty)
        assertTrue(disconnected)
        val kick = KickedFromServerEvent(player, backend, null, true,
            KickedFromServerEvent.RedirectPlayer.create(backend))
        plugin.denyUnavailableKick(kick)
        assertIs<KickedFromServerEvent.DisconnectPlayer>(kick.result)
    }

    @Test
    fun `initialization is fail closed even before the initialization event runs`() {
        val plugin = KyokouAuthVelocity(proxy, NOPLogger.NOP_LOGGER, directory)
        val pre = PreLoginEvent(player, "Steve", UUID.randomUUID())
        plugin.denyUnavailablePreLogin(pre)
        assertFalse(pre.result.isAllowed)
    }

    @Test
    fun `the real proxy configuration getter supplies a defensive secret copy`() {
        val plugin = KyokouAuthVelocity(proxy, NOPLogger.NOP_LOGGER, directory)
        val config = Configuration(Mode.MODERN, byteArrayOf(1, 2, 3))
        val secret = plugin.forwardingSecret(config)
        assertContentEquals(config.secret, secret)
        assertNotSame(config.secret, secret)
    }

    @Test
    fun `missing getter legacy mode and empty secret cannot start a permissive gate`() {
        val plugin = KyokouAuthVelocity(proxy, NOPLogger.NOP_LOGGER, directory)
        assertFailsWith<NoSuchMethodException> { plugin.forwardingSecret(Any()) }
        assertFailsWith<IllegalStateException> { plugin.forwardingSecret(Configuration(Mode.LEGACY, byteArrayOf(1))) }
        assertFailsWith<IllegalStateException> { plugin.forwardingSecret(Configuration(Mode.MODERN, byteArrayOf())) }
    }

    enum class Mode { MODERN, LEGACY }
    class Configuration(private val mode: Mode, val secret: ByteArray) {
        fun getPlayerInfoForwardingMode() = mode
        fun getForwardingSecret() = secret
    }

    private inline fun <reified T> stub(crossinline invoke: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
            when (method.name) {
                "equals" -> self === args?.get(0)
                "hashCode" -> System.identityHashCode(self)
                "toString" -> T::class.java.simpleName
                else -> invoke(method.name)
            }
        } as T
}
