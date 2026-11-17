package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoleHandoffTest {
    @Test
    fun `selected role cookie resumes only once and a forged cookie cannot consume it`() {
        var now = 1_000L
        val store = RoleHandoffStore(8, 30_000L) { now }
        val issuer = UUID(1L, 1L)
        val grant = RoleHandoff(Xuid(7L), "Gamer", RoleReference(BoundIdentity.Kind.XUID,
            UUID(0L, 7L), "OldAlias"), null, RoleOrigin.MICROSOFT, issuer)
        val cookie = store.issueJava(grant)
        assertEquals(32, cookie.size)
        val forged = cookie.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(store.consumeJava(forged, UUID(2L, 2L)))
        assertEquals(grant, store.consumeJava(cookie, UUID(2L, 2L)))
        assertNull(store.consumeJava(cookie, UUID(3L, 3L)), "selection must not be replayed")
        val expired = store.issueJava(grant)
        now += 30_000L
        assertNull(store.consumeJava(expired, UUID(4L, 4L)))
        store.close()
        assertTrue(store.isEmpty())
    }

    @Test
    fun `Bedrock intent needs a current proof on a new connection and cannot consume another XUID`() {
        val store = RoleHandoffStore(8, 30_000L) { 1_000L }
        val issuer = UUID(1L, 1L)
        val grant = RoleHandoff(Xuid(7L), "Gamer", RoleReference(BoundIdentity.Kind.XUID,
            UUID(0L, 7L), "Alias"), null, RoleOrigin.FLOODGATE, issuer)
        store.issueBedrock(grant)
        fun proof(id: Long, active: Boolean = true) = BedrockProof.create(Xuid(id), UUID(0L, id),
            ".Bedrock", "Gamer", ".", Any()) { active }
        assertNull(store.consumeBedrock(proof(7L, false), UUID(2L, 2L)))
        assertNull(store.consumeBedrock(proof(8L), UUID(2L, 2L)))
        assertNull(store.consumeBedrock(proof(7L), issuer))
        assertTrue(store.hasBedrockHint(Xuid(7L)))
        assertEquals(grant, store.consumeBedrock(proof(7L), UUID(2L, 2L)))
        assertNull(store.consumeBedrock(proof(7L), UUID(3L, 3L)))
    }

    @Test
    fun `failed transfer revokes only its own grant and closed storage cannot issue credentials`() {
        val store = RoleHandoffStore(2, 30_000L) { 1_000L }
        fun grant(id: Long) = RoleHandoff(Xuid(id), "Gamer", RoleReference(BoundIdentity.Kind.XUID,
            UUID(0L, id), "Alias"), null, RoleOrigin.MICROSOFT, UUID(1L, id))
        val first = grant(7L)
        val cookie = store.issueJava(first)
        val other = store.issueJava(grant(8L))
        kotlin.test.assertFailsWith<IllegalStateException> { store.issueJava(grant(9L)) }
        store.revoke(first)
        assertNull(store.consumeJava(cookie, UUID(2L, 2L)))
        assertEquals(Xuid(8L), store.consumeJava(other, UUID(2L, 2L))?.xuid)
        store.close()
        kotlin.test.assertFailsWith<IllegalStateException> { store.issueJava(first) }
    }
}
