package moe.ryuu.kyokouauth.velocity

import moe.ryuu.kyokouauth.core.identity.GameName
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.Inet6Address
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.ryuu.kyokouauth.proxy.PremiumHandshakeGuard
import moe.ryuu.kyokouauth.proxy.PremiumHandshakeGuard.Decision

class PremiumHandshakeGuardTest {
    private val name = GameName.parseOrThrow("Notch")
    private val source = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))

    @Test
    fun `concurrent starts for one source and name admit exactly one lease`() {
        val guard = PremiumHandshakeGuard(clock = { 0L })
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(16)
        try {
            val requests = (1..16).map {
                executor.submit(Callable {
                    start.await()
                    guard.reserve(source, name, UUID.randomUUID()) { true }
                })
            }
            start.countDown()
            val decisions = requests.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, decisions.count { it == PremiumHandshakeGuard.Decision.Allowed })
            assertEquals(15, decisions.count { it == PremiumHandshakeGuard.Decision.InFlight })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `same name at another source is not locked out by an active or failed lease`() {
        var now = 0L
        val guard = PremiumHandshakeGuard(clock = { now })
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, first) { true })
        assertEquals(Decision.Allowed, guard.reserve(ipv4(2), name, second) { true })
        assertFalse(guard.complete(first, false))
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
        assertTrue(guard.complete(second, true))
        now = 5_000L
        assertEquals(Decision.Allowed, guard.reserve(ipv4(2), name, UUID.randomUUID()) { true })
    }

    @Test
    fun `source and case insensitive name form the key`() {
        val guard = PremiumHandshakeGuard(clock = { 0L })
        assertEquals(Decision.Allowed, guard.reserve(source, name, UUID.randomUUID()) { true })
        assertEquals(Decision.InFlight, guard.reserve(source, GameName.parseOrThrow("nOtCh"), UUID.randomUUID()) { true })
        assertEquals(Decision.Allowed, guard.reserve(source, GameName.parseOrThrow("Alex"), UUID.randomUUID()) { true })
    }

    @Test
    fun `IPv6 addresses in a 64 bit prefix share a lease but another prefix does not`() {
        val guard = PremiumHandshakeGuard(clock = { 0L })
        assertEquals(Decision.Allowed, guard.reserve(ipv6(1, 1), name, UUID.randomUUID()) { true })
        assertEquals(Decision.InFlight, guard.reserve(ipv6(1, 127), name, UUID.randomUUID()) { true })
        assertEquals(Decision.Allowed, guard.reserve(ipv6(2, 1), name, UUID.randomUUID()) { true })
    }

    @Test
    fun `IPv4 mapped IPv6 cannot bypass the IPv4 lease`() {
        val guard = PremiumHandshakeGuard(clock = { 0L })
        val mapped = ByteArray(16)
        mapped[10] = -1
        mapped[11] = -1
        source.address.copyInto(mapped, 12)
        assertEquals(Decision.Allowed, guard.reserve(source, name, UUID.randomUUID()) { true })
        assertEquals(
            Decision.InFlight,
            guard.reserve(Inet6Address.getByAddress(null, mapped, -1), name, UUID.randomUUID()) { true },
        )
    }

    @Test
    fun `failed handshakes back off for 30 then 60 then 120 seconds and stay capped`() {
        var now = 0L
        val guard = PremiumHandshakeGuard(clock = { now })
        var session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { true })
        for (delay in listOf(30_000L, 60_000L, 120_000L, 120_000L)) {
            assertFalse(guard.complete(session, false))
            now += delay - 1
            assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
            now++
            session = UUID.randomUUID()
            assertEquals(Decision.Allowed, guard.reserve(source, name, session) { true })
        }
    }

    @Test
    fun `a real proof resets failure backoff but is never reusable authentication`() {
        var now = 0L
        val guard = PremiumHandshakeGuard(clock = { now }, minRetryMillis = 0)
        val first = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, first) { true })
        assertFalse(guard.complete(first, false))
        now = 30_000L
        val verified = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, verified) { true })
        assertTrue(guard.complete(verified, true))
        assertFalse(guard.complete(verified, true))
        val next = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, next) { true })
        assertFalse(guard.complete(next, false))
        now = 59_999L
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
        now = 60_000L
        assertEquals(Decision.Allowed, guard.reserve(source, name, UUID.randomUUID()) { true })
    }

    @Test
    fun `rejected requests and repeated completion never extend cooldown or spend global budget`() {
        var now = 0L
        val guard = PremiumHandshakeGuard(clock = { now }, globalLimit = 2)
        val session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { true })
        repeat(10) {
            assertEquals(Decision.InFlight, guard.reserve(source, name, UUID.randomUUID()) { true })
        }
        assertFalse(guard.complete(session, false))
        now = 29_999L
        repeat(10) {
            assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
            assertFalse(guard.complete(session, false))
        }
        now = 30_000L
        assertEquals(Decision.Allowed, guard.reserve(source, name, UUID.randomUUID()) { true })
        assertEquals(Decision.GlobalBudget, guard.reserve(ipv4(2), name, UUID.randomUUID()) { true })
    }

    @Test
    fun `minimum retry is measured between accepted starts and rejection does not refresh it`() {
        var now = 0L
        val guard = PremiumHandshakeGuard(clock = { now })
        val first = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, first) { true })
        now = 1_000L
        assertTrue(guard.complete(first, true))
        now = 4_999L
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
        now = 5_000L
        assertEquals(Decision.Allowed, guard.reserve(source, name, UUID.randomUUID()) { true })
    }

    @Test
    fun `default global budget allows only 60 starts in a rolling minute`() {
        var now = 0L
        val guard = PremiumHandshakeGuard(clock = { now })
        repeat(60) { index ->
            val session = UUID.randomUUID()
            assertEquals(Decision.Allowed, guard.reserve(ipv4(index + 1), name, session) { true })
            assertTrue(guard.complete(session, true))
        }
        val next = UUID.randomUUID()
        assertEquals(Decision.GlobalBudget, guard.reserve(ipv4(61), name, next) { true })
        now = 59_999L
        assertEquals(Decision.GlobalBudget, guard.reserve(ipv4(61), name, next) { true })
        now = 60_000L
        assertEquals(Decision.Allowed, guard.reserve(ipv4(61), name, next) { true })
    }

    @Test
    fun `capacity refuses new keys without evicting an active lease`() {
        val guard = PremiumHandshakeGuard(clock = { 0L }, maxActive = 1)
        val session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { true })
        repeat(20) { index ->
            assertEquals(Decision.Capacity, guard.reserve(ipv4(index + 2), name, UUID.randomUUID()) { true })
        }
        assertTrue(guard.observe(session))
        assertEquals(Decision.InFlight, guard.reserve(source, name, UUID.randomUUID()) { true })
        assertTrue(guard.complete(session, true))
        assertEquals(Decision.Allowed, guard.reserve(ipv4(2), name, UUID.randomUUID()) { true })
    }

    @Test
    fun `history eviction never evicts or bypasses an active lease`() {
        val guard = PremiumHandshakeGuard(clock = { 0L }, maxHistory = 1, maxActive = 2)
        val held = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, held) { true })
        repeat(5) { index ->
            val session = UUID.randomUUID()
            assertEquals(Decision.Allowed, guard.reserve(ipv4(index + 2), name, session) { true })
            assertFalse(guard.complete(session, false))
        }
        assertTrue(guard.observe(held))
        assertEquals(Decision.InFlight, guard.reserve(source, name, UUID.randomUUID()) { true })
        // Only the newest failed key is kept in the independently bounded history.
        assertEquals(Decision.Cooldown, guard.reserve(ipv4(6), name, UUID.randomUUID()) { true })
        assertEquals(Decision.Allowed, guard.reserve(ipv4(2), name, UUID.randomUUID()) { true })
    }

    @Test
    fun `failure history expires after its TTL without extending on rejected attempts`() {
        var now = 0L
        val guard = PremiumHandshakeGuard(clock = { now })
        val first = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, first) { true })
        assertFalse(guard.complete(first, false))
        now = 29_999L
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
        now = 600_000L
        val fresh = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, fresh) { true })
        assertFalse(guard.complete(fresh, false))
        now = 630_000L
        assertEquals(Decision.Allowed, guard.reserve(source, name, UUID.randomUUID()) { true })
    }

    @Test
    fun `stale success and failure cannot release a newer lease or reset its backoff`() {
        var now = 0L
        val guard = PremiumHandshakeGuard(clock = { now })
        val old = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, old) { true })
        assertFalse(guard.complete(old, false))
        now = 30_000L
        val current = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, current) { true })
        assertFalse(guard.complete(old, true))
        assertFalse(guard.complete(old, false))
        assertFalse(guard.observe(old))
        assertTrue(guard.observe(current))
        assertEquals(Decision.InFlight, guard.reserve(source, name, UUID.randomUUID()) { true })
        assertFalse(guard.complete(current, false))
        now = 89_999L
        assertFalse(guard.complete(old, true))
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
        now = 90_000L
        assertEquals(Decision.Allowed, guard.reserve(source, name, UUID.randomUUID()) { true })
    }

    @Test
    fun `a proxy session id cannot own two different leases`() {
        val guard = PremiumHandshakeGuard(clock = { 0L })
        val session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { true })
        assertEquals(Decision.SessionInUse, guard.reserve(ipv4(2), name, session) { true })
        assertFalse(guard.complete(UUID.randomUUID(), true))
        assertTrue(guard.observe(session))
    }

    @Test
    fun `timeout releases a lease and a late proof is a failure rather than authentication`() {
        var now = 0L
        val guard = PremiumHandshakeGuard(clock = { now }, maxActive = 1)
        val session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { true })
        now = 29_999L
        assertTrue(guard.observe(session))
        now = 30_000L
        assertFalse(guard.complete(session, true))
        assertFalse(guard.observe(session))
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
        assertEquals(Decision.Allowed, guard.reserve(ipv4(2), name, UUID.randomUUID()) { true })
        now = 60_000L
        assertEquals(Decision.Allowed, guard.reserve(source, name, UUID.randomUUID()) { true })
    }

    @Test
    fun `periodic sweep observes closed connections and charges failure only once`() {
        var now = 0L
        var connected = true
        val guard = PremiumHandshakeGuard(clock = { now }, maxActive = 1)
        val session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { connected })
        connected = false
        guard.sweep()
        assertFalse(guard.observe(session))
        assertFalse(guard.complete(session, true))
        now = 29_999L
        guard.sweep()
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
        now = 30_000L
        assertEquals(Decision.Allowed, guard.reserve(source, name, UUID.randomUUID()) { true })
    }

    @Test
    fun `sweep releases timed out leases even when no connection callbacks arrive`() {
        var now = 0L
        val guard = PremiumHandshakeGuard(clock = { now }, maxActive = 1)
        val session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { true })
        now = 30_000L
        guard.sweep()
        assertFalse(guard.observe(session))
        assertEquals(Decision.Allowed, guard.reserve(ipv4(2), name, UUID.randomUUID()) { true })
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
    }

    @Test
    fun `activity callback exceptions fail closed without leaking a lease or consuming a rejected budget`() {
        val guard = PremiumHandshakeGuard(clock = { 0L }, globalLimit = 1, maxActive = 1)
        assertEquals(Decision.Inactive, guard.reserve(source, name, UUID.randomUUID()) { error("closed") })
        assertEquals(Decision.Inactive, guard.reserve(source, name, UUID.randomUUID()) { false })
        var broken = false
        val session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { if (broken) error("closed") else true })
        broken = true
        assertFalse(guard.complete(session, true))
        assertFalse(guard.observe(session))
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
        assertEquals(Decision.GlobalBudget, guard.reserve(ipv4(2), name, UUID.randomUUID()) { true })
    }

    @Test
    fun `a proof for an inactive connection is refused even before scheduled observation`() {
        var connected = true
        val guard = PremiumHandshakeGuard(clock = { 0L })
        val session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { connected })
        connected = false
        assertFalse(guard.complete(session, true))
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
    }

    @Test
    fun `backwards clock invalidates leases and refuses new work until time catches up`() {
        var now = 100_000L
        val guard = PremiumHandshakeGuard(clock = { now }, maxActive = 1)
        val old = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, old) { true })
        now = 90_000L
        assertFalse(guard.complete(old, true))
        assertEquals(Decision.Cooldown, guard.reserve(ipv4(2), name, UUID.randomUUID()) { true })
        now = 100_000L
        assertFalse(guard.observe(old))
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
        assertEquals(Decision.Allowed, guard.reserve(ipv4(2), name, UUID.randomUUID()) { true })
        now = 130_000L
        assertEquals(Decision.Allowed, guard.reserve(source, name, UUID.randomUUID()) { true })
    }

    @Test
    fun `timing and budget parameters are injectable without sleeps`() {
        var now = 0L
        val guard = PremiumHandshakeGuard(
            baseBackoffMillis = 7,
            maxBackoffMillis = 11,
            handshakeTimeoutMillis = 100,
            minRetryMillis = 2,
            globalLimit = 3,
            clock = { now },
        )
        var session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { true })
        assertFalse(guard.complete(session, false))
        now = 6
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
        now = 7
        session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { true })
        assertFalse(guard.complete(session, false))
        now = 17
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
        now = 18
        session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { true })
        now = 117
        assertTrue(guard.observe(session))
        now = 118
        assertFalse(guard.observe(session))
        assertEquals(Decision.GlobalBudget, guard.reserve(ipv4(2), name, UUID.randomUUID()) { true })
    }

    @Test
    fun `short history TTL cannot erase a still running cooldown`() {
        var now = 0L
        val guard = PremiumHandshakeGuard(clock = { now }, historyTtlMillis = 1)
        val session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { true })
        assertFalse(guard.complete(session, false))
        now = 1
        guard.sweep()
        assertEquals(Decision.Cooldown, guard.reserve(source, name, UUID.randomUUID()) { true })
    }

    @Test
    fun `clear drops callbacks and permanently closes the guard`() {
        var calls = 0
        val guard = PremiumHandshakeGuard(clock = { 0L })
        val session = UUID.randomUUID()
        assertEquals(Decision.Allowed, guard.reserve(source, name, session) { calls++; true })
        guard.clear()
        val before = calls
        guard.sweep()
        guard.clear()
        assertFalse(guard.observe(session))
        assertFalse(guard.complete(session, true))
        assertEquals(Decision.Closed, guard.reserve(source, name, UUID.randomUUID()) { calls++; true })
        assertEquals(before, calls)
    }

    private fun ipv4(host: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, host.toByte()))

    private fun ipv6(prefix: Int, host: Int): InetAddress {
        val bytes = ByteArray(16)
        bytes[0] = 0x20
        bytes[1] = 0x01
        bytes[2] = 0x0d
        bytes[3] = 0xb8.toByte()
        bytes[7] = prefix.toByte()
        bytes[15] = host.toByte()
        return InetAddress.getByAddress(bytes)
    }
}
