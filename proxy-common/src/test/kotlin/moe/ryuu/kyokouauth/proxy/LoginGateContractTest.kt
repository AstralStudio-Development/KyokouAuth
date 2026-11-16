package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.core.text.RichText
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoginGateContractTest {
    @Test
    fun `every supported protocol permits only its exact registered limbo target`() {
        for (version in 767..777) {
            val fixture = Fixture()
            val player = fixture.player(version)
            fixture.login(player)
            val expected = fixture.servers.getValue(limboName(version))
            val choose = PlayerChooseInitialServerEvent(player)
            fixture.gate.onChooseInitialServer(choose)
            assertSame(expected, choose.initialServer, "protocol $version")

            val candidates = fixture.servers.values + RegisteredServer(expected.name)
            for (target in candidates) {
                val request = ServerPreConnectEvent(player, expected)
                request.result = ServerPreConnectEvent.ServerResult(Optional.of(target))
                fixture.gate.onPreConnect(request)
                if (target === expected) {
                    assertSame(expected, request.result.server.orElse(null))
                } else {
                    assertTrue(request.result.server.isEmpty,
                        "protocol $version must reject the final target ${target.name}, including a same-name impostor")
                }
            }
            assertTrue(player.disconnects.isEmpty())
            assertTrue(fixture.services.work.isEmpty())
        }
    }

    @Test
    fun `unsupported protocols are rejected even when legacy and matching named servers exist`() {
        for (version in listOf(-1, 766, 778, Int.MAX_VALUE)) {
            val fixture = Fixture()
            fixture.servers[limboName(version)] = RegisteredServer(limboName(version))
            val player = fixture.player(version)
            fixture.login(player)
            for (target in fixture.servers.values) {
                val request = ServerPreConnectEvent(player, target)
                fixture.gate.onPreConnect(request)
                assertTrue(request.result.server.isEmpty, "An unsupported held player cannot enter ${target.name}")
            }
            val choose = PlayerChooseInitialServerEvent(player)
            choose.setInitialServer(fixture.lobby)
            fixture.gate.onChooseInitialServer(choose)
            assertEquals(null, choose.initialServer, "protocol $version must not inherit a fallback")
            assertEquals(1, player.disconnects.size)
            assertEquals(0, fixture.services.beginCalls)
            assertTrue(fixture.services.work.isEmpty())
            assertTrue(fixture.services.timers.all { it.isDone })
        }
    }

    @Test
    fun `missing exact limbo registrations never fall back across older supported protocols`() {
        for (version in 767..776) {
            val fixture = Fixture()
            fixture.servers.remove(limboName(version))
            val player = fixture.player(version)
            fixture.login(player)
            val legacy = ServerPreConnectEvent(player, fixture.servers.getValue(LoginGate.LIMBO_SERVER_NAME))
            fixture.gate.onPreConnect(legacy)
            assertTrue(legacy.result.server.isEmpty, "protocol $version cannot enter the legacy 777 limbo")
            val choose = PlayerChooseInitialServerEvent(player)
            choose.setInitialServer(fixture.lobby)
            fixture.gate.onChooseInitialServer(choose)
            assertEquals(null, choose.initialServer)
            assertEquals(1, player.disconnects.size)
        }
    }

    @Test
    fun `protocol 777 retains the legacy alias only when its exact registration is absent`() {
        val fixture = Fixture()
        fixture.servers.remove(limboName(777))
        val player = fixture.player(777)
        fixture.login(player)
        val legacy = fixture.servers.getValue(LoginGate.LIMBO_SERVER_NAME)
        val choose = PlayerChooseInitialServerEvent(player)
        fixture.gate.onChooseInitialServer(choose)
        assertSame(legacy, choose.initialServer)
        val request = ServerPreConnectEvent(player, legacy)
        fixture.gate.onPreConnect(request)
        assertSame(legacy, request.result.server.orElse(null))
    }

    @Test
    fun `successful verification skips all protocol limbos when selecting the real backend`() {
        for (version in listOf(767, 777)) {
            val fixture = Fixture(mapOf("post-login.server" to limboName(768)))
            val player = fixture.player(version)
            fixture.join(player)
            fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
            fixture.services.advanceBy(1_000L)
            assertEquals(listOf(fixture.lobby), player.requests)
            assertEquals(1, fixture.services.grants)
            assertEquals(1, player.limbo.clearCount)
            assertEquals(0, fixture.gate.verifyingCount())
            assertTrue(player.disconnects.isEmpty())
        }
    }

    @Test
    fun `queued restart captures the exact player instead of following its UUID to a replacement`() {
        for (reuseSessionId in listOf(false, true)) {
            val fixture = Fixture()
            val old = fixture.player(767)
            fixture.join(old)
            val captured = fixture.proxy.getPlayer(old.uniqueId).orElseThrow()
            var restarted: Boolean? = null
            fixture.services.submit { restarted = fixture.gate.restartVerification(captured) }
            old.isActive = false
            fixture.gate.onDisconnect(DisconnectEvent(old))
            val replacement = fixture.player(777, if (reuseSessionId) old.sessionId else UUID.randomUUID())
            fixture.login(replacement)
            fixture.gate.onJoin(replacement.limbo)
            fixture.services.runWork()

            assertEquals(false, restarted)
            assertEquals(old.uniqueId, replacement.uniqueId)
            assertEquals(2, fixture.services.beginCalls, "Only the two real arrivals may request a challenge")
            assertEquals(1, old.limbo.clearCount)
            assertEquals(1, replacement.limbo.titleCount)
            assertEquals(0, replacement.limbo.clearCount)
            assertTrue(replacement.disconnects.isEmpty())
            assertEquals(1, fixture.gate.verifyingCount())
        }
    }

    @Test
    fun `stale leave disconnect and timer callbacks cannot clear a replacement session`() {
        val fixture = Fixture()
        val old = fixture.player(767)
        fixture.join(old)
        val oldTimers = fixture.services.timers.toList()
        old.isActive = false
        fixture.gate.onDisconnect(DisconnectEvent(old))
        val replacement = fixture.player(777, old.sessionId)
        fixture.join(replacement)

        fixture.gate.onLeave(old.limbo)
        fixture.gate.onDisconnect(DisconnectEvent(old))
        oldTimers.forEach { it.runEvenIfCancelled() }
        fixture.services.runWork()
        fixture.services.advanceBy(9_000L)

        assertEquals(1, old.limbo.clearCount)
        assertEquals(1, replacement.limbo.titleCount)
        assertEquals(0, replacement.limbo.clearCount)
        assertTrue(replacement.disconnects.isEmpty())
        assertTrue(replacement.requests.isEmpty())
        assertEquals(1, fixture.gate.verifyingCount())
        fixture.gate.onLeave(replacement.limbo)
        assertEquals(1, replacement.limbo.clearCount)
        assertEquals(0, fixture.gate.verifyingCount())
    }

    private class Fixture(values: Map<String, String> = emptyMap()) {
        var now = 1_000_000L
        val lobby = RegisteredServer("lobby")
        val servers = (767..777).associate { limboName(it) to RegisteredServer(limboName(it)) }.toMutableMap().apply {
            put(LoginGate.LIMBO_SERVER_NAME, RegisteredServer(LoginGate.LIMBO_SERVER_NAME))
            put(lobby.name, lobby)
        }
        private val players = mutableListOf<FakePlayer>()
        val proxy = object : ProxyServer {
            override fun getServer(name: String): Optional<RegisteredServer> = Optional.ofNullable(servers[name])
            override fun getPlayer(name: String): Optional<Player> =
                Optional.ofNullable(players.lastOrNull { it.isActive && it.username == name })
            override fun getPlayer(uuid: UUID): Optional<Player> =
                Optional.ofNullable(players.lastOrNull { it.isActive && it.uniqueId == uuid })
            override val allPlayers: Collection<Player> get() = players.filter { it.isActive }
            override val configuration = object : ProxyConfiguration {
                override val attemptConnectionOrder = listOf(LoginGate.LIMBO_SERVER_NAME) +
                    (767..777).map(::limboName) + lobby.name
            }
        }
        val services = MemoryServices { now }
        val gate = LoginGate(proxy, ProxyConfig(values, File("unused-contract-config")),
            MessageCatalogue("en_US", File("unused-contract-messages")), services, ProxyLog { _, _, _ -> }, { now },
        ).also { it.activate() }

        fun player(version: Int, sessionId: UUID = UUID.randomUUID()) =
            FakePlayer(version, sessionId).also { players += it }

        fun login(player: FakePlayer) {
            val pre = PreLoginEvent(player, player.username, player.uniqueId)
            gate.onPreLogin(pre)
            assertTrue(pre.result.isAllowed)
            val login = LoginEvent(player)
            gate.onLogin(login)
            assertTrue(login.result.isAllowed)
        }

        fun join(player: FakePlayer) {
            login(player)
            gate.onJoin(player.limbo)
            services.runWork()
            assertEquals(1, player.limbo.titleCount)
        }

        inner class MemoryServices(private val clock: () -> Long) : LoginGate.Services {
            val work = ArrayDeque<() -> Unit>()
            val timers = mutableListOf<Timer>()
            var beginCalls = 0
            var grants = 0
            var pollResult: DeviceCodeFlow.PollResult = DeviceCodeFlow.PollResult.Pending
            override fun allow(key: String) = true
            override fun decide(name: GameName, address: String) =
                AuthCoordinator.Decision.Verify(record.uuid, AuthCoordinator.Reason.SESSION_EXPIRED, false, null)
            override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome = error("No premium work expected")
            override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String) = AuthCoordinator.ClaimOutcome.Bound(record)
            override fun grantSession(uuid: UUID, address: String) { grants++ }
            override fun submit(task: () -> Unit) { work.addLast(task) }
            override fun schedule(delayMillis: Long, task: () -> Unit) =
                Timer(clock() + delayMillis, clock, task).also { timers += it }
            override fun scheduleAtFixedRate(periodMillis: Long, task: () -> Unit): ScheduledFuture<*> =
                error("Verification must not create a repeating title task")
            override fun begin(): DeviceCodeFlow.Challenge {
                beginCalls++
                return DeviceCodeFlow.Challenge("code-$beginCalls", "https://example.invalid",
                    "https://example.invalid?otc=code-$beginCalls", 300, 1, "device-$beginCalls")
            }
            override fun poll(challenge: DeviceCodeFlow.Challenge) = pollResult
            override fun exchange(accessToken: String) = XboxLive.XstsToken("test", 1L, "Steve", "test-token")
            fun runWork() { while (work.isNotEmpty()) work.removeFirst().invoke() }
            fun advanceBy(millis: Long) {
                val target = now + millis
                while (true) {
                    val timer = timers.filter { !it.isDone && it.dueAt <= target }.minByOrNull { it.dueAt } ?: break
                    now = timer.dueAt
                    timer.run()
                    runWork()
                }
                now = target
            }
        }
    }

    private class FakePlayer(override val protocolVersion: Int, override val sessionId: UUID) : Player {
        override var isActive = true
        override val uniqueId = record.uuid
        override val username = record.name.value
        override val isOnlineMode = false
        override val effectiveLocale = Locale.US
        override val remoteAddress = InetSocketAddress("127.0.0.1", 50000)
        val limbo = FakeLimbo(this)
        val disconnects = mutableListOf<RichText.Parsed>()
        val requests = mutableListOf<RegisteredServer>()
        override fun hasPermission(permission: String) = true
        override fun sendMessage(message: RichText.Parsed) = Unit
        override fun disconnect(message: RichText.Parsed) { disconnects += message }
        override fun createConnectionRequest(target: RegisteredServer): ConnectionRequestBuilder {
            requests += target
            return object : ConnectionRequestBuilder {
                override fun connect() = CompletableFuture.completedFuture(
                    ConnectionRequestBuilder.Result(ConnectionRequestBuilder.Status.SUCCESS))
            }
        }
    }

    private class FakeLimbo(private val player: FakePlayer) : LimboPlayer {
        override val name get() = player.username
        override val uuid get() = player.uniqueId
        override val connectionId get() = player.sessionId
        override val address = "127.0.0.1"
        var titleCount = 0
        var clearCount = 0
        override fun sendMessage(message: TextComponent) = Unit
        override fun showTitle(title: TextComponent, subtitle: TextComponent?, fadeIn: Int, stay: Int, fadeOut: Int) { titleCount++ }
        override fun clearTitle() { clearCount++ }
        override fun disconnect(reason: TextComponent) = error("No limbo-handle disconnect expected")
        override fun close() = Unit
    }

    private class Timer(val dueAt: Long, private val clock: () -> Long, private val task: () -> Unit) : ScheduledFuture<Unit> {
        private var cancelled = false
        private var completed = false
        fun run() {
            if (isDone) return
            completed = true
            task()
        }
        fun runEvenIfCancelled() = task()
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean { cancelled = true; return true }
        override fun isCancelled() = cancelled
        override fun isDone() = cancelled || completed
        override fun get() = Unit
        override fun get(timeout: Long, unit: TimeUnit) = Unit
        override fun getDelay(unit: TimeUnit) = unit.convert(dueAt - clock(), TimeUnit.MILLISECONDS)
        override fun compareTo(other: Delayed) = getDelay(TimeUnit.MILLISECONDS).compareTo(other.getDelay(TimeUnit.MILLISECONDS))
    }

    private companion object {
        val record = BoundIdentity.of(Identity.Microsoft(GameName.parseOrThrow("Steve"), Xuid(1L), "Steve"), 1L)
        fun limboName(version: Int) = "${LoginGate.LIMBO_SERVER_NAME}-$version"
    }
}
