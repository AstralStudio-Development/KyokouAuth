package moe.ryuu.kyokouauth.core.auth

/**
 * How a sign-in poll slows down when Microsoft asks it to.
 *
 * The device-code specification says a client that is told `slow_down` must wait longer, and every
 * further poll at the old interval deepens the throttle. There is a second, harder reason for the
 * ceiling: the code expires on Microsoft's clock, so an interval that grows without bound turns the
 * remaining lifetime of the code into a single poll that never happens.
 *
 * One minute is the ceiling that keeps both true against a five-minute code: the backoff can double
 * four times and still leave room to notice an authorisation.
 */
object PollBackoff {

    /** The slowest a poll may ever become. */
    const val MAX_INTERVAL_MILLIS = 60_000L

    /** A poll interval needs *some* floor; a zero or negative one would be a busy loop. */
    const val MIN_INTERVAL_MILLIS = 1_000L

    /** The next interval after Microsoft answered `slow_down`. */
    fun next(currentMillis: Long): Long =
        (currentMillis.coerceAtLeast(MIN_INTERVAL_MILLIS) * 2).coerceAtMost(MAX_INTERVAL_MILLIS)
}
