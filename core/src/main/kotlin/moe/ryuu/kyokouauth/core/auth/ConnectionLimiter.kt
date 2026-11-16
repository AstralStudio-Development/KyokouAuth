package moe.ryuu.kyokouauth.core.auth

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration

/**
 * Caps how often one address may attempt a login.
 *
 * ## What this protects
 *
 * Not the password - there is none. It protects the [RequestBudget] that every uncached login
 * spends. That budget is small on purpose (600 lookups per ten minutes, the limit Mojang publishes)
 * and it fails closed, which means a client that opens connections in a loop can exhaust it and lock
 * every real player out for the rest of the window without ever proving an account.
 *
 * Ten attempts a minute is far above what a player produces - a reconnect loop with a broken client
 * is the realistic worst case - and far below what it takes to drain the budget.
 *
 * ## What it deliberately does not do
 *
 * It does not persist, ban, or count across restarts. A limiter that escalates is a limiter that
 * eventually bans a shared address for someone else's behaviour; this one only slows a burst down,
 * and the window is a minute.
 */
class ConnectionLimiter(
    private val maxPerWindow: Int,
    private val windowMillis: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * One budget per address, evicted when the address stops connecting.
     *
     * Reusing [RequestBudget] rather than writing a second rolling-window counter keeps one
     * definition of "how many in the last N milliseconds" in the project. The cache is what bounds
     * the number of addresses tracked; without it a flood of spoofed source addresses would be a
     * memory leak with a rate limiter attached.
     */
    private val budgets: Cache<String, RequestBudget> = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(10))
        .maximumSize(20_000)
        .build()

    /** True when this attempt may proceed. A non-positive [maxPerWindow] disables the limiter. */
    fun allow(address: String): Boolean {
        if (maxPerWindow <= 0) return true
        val budget = budgets.get(address) { RequestBudget(maxPerWindow, windowMillis, clock) }
        return budget.tryConsume()
    }
}
