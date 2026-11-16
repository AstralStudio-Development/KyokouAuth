package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.auth.RequestBudget
import moe.ryuu.kyokouauth.core.identity.GameName
import java.net.InetAddress
import java.util.UUID

/**
 * Admission control for Mojang handshakes, not an authentication cache.
 *
 * A lease belongs to one proxy-generated, never-reused connection [UUID]. Only a real proof from
 * that still-live connection may complete it successfully. Names, source addresses and player UUIDs
 * are never proof. IPv4 addresses and IPv6 /64 networks share limits only with the same name key.
 *
 * All state transitions are serialized, including the global request budget. Activity callbacks
 * must be quick and non-blocking (normally just `connection.isActive`). Call [sweep] periodically
 * even when no login events arrive, then [clear] when shutting down.
 */
class PremiumHandshakeGuard(
    private val baseBackoffMillis: Long = 30_000L,
    private val maxBackoffMillis: Long = 120_000L,
    private val handshakeTimeoutMillis: Long = 30_000L,
    private val minRetryMillis: Long = 5_000L,
    globalLimit: Int = 60,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxActive: Int = 1_024,
    private val maxHistory: Int = 20_000,
    private val historyTtlMillis: Long = 600_000L,
) {
    init {
        require(baseBackoffMillis > 0 && maxBackoffMillis >= baseBackoffMillis)
        require(handshakeTimeoutMillis > 0 && minRetryMillis >= 0)
        require(maxActive > 0 && maxHistory > 0 && historyTtlMillis > 0)
    }

    sealed interface Decision {
        data object Allowed : Decision
        data object InFlight : Decision
        data object Cooldown : Decision
        data object GlobalBudget : Decision
        data object Capacity : Decision
        data object SessionInUse : Decision
        data object Inactive : Decision
        data object Closed : Decision
    }

    private data class Key(val source: InetAddress, val name: String)
    private class Lease(
        val key: Key,
        val sessionId: UUID,
        val startedAt: Long,
        val deadline: Long,
        val failures: Int,
        val isActive: () -> Boolean,
    )
    private data class History(val failures: Int, val retryAt: Long, val updatedAt: Long)
    private data class Tick(val now: Long, val backwards: Boolean)

    // Active leases are NEVER evicted to make room. History may be evicted without losing a lease.
    private val sessions = LinkedHashMap<UUID, Lease>()
    private val activeKeys = HashMap<Key, Lease>()
    // Insertion order is last accepted transition order, not last rejected request order.
    private val history = LinkedHashMap<Key, History>()
    private var lastNow: Long? = null
    private val budget = RequestBudget(globalLimit, 60_000L) { lastNow ?: 0L }
    private var closed = false

    /** A rejected attempt never consumes the global budget or extends an existing cooldown. */
    @Synchronized
    fun reserve(source: InetAddress, name: GameName, sessionId: UUID, isActive: () -> Boolean): Decision {
        if (closed) return Decision.Closed
        val tick = tick()
        if (tick.backwards) return Decision.Cooldown
        val now = tick.now
        pruneHistory(now)
        if (sessions.containsKey(sessionId)) return Decision.SessionInUse

        val key = key(source, name)
        activeKeys[key]?.let { lease ->
            if (observeLease(lease, now)) return Decision.InFlight
        }
        val previous = history[key]
        if (previous != null && now < previous.retryAt) return Decision.Cooldown
        if (!active(isActive)) return Decision.Inactive
        if (sessions.size >= maxActive) {
            sweepAt(now)
            if (sessions.size >= maxActive) return Decision.Capacity
        }
        if (!budget.tryConsume()) return Decision.GlobalBudget

        val lease = Lease(key, sessionId, now, add(now, handshakeTimeoutMillis), previous?.failures ?: 0, isActive)
        history.remove(key)
        sessions[sessionId] = lease
        activeKeys[key] = lease
        return Decision.Allowed
    }

    /**
     * Returns true only if [verified] is a real proof for this still-live, unexpired session.
     * False proofs, absent leases and late callbacks return false; none can authorize another login.
     */
    @Synchronized
    fun complete(sessionId: UUID, verified: Boolean): Boolean {
        if (closed) return false
        val tick = tick()
        if (tick.backwards) return false
        val lease = sessions[sessionId] ?: return false
        if (!observeLease(lease, tick.now)) return false
        finish(lease, verified, tick.now)
        return verified
    }

    /** False means absent, closed, timed out or inactive; observing failure releases the lease. */
    @Synchronized
    fun observe(sessionId: UUID): Boolean {
        if (closed) return false
        val tick = tick()
        if (tick.backwards) return false
        val lease = sessions[sessionId] ?: return false
        return observeLease(lease, tick.now)
    }

    @Synchronized
    fun sweep() {
        if (closed) return
        val tick = tick()
        if (!tick.backwards) sweepAt(tick.now)
        pruneHistory(tick.now)
    }

    /** Terminal shutdown: drop callbacks and history, and never admit another lease. */
    @Synchronized
    fun clear() {
        closed = true
        sessions.clear()
        activeKeys.clear()
        history.clear()
        budget.reset()
    }

    private fun sweepAt(now: Long) {
        sessions.values.toList().forEach { observeLease(it, now) }
    }

    private fun observeLease(lease: Lease, now: Long): Boolean {
        if (sessions[lease.sessionId] !== lease) return false
        if (now >= lease.deadline || !active(lease.isActive)) {
            finish(lease, false, now)
            return false
        }
        return sessions[lease.sessionId] === lease
    }

    private fun finish(lease: Lease, verified: Boolean, now: Long) {
        // A stale callback cannot release or reset a newer lease for the same source/name.
        if (sessions[lease.sessionId] !== lease) return
        sessions.remove(lease.sessionId)
        activeKeys.remove(lease.key, lease)
        val failures = if (verified) 0 else (lease.failures + 1).coerceAtMost(64)
        val retryAt = maxOf(
            add(lease.startedAt, minRetryMillis),
            if (verified) now else add(now, backoff(failures)),
        )
        history.remove(lease.key)
        history[lease.key] = History(failures, retryAt, now)
        while (history.size > maxHistory) {
            val oldest = history.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    private fun backoff(failures: Int): Long {
        var delay = baseBackoffMillis
        repeat(failures - 1) {
            delay = if (delay >= maxBackoffMillis - delay) maxBackoffMillis else delay * 2
        }
        return delay
    }

    private fun pruneHistory(now: Long) {
        val entries = history.entries.iterator()
        while (entries.hasNext()) {
            val value = entries.next().value
            if (now < add(value.updatedAt, historyTtlMillis)) break
            // Even an unusually short configured TTL cannot erase an unexpired cooldown.
            if (now >= value.retryAt) entries.remove()
        }
    }

    private fun tick(): Tick {
        val now = clock()
        val previous = lastNow
        if (previous != null && now < previous) {
            // Fail closed until the clock catches up; do not reinterpret old deadlines as live.
            sessions.values.toList().forEach { finish(it, false, previous) }
            return Tick(previous, true)
        }
        lastNow = now
        return Tick(now, false)
    }

    private fun active(callback: () -> Boolean): Boolean = try {
        callback()
    } catch (_: Exception) {
        false
    }

    private fun key(source: InetAddress, name: GameName): Key {
        val bytes = source.address
        val grouped = if (bytes.size == 16) {
            if (bytes.take(10).all { it == 0.toByte() } && bytes[10] == (-1).toByte() && bytes[11] == (-1).toByte()) {
                // Treat explicitly constructed IPv4-mapped IPv6 addresses as their IPv4 source.
                InetAddress.getByAddress(bytes.copyOfRange(12, 16))
            } else {
                bytes.fill(0, 8, 16)
                InetAddress.getByAddress(bytes)
            }
        } else source
        return Key(grouped, name.key)
    }

    private fun add(now: Long, duration: Long): Long =
        if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
}