package moe.ryuu.kyokouauth.limbo.session

import org.junit.jupiter.api.Test
import kotlin.test.*
import moe.ryuu.kyokouauth.limbo.lifecycle.ConnectionLifecycle
import moe.ryuu.kyokouauth.limbo.lifecycle.ConnectionLifecycle.Phase

class ConnectionLifecycleTest {
    private fun joining() = ConnectionLifecycle().apply { move(Phase.HANDSHAKE, Phase.JOINING) }

    @Test fun `batch receipt and teleport do not pretend the renderer is loaded`() {
        val state = joining()
        state.acknowledgeBatch(8f)
        state.acknowledgeTeleport(7, 7)
        assertEquals(Phase.JOINING, state.phase)
        assertTrue(state.loaded())
        assertFalse(state.loaded(), "duplicate player-loaded must not reannounce the listener")
    }
    @Test fun `missing heartbeat is timed from the unanswered challenge not the latest tick`() {
        val state = joining()
        assertTrue(state.needsPing(0, 10))
        state.ping(8, 0)
        assertFalse(state.needsPing(9, 10))
        assertFailsWith<IllegalStateException> { state.needsPing(10, 10) }
    }
    @Test fun `only the current ping response clears the challenge`() {
        val state = joining()
        state.ping(9, 0)
        assertFailsWith<IllegalArgumentException> { state.pong(8) }
        state.pong(9)
        assertTrue(state.needsPing(11, 10))
    }
    @Test fun `closed sessions cannot become ready or change states`() {
        val state = joining()
        assertTrue(state.close())
        assertFalse(state.close())
        assertFailsWith<IllegalStateException> { state.loaded() }
        assertFailsWith<IllegalStateException> { state.move(Phase.JOINING, Phase.READY) }
    }
    @Test fun `invalid acknowledgement is rejected`() {
        val state = joining()
        assertFailsWith<IllegalArgumentException> { state.acknowledgeBatch(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { state.acknowledgeBatch(0f) }
        assertFailsWith<IllegalArgumentException> { state.acknowledgeTeleport(1, 2) }
    }
}
