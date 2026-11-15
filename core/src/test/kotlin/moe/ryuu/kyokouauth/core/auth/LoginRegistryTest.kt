package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.identity.GameName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The registry exists to stop one specific attack: two connections presenting the same UUID, one of
 * which would end up admitted without verifying. These tests name that attack and the expiry rules
 * that keep the map from leaking.
 */
class LoginRegistryTest {

    private var now = 1_000L
    private val registry = LoginRegistry(ttlMillis = 60_000L, clock = { now })

    private val uuid = UUID(0L, 1001L)
    private fun name(raw: String) = GameName.parseOrThrow(raw)

    private fun entry(uuid: UUID = this.uuid) = LoginRegistry.Pending(
        uuid = uuid,
        name = name("Steve"),
        address = "10.0.0.1",
        verification = null,
        reservedAt = now,
    )

    @Test
    fun `a first reservation is granted`() {
        assertIs<LoginRegistry.Reservation.Granted>(registry.reserve(entry()))
    }

    @Test
    fun `a second connection for the same uuid is refused`() {
        registry.reserve(entry())

        val second = registry.reserve(entry())
        assertIs<LoginRegistry.Reservation.AlreadyInFlight>(second)
        assertEquals(1, registry.size())
    }

    @Test
    fun `the refused connection did not replace the first`() {
        val first = entry()
        registry.reserve(first)
        registry.reserve(entry())

        // The original reservation must survive, otherwise the connection it belongs to would find
        // nothing at join and be let through unverified.
        assertSame(first, registry.consume(uuid))
    }

    @Test
    fun `consume returns the entry exactly once`() {
        val first = entry()
        registry.reserve(first)

        assertSame(first, registry.consume(uuid))
        assertNull(registry.consume(uuid), "a consumed entry must not be handed out twice")
    }

    @Test
    fun `an entry that was never joined expires`() {
        registry.reserve(entry())

        now += 60_001L

        assertNull(registry.consume(uuid))
        assertEquals(0, registry.size())
    }

    @Test
    fun `expired entries do not block a new reservation`() {
        registry.reserve(entry())
        now += 60_001L

        assertIs<LoginRegistry.Reservation.Granted>(registry.reserve(entry()))
    }

    @Test
    fun `release frees the uuid for a reconnect`() {
        registry.reserve(entry())
        registry.release(uuid)

        assertNull(registry.consume(uuid))
        assertIs<LoginRegistry.Reservation.Granted>(registry.reserve(entry()))
    }

    @Test
    fun `different uuids do not interfere`() {
        val other = UUID(0L, 2002L)

        assertIs<LoginRegistry.Reservation.Granted>(registry.reserve(entry()))
        assertIs<LoginRegistry.Reservation.Granted>(registry.reserve(entry(other)))
        assertEquals(2, registry.size())
    }
}
