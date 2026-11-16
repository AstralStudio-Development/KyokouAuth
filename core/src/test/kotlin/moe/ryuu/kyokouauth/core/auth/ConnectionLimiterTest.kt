package moe.ryuu.kyokouauth.core.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The limiter exists so that one address cannot spend the Mojang lookup budget on behalf of
 * everyone else. These cases pin the two behaviours that matter: a normal burst passes, and the
 * allowance refills as the window rolls.
 */
class ConnectionLimiterTest {

    private var now = 0L

    private fun limiter(max: Int, windowMillis: Long = 60_000L) =
        ConnectionLimiter(max, windowMillis) { now }

    @Test
    fun `a normal reconnect loop passes`() {
        val limiter = limiter(10)

        repeat(10) { assertTrue(limiter.allow("10.0.0.1"), "attempt ${it + 1} should pass") }
    }

    @Test
    fun `the attempt after the allowance is refused`() {
        val limiter = limiter(3)

        repeat(3) { limiter.allow("10.0.0.1") }

        assertFalse(limiter.allow("10.0.0.1"))
    }

    @Test
    fun `addresses do not share an allowance`() {
        val limiter = limiter(1)

        assertTrue(limiter.allow("10.0.0.1"))
        assertFalse(limiter.allow("10.0.0.1"))
        assertTrue(limiter.allow("10.0.0.2"), "a different address has its own allowance")
    }

    @Test
    fun `the allowance refills as the window rolls`() {
        val limiter = limiter(2)

        assertTrue(limiter.allow("10.0.0.1"))
        assertTrue(limiter.allow("10.0.0.1"))
        assertFalse(limiter.allow("10.0.0.1"))

        now += 60_000L

        assertTrue(limiter.allow("10.0.0.1"), "the oldest attempts have aged out")
    }

    @Test
    fun `a disabled limiter lets everything through`() {
        val limiter = limiter(0)

        repeat(1_000) { assertTrue(limiter.allow("10.0.0.1")) }
    }
}
