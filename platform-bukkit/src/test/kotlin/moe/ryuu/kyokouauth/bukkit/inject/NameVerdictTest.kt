package moe.ryuu.kyokouauth.bukkit.inject

import moe.ryuu.kyokouauth.core.auth.MojangDirectory
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The three-state directory answer must stay three-state.
 *
 * Collapsing it to a boolean is not a style question: `Failed` became "not premium", the handshake
 * was skipped for a genuine premium player, and the login gate's second lookup - which retried,
 * because failures are never cached - answered `Premium` and rejected them with "use the official
 * launcher". This test exists so that collapse cannot come back.
 */
class NameVerdictTest {

    @Test
    fun `a premium name owes a handshake`() {
        assertEquals(
            NameVerdict.HANDSHAKE,
            verdictFor(MojangDirectory.Lookup.Premium(UUID.randomUUID(), "Steve")),
        )
    }

    @Test
    fun `a free name passes through to the login gate`() {
        assertEquals(NameVerdict.PASS_THROUGH, verdictFor(MojangDirectory.Lookup.Free))
    }

    @Test
    fun `an unanswered lookup is refused and never treated as free`() {
        assertEquals(NameVerdict.REFUSE, verdictFor(MojangDirectory.Lookup.Failed("timeout")))
    }

    @Test
    fun `a refused lookup never starts a handshake`() {
        assertNotEquals(
            NameVerdict.HANDSHAKE,
            verdictFor(MojangDirectory.Lookup.Failed("429")),
            "refusing must not start a handshake the client cannot answer",
        )
    }
}
