package moe.ryuu.kyokouauth.bukkit.inject

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The handshake crypto, exercised without a socket.
 *
 * Every failure mode here is silent on a live server - a bad padding mode or a missed byte reversal
 * makes the client disconnect with no server-side error - so the arithmetic is pinned by test.
 */
class HandshakeKeysTest {

    private val keys = HandshakeKeys()

    /** Reproduces what a vanilla client does: plain RSA, no byte reversal. */
    private fun clientEncrypt(plain: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keys.keyPair.public)
        return cipher.doFinal(plain)
    }

    @Test
    fun `a client-encrypted shared secret decrypts back to the same bytes`() {
        val secret = ByteArray(16) { (it * 7).toByte() }

        val recovered = keys.decrypt(clientEncrypt(secret))

        assertTrue(secret.contentEquals(recovered), "shared secret did not survive the round trip")
    }

    @Test
    fun `reversing the payload breaks it, which is why the client must not`() {
        // Pins the direction of the mistake that shipped once: a reversed payload is *not* what
        // vanilla sends, so the server must reject it rather than compensate for it.
        val reversed = clientEncrypt(ByteArray(16) { 3 }).reversedArray()

        val failed = runCatching { keys.decrypt(reversed) }.isFailure
        assertTrue(failed, "a reversed payload must not decrypt")
    }

    @Test
    fun `a matching challenge is accepted and a different one is not`() {
        val token = keys.newVerifyToken()
        assertEquals(4, token.size)

        assertTrue(keys.challengeMatches(token, token.copyOf()))
        assertFalse(keys.challengeMatches(token, keys.newVerifyToken()))
    }

    @Test
    fun `the two ciphers start in opposite modes and stay in sync`() {
        val secret = ByteArray(16) { 9 }
        val (decrypt, encrypt) = keys.ciphers(secret)

        val plaintext = "hello minecraft".toByteArray(Charsets.UTF_8)
        val wire = encrypt.update(plaintext)
        val received = decrypt.update(wire)

        assertTrue(plaintext.contentEquals(received), "the cipher pair did not reproduce the plaintext")
    }

    @Test
    fun `a different secret produces a different cipher stream`() {
        val (decryptA, encryptA) = keys.ciphers(ByteArray(16) { 1 })
        val (decryptB, _) = keys.ciphers(ByteArray(16) { 2 })

        val wire = encryptA.update("x".toByteArray())
        val wrong = decryptB.update(wire)

        assertFalse("x".toByteArray().contentEquals(wrong), "two secrets must not share a keystream")
    }

    @Test
    fun `the public key is the 1024-bit DER form the protocol expects`() {
        val encoded = keys.publicKeyEncoded

        // A 1024-bit RSA SubjectPublicKeyInfo is 162 bytes. The client parses whatever arrives, but
        // anything else means the keypair was generated with the wrong size.
        assertEquals(162, encoded.size, "unexpected public key encoding length")
        assertEquals("RSA", keys.keyPair.public.algorithm)
    }
}
