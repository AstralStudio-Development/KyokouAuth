package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoleHandoffTest {
    @Test
    fun `new contract matching XUID with changed gamertag cannot consume the pending choice`() {
        val store = BedrockRoleHints(8, 30_000L) { 1_000L }
        store.issue(choice(7L))
        assertNull(store.consume(proof(7L, gamertag = "Renamed"), UUID(2L, 2L)))
        assertTrue(store.hasHint(Xuid(7L)))
        assertEquals(choice(7L), store.consume(proof(7L), UUID(2L, 2L)))
    }

    @Test
    fun `Bedrock intent needs a current proof on a new connection and cannot consume another XUID`() {
        val store = BedrockRoleHints(8, 30_000L) { 1_000L }
        val choice = choice(7L)
        store.issue(choice)
        assertNull(store.consume(proof(7L, active = false), UUID(2L, 2L)))
        assertNull(store.consume(proof(8L), UUID(2L, 2L)))
        assertNull(store.consume(proof(7L), choice.issuerSession))
        assertTrue(store.hasHint(Xuid(7L)))
        assertEquals(choice, store.consume(proof(7L), UUID(2L, 2L)))
        assertNull(store.consume(proof(7L), UUID(3L, 3L)), "No replay or remembered preference")
    }

    @Test
    fun `native relationship mismatch cannot consume a choice and TTL boundary expires exactly`() {
        var now = 1_000L
        val store = BedrockRoleHints(8, 30_000L) { now }
        val choice = choice(7L)
        store.issue(choice)
        val linked = BedrockProof.createLinked(Xuid(7L), UUID.fromString("12345678-1234-4234-9234-123456789abc"),
            "Alex", "Gamer", Any()) { true }
        assertNull(store.consume(linked, UUID(2L, 2L)))
        assertTrue(store.hasHint(Xuid(7L)))
        now += 30_000L
        assertTrue(store.hasHint(Xuid(7L)), "An attempted expired resume must not silently become an ordinary login")
        assertNull(store.consume(proof(7L), UUID(2L, 2L)))
        assertTrue(store.isEmpty())
    }

    @Test
    fun `capacity and duplicate XUID are bounded while revoke never removes a replacement intent`() {
        val store = BedrockRoleHints(2, 30_000L) { 1_000L }
        val first = choice(7L)
        store.issue(first)
        store.issue(choice(8L))
        assertFailsWith<IllegalStateException> { store.issue(choice(9L)) }
        assertFailsWith<IllegalStateException> { store.issue(first) }
        store.revoke(first)
        val replacement = first.copy(issuerSession = UUID(5L, 5L))
        store.issue(replacement)
        store.revoke(first)
        assertEquals(replacement, store.consume(proof(7L), UUID(2L, 2L)))
        assertEquals(Xuid(8L), store.consume(proof(8L), UUID(2L, 2L))?.xuid)
        store.close()
        assertTrue(store.isEmpty())
        assertFailsWith<IllegalStateException> { store.issue(first) }
        assertNull(store.consume(proof(7L), UUID(2L, 2L)))
    }

    @Test
    fun `expired entries free bounded capacity and disabled store retains nothing`() {
        var now = 1_000L
        val store = BedrockRoleHints(1, 10L) { now }
        store.issue(choice(7L))
        now += 10L
        store.issue(choice(8L))
        assertNull(store.consume(proof(7L), UUID(2L, 2L)))
        store.close()
        assertTrue(store.isEmpty())
        assertTrue(!store.hasHint(Xuid(8L)))
    }

    private fun choice(id: Long) = BedrockRoleChoice(Xuid(id), "Gamer",
        RoleReference(BoundIdentity.Kind.XUID, UUID(0L, id), "Alias"), null, UUID(1L, id))
    private fun proof(id: Long, active: Boolean = true, gamertag: String = "Gamer") =
        BedrockProof.create(Xuid(id), UUID(0L, id), ".Bedrock", gamertag, ".", Any()) { active }
}
