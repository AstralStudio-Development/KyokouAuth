package moe.ryuu.kyokouauth.core.auth

import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session hash is the single value both sides must agree on, and it is computed identically by
 * the client. Getting its rendering wrong makes every premium login fail at `hasJoined` with no
 * diagnostic, so the format is pinned here rather than left to inspection.
 */
class MojangSessionTest {

    private fun keyPair(): java.security.KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()

    @Test
    fun `the hash is sha1 over serverId, secret and public key`() {
        val pair = keyPair()
        val secret = ByteArray(16) { it.toByte() }

        val expected = MessageDigest.getInstance("SHA-1").run {
            update("".toByteArray(Charsets.ISO_8859_1))
            update(secret)
            update(pair.public.encoded)
            BigInteger(digest()).toString(16)
        }

        assertEquals(expected, MojangSession.serverHash("", secret, pair.public))
    }

    @Test
    fun `the hash is rendered as signed hex, matching the vanilla client`() {
        val pair = keyPair()

        // Find a secret whose digest has the high bit set, which is the case that produces a leading
        // minus sign. Roughly half of all digests do; the loop makes the test deterministic rather
        // than relying on the first candidate.
        var negative: String? = null
        for (seed in 0..512) {
            val secret = ByteArray(16) { (it + seed).toByte() }
            val hash = MojangSession.serverHash("", secret, pair.public)
            if (hash.startsWith("-")) {
                negative = hash
                break
            }
        }

        assertTrue(
            negative != null,
            "no digest with the high bit set was found; the signed-hex branch is untested",
        )
    }

    @Test
    fun `an empty serverId is the modern form and changes the hash`() {
        val pair = keyPair()
        val secret = ByteArray(16) { 7 }

        val modern = MojangSession.serverHash("", secret, pair.public)
        val legacy = MojangSession.serverHash("someid", secret, pair.public)

        assertTrue(modern != legacy, "the serverId must participate in the digest")
    }
}
