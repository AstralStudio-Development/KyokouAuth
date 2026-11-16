package moe.ryuu.kyokouauth.bukkit.verification

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The back-off that answers Microsoft's `SlowDown`.
 *
 * Getting this wrong is not a display bug: ignoring the signal escalates the throttle, and the
 * client id this plugin shares with every other installation is what gets blocked. The cap matters
 * just as much in the other direction - a poll interval longer than the code's lifetime means the
 * player waits out the timeout with no way to finish.
 */
class VerificationBackoffTest {

    @Test
    fun `each slow-down doubles the interval`() {
        assertEquals(200L, VerificationService.backoffTicks(100L))
        assertEquals(400L, VerificationService.backoffTicks(200L))
    }

    @Test
    fun `the interval never passes one minute`() {
        assertEquals(
            VerificationService.MAX_INTERVAL_TICKS,
            VerificationService.backoffTicks(VerificationService.MAX_INTERVAL_TICKS),
        )
    }

    @Test
    fun `a repeated slow-down stays at the cap instead of overflowing`() {
        var interval = 20L
        repeat(20) { interval = VerificationService.backoffTicks(interval) }

        assertEquals(VerificationService.MAX_INTERVAL_TICKS, interval)
        assertTrue(interval > 0, "the interval must never wrap to a negative tick count")
    }

    @Test
    fun `the cap is one minute of ticks`() {
        assertEquals(1200L, VerificationService.MAX_INTERVAL_TICKS)
    }
}
