package moe.ryuu.kyokouauth.core.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The poll backoff, pinned at both ends.
 *
 * It is two lines of arithmetic, and both of its failure modes are silent: a ceiling that is too
 * high means the code expires between polls, and no ceiling at all means every `slow_down` doubles a
 * wait until the player is disconnected for not having been asked about.
 */
class PollBackoffTest {

    @Test
    fun `a slow down doubles the interval`() {
        assertEquals(10_000L, PollBackoff.next(5_000L))
        assertEquals(20_000L, PollBackoff.next(10_000L))
    }

    @Test
    fun `the interval stops at one minute`() {
        assertEquals(PollBackoff.MAX_INTERVAL_MILLIS, PollBackoff.next(40_000L))
        assertEquals(PollBackoff.MAX_INTERVAL_MILLIS, PollBackoff.next(60_000L))
        assertEquals(PollBackoff.MAX_INTERVAL_MILLIS, PollBackoff.next(120_000L))
    }

    @Test
    fun `a nonsensical interval is floored rather than doubled`() {
        assertEquals(2_000L, PollBackoff.next(0L))
        assertEquals(2_000L, PollBackoff.next(-5_000L))
    }
}
