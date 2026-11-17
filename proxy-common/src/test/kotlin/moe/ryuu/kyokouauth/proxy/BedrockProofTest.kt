package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.identity.Xuid
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BedrockProofTest {
    private fun proof(source: Any = Any(), current: () -> Boolean = { true }) =
        BedrockProof.create(Xuid(123L), UUID(0L, 123L), ".Bedrock", "Bedrock", ".", source, current)

    @Test fun `current proof preserves XUID identity but never authorizes another physical source`() {
        val channel = Any()
        val first = proof(channel)
        assertTrue(first.isCurrent())
        assertEquals(UUID(0L, 123L), first.uniqueId)
        assertEquals(".Bedrock", first.username)
        assertTrue(first.matches(proof(channel)))
        assertFalse(first.matches(proof(Any())), "Same identity is not the same physical connection")
    }

    @Test fun `stale or failing provider validation cannot remain a current proof`() {
        val channel = Any()
        var current = true
        val first = proof(channel) { current }
        current = false
        assertFalse(first.isCurrent())
        assertFalse(first.matches(proof(channel)))
        assertFalse(proof(channel) { error("controlled provider failure") }.isCurrent())
    }

    @Test fun `linked native identity retains the authenticated XUID independently of its Java UUID`() {
        val javaUuid = UUID.fromString("12345678-1234-4234-8234-123456789abc")
        val channel = Any()
        val linked = BedrockProof.createLinked(Xuid(123L), javaUuid, "PremiumRole", "XboxName", channel) { true }
        assertEquals(Xuid(123L), linked.xuid)
        assertEquals(javaUuid, linked.uniqueId)
        assertEquals(javaUuid, linked.linkedJavaUuid)
        assertEquals("PremiumRole", linked.username)
        assertFalse(linked.matches(proof(channel)), "linked metadata must not silently become an unlinked identity")
        assertTrue(linked.matches(BedrockProof.createLinked(Xuid(123L), javaUuid,
            "PremiumRole", "XboxName", channel) { true }))
        assertFailsWith<IllegalArgumentException> {
            BedrockProof.createLinked(Xuid(123L), UUID(0L, 123L), "PremiumRole", "XboxName", channel) { true }
        }
    }

    @Test fun `invalid XUID UUID and unsafe namespaces cannot create a proof`() {
        for ((xuid, uuid) in listOf(0L to UUID(0L, 0L), -1L to UUID(0L, -1L), 123L to UUID.randomUUID())) {
            assertFailsWith<IllegalArgumentException> {
                BedrockProof.create(Xuid(xuid), uuid, ".Bedrock", "Bedrock", ".", Any()) { true }
            }
        }
        for ((name, prefix) in listOf("Bedrock" to "", "BE_Bedrock" to "BE_", ".B\u0000ad" to ".",
                                      ".Bed rock" to ".", ("." + "x".repeat(40)) to ".")) {
            assertFailsWith<IllegalArgumentException> {
                BedrockProof.create(Xuid(123L), UUID(0L, 123L), name, "Bedrock", prefix, Any()) { true }
            }
        }
    }
}
