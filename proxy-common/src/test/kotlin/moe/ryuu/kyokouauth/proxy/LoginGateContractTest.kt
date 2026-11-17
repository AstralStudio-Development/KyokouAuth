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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoginGateContractTest {
    @Test
    fun `configured commands stay blocked until this exact connection is authenticated`() {
        val fixture = Fixture(mapOf("verification.blocked-commands" to "/SERVER\nexample:Msg"))
        val player = fixture.player(777)
        assertTrue(fixture.gate.isCommandBlocked(null, "server lobby"))
        assertTrue(fixture.gate.isCommandBlocked(player, "server lobby"))
        fixture.join(player)
        for (command in listOf("server", "/SERVER lobby", "velocity:server lobby", "msg Steve hello")) {
            assertTrue(fixture.gate.isCommandBlocked(player, command), command)
        }
        assertFalse(fixture.gate.isCommandBlocked(player, "help"))
        assertFalse(fixture.gate.allowCommand(player, "server lobby"))
        assertTrue(player.messages.isNotEmpty())

        fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        fixture.services.advanceBy(1_000L)
        assertEquals(1, fixture.services.grants)
        assertFalse(fixture.gate.isCommandBlocked(player, "server lobby"))
        assertTrue(fixture.gate.allowCommand(player, "msg Steve hello"))
        val impostor = fixture.player(777, player.sessionId)
        assertTrue(fixture.gate.isCommandBlocked(impostor, "server lobby"))
        fixture.gate.deactivate()
        assertTrue(fixture.gate.isCommandBlocked(player, "server lobby"))
    }

    @Test
    fun `blocked command admission remains denied when feedback transport fails`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.join(player)
        player.throwOnMessage = true
        assertFalse(fixture.gate.allowCommand(player, "server lobby"))
        assertTrue(player.requests.isEmpty())
        assertEquals(0, fixture.services.grants)
    }

    @Test
    fun `wildcard ends at exact authentication while an empty list never disables backend isolation`() {
        val all = Fixture(mapOf("verification.blocked-commands" to "*"))
        val player = all.player(777)
        all.join(player)
        for (command in listOf("help", "server lobby", "example:msg Steve hello", "//wand")) {
            assertTrue(all.gate.isCommandBlocked(player, command), command)
        }
        all.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        all.services.advanceBy(1_000L)
        assertFalse(all.gate.isCommandBlocked(player, "help"))
        player.isActive = false
        assertTrue(all.gate.isCommandBlocked(player, "help"))

        val none = Fixture(mapOf("verification.blocked-commands" to ""))
        val held = none.player(777)
        none.join(held)
        assertTrue(none.gate.allowCommand(held, "server lobby"))
        val request = ServerPreConnectEvent(held, none.lobby)
        none.gate.onPreConnect(request)
        assertTrue(request.result.server.isEmpty)
        assertEquals(0, none.services.grants)
    }

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

    @Test
    fun `poll cadence honors both the production default and the upstream challenge minimum`() {
        for ((configured, upstream, expected) in listOf(Triple(null, 1, 5_000L), Triple("2", 7, 7_000L))) {
            val fixture = Fixture(configured?.let { mapOf("verification.poll-interval-seconds" to it) } ?: emptyMap(),
                fastPolling = false)
            fixture.services.challengeIntervalSeconds = upstream
            val player = fixture.player(777)
            fixture.join(player)
            fixture.services.advanceBy(expected - 1)
            assertEquals(0, fixture.services.pollCalls)
            fixture.services.advanceBy(1)
            assertEquals(1, fixture.services.pollCalls)
            assertEquals(1, player.limbo.titleCount)
            assertEquals(0, player.limbo.clearCount)
        }
    }

    @Test
    fun `latency ready prompt work consumes only the remaining response anchored interval`() {
        val fixture = Fixture(fastPolling = false)
        val player = fixture.player(777)
        val challengeReceivedAt = fixture.now
        player.limbo.duringReadyPrompt = { fixture.now += 3_000 }
        fixture.join(player)
        assertEquals(challengeReceivedAt + 5_000, fixture.services.timers.last().dueAt,
            "Three seconds spent sending the prompt must not become three extra seconds before polling")
        fixture.services.advanceBy(1_999)
        assertEquals(0, fixture.services.pollCalls)
        fixture.services.advanceBy(1)
        assertEquals(listOf(challengeReceivedAt + 5_000), fixture.services.pollRequestsAt)
        assertEquals(1, player.limbo.titleCount)
    }

    @Test
    fun `latency retry uses response anchor but never subtracts network RTT or Retry After`() {
        for ((failureCode, status, retryAfter) in listOf(
            Triple(DeviceCodeFlow.FailureCode.TRANSPORT, null, null),
            Triple(DeviceCodeFlow.FailureCode.RATE_LIMITED, 429, 12_000L),
            Triple(DeviceCodeFlow.FailureCode.UPSTREAM_UNAVAILABLE, 503, 12_000L))) {
            val fixture = Fixture(fastPolling = false)
            val player = fixture.player(777)
            fixture.join(player)
            fixture.services.pollFailure = DeviceCodeFlow.DeviceCodeException(failureCode, status, retryAfter)
            fixture.services.duringPoll = { fixture.now += 4_000 }
            fixture.duringLog = { if (it.startsWith("Microsoft sign-in poll")) fixture.now += 2_000 }
            fixture.services.timers.last().let { timer -> fixture.now = timer.dueAt; timer.run() }
            fixture.services.runWork()
            val responseReceivedAt = fixture.services.pollResponsesAt.single()
            val minimum = retryAfter ?: 5_000L
            assertEquals(responseReceivedAt + minimum, fixture.services.timers.last().dueAt,
                "Only two seconds of local warning logging may be removed; never the four second HTTP request")
            assertEquals(4_000L, responseReceivedAt - fixture.services.pollRequestsAt.single())
            fixture.services.pollFailure = null
            fixture.services.duringPoll = {}
            fixture.duringLog = {}
            fixture.services.advanceBy(minimum - 2_000)
            assertEquals(listOf(fixture.services.pollRequestsAt.first(), responseReceivedAt + minimum),
                fixture.services.pollRequestsAt)
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `latency local work exceeding the interval schedules immediately without losing the next poll`() {
        val fixture = Fixture(fastPolling = false)
        val player = fixture.player(777)
        fixture.join(player)
        fixture.services.pollFailure = DeviceCodeFlow.DeviceCodeException(DeviceCodeFlow.FailureCode.TRANSPORT)
        fixture.duringLog = {
            if (it.startsWith("Microsoft sign-in poll")) {
                fixture.now += 6_000
                fixture.services.pollFailure = null
                fixture.duringLog = {}
            }
        }
        fixture.services.timers.last().let { timer -> fixture.now = timer.dueAt; timer.run() }
        fixture.services.runWork()
        assertEquals(fixture.now, fixture.services.timers.last().dueAt)
        fixture.services.advanceBy(0)
        assertEquals(2, fixture.services.pollCalls, "A zero-delay follow-up must run after the old poll releases busy")
        assertTrue(fixture.services.pollRequestsAt[1] - fixture.services.pollResponsesAt[0] >= 5_000)
        assertEquals(0, fixture.services.grants)
        assertEquals(1, fixture.gate.verifyingCount())
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `confirmation starts immediately after a real token and timing separates waiting HTTP Xbox and binding`() {
        val fixture = Fixture(fastPolling = false)
        val player = fixture.player(777)
        fixture.services.duringBegin = { fixture.now += 125 }
        fixture.services.duringPoll = { fixture.now += 400 }
        fixture.services.duringExchange = {
            assertEquals(1, player.limbo.messages.count { it.toString().contains("confirming your Xbox identity") })
            assertEquals(fixture.services.pollResponsesAt.single(), fixture.services.exchangeRequestsAt.single(),
                "A successful token must go straight to Xbox without another interval or queue hop")
            fixture.assertBackendDenied(player)
            fixture.now += 1_200
        }
        fixture.services.duringBind = { fixture.now += 300 }
        fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        fixture.join(player)
        assertFalse(player.limbo.messages.any { it.toString().contains("confirming your Xbox identity") })
        fixture.services.timers.last().let { timer -> fixture.now = timer.dueAt; timer.run() }
        fixture.now += 700 // The timer fired, but the worker was still queued.
        fixture.services.runWork()
        assertEquals(1, fixture.services.grants)
        assertEquals(listOf(fixture.lobby), player.requests)
        assertEquals(1, player.limbo.clearCount)
        val timing = fixture.logs.single { it.startsWith("Microsoft verification timing:") }
        assertEquals("Microsoft verification timing: deviceCodeMs=125 pollRequests=1 pollHttpMs=400 pollWaitMs=5700 pollQueuedMs=700 xboxMs=1200 completionMs=300",
            timing)
        assertFalse(listOf("test-token", "code-1", "device-1", "Steve", "https://", record.uuid.toString()).any { it in timing })
    }

    @Test
    fun `pending declined expired and stale authorized polls never promise Xbox confirmation`() {
        for (result in listOf(DeviceCodeFlow.PollResult.Pending, DeviceCodeFlow.PollResult.Declined,
            DeviceCodeFlow.PollResult.Expired, DeviceCodeFlow.PollResult.Authorized("test-token", null, 300))) {
            val fixture = Fixture()
            val player = fixture.player(777)
            fixture.services.pollResult = result
            if (result is DeviceCodeFlow.PollResult.Authorized) fixture.services.duringPoll = { fixture.now += 300_000 }
            fixture.join(player)
            fixture.services.advanceBy(1_000)
            assertFalse(player.limbo.messages.any { it.toString().contains("confirming your Xbox identity") })
            assertEquals(0, fixture.services.exchangeCalls)
            assertEquals(0, fixture.services.grants)
            assertFalse(fixture.logs.any { it.startsWith("Microsoft verification timing:") })
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `failed or deadline crossing confirmation IO still fails closed before Xbox work`() {
        for (throwFailure in listOf(false, true)) {
            val fixture = Fixture(mapOf("verification.timeout-seconds" to "2"))
            val player = fixture.player(777)
            fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
            fixture.join(player)
            player.limbo.duringMessage = {
                if (it.toString().contains("confirming your Xbox identity")) {
                    if (throwFailure) throw java.io.IOException("Synthetic closed chat channel")
                    fixture.now += 2_000
                }
            }
            fixture.services.advanceBy(1_000)
            assertEquals(1, player.disconnects.size)
            assertEquals(1, player.limbo.clearCount)
            assertEquals(0, fixture.services.exchangeCalls)
            assertEquals(0, fixture.services.bindCalls)
            assertEquals(0, fixture.services.grants)
            assertTrue(fixture.services.timers.all { it.isDone })
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `duplicate poll callback during a request cannot start another request or confirmation`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.join(player)
        val timer = fixture.services.timers.last()
        fixture.services.duringPoll = {
            fixture.services.duringPoll = {}
            timer.runEvenIfCancelled()
            fixture.services.runWork()
            assertEquals(1, fixture.services.pollCalls)
        }
        fixture.services.advanceBy(1_000)
        assertEquals(1, fixture.services.pollCalls)
        assertEquals(0, fixture.services.exchangeCalls)
        fixture.services.advanceBy(1_000)
        assertEquals(2, fixture.services.pollCalls)
        assertFalse(player.limbo.messages.any { it.toString().contains("confirming your Xbox identity") })
    }

    @Test
    fun `consecutive slowdown uses exact five second increments without exponential waiting`() {
        for ((upstream, intervals) in listOf(
            5 to listOf(5_000L, 10_000L, 15_000L, 20_000L),
            90 to listOf(90_000L, 95_000L, 100_000L))) {
            val fixture = Fixture(mapOf("verification.timeout-seconds" to "600"), fastPolling = false)
            val player = fixture.player(777)
            fixture.services.challengeIntervalSeconds = upstream
            fixture.services.pollResult = DeviceCodeFlow.PollResult.SlowDown
            fixture.join(player)
            val readyAt = fixture.now
            for ((index, interval) in intervals.withIndex()) {
                assertEquals(fixture.now + interval, fixture.services.timers.last().dueAt,
                    "slow_down adds exactly five seconds, not an unnecessary exponential delay")
                fixture.services.advanceBy(interval - 1)
                assertEquals(index, fixture.services.pollCalls, "Never poll below the upstream minimum")
                if (index == intervals.lastIndex) {
                    fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
                }
                fixture.services.advanceBy(1)
                assertEquals(index + 1, fixture.services.pollCalls)
            }
            assertEquals(readyAt + intervals.sum(), fixture.now)
            assertEquals(1, fixture.services.grants)
            assertEquals(listOf(fixture.lobby), player.requests)
            assertEquals(1, player.limbo.titleCount)
            assertEquals(1, player.limbo.clearCount)
            assertTrue(player.disconnects.isEmpty())
        }
    }

    @Test
    fun `every slowdown adds at least five seconds even after sixty second backoff ceiling`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.services.challengeIntervalSeconds = 60
        fixture.services.pollResult = DeviceCodeFlow.PollResult.SlowDown
        fixture.join(player)
        fixture.services.advanceBy(60_000)
        assertEquals(1, fixture.services.pollCalls)
        fixture.services.advanceBy(64_999)
        assertEquals(1, fixture.services.pollCalls)
        fixture.services.advanceBy(1)
        assertEquals(2, fixture.services.pollCalls)
        assertEquals(fixture.now + 70_000, fixture.services.timers.last().dueAt)
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `ready prompt sends the complete clickable URL once and successful callbacks are idempotent`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.join(player)
        val initialMessages = player.limbo.messages.toList()
        assertTrue(initialMessages.flatMap { it.children }.any {
            it.click == TextComponent.Click.OpenUrl("https://example.invalid?otc=code-1")
        })
        fixture.services.advanceBy(9_000)
        assertEquals(initialMessages, player.limbo.messages)
        assertEquals(1, player.limbo.titleCount)
        val timers = fixture.services.timers.toList()
        fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        fixture.services.advanceBy(1_000)
        timers.forEach { it.runEvenIfCancelled() }
        fixture.services.runWork()
        fixture.gate.onLeave(player.limbo)
        assertEquals(1, fixture.services.bindCalls)
        assertEquals(1, fixture.services.grants)
        assertEquals(1, player.limbo.clearCount)
        assertEquals(listOf(fixture.lobby), player.requests)
        assertTrue(player.disconnects.isEmpty())
    }

    @Test
    fun `slowdown and retry after never reduce a challenge or configured minimum`() {
        for (upstream in listOf(1, 90)) {
            val fixture = Fixture()
            fixture.services.challengeIntervalSeconds = upstream
            fixture.services.pollResult = DeviceCodeFlow.PollResult.SlowDown
            val player = fixture.player(777)
            fixture.join(player)
            fixture.services.advanceBy(upstream * 1000L)
            assertEquals(1, fixture.services.pollCalls)
            val next = if (upstream == 1) 6_000L else 95_000L
            fixture.services.advanceBy(next - 1)
            assertEquals(1, fixture.services.pollCalls)
            fixture.services.advanceBy(1)
            assertEquals(2, fixture.services.pollCalls)
            assertEquals(1, player.limbo.titleCount)
        }
        val fixture = Fixture(mapOf("verification.poll-interval-seconds" to "7"))
        fixture.services.pollFailure = DeviceCodeFlow.DeviceCodeException(
            DeviceCodeFlow.FailureCode.RATE_LIMITED, 429, 12_000L)
        val player = fixture.player(777)
        fixture.join(player)
        fixture.services.advanceBy(7_000)
        fixture.services.pollFailure = null
        fixture.services.advanceBy(11_999)
        assertEquals(1, fixture.services.pollCalls)
        fixture.services.advanceBy(1)
        assertEquals(2, fixture.services.pollCalls)
        assertEquals(1, player.limbo.titleCount)
    }

    @Test
    fun `worker and scheduler startup rejection immediately close the captured frontend`() {
        for (rejectWorker in listOf(false, true)) {
            val fixture = Fixture()
            val player = fixture.player(777)
            fixture.login(player)
            fixture.services.rejectSubmissions = rejectWorker
            fixture.services.rejectSchedules = !rejectWorker
            fixture.gate.onJoin(player.limbo)
            assertEquals(1, player.disconnects.size)
            assertEquals(0, fixture.services.beginCalls)
            assertEquals(0, fixture.gate.verifyingCount())
            assertTrue(fixture.services.timers.all { it.isDone })
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `rejected poll submission or reschedule cancels all work without a backend fallback`() {
        for (rejectWorker in listOf(false, true)) {
            val fixture = Fixture()
            val player = fixture.player(777)
            fixture.join(player)
            fixture.services.rejectSubmissions = rejectWorker
            fixture.services.rejectSchedules = !rejectWorker
            fixture.services.advanceBy(1_000)
            assertEquals(if (rejectWorker) 0 else 1, fixture.services.pollCalls)
            assertEquals(1, player.disconnects.size)
            assertEquals(1, player.limbo.clearCount)
            assertEquals(0, fixture.services.grants)
            assertTrue(fixture.services.timers.all { it.isDone })
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `restart scheduler rejection cancels the old prompt once and never starts a new challenge`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.join(player)
        val oldTimers = fixture.services.timers.toList()
        fixture.services.rejectSchedules = true
        assertFalse(fixture.gate.restartVerification(player))
        oldTimers.forEach { it.runEvenIfCancelled() }
        fixture.services.runWork()
        fixture.gate.onLeave(player.limbo)
        fixture.gate.onDisconnect(DisconnectEvent(player))
        assertEquals(1, fixture.services.beginCalls)
        assertEquals(1, player.limbo.titleCount)
        assertEquals(1, player.limbo.clearCount)
        assertEquals(1, player.disconnects.size)
        assertTrue(fixture.services.timers.all { it.isDone })
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `queued startup after a delayed deadline performs no network or title work`() {
        val fixture = Fixture(mapOf("verification.timeout-seconds" to "1"))
        val player = fixture.player(777)
        fixture.login(player)
        fixture.gate.onJoin(player.limbo)
        fixture.now += 1_000
        fixture.services.runWork()
        assertEquals(0, fixture.services.beginCalls)
        assertEquals(0, player.limbo.titleCount)
        assertEquals(0, player.limbo.clearCount)
        assertEquals(1, player.disconnects.size)
        fixture.services.timers.forEach { it.runEvenIfCancelled() }
        assertEquals(1, player.disconnects.size)
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `queued poll after a delayed deadline performs no poll exchange bind or grant`() {
        val fixture = Fixture(mapOf("verification.timeout-seconds" to "2"))
        val player = fixture.player(777)
        fixture.join(player)
        fixture.services.timers.last().run()
        fixture.now += 2_000
        fixture.services.runWork()
        assertEquals(0, fixture.services.pollCalls)
        assertEquals(0, fixture.services.exchangeCalls)
        assertEquals(0, fixture.services.bindCalls)
        assertEquals(0, fixture.services.grants)
        assertEquals(1, player.disconnects.size)
        assertEquals(1, player.limbo.clearCount)
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `restart cannot extend an already elapsed deadline when its timer has not run`() {
        val fixture = Fixture(mapOf("verification.timeout-seconds" to "1"))
        val player = fixture.player(777)
        fixture.join(player)
        fixture.now += 1_000
        assertFalse(fixture.gate.restartVerification(player))
        assertEquals(1, fixture.services.beginCalls)
        assertEquals(1, player.disconnects.size)
        assertEquals(1, player.limbo.clearCount)
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `late begin poll exchange and binding results cannot authenticate a timed out attempt`() {
        for (stage in listOf("begin", "poll", "exchange", "bind")) {
            val fixture = Fixture(mapOf("verification.timeout-seconds" to "2"))
            val player = fixture.player(777)
            val expire = { fixture.now += 2_000; Unit }
            when (stage) {
                "begin" -> fixture.services.duringBegin = expire
                "poll" -> fixture.services.duringPoll = expire
                "exchange" -> fixture.services.duringExchange = expire
                "bind" -> fixture.services.duringBind = expire
            }
            fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
            fixture.login(player)
            fixture.gate.onJoin(player.limbo)
            fixture.services.runWork()
            if (stage != "begin") fixture.services.advanceBy(1_000)
            assertEquals(0, fixture.services.grants, stage)
            assertEquals(1, player.disconnects.size, stage)
            assertEquals(if (stage == "begin") 0 else 1, player.limbo.clearCount, stage)
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `late network completion and stale cancellation never touch a new same UUID connection`() {
        for (stage in listOf("begin", "poll", "exchange", "bind")) {
            val fixture = Fixture()
            val old = fixture.player(767)
            var replacement: FakePlayer? = null
            val reconnect = {
                fixture.services.duringBegin = {}
                fixture.services.duringPoll = {}
                fixture.services.duringExchange = {}
                fixture.services.duringBind = {}
                old.isActive = false
                fixture.gate.onDisconnect(DisconnectEvent(old))
                fixture.player(777, old.sessionId).also {
                    replacement = it
                    fixture.login(it)
                    fixture.gate.onJoin(it.limbo)
                }
                Unit
            }
            when (stage) {
                "begin" -> fixture.services.duringBegin = reconnect
                "poll" -> fixture.services.duringPoll = reconnect
                "exchange" -> fixture.services.duringExchange = reconnect
                "bind" -> fixture.services.duringBind = reconnect
            }
            fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
            fixture.login(old)
            fixture.gate.onJoin(old.limbo)
            val staleTimers = fixture.services.timers.toMutableList()
            fixture.services.runWork()
            if (stage != "begin") {
                staleTimers += fixture.services.timers.filter { it !in staleTimers }
                fixture.services.advanceBy(1_000)
            }
            val next = requireNotNull(replacement)
            fixture.services.duringBegin = {}
            fixture.services.duringPoll = {}
            fixture.services.duringExchange = {}
            fixture.services.duringBind = {}
            staleTimers.forEach { it.runEvenIfCancelled() }
            fixture.gate.onLeave(old.limbo)
            fixture.gate.onDisconnect(DisconnectEvent(old))
            fixture.services.runWork()
            assertEquals(0, fixture.services.grants, stage)
            assertEquals(1, next.limbo.titleCount, stage)
            assertEquals(0, next.limbo.clearCount, stage)
            assertTrue(next.disconnects.isEmpty(), stage)
            assertEquals(1, fixture.gate.verifyingCount(), stage)
            fixture.assertBackendDenied(next)
        }
    }

    @Test
    fun `failed session storage never authorizes and duplicate completion remains cancelled`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.join(player)
        fixture.services.pollResult = DeviceCodeFlow.PollResult.Authorized("test-token", null, 300)
        fixture.services.grantFailure = IllegalStateException("Synthetic storage failure")
        val timers = fixture.services.timers.toList()
        fixture.services.advanceBy(1_000)
        timers.forEach { it.runEvenIfCancelled() }
        fixture.services.runWork()
        assertEquals(1, fixture.services.bindCalls)
        assertEquals(0, fixture.services.grants)
        assertEquals(1, player.disconnects.size)
        assertEquals(1, player.limbo.clearCount)
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `deactivation invalidates queued startup and all late cancellation callbacks`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.login(player)
        fixture.gate.onJoin(player.limbo)
        val timers = fixture.services.timers.toList()
        fixture.gate.deactivate()
        fixture.services.runWork()
        timers.forEach { it.runEvenIfCancelled() }
        fixture.gate.onLeave(player.limbo)
        fixture.gate.onDisconnect(DisconnectEvent(player))
        assertEquals(0, fixture.services.beginCalls)
        assertEquals(0, player.limbo.titleCount)
        assertEquals(0, player.limbo.clearCount)
        assertEquals(1, player.disconnects.size)
        assertEquals(0, fixture.services.grants)
        assertTrue(fixture.services.timers.all { it.isDone })
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `limbo arrival timeout and late onJoin cannot start authentication or inherit fallback`() {
        for (runTimeout in listOf(false, true)) {
            val fixture = Fixture(mapOf("limbo.join-timeout-seconds" to "5"))
            val player = fixture.player(777)
            fixture.login(player)
            if (runTimeout) {
                fixture.services.advanceBy(5_000)
            } else {
                fixture.now += 5_000
                fixture.gate.onJoin(player.limbo)
            }
            fixture.services.runWork()
            assertEquals(1, player.disconnects.size)
            assertEquals(0, fixture.services.beginCalls)
            assertEquals(0, player.limbo.titleCount)
            assertTrue(fixture.services.timers.all { it.isDone })
            fixture.assertBackendDenied(player)
        }
    }

    @Test
    fun `arrival scheduler rejection denies login and never leaves a held connection`() {
        val fixture = Fixture()
        val player = fixture.player(777)
        fixture.gate.onPreLogin(PreLoginEvent(player, player.username, player.uniqueId))
        fixture.services.rejectSchedules = true
        val event = LoginEvent(player)
        fixture.gate.onLogin(event)
        assertFalse(event.result.isAllowed)
        assertEquals(0, fixture.gate.verifyingCount())
        fixture.assertBackendDenied(player)
    }

    @Test
    fun `pending capacity refuses before another lookup and reclaims disconnected and expired slots`() {
        for (expire in listOf(false, true)) {
            val fixture = Fixture(mapOf("safety.pending-login-limit" to "1"))
            val old = fixture.player(777)
            val first = PreLoginEvent(old, old.username, old.uniqueId)
            fixture.gate.onPreLogin(first)
            assertTrue(first.result.isAllowed)
            val next = fixture.player(777)
            val busy = PreLoginEvent(next, next.username, next.uniqueId)
            fixture.gate.onPreLogin(busy)
            assertFalse(busy.result.isAllowed)
            assertEquals(1, fixture.services.decideCalls)
            if (expire) fixture.now += 300_000 else old.isActive = false
            val retry = PreLoginEvent(next, next.username, next.uniqueId)
            fixture.gate.onPreLogin(retry)
            assertTrue(retry.result.isAllowed)
            assertEquals(2, fixture.services.decideCalls)
            val stale = LoginEvent(old)
            fixture.gate.onLogin(stale)
            assertFalse(stale.result.isAllowed)
            fixture.assertBackendDenied(old)
        }
    }

    @Test
    fun `capacity filled during a premium lookup releases its handshake lease on refusal`() {
        val fixture = Fixture(mapOf("safety.pending-login-limit" to "1"))
        val premium = fixture.player(777)
        lateinit var occupant: FakePlayer
        fixture.services.duringDecide = {
            fixture.services.duringDecide = {}
            occupant = fixture.player(777)
            fixture.gate.onPreLogin(PreLoginEvent(occupant, occupant.username, occupant.uniqueId))
            fixture.services.decision = AuthCoordinator.Decision.MojangSession(record.uuid)
        }
        val event = PreLoginEvent(premium, premium.username, premium.uniqueId)
        fixture.gate.onPreLogin(event)
        assertFalse(event.result.isAllowed)
        assertTrue(fixture.services.timers.isEmpty(), "A refused premium lookup must not leave a watch")
        fixture.assertBackendDenied(premium)
        occupant.isActive = false
        fixture.now += 30_000
        val retryPlayer = fixture.player(777)
        val retry = PreLoginEvent(retryPlayer, retryPlayer.username, retryPlayer.uniqueId)
        fixture.gate.onPreLogin(retry)
        assertTrue(retry.result.isAllowed, "The released lease's cooldown has elapsed, not just its handshake deadline")
        assertEquals(1, fixture.services.timers.size)
    }

    @Test
    fun `stale disconnect before replacement login cannot consume its pending decision`() {
        val fixture = Fixture()
        val old = fixture.player(767)
        fixture.join(old)
        old.isActive = false
        fixture.gate.onDisconnect(DisconnectEvent(old))
        val next = fixture.player(777, old.sessionId)
        val pre = PreLoginEvent(next, next.username, next.uniqueId)
        fixture.gate.onPreLogin(pre)
        assertTrue(pre.result.isAllowed)
        fixture.gate.onDisconnect(DisconnectEvent(old))
        val login = LoginEvent(next)
        fixture.gate.onLogin(login)
        assertTrue(login.result.isAllowed)
        fixture.gate.onJoin(next.limbo)
        fixture.services.runWork()
        assertEquals(1, next.limbo.titleCount)
        assertEquals(0, next.limbo.clearCount)
        fixture.assertBackendDenied(next)
    }

    private class Fixture(values: Map<String, String> = emptyMap(), fastPolling: Boolean = true) {
        var now = 1_000_000L
        val logs = mutableListOf<String>()
        var duringLog: (String) -> Unit = {}
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
        val gate = LoginGate(proxy, ProxyConfig(
            (if (fastPolling) mapOf("verification.poll-interval-seconds" to "1") else emptyMap()) + values,
            File("unused-contract-config")),
            MessageCatalogue("en_US", File("unused-contract-messages")), services, ProxyLog { _, message, _ ->
                logs += message
                duringLog(message)
            }, { now },
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

        fun assertBackendDenied(player: FakePlayer) {
            val event = ServerPreConnectEvent(player, lobby)
            gate.onPreConnect(event)
            assertTrue(event.result.server.isEmpty)
            assertTrue(player.requests.isEmpty())
        }

        inner class MemoryServices(private val clock: () -> Long) : LoginGate.Services {
            val work = ArrayDeque<() -> Unit>()
            val timers = mutableListOf<Timer>()
            var beginCalls = 0
            var grants = 0
            var pollCalls = 0
            val pollRequestsAt = mutableListOf<Long>()
            val pollResponsesAt = mutableListOf<Long>()
            val exchangeRequestsAt = mutableListOf<Long>()
            var exchangeCalls = 0
            var bindCalls = 0
            var decideCalls = 0
            var allow = true
            var rejectSubmissions = false
            var rejectSchedules = false
            var challengeIntervalSeconds = 1
            var pollFailure: Exception? = null
            var beginFailure: Exception? = null
            var grantFailure: Exception? = null
            var duringDecide: () -> Unit = {}
            var duringBegin: () -> Unit = {}
            var duringPoll: () -> Unit = {}
            var duringExchange: () -> Unit = {}
            var duringBind: () -> Unit = {}
            var decision: AuthCoordinator.Decision =
                AuthCoordinator.Decision.Verify(record.uuid, AuthCoordinator.Reason.SESSION_EXPIRED, false, null)
            var pollResult: DeviceCodeFlow.PollResult = DeviceCodeFlow.PollResult.Pending
            override fun allow(key: String) = allow
            override fun decide(name: GameName, address: String): AuthCoordinator.Decision {
                decideCalls++
                duringDecide()
                return decision
            }
            override fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome = error("No premium work expected")
            override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome {
                bindCalls++
                duringBind()
                return AuthCoordinator.ClaimOutcome.Bound(record)
            }
            override fun grantSession(uuid: UUID, address: String) {
                grantFailure?.let { throw it }
                grants++
            }
            override fun submit(task: () -> Unit) {
                if (rejectSubmissions) throw RejectedExecutionException("Worker unavailable")
                work.addLast(task)
            }
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*> {
                if (rejectSchedules) throw RejectedExecutionException("Scheduler unavailable")
                return Timer(clock() + delayMillis, clock, task).also { timers += it }
            }
            override fun begin(): DeviceCodeFlow.Challenge {
                beginCalls++
                beginFailure?.let { throw it }
                duringBegin()
                return DeviceCodeFlow.Challenge("code-$beginCalls", "https://example.invalid",
                    "https://example.invalid?otc=code-$beginCalls", 300, challengeIntervalSeconds, "device-$beginCalls")
            }
            override fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult {
                pollCalls++
                pollRequestsAt += clock()
                try {
                    duringPoll()
                    pollFailure?.let { throw it }
                    return pollResult
                } finally {
                    pollResponsesAt += clock()
                }
            }
            override fun exchange(accessToken: String): XboxLive.XstsToken {
                exchangeCalls++
                exchangeRequestsAt += clock()
                duringExchange()
                return XboxLive.XstsToken("test", 1L, "Steve", "test-token")
            }
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
        val messages = mutableListOf<RichText.Parsed>()
        var throwOnMessage = false
        val requests = mutableListOf<RegisteredServer>()
        var backendResult = CompletableFuture.completedFuture(
            ConnectionRequestBuilder.Result(ConnectionRequestBuilder.Status.SUCCESS))
        override fun hasPermission(permission: String) = true
        override fun sendMessage(message: RichText.Parsed) {
            check(!throwOnMessage) { "controlled feedback transport failure" }
            messages += message
        }
        override fun disconnect(message: RichText.Parsed) { disconnects += message }
        override fun createConnectionRequest(target: RegisteredServer): ConnectionRequestBuilder {
            requests += target
            return object : ConnectionRequestBuilder {
                override fun connect() = backendResult
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
        val messages = mutableListOf<TextComponent>()
        var duringReadyPrompt: () -> Unit = {}
        var duringMessage: (TextComponent) -> Unit = {}
        override fun sendMessage(message: TextComponent) {
            duringMessage(message)
            messages += message
            if (message.children.any { it.click != null }) duringReadyPrompt()
        }
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
