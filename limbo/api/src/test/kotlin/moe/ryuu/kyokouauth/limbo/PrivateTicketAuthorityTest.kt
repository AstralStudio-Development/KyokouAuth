package moe.ryuu.kyokouauth.limbo

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import kotlin.test.*

class PrivateTicketAuthorityTest {
    private fun identity(protocol: Int = 777) = TicketIdentity(UUID.randomUUID(), UUID.randomUUID(), "TicketPlayer", "203.0.113.4", protocol)
    private fun PrivateTicketAuthority.consume(token: String, identity: TicketIdentity) =
        redeem(token, identity.protocolVersion, identity.uuid, identity.name, identity.address)

    @Test fun `private forwarding accepts only the alias bound to its exact placeholder UUID`() {
        val id = UUID.nameUUIDFromBytes("OfflinePlayer:Temporary".toByteArray())
        val alias = "KA\$" + id.toString().replace("-", "").take(13)
        val bound = TicketIdentity(UUID.randomUUID(), id, alias, "203.0.113.4", 777)
        PrivateTicketAuthority().use { authority ->
            val token = authority.issue(bound, BooleanSupplier { true })
            assertEquals(bound, authority.consume(token, bound))
            assertFails { authority.consume(token, bound) }
        }
        assertFails { bound.copy(uuid = UUID.randomUUID()) }
        assertFails { bound.copy(name = "KA\$0000000000000") }
        assertFails { bound.copy(name = "arbitrary\$name") }
    }

    @Test fun `256 bit credential redeems once with exact identity`() {
        PrivateTicketAuthority().use { authority ->
            val identity = identity()
            val token = authority.issue(identity, BooleanSupplier { true })
            assertEquals(43, token.length)
            assertEquals(identity, authority.consume(token, identity))
            assertFails { authority.consume(token, identity) }
        }
    }

    @Test fun `wrong engine uuid name or address burns credential without downgrade`() {
        for (difference in 0..3) PrivateTicketAuthority().use { authority ->
            val identity = identity()
            val token = authority.issue(identity, BooleanSupplier { true })
            val wrong = when (difference) {
                0 -> identity.copy(protocolVersion = 767)
                1 -> identity.copy(uuid = UUID.randomUUID())
                2 -> identity.copy(name = "OtherPlayer")
                else -> identity.copy(address = "203.0.113.8")
            }
            assertFails { authority.consume(token, wrong) }
            assertFails { authority.consume(token, identity) }
        }
    }

    @Test fun `exact frontend liveness rejects closed original even if uuid is reused`() {
        val alive = AtomicBoolean(true)
        PrivateTicketAuthority().use { authority ->
            val identity = identity()
            val token = authority.issue(identity, BooleanSupplier(alive::get))
            alive.set(false)
            assertFails { authority.consume(token, identity) }
            assertFails { authority.issue(identity, BooleanSupplier(alive::get)) }
        }
    }

    @Test fun `capacity duplicate attempts revocation and shutdown fail closed`() {
        val authority = PrivateTicketAuthority(maxTickets = 1)
        val original = identity()
        val first = authority.issue(original, BooleanSupplier { true })
        assertFails { authority.issue(original, BooleanSupplier { true }) }
        assertFails { authority.issue(identity(), BooleanSupplier { true }) }
        authority.revoke(original.connectionId)
        assertFails { authority.consume(first, original) }
        val second = authority.issue(original, BooleanSupplier { true })
        authority.revokeTicket(second)
        assertFails { authority.consume(second, original) }
        val third = authority.issue(original, BooleanSupplier { true })
        authority.close()
        assertFails { authority.consume(third, original) }
        assertFails { authority.issue(original, BooleanSupplier { true }) }
    }

    @Test fun `expired and forged credentials fail`() {
        PrivateTicketAuthority(ttl = Duration.ofNanos(1)).use { authority ->
            val identity = identity()
            val token = authority.issue(identity, BooleanSupplier { true })
            assertFails { authority.consume(token, identity) }
            assertFails { authority.consume("A".repeat(43), identity) }
            assertFails { authority.consume("not-a-token", identity) }
        }
    }

    @Test fun `simultaneous redemption has one winner`() {
        PrivateTicketAuthority().use { authority ->
            val identity = identity()
            val token = authority.issue(identity, BooleanSupplier { true })
            val pool = Executors.newFixedThreadPool(2)
            try {
                val outcomes = pool.invokeAll(List(2) { Callable { runCatching { authority.consume(token, identity) }.isSuccess } })
                assertEquals(1, outcomes.count { it.get() })
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun `modern mode requires nonempty secret independently of ticket authority`() {
        assertFailsWith<IllegalArgumentException> { ForwardingMode.VelocityModern(byteArrayOf()) }
        val source = byteArrayOf(1)
        val mode = ForwardingMode.VelocityModern(source)
        source[0] = 2
        assertEquals(1, mode.secret[0])
    }
}
