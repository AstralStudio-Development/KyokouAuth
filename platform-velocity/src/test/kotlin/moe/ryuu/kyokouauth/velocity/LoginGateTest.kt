package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.command.CommandManager
import com.velocitypowered.api.proxy.ConsoleCommandSource
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.command.CommandExecuteEvent
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent
import com.velocitypowered.api.event.player.TabCompleteEvent
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.RootCommandNode
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.ConnectionRequestBuilder
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.config.ProxyConfig as VelocityProxyConfig
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.proxy.BedrockFailure
import moe.ryuu.kyokouauth.proxy.BedrockLogin
import moe.ryuu.kyokouauth.proxy.BedrockProof
import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import com.velocitypowered.api.util.GameProfile
import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoginGateTest {
    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val name = GameName.parseOrThrow("Steve")
    private val record = BoundIdentity.of(Identity.Microsoft(name, Xuid(1L), "Steve"), 1L)
    private val backend = registered("lobby", 25566)
    private val limbo = registered(LoginGate.LIMBO_SERVER_NAME, 25567)
    private val players = mutableListOf<Client>()
    private val config = ProxyConfig(mapOf("verification.poll-interval-seconds" to "1"), File("unused-test-config"))
    private var now = 1_000_000L
    private var limboRegistered = true
    private val versionedLimbos = mutableMapOf<Int, RegisteredServer>()
    private val aliases = mutableMapOf<String, Set<String>>()
    private val commandManager = stub<CommandManager> { method, args ->
        when (method) {
            "getCommandMeta" -> aliases[args[0]]?.let { names ->
                stub<CommandMeta> { meta, _ -> if (meta == "getAliases") names else error(meta) }
            }
            else -> error(method)
        }
    }
    private val proxyConfig = stub<VelocityProxyConfig> { method, _ ->
        when (method) {
            "getAttemptConnectionOrder" -> listOf("lobby")
            else -> error(method)
        }
    }
    private val proxy = stub<ProxyServer> { method, args ->
        when (method) {
            "getServer" -> Optional.ofNullable(when (args[0]) {
                "lobby" -> backend
                LoginGate.LIMBO_SERVER_NAME -> limbo.takeIf { limboRegistered }
                else -> versionedLimbos.values.firstOrNull { it.serverInfo.name == args[0] }
            })
            "getPlayer" -> Optional.ofNullable(players.lastOrNull { it.active && it.player.uniqueId == args[0] }?.player)
            "getConfiguration" -> proxyConfig
            "getCommandManager" -> commandManager
            else -> error(method)
        }
    }
    private val services = FakeServices()
    private val gate = LoginGate(
        proxy, config, MessageCatalogue("en_US", File("unused-test-messages")), services, NOPLogger.NOP_LOGGER, { now },
    ).also { it.activate() }

    @Test
    fun `Java identity is final before normal skin listeners and their textures survive last validation`() {
        val client = client()
        val pre = PreLoginEvent(client.player, name.value, null)
        gate.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
        val original = GameProfile(UUID.randomUUID(), name.value, listOf(
            GameProfile.Property("textures", "old-texture", "old-signature"),
            GameProfile.Property("other-plugin", "keep", "")))
        val event = GameProfileRequestEvent(client.player, original, false)
        gate.onPrepareJavaProfile(event)
        assertEquals(uuid, event.gameProfile.id, "NORMAL skin storage must not see the temporary offline UUID")
        assertEquals(name.value, event.gameProfile.name)
        val textures = event.gameProfile.properties.filterNot { it.name == "textures" } +
            GameProfile.Property("textures", "normal-listener-texture", "normal-listener-signature")
        event.gameProfile = event.gameProfile.withProperties(textures)
        gate.onGameProfile(event)
        assertEquals(uuid, event.gameProfile.id)
        assertEquals(listOf("normal-listener-texture"), event.gameProfile.properties.filter { it.name == "textures" }.map { it.value })
        assertEquals(1, event.gameProfile.properties.count { it.name == LoginGate.CONNECTION_PROPERTY })
        assertEquals("keep", event.gameProfile.properties.single { it.name == "other-plugin" }.value)
        val login = LoginEvent(client.player, "")
        gate.onLogin(login)
        assertTrue(login.result.isAllowed)
    }

    @Test
    fun `native command execution is denied while held and restored after authentication`() {
        val client = client()
        login(client)
        for (command in listOf("server lobby", "SERVER lobby", "velocity:server lobby")) {
            val event = CommandExecuteEvent(client.player, command)
            gate.onCommand(event)
            assertFalse(event.result.isAllowed, command)
            assertFalse(event.result.isForwardToServer, command)
        }
        val allowed = CommandExecuteEvent(client.player, "help")
        gate.onCommand(allowed)
        assertTrue(allowed.result.isAllowed)
        services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        gate.onJoin(client.limbo)
        services.runWork()
        services.advanceBy(1_000L)
        val restored = CommandExecuteEvent(client.player, "server lobby")
        gate.onCommand(restored)
        assertTrue(restored.result.isAllowed)
    }

    @Test
    fun `native aliases rewritten commands and forced backend forwarding cannot bypass a configured root`() {
        val client = client()
        login(client)
        aliases["srv"] = setOf("server", "srv")
        val alias = CommandExecuteEvent(client.player, "srv lobby")
        gate.onCommand(alias)
        assertFalse(alias.result.isAllowed)
        val rewritten = CommandExecuteEvent(client.player, "help")
        rewritten.result = CommandExecuteEvent.CommandResult.command("server lobby")
        gate.onCommand(rewritten)
        assertFalse(rewritten.result.isAllowed)
        val forward = CommandExecuteEvent(client.player, "server lobby")
        forward.result = CommandExecuteEvent.CommandResult.forwardToServer()
        gate.onCommand(forward)
        assertFalse(forward.result.isAllowed)
        assertFalse(forward.result.isForwardToServer)
    }

    @Test
    fun `native console and an existing plugin denial are never overridden`() {
        val console = stub<ConsoleCommandSource> { method, _ -> error(method) }
        val administrative = CommandExecuteEvent(console, "server lobby")
        gate.onCommand(administrative)
        assertTrue(administrative.result.isAllowed)
        val client = client()
        services.decision = AuthCoordinator.Decision.Admit(record.toIdentity(), record)
        login(client)
        val denied = CommandExecuteEvent(client.player, "server lobby")
        denied.result = CommandExecuteEvent.CommandResult.denied()
        gate.onCommand(denied)
        assertFalse(denied.result.isAllowed)
    }

    @Test
    fun `native command tree hides configured roots aliases and redirects only while held`() {
        val client = client()
        login(client)
        aliases["srv"] = setOf("server", "srv")
        fun tree() = RootCommandNode<Any>().apply {
            val server = LiteralArgumentBuilder.literal<Any>("server").build()
            addChild(server)
            addChild(LiteralArgumentBuilder.literal<Any>("velocity:server").build())
            addChild(LiteralArgumentBuilder.literal<Any>("srv").build())
            addChild(LiteralArgumentBuilder.literal<Any>("shortcut").redirect(server).build())
            addChild(LiteralArgumentBuilder.literal<Any>("help").build())
        }
        val held = tree()
        gate.onAvailableCommands(PlayerAvailableCommandsEvent(client.player, held))
        assertEquals(listOf("help"), held.children.map { it.name })
        services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        gate.onJoin(client.limbo)
        services.runWork()
        services.advanceBy(1_000L)
        val authenticated = tree()
        gate.onAvailableCommands(PlayerAvailableCommandsEvent(client.player, authenticated))
        assertEquals(5, authenticated.children.size)
    }

    @Test
    fun `native legacy completion removes blocked roots and arguments without hiding allowed suggestions`() {
        val client = client()
        login(client)
        val roots = TabCompleteEvent(client.player, "/", mutableListOf("/server", "/help", "/velocity:server"))
        gate.onTabComplete(roots)
        assertEquals(listOf("/help"), roots.suggestions)
        val arguments = TabCompleteEvent(client.player, "/server ", mutableListOf("lobby", "login"))
        gate.onTabComplete(arguments)
        assertTrue(arguments.suggestions.isEmpty())
    }

    @Test
    fun `ready verification title stays visible without restarting at three second boundaries`() {
        services.pollResult = DeviceCodeFlow.PollResult.Pending
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        assertTrue(client.limbo.titles.isEmpty(), "No title is shown before the challenge is ready")
        services.runWork()
        assertEquals(1, client.limbo.titles.size)
        assertEquals(10, client.limbo.titles.single().fadeIn)
        val originalMessages = client.limbo.messages.toList()
        assertTrue(originalMessages.flatMap { it.children }.any {
            it.click == TextComponent.Click.OpenUrl("https://example.invalid")
        })
        assertTrue(originalMessages.flatMap { it.children }.any { it.text.contains("test-code") })

        listOf(2_999L, 1L, 1L, 2_998L, 1L, 3_000L).forEach { elapsed ->
            services.advanceBy(elapsed)
            assertEquals(1, client.limbo.titles.size, "The initial title must survive every three-second boundary")
            assertEquals(originalMessages, client.limbo.messages, "The clickable chat prompt is not repeated or changed")
        }
        assertEquals(0, client.limbo.titleClears)
        assertFalse(services.timers.any { it.repeating }, "Waiting verification has no periodic title task")
    }

    @Test
    fun `title stay uses the remaining verification deadline after startup work`() {
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        now += 12_345L
        services.runWork()
        val title = client.limbo.titles.single()
        assertEquals(5_754, title.stay, "287655 ms remaining is rounded up to 5754 ticks")
        assertEquals(10, title.fadeIn)
        assertEquals(10, title.fadeOut)
    }

    @Test
    fun `late startup challenge cannot overwrite or clear a restarted verification title`() {
        services.pollResult = DeviceCodeFlow.PollResult.Pending
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        val oldTimers = services.timers.toList()
        services.duringBegin = {
            services.duringBegin = {}
            assertTrue(gate.restartVerification(uuid))
        }
        services.runWork()
        oldTimers.forEach { it.runEvenIfCancelled() }
        services.advanceBy(9_000L)
        assertEquals(2, services.beginCalls)
        assertEquals(1, client.limbo.titles.size)
        assertEquals(0, client.limbo.titleClears)
        assertEquals(1, gate.verifyingCount())
    }

    @Test
    fun `challenge arriving after the deadline never shows a title or ready chat prompt`() {
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        services.duringBegin = { services.advanceBy(300_000L) }
        services.runWork()
        assertEquals(1, client.disconnects)
        assertTrue(client.limbo.titles.isEmpty())
        assertEquals(0, client.limbo.titleClears)
        assertFalse(client.limbo.messages.flatMap { it.children }.any { it.click != null })
    }

    @Test
    fun `very long verification deadlines clamp title ticks without integer overflow`() {
        val longGate = LoginGate(proxy,
            ProxyConfig(mapOf("verification.timeout-seconds" to Int.MAX_VALUE.toString()), File("unused-test-config")),
            MessageCatalogue("en_US", File("unused-test-messages")), services, NOPLogger.NOP_LOGGER, { now },
        ).also { it.activate() }
        val client = client()
        login(client, gate = longGate)
        longGate.onJoin(client.limbo)
        services.runWork()
        assertEquals(Int.MAX_VALUE, client.limbo.titles.single().stay)
    }

    @Test
    fun `the final millisecond remains visible until the actual deadline then clears once`() {
        services.pollResult = DeviceCodeFlow.PollResult.Pending
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        now += 299_999L
        services.runWork()
        assertEquals(1, client.limbo.titles.single().stay)
        services.advanceBy(1L)
        assertEquals(listOf("show-title", "clear-title", "disconnect"), client.limbo.events)
        assertEquals(0, gate.verifyingCount())
        gate.onLeave(client.limbo)
        gate.onDisconnect(DisconnectEvent(client.player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN))
        assertEquals(1, client.limbo.titleClears)
    }

    @Test
    fun `declined and expired device codes clear the title before disconnecting`() {
        listOf(DeviceCodeFlow.PollResult.Declined, DeviceCodeFlow.PollResult.Expired).forEach { result ->
            services.pollResult = result
            val client = client()
            login(client)
            gate.onJoin(client.limbo)
            services.runWork()
            services.runLastTimer()
            services.runWork()
            assertEquals(listOf("show-title", "clear-title", "disconnect"), client.limbo.events)
            gate.onLeave(client.limbo)
            assertEquals(1, client.limbo.titleClears)
        }
    }

    @Test
    fun `restart clears only the old title and stale callbacks cannot clear the new title`() {
        services.pollResult = DeviceCodeFlow.PollResult.Pending
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        services.runWork()
        val oldTimers = services.timers.toList()
        services.runLastTimer() // Capture an old poll in the worker queue before restarting.
        assertTrue(gate.restartVerification(uuid))
        assertEquals(listOf("show-title", "clear-title", "show-title"), client.limbo.events)
        assertTrue(oldTimers.all { it.isCancelled })
        oldTimers.forEach { it.runEvenIfCancelled() }
        while (services.work.isNotEmpty()) services.runWork()
        services.advanceBy(9_000L)
        assertEquals(2, client.limbo.titles.size)
        assertEquals(1, client.limbo.titleClears)
        assertEquals(0, client.disconnects)
        assertEquals(1, gate.verifyingCount())
    }

    @Test
    fun `leaving or cancelling verification clears its title exactly once`() {
        val leaving = client()
        login(leaving)
        gate.onJoin(leaving.limbo)
        services.runWork()
        gate.onLeave(leaving.limbo)
        gate.onDisconnect(DisconnectEvent(leaving.player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN))
        assertEquals(listOf("show-title", "clear-title", "disconnect"), leaving.limbo.events)

        val cancelled = client()
        login(cancelled)
        gate.onJoin(cancelled.limbo)
        services.runWork()
        gate.deactivate()
        gate.onLeave(cancelled.limbo)
        assertEquals(listOf("show-title", "clear-title", "disconnect"), cancelled.limbo.events)
        assertTrue(services.timers.all { it.isDone })
    }

    @Test
    fun `title clear IO failure cannot leave timers running or prevent the timeout disconnect`() {
        services.pollResult = DeviceCodeFlow.PollResult.Pending
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        services.runWork()
        client.limbo.clearFailure = java.io.IOException("simulated closed title channel")
        services.advanceBy(300_000L)
        assertEquals(listOf("show-title", "clear-title", "disconnect"), client.limbo.events)
        assertTrue(services.timers.all { it.isDone })
        assertEquals(0, gate.verifyingCount())
        gate.onLeave(client.limbo)
        gate.onDisconnect(DisconnectEvent(client.player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN))
        assertEquals(1, client.limbo.titleClears)
    }

    @Test
    fun `directory limitation exposes its real reason rather than an unqualified busy message`() {
        services.decision = AuthCoordinator.Decision.Reject(AuthCoordinator.RejectReason.DIRECTORY_THROTTLED,
            "retry after 17 seconds")
        val attempt = PreLoginEvent(client().player, name.value, uuid)
        gate.onPreLogin(attempt)
        assertFalse(attempt.result.isAllowed)
        assertFalse(attempt.result.isForceOfflineMode)
        assertTrue(attempt.result.reasonComponent.orElseThrow().toString().contains("17 seconds"))
    }

    @Test
    fun `an offline UUID claiming a premium name is rejected before a session request`() {
        services.decision = AuthCoordinator.Decision.MojangSession(uuid)
        val attempt = PreLoginEvent(client().player, name.value, UUID.randomUUID())
        gate.onPreLogin(attempt)
        assertFalse(attempt.result.isAllowed, "A mismatched UUID needs the premium-name message, not Mojang authentication")
    }

    @Test
    fun `matching a public premium UUID never bypasses the real session check`() {
        services.decision = AuthCoordinator.Decision.MojangSession(uuid)
        val attempt = PreLoginEvent(client().player, name.value, uuid)
        gate.onPreLogin(attempt)
        assertTrue(attempt.result.isOnlineModeAllowed)
        val backendRequest = ServerPreConnectEvent(client().player, backend)
        gate.onPreConnect(backendRequest)
        assertFalse(backendRequest.result.isAllowed)
    }

    @Test
    fun `repeated premium reconnects are rejected before forcing another handshake`() {
        services.decision = AuthCoordinator.Decision.MojangSession(uuid)
        val firstClient = client(online = true)
        val first = PreLoginEvent(firstClient.player, name.value, uuid)
        gate.onPreLogin(first)
        assertTrue(first.result.isAllowed)
        val repeated = PreLoginEvent(client().player, name.value, uuid)
        gate.onPreLogin(repeated)
        assertFalse(repeated.result.isAllowed)
        now += 5_000
        val stillRunning = PreLoginEvent(client().player, name.value, uuid)
        gate.onPreLogin(stillRunning)
        assertFalse(stillRunning.result.isAllowed, "the minimum interval cannot bypass an in-flight lease")
        val verified = LoginEvent(firstClient.player, "")
        gate.onLogin(verified)
        assertTrue(verified.result.isAllowed)
        val later = PreLoginEvent(client().player, name.value, uuid)
        gate.onPreLogin(later)
        assertTrue(later.result.isOnlineModeAllowed)
    }

    @Test
    fun `a failed initial handshake blocks the ten-second reconnect from the reported logs`() {
        services.decision = AuthCoordinator.Decision.MojangSession(uuid)
        val firstClient = client(online = true)
        val first = PreLoginEvent(firstClient.player, name.value, uuid)
        gate.onPreLogin(first)
        assertTrue(first.result.isAllowed)
        now += 5_000
        firstClient.active = false
        services.runLastTimer() // Initial-login disconnect has no Player DisconnectEvent in Velocity.
        now += 5_000
        val repeated = PreLoginEvent(client().player, name.value, uuid)
        gate.onPreLogin(repeated)
        assertFalse(repeated.result.isAllowed)
        now += 25_000
        val later = PreLoginEvent(client().player, name.value, uuid)
        gate.onPreLogin(later)
        assertTrue(later.result.isOnlineModeAllowed)
    }

    @Test
    fun `a timed-out premium handshake cannot authenticate after its lease was released`() {
        services.decision = AuthCoordinator.Decision.MojangSession(uuid)
        val initial = client(online = true)
        val first = PreLoginEvent(initial.player, name.value, uuid)
        gate.onPreLogin(first)
        now += 30_000
        services.runLastTimer()
        assertEquals(1, initial.disconnects)
        val late = LoginEvent(initial.player, "")
        gate.onLogin(late)
        assertFalse(late.result.isAllowed)
        assertEquals(0, services.premiumCalls)
    }

    @Test
    fun `premium handshake requests have a separate global budget`() {
        services.decision = AuthCoordinator.Decision.MojangSession(uuid)
        repeat(60) { index ->
            val attempt = PreLoginEvent(client().player, "Name$index", uuid)
            gate.onPreLogin(attempt)
            assertTrue(attempt.result.isAllowed)
        }
        val overBudget = PreLoginEvent(client().player, "OneMoreName", uuid)
        gate.onPreLogin(overBudget)
        assertFalse(overBudget.result.isAllowed)
    }

    @Test
    fun `fallback before the first limbo connection never enters a backend`() {
        val client = client()
        login(client)

        val fallback = ServerPreConnectEvent(client.player, backend)
        gate.onPreConnect(fallback)
        assertFalse(fallback.result.isAllowed, "No currentServer is not proof of authentication")
    }

    @Test
    fun `an untracked player is not implicitly authenticated`() {
        val event = ServerPreConnectEvent(client().player, backend)
        gate.onPreConnect(event)
        assertFalse(event.result.isAllowed)
    }

    @Test
    fun `premium database refusal denies login synchronously`() {
        services.decision = AuthCoordinator.Decision.MojangSession(uuid)
        services.premium = { AuthCoordinator.ClaimOutcome.Refused(AuthCoordinator.RejectReason.NAME_TAKEN, "test") }
        val event = login(client(online = true), allowed = false)
        assertFalse(event.result.isAllowed, "Premium binding rejection must not be an async warning")
        assertEquals(1, services.premiumCalls)
    }

    @Test
    fun `verifying slash server and a redirected limbo request are both blocked`() {
        val client = client()
        login(client)
        val limboRequest = ServerPreConnectEvent(client.player, limbo)
        gate.onPreConnect(limboRequest)
        assertTrue(limboRequest.result.isAllowed)
        gate.onJoin(client.limbo)
        val command = ServerPreConnectEvent(client.player, backend)
        gate.onPreConnect(command)
        assertFalse(command.result.isAllowed)
        val redirected = ServerPreConnectEvent(client.player, limbo)
        redirected.result = ServerPreConnectEvent.ServerResult.allowed(backend)
        gate.onPreConnect(redirected)
        assertFalse(redirected.result.isAllowed, "Guard the final target, not originalServer")
    }

    @Test
    fun `successful verification authorizes only this player without a consumable release token`() {
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        services.runWork()
        val oldTimers = services.timers.toList()
        services.runLastTimer()
        services.runWork()
        assertEquals(1, client.requests)
        assertEquals(1, services.grants)
        assertEquals(listOf("show-title", "clear-title", "connect"), client.limbo.events)
        assertTrue(oldTimers.all { it.isCancelled })
        repeat(2) {
            val event = ServerPreConnectEvent(client.player, backend)
            gate.onPreConnect(event)
            assertTrue(event.result.isAllowed)
        }
        val sameUuid = client()
        login(sameUuid)
        gate.onJoin(sameUuid.limbo)
        services.runWork()
        oldTimers.forEach { it.runEvenIfCancelled() }
        while (services.work.isNotEmpty()) services.runWork()
        val event = ServerPreConnectEvent(sameUuid.player, backend)
        gate.onPreConnect(event)
        assertFalse(event.result.isAllowed)
        gate.onLeave(client.limbo)
        assertEquals(1, client.limbo.titleClears)
        assertEquals(1, sameUuid.limbo.titles.size)
        assertEquals(0, sameUuid.limbo.titleClears, "Old completion and timeout cannot clear the replacement's title")
        assertEquals(0, client.disconnects, "Successful backend handoff is not a failed verification")
    }

    @Test
    fun `late xbox result from a disconnected connection cannot grant or release its replacement`() {
        val old = client()
        login(old)
        gate.onJoin(old.limbo)
        services.runWork()
        lateinit var replacement: Client
        services.duringExchange = {
            old.active = false
            gate.onDisconnect(DisconnectEvent(old.player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN))
            replacement = client()
            login(replacement)
            gate.onJoin(replacement.limbo)
        }
        services.runLastTimer()
        services.runWork()
        assertEquals(0, services.bindCalls)
        assertEquals(0, services.grants)
        assertEquals(0, old.requests)
        assertEquals(0, replacement.requests)
        services.runWork()
        gate.onLeave(old.limbo)
        assertEquals(1, old.limbo.titleClears)
        assertEquals(1, replacement.limbo.titles.size)
        assertEquals(0, replacement.limbo.titleClears)
        assertEquals(1, gate.verifyingCount(), "Old onLeave must not remove the replacement")
        val event = ServerPreConnectEvent(replacement.player, backend)
        gate.onPreConnect(event)
        assertFalse(event.result.isAllowed)
    }

    @Test
    fun `an unknown old or duplicate limbo player is never released`() {
        val client = client()
        gate.onJoin(client.limbo)
        assertEquals(1, client.limbo.disconnects)
        login(client)
        val stale = LimboHandle(UUID.randomUUID())
        gate.onJoin(stale)
        assertEquals(1, stale.disconnects)
        assertTrue(services.work.isEmpty())
        gate.onJoin(client.limbo)
        gate.onJoin(client.limbo)
        assertEquals(1, services.work.size)
        assertEquals(0, client.requests)
    }

    @Test
    fun `timeout while xbox is in flight prevents authorization and session grants`() {
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        services.runWork()
        services.duringExchange = { services.timers.first { it.delay == config.verificationTimeoutSeconds * 1000L }.run() }
        services.runLastTimer()
        services.runWork()
        assertEquals(1, client.disconnects)
        assertEquals(0, services.grants)
        assertEquals(0, client.requests)
        assertEquals(listOf("show-title", "clear-title", "disconnect"), client.limbo.events)
        gate.onLeave(client.limbo)
        assertEquals(1, client.limbo.titleClears)
    }

    @Test
    fun `expired pending decision and missing limbo fail closed before a backend exists`() {
        val client = client()
        val pre = PreLoginEvent(client.player, name.value, uuid)
        gate.onPreLogin(pre)
        now += TimeUnit.MINUTES.toMillis(6)
        val expiredLogin = LoginEvent(client.player, "")
        gate.onLogin(expiredLogin)
        assertFalse(expiredLogin.result.isAllowed)

        val fresh = client()
        login(fresh)
        limboRegistered = false
        val choose = PlayerChooseInitialServerEvent(fresh.player, backend)
        gate.onChooseInitialServer(choose)
        assertTrue(choose.initialServer.isEmpty)
        assertEquals(1, fresh.disconnects)
        val fallback = ServerPreConnectEvent(fresh.player, backend)
        gate.onPreConnect(fallback)
        assertFalse(fallback.result.isAllowed)
    }

    @Test
    fun `limbo failure kick cancels automatic backend redirection`() {
        val client = client()
        login(client)
        val kick = KickedFromServerEvent(client.player, limbo, null, true,
            KickedFromServerEvent.RedirectPlayer.create(backend))
        gate.onKicked(kick)
        assertIs<KickedFromServerEvent.DisconnectPlayer>(kick.result)
        val fallback = ServerPreConnectEvent(client.player, backend)
        gate.onPreConnect(fallback)
        assertFalse(fallback.result.isAllowed)
    }

    @Test
    fun `limbo arrival timeout closes a player even without onJoin`() {
        val client = client()
        login(client)
        services.timers.single().run()
        assertEquals(1, client.disconnects)
        val event = ServerPreConnectEvent(client.player, backend)
        gate.onPreConnect(event)
        assertFalse(event.result.isAllowed)
    }

    @Test
    fun `denied post verification connection disconnects only the captured player`() {
        val client = client()
        client.result = ConnectionRequestBuilder.Status.CONNECTION_CANCELLED
        login(client)
        gate.onJoin(client.limbo)
        services.runWork()
        services.runLastTimer()
        services.runWork()
        assertEquals(1, client.requests)
        assertEquals(1, client.disconnects)
    }

    @Test
    fun `premium exceptions are denied and successful binds admit only online players`() {
        services.decision = AuthCoordinator.Decision.MojangSession(uuid)
        services.premium = { error("test database unavailable") }
        login(client(online = true), allowed = false)
        now += 5_000 // A distinct permitted retry, not a burst stopped by the handshake limiter.
        services.premium = { AuthCoordinator.ClaimOutcome.Bound(record) }
        val online = client(online = true)
        login(online)
        val event = ServerPreConnectEvent(online.player, backend)
        gate.onPreConnect(event)
        assertTrue(event.result.isAllowed)
        now += 5_000
        login(client(online = false), allowed = false)
    }

    @Test
    fun `native Bedrock prelogin profile and login use fresh proofs of the same channel and retain textures`() {
        val client = client(playerName = ".Bedrock")
        val source = Any()
        val seen = mutableListOf<InboundConnection>()
        val bedrock = bedrockGate { native ->
            seen += native
            BedrockLogin.Verified(bedrockProof(source))
        }
        val pre = PreLoginEvent(client.player, ".Bedrock", uuid)
        bedrock.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
        assertTrue(pre.result.isForceOfflineMode)
        val textures = GameProfile.Property("textures", "bedrock-texture", "bedrock-signature")
        val profile = GameProfileRequestEvent(client.player, GameProfile(uuid, ".Bedrock", listOf(textures)), false)
        bedrock.onGameProfile(profile)
        assertEquals(uuid, profile.gameProfile.id)
        assertEquals(".Bedrock", profile.gameProfile.name)
        assertEquals(listOf(textures), profile.gameProfile.properties.filter { it.name == "textures" })
        val login = LoginEvent(client.player, "")
        bedrock.onLogin(login)
        assertTrue(login.result.isAllowed)
        assertTrue(bedrock.common.isAuthenticated(bedrock.ports.player(client.player)))
        assertEquals(listOf<InboundConnection>(client.player, client.player, client.player), seen)
        assertEquals(0, services.beginCalls)
        assertEquals(0, services.bindCalls)
        assertEquals(0, services.premiumCalls)
        assertEquals(0, services.grants)
        assertTrue(services.work.isEmpty())
        bedrock.deactivate()
    }

    @Test
    fun `native Bedrock final profile UUID and name cannot replace the proven identity`() {
        for ((id, displayName) in listOf(UUID(0L, 2L) to ".Bedrock", uuid to ".Impostor")) {
            val client = client(playerName = ".Bedrock")
            val source = Any()
            val bedrock = bedrockGate { BedrockLogin.Verified(bedrockProof(source)) }
            val pre = PreLoginEvent(client.player, ".Bedrock", uuid)
            bedrock.onPreLogin(pre)
            assertTrue(pre.result.isAllowed)
            val profile = GameProfileRequestEvent(client.player, GameProfile(id, displayName, emptyList()), false)
            bedrock.onGameProfile(profile)
            val login = LoginEvent(client.player, "")
            bedrock.onLogin(login)
            assertFalse(login.result.isAllowed)
            assertFalse(bedrock.common.isAuthenticated(bedrock.ports.player(client.player)))
            assertEquals(0, client.requests)
            bedrock.deactivate()
        }
    }

    @Test
    fun `same XUID on another channel cannot reuse the native prelogin proof`() {
        val client = client(playerName = ".Bedrock")
        var source = Any()
        val bedrock = bedrockGate { BedrockLogin.Verified(bedrockProof(source)) }
        val pre = PreLoginEvent(client.player, ".Bedrock", uuid)
        bedrock.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
        source = Any()
        bedrock.onGameProfile(GameProfileRequestEvent(client.player, GameProfile(uuid, ".Bedrock", emptyList()), false))
        val login = LoginEvent(client.player, "")
        bedrock.onLogin(login)
        assertFalse(login.result.isAllowed)
        assertFalse(bedrock.common.isAuthenticated(bedrock.ports.player(client.player)))
        bedrock.deactivate()
    }

    @Test
    fun `fresh native login probe rejects a provider that became unavailable after profile`() {
        val client = client(playerName = ".Bedrock")
        val source = Any()
        var available = true
        var probes = 0
        val bedrock = bedrockGate {
            probes++
            if (available) BedrockLogin.Verified(bedrockProof(source)) else BedrockLogin.Rejected(BedrockFailure.STALE)
        }
        val pre = PreLoginEvent(client.player, ".Bedrock", uuid)
        bedrock.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
        bedrock.onGameProfile(GameProfileRequestEvent(client.player, GameProfile(uuid, ".Bedrock", emptyList()), false))
        available = false
        val login = LoginEvent(client.player, "")
        bedrock.onLogin(login)
        assertFalse(login.result.isAllowed)
        assertEquals(3, probes)
        bedrock.deactivate()
    }

    @Test
    fun `native Bedrock never overrides another plugin prelogin or login denial`() {
        val client = client(playerName = ".Bedrock")
        val source = Any()
        val bedrock = bedrockGate { BedrockLogin.Verified(bedrockProof(source)) }
        val pre = PreLoginEvent(client.player, ".Bedrock", uuid)
        val preDenial = PreLoginEvent.PreLoginComponentResult.denied(Component.text("Other plugin prelogin denial"))
        pre.result = preDenial
        bedrock.onPreLogin(pre)
        assertSame(preDenial, pre.result)
        val login = LoginEvent(client.player, "")
        val loginDenial = com.velocitypowered.api.event.ResultedEvent.ComponentResult.denied(Component.text("Other plugin login denial"))
        login.result = loginDenial
        bedrock.onLogin(login)
        assertSame(loginDenial, login.result)
        bedrock.deactivate()
    }

    private fun bedrockGate(probe: (InboundConnection) -> BedrockLogin) = LoginGate(
        proxy, ProxyConfig(mapOf("bedrock.enabled" to "true"), File("unused-bedrock-config")),
        MessageCatalogue("en_US", File("unused-test-messages")), services, NOPLogger.NOP_LOGGER, { now },
        bedrockProbe = probe,
    ).also { it.activate() }

    private fun bedrockProof(source: Any) = BedrockProof.create(
        Xuid(1L), uuid, ".Bedrock", "Bedrock", ".", source, { true },
    )

    @Test
    fun `forwarded profile UUID and connection marker belong to the exact prelogin`() {
        val client = client()
        gate.onPreLogin(PreLoginEvent(client.player, name.value, uuid))
        val original = GameProfile(UUID.randomUUID(), name.value, listOf(
            GameProfile.Property(LoginGate.CONNECTION_PROPERTY, UUID.randomUUID().toString(), ""),
            GameProfile.Property("textures", "test-texture", "test-signature"),
        ))
        val event = GameProfileRequestEvent(client.player, original, false)
        gate.onGameProfile(event)
        assertEquals(uuid, event.gameProfile.id)
        assertEquals(listOf(client.sessionId.toString()), event.gameProfile.properties
            .filter { it.name == LoginGate.CONNECTION_PROPERTY }.map { it.value })
        assertEquals(2, event.gameProfile.properties.size)
    }

    @Test
    fun `unavailable gate refuses every new login and backend connection`() {
        gate.deactivate()
        val client = client()
        val pre = PreLoginEvent(client.player, name.value, uuid)
        gate.onPreLogin(pre)
        assertFalse(pre.result.isAllowed)
        val event = LoginEvent(client.player, "")
        gate.onLogin(event)
        assertFalse(event.result.isAllowed)
        val connect = ServerPreConnectEvent(client.player, backend)
        gate.onPreConnect(connect)
        assertFalse(connect.result.isAllowed)
    }

    @Test
    fun `same claimed name cannot steal a prelogin decision`() {
        val first = client()
        gate.onPreLogin(PreLoginEvent(first.player, name.value, uuid))
        val second = client()
        val stolen = LoginEvent(second.player, "")
        gate.onLogin(stolen)
        assertFalse(stolen.result.isAllowed)
        val actual = LoginEvent(first.player, "")
        gate.onLogin(actual)
        assertTrue(actual.result.isAllowed)
    }

    @Test
    fun `verification deadline still fires while start work is queued`() {
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        services.timers.last().run()
        services.runWork()
        assertEquals(1, client.disconnects)
        assertEquals(0, services.beginCalls)
        assertEquals(0, client.requests)
        assertTrue(client.limbo.titles.isEmpty())
        assertEquals(0, client.limbo.titleClears)
    }

    @Test
    fun `a restarted attempt cannot reuse an earlier poll result or timeout`() {
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        services.runWork()
        val oldTimeout = services.timers.first { it.delay == config.verificationTimeoutSeconds * 1000L }
        services.duringExchange = {
            assertTrue(gate.restartVerification(uuid))
            services.duringExchange = {}
        }
        services.runLastTimer()
        services.runWork()
        oldTimeout.runEvenIfCancelled()
        assertEquals(0, client.disconnects)
        assertEquals(0, services.bindCalls)
        assertEquals(0, client.requests)
        assertEquals(1, gate.verifyingCount())
        assertEquals(listOf("show-title", "clear-title", "show-title"), client.limbo.events)
        assertEquals(1, client.limbo.titleClears, "A late exchanged token and old timeout cannot clear the fresh title")
    }

    @Test
    fun `refused binding or failed storage write never authorizes the connection`() {
        val refused = client()
        login(refused)
        gate.onJoin(refused.limbo)
        services.microsoft = { AuthCoordinator.ClaimOutcome.Refused(AuthCoordinator.RejectReason.NAME_TAKEN, "test") }
        services.runWork()
        services.runLastTimer()
        services.runWork()
        assertEquals(1, refused.disconnects)
        assertEquals(0, refused.requests)
        assertEquals(0, services.grants)
        assertEquals(listOf("show-title", "clear-title", "disconnect"), refused.limbo.events)

        services.microsoft = { error("test database failure") }
        val failed = client()
        login(failed)
        gate.onJoin(failed.limbo)
        services.runWork()
        services.runLastTimer()
        services.runWork()
        assertEquals(1, failed.disconnects)
        assertEquals(0, failed.requests)
        assertEquals(listOf("show-title", "clear-title", "disconnect"), failed.limbo.events)
    }

    @Test
    fun `first bind grants the real identity a session but never authorizes its placeholder connection`() {
        services.decision = AuthCoordinator.Decision.Verify(uuid, AuthCoordinator.Reason.FIRST_BIND, true, null)
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        services.runWork()
        services.runLastTimer()
        services.runWork()
        assertEquals(1, services.grants)
        assertEquals(1, client.disconnects)
        assertEquals(0, client.requests)
        val event = ServerPreConnectEvent(client.player, backend)
        gate.onPreConnect(event)
        assertFalse(event.result.isAllowed)
    }

    @Test
    fun `trusted session skips limbo but stale authenticated connection cannot authorize a new player`() {
        services.decision = AuthCoordinator.Decision.Admit(record.toIdentity(), record)
        val old = client()
        login(old)
        val choose = PlayerChooseInitialServerEvent(old.player, backend)
        gate.onChooseInitialServer(choose)
        assertSame(backend, choose.initialServer.get())
        old.active = false
        gate.onDisconnect(DisconnectEvent(old.player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN))
        services.decision = AuthCoordinator.Decision.Verify(uuid, AuthCoordinator.Reason.SESSION_EXPIRED, false, null)
        val replacement = client()
        login(replacement)
        val connect = ServerPreConnectEvent(replacement.player, backend)
        gate.onPreConnect(connect)
        assertFalse(connect.result.isAllowed)
    }

    @Test
    fun `late backend request failure cannot disconnect a replacement connection`() {
        val old = client()
        val result = CompletableFuture<ConnectionRequestBuilder.Result>()
        old.resultFuture = result
        login(old)
        gate.onJoin(old.limbo)
        services.runWork()
        services.runLastTimer()
        services.runWork()
        assertEquals(1, old.requests)
        old.active = false
        gate.onDisconnect(DisconnectEvent(old.player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN))
        val replacement = client()
        login(replacement)
        result.completeExceptionally(IllegalStateException("late backend failure"))
        assertEquals(0, replacement.disconnects)
        assertEquals(0, replacement.requests)
    }

    @Test
    fun `verified automatic rename reconnects once and retires the old online name`() {
        val oldName = GameName.parseOrThrow("OldName")
        val oldRecord = BoundIdentity.of(Identity.Microsoft(oldName, Xuid(9), "SteveGT"), now)
        services.decision = AuthCoordinator.Decision.Admit(oldRecord.toIdentity(), oldRecord)
        val oldClient = client(identityUuid = oldRecord.uuid, playerName = oldName.value)
        login(oldClient)

        services.decision = AuthCoordinator.Decision.Verify(uuid, AuthCoordinator.Reason.FIRST_BIND, true, null)
        services.signedXuid = 9
        val renamed = oldRecord.copy(name = name)
        services.microsoft = { AuthCoordinator.ClaimOutcome.Bound(renamed, oldName) }
        val renamingClient = client()
        login(renamingClient)
        gate.onJoin(renamingClient.limbo)
        services.runWork()
        services.runLastTimer()
        services.runWork()
        assertEquals(1, services.grants)
        assertEquals(1, renamingClient.disconnects, "a provisional profile must reconnect on the stable XUID UUID")
        assertTrue(renamingClient.lastReason.toString().contains("OldName"))
        assertTrue(renamingClient.lastReason.toString().contains("Steve"))
        assertEquals(0, renamingClient.requests)
        assertEquals(1, oldClient.disconnects)
        val oldBackendRequest = ServerPreConnectEvent(oldClient.player, backend)
        gate.onPreConnect(oldBackendRequest)
        assertFalse(oldBackendRequest.result.isAllowed)

        services.decision = AuthCoordinator.Decision.Admit(renamed.toIdentity(), renamed)
        val reconnect = client(identityUuid = renamed.uuid)
        login(reconnect)
        val request = ServerPreConnectEvent(reconnect.player, backend)
        gate.onPreConnect(request)
        assertTrue(request.result.isAllowed)
        assertEquals(1, services.beginCalls, "the trusted reconnect must not start another device-code flow")
    }

    @Test
    fun `disabled automatic rename explains the bound name instead of promising a reconnect fix`() {
        services.microsoft = { AuthCoordinator.ClaimOutcome.Refused(AuthCoordinator.RejectReason.IDENTITY_RENAMED,
            "already named", GameName.parseOrThrow("OldName")) }
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        services.runWork()
        services.runLastTimer()
        services.runWork()
        assertEquals(0, services.grants)
        assertTrue(client.lastReason.toString().contains("OldName"))
        assertTrue(client.lastReason.toString().contains("already bound"))
    }

    @Test
    fun `a rate limited device poll waits for Retry-After rather than hammering again`() {
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        services.runWork()
        services.pollFailure = DeviceCodeFlow.DeviceCodeException(DeviceCodeFlow.FailureCode.RATE_LIMITED, 429, 180_000)
        services.runLastTimer()
        services.runWork()
        assertEquals(180_000L, services.timers.last().delay)
        assertEquals(0, services.grants)
        assertEquals(0, client.disconnects)
    }

    @Test
    fun `transient Microsoft startup failure has a distinct actionable message`() {
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        services.beginFailure = DeviceCodeFlow.DeviceCodeException(DeviceCodeFlow.FailureCode.TIMEOUT)
        services.runWork()
        assertEquals(1, client.disconnects)
        assertTrue(client.lastReason.toString().contains("temporarily unreachable"))
        assertTrue(client.limbo.messages.flatMap { it.children }.any { it.text.contains("Contacting Microsoft") })
        val fallback = ServerPreConnectEvent(client.player, backend)
        gate.onPreConnect(fallback)
        assertFalse(fallback.result.isAllowed)
    }

    @Test
    fun `Microsoft rate limit never asks the player to reconnect immediately`() {
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        services.beginFailure = DeviceCodeFlow.DeviceCodeException(DeviceCodeFlow.FailureCode.RATE_LIMITED, 429)
        services.runWork()
        assertEquals(1, client.disconnects)
        assertTrue(client.lastReason.toString().contains("rate limited"))
        assertEquals(1, services.beginCalls)
        assertEquals(0, services.grants)
    }

    @Test
    fun `a code initialization failure closes the frontend instead of falling back`() {
        val client = client()
        login(client)
        gate.onJoin(client.limbo)
        services.beginFailure = IllegalStateException("simulated Microsoft failure")
        services.runWork()
        assertEquals(1, client.disconnects)
        val fallback = ServerPreConnectEvent(client.player, backend)
        gate.onPreConnect(fallback)
        assertFalse(fallback.result.isAllowed)
        assertEquals(0, services.grants)
    }

    @Test
    fun `each supported client selects only its own protocol engine`() {
        limboRegistered = false
        (767..777).forEach { protocol ->
            val engine = registered("${LoginGate.LIMBO_SERVER_NAME}-$protocol", 26000 + protocol)
            versionedLimbos[protocol] = engine
            val client = client().apply { this.protocol = protocol }
            login(client)
            val choose = PlayerChooseInitialServerEvent(client.player, backend)
            gate.onChooseInitialServer(choose)
            assertSame(engine, choose.initialServer.orElse(null))
            val own = ServerPreConnectEvent(client.player, engine)
            gate.onPreConnect(own)
            assertTrue(own.result.isAllowed)
            val fallback = ServerPreConnectEvent(client.player, backend)
            gate.onPreConnect(fallback)
            assertFalse(fallback.result.isAllowed)
            versionedLimbos.values.filter { it !== engine }.forEach { other ->
                val wrong = ServerPreConnectEvent(client.player, other)
                gate.onPreConnect(wrong)
                assertFalse(wrong.result.isAllowed)
            }
        }
    }

    @Test
    fun `an unsupported client is told the range without reaching the backend`() {
        val client = client().apply { protocol = 765 }
        login(client)
        val choose = PlayerChooseInitialServerEvent(client.player, backend)
        gate.onChooseInitialServer(choose)
        assertTrue(choose.initialServer.isEmpty)
        assertEquals(1, client.disconnects)
        assertTrue(client.lastReason.toString().contains("1.21"))
        val fallback = ServerPreConnectEvent(client.player, backend)
        gate.onPreConnect(fallback)
        assertFalse(fallback.result.isAllowed)
        assertEquals(0, services.beginCalls)
    }

    private fun login(client: Client, allowed: Boolean = true, gate: LoginGate = this.gate): LoginEvent {
        val pre = PreLoginEvent(client.player, client.player.username, client.player.uniqueId)
        gate.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
        val event = LoginEvent(client.player, "")
        gate.onLogin(event)
        assertEquals(allowed, event.result.isAllowed)
        return event
    }

    private fun client(online: Boolean = false, identityUuid: UUID = uuid, playerName: String = name.value) =
        Client(online, identityUuid, playerName).also { players += it }

    private inner class Client(online: Boolean, identityUuid: UUID, playerName: String) {
        val sessionId: UUID = UUID.randomUUID()
        var active = true
        var protocol = 777
        var disconnects = 0
        var lastReason: Component? = null
        var requests = 0
        var result = ConnectionRequestBuilder.Status.SUCCESS
        var resultFuture: CompletableFuture<ConnectionRequestBuilder.Result>? = null
        val limbo = LimboHandle(sessionId, identityUuid, playerName)
        val player = stub<Player> { method, args ->
            when (method) {
                "getUsername" -> playerName
                "getUniqueId" -> identityUuid
                "getSessionId" -> sessionId
                "getRemoteAddress" -> InetSocketAddress("127.0.0.1", 50000)
                "getCurrentServer" -> Optional.empty<Any>()
                "getEffectiveLocale" -> Locale.US
                "getProtocolVersion" -> com.velocitypowered.api.network.ProtocolVersion.getProtocolVersion(protocol)
                "isOnlineMode" -> online
                "isActive" -> active
                "sendMessage" -> Unit
                "disconnect" -> { disconnects++; lastReason = args[0] as Component; limbo.events += "disconnect"; Unit }
                "createConnectionRequest" -> {
                    limbo.events += "connect"
                    requests++
                    stub<ConnectionRequestBuilder> { request, _ ->
                        when (request) {
                            "connect" -> resultFuture ?: CompletableFuture.completedFuture(stub<ConnectionRequestBuilder.Result> { query, _ ->
                                when (query) {
                                    "getStatus" -> result
                                    else -> error(query)
                                }
                            })
                            else -> error(request)
                        }
                    }
                }
                else -> error(method)
            }
        }
    }

    private inner class LimboHandle(override val connectionId: UUID,
        private val identityUuid: UUID = this@LoginGateTest.uuid,
        private val playerName: String = this@LoginGateTest.name.value,
    ) : LimboPlayer {
        override val name get() = playerName
        override val uuid get() = identityUuid
        override val address = "127.0.0.1"
        var disconnects = 0
        val messages = mutableListOf<TextComponent>()
        val titles = mutableListOf<TitleCall>()
        val events = mutableListOf<String>()
        var titleClears = 0
        var clearFailure: Exception? = null
        override fun sendMessage(message: TextComponent) { messages += message }
        override fun showTitle(title: TextComponent, subtitle: TextComponent?, fadeIn: Int, stay: Int, fadeOut: Int) {
            events += "show-title"
            titles += TitleCall(title, subtitle, fadeIn, stay, fadeOut)
        }
        override fun clearTitle() {
            titleClears++
            events += "clear-title"
            clearFailure?.let { throw it }
        }
        override fun disconnect(reason: TextComponent) { disconnects++ }
        override fun close() = Unit
    }

    private data class TitleCall(
        val title: TextComponent,
        val subtitle: TextComponent?,
        val fadeIn: Int,
        val stay: Int,
        val fadeOut: Int,
    )

    private inner class FakeServices : LoginGate.Services {
        var decision: AuthCoordinator.Decision = AuthCoordinator.Decision.Verify(
            uuid, AuthCoordinator.Reason.SESSION_EXPIRED, false, null,
        )
        val work = ArrayDeque<() -> Unit>()
        val timers = mutableListOf<Timer>()
        var premiumCalls = 0
        var bindCalls = 0
        var grants = 0
        var beginCalls = 0
        var beginFailure: Exception? = null
        var pollFailure: Exception? = null
        var pollResult: DeviceCodeFlow.PollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        var premium: () -> AuthCoordinator.ClaimOutcome = { AuthCoordinator.ClaimOutcome.Bound(record) }
        var microsoft: () -> AuthCoordinator.ClaimOutcome = { AuthCoordinator.ClaimOutcome.Bound(record) }
        var signedXuid = 1L
        var duringBegin: () -> Unit = {}
        var duringExchange: () -> Unit = {}
        override fun allow(key: String) = true
        override fun decide(name: GameName, address: String) = decision
        override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome {
            premiumCalls++
            return premium()
        }
        override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome {
            bindCalls++
            return microsoft()
        }
        override fun grantSession(uuid: UUID, address: String) { grants++ }
        override fun submit(task: () -> Unit) { work.addLast(task) }
        override fun schedule(delayMillis: Long, task: () -> Unit) = Timer(delayMillis, now, task).also { timers += it }
        override fun begin(): DeviceCodeFlow.Challenge {
            beginCalls++
            beginFailure?.let { throw it }
            duringBegin()
            return DeviceCodeFlow.Challenge("test-code", "https://example.invalid", "https://example.invalid", 300, 1, "test-device-code")
        }
        override fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult {
            pollFailure?.let { throw it }
            return pollResult
        }
        override fun exchange(accessToken: String): XboxLive.XstsToken {
            duringExchange()
            return XboxLive.XstsToken("test", signedXuid, "Steve", "test-token")
        }
        fun runWork() = work.removeFirst().invoke()
        fun runLastTimer() = timers.last().run()
        fun advanceBy(millis: Long) {
            val target = now + millis
            while (true) {
                val timer = timers.filter { !it.isDone && it.nextRunAt <= target }.minByOrNull { it.nextRunAt } ?: break
                now = timer.nextRunAt
                timer.run()
                while (work.isNotEmpty()) runWork()
            }
            now = target
        }
    }

    private class Timer(val delay: Long, scheduledAt: Long, private val task: () -> Unit, val repeating: Boolean = false) : ScheduledFuture<Unit> {
        private var cancelled = false
        private var completed = false
        var nextRunAt = scheduledAt + delay
            private set
        fun run() {
            if (isDone) return
            if (repeating) nextRunAt += delay else completed = true
            task()
        }
        fun runEvenIfCancelled() = task()
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean { cancelled = true; return true }
        override fun isCancelled() = cancelled
        override fun isDone() = cancelled || completed
        override fun get() = Unit
        override fun get(timeout: Long, unit: TimeUnit) = Unit
        override fun getDelay(unit: TimeUnit) = unit.convert(delay, TimeUnit.MILLISECONDS)
        override fun compareTo(other: Delayed) = getDelay(TimeUnit.MILLISECONDS).compareTo(other.getDelay(TimeUnit.MILLISECONDS))
    }

    private fun registered(name: String, port: Int) = stub<RegisteredServer> { method, _ ->
        when (method) {
            "getServerInfo" -> ServerInfo(name, InetSocketAddress("127.0.0.1", port))
            else -> error(method)
        }
    }

    private inline fun <reified T> stub(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
            when (method.name) {
                "equals" -> self === args?.get(0)
                "hashCode" -> System.identityHashCode(self)
                "toString" -> T::class.java.simpleName
                else -> invoke(method.name, args ?: emptyArray())
            }
        } as T
}
