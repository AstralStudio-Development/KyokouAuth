package moe.ryuu.kyokouauth.core.auth

/**
 * Caps how many requests are sent to Mojang in a rolling window.
 *
 * Mojang publishes a limit of roughly 600 profile lookups per ten minutes per address, and answers
 * 429 once it is exceeded. Without a budget of our own the only thing we can do with a 429 is react
 * to it - by which point we have already spent the allowance and every player who needed a lookup in
 * that window is refused.
 *
 * Counting here means the plugin stops *before* the wall instead of against it, and the failure
 * becomes predicable rather than incidental.
 *
 * ## Why a rolling window rather than a plain counter
 *
 * A counter reset on a fixed boundary lets a burst straddle the boundary and send twice the allowance
 * back to back. Mojang's limit is rolling, so the guard is rolling too.
 *
 * ## Why exhausted means "refuse", not "assume not premium"
 *
 * FastLogin answers an exhausted budget by treating everyone as cracked, because its cracked path is
 * still gated by a password. Here a name that Mojang owns can only be entered by a Mojang handshake,
 * so treating an unknown name as free would hand a premium name to whoever asked for it. Refusing is
 * the only safe direction; the player is told the directory is unavailable and can retry.
 */
class RequestBudget(
    private val maxRequests: Int,
    private val windowMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val stamps = ArrayDeque<Long>()

    /** True when this request may be sent. A non-positive [maxRequests] disables the budget. */
    @Synchronized
    fun tryConsume(): Boolean {
        if (maxRequests <= 0) return true

        val now = clock()
        while (stamps.isNotEmpty() && now - stamps.first() >= windowMillis) {
            stamps.removeFirst()
        }
        if (stamps.size >= maxRequests) return false

        stamps.addLast(now)
        return true
    }

    @Synchronized
    fun used(): Int {
        val now = clock()
        while (stamps.isNotEmpty() && now - stamps.first() >= windowMillis) {
            stamps.removeFirst()
        }
        return stamps.size
    }

    @Synchronized
    fun reset() = stamps.clear()
}
