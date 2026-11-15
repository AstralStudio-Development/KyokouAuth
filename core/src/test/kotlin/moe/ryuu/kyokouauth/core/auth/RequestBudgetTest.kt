package moe.ryuu.kyokouauth.core.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The budget is the only thing standing between a reconnecting server and a 429 from Mojang, so the
 * cases that matter are the boundary ones: exactly at the limit, one just after, and a burst that
 * straddles the window.
 */
class RequestBudgetTest {

    private var now = 0L

    private fun budget(max: Int, windowMillis: Long) = RequestBudget(max, windowMillis) { now }

    @Test
    fun `requests up to the limit are allowed`() {
        val budget = budget(3, 10_000L)

        repeat(3) { assertTrue(budget.tryConsume(), "request ${it + 1} should be allowed") }
        assertFalse(budget.tryConsume(), "the fourth request exceeds the budget")
    }

    @Test
    fun `the window rolls rather than resetting on a boundary`() {
        val budget = budget(2, 10_000L)

        assertTrue(budget.tryConsume())      // t=0
        now = 9_000
        assertTrue(budget.tryConsume())      // t=9000
        assertFalse(budget.tryConsume())     // still 2 within the window

        // The first stamp expires at t=10000, which frees exactly one slot - not all of them, which
        // is what a fixed-window counter would do and what would let a burst straddle the boundary.
        now = 10_000
        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())
    }

    @Test
    fun `everything expires after a full window of silence`() {
        val budget = budget(2, 10_000L)
        budget.tryConsume()
        budget.tryConsume()

        now = 20_000
        assertEquals(0, budget.used())
        assertTrue(budget.tryConsume())
    }

    @Test
    fun `a non-positive limit disables the budget entirely`() {
        val unlimited = budget(0, 10_000L)

        repeat(1_000) { assertTrue(unlimited.tryConsume()) }
    }

    @Test
    fun `reset frees the window`() {
        val budget = budget(1, 10_000L)
        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())

        budget.reset()
        assertTrue(budget.tryConsume())
    }
}
