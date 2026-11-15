package moe.ryuu.kyokouauth.bukkit.inject

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.UUID

/**
 * The server's half of the Minecraft encryption handshake, minus the protocol plumbing.
 *
 * Kept apart from the Netty handler so the cryptography can be exercised without a channel: a wrong
 * RSA padding mode or a mis-decoded shared secret produces a login that simply hangs, with nothing
 * in the log to say why.
 */
class HandshakeKeys {

    private val random = SecureRandom()

    /** One keypair for the process: vanilla also generates it once and reuses it for every connection. */
    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()

    val publicKeyEncoded: ByteArray get() = keyPair.public.encoded

    fun newVerifyToken(): ByteArray = ByteArray(4).also { random.nextBytes(it) }

    /**
     * Decrypts a field the client RSA-encrypted with our public key.
     *
     * **No byte reversal.** Vanilla's `Crypt.cipherData` is `cipher.doFinal(data)` and nothing else,
     * verified with `javap` against both 1.12.2 (`MinecraftEncryption.a`) and 26.3 (`Crypt.cipherData`).
     * An earlier version of this class reversed the bytes first, on the assumption that Minecraft did
     * - and the test probe reversed too, so the two mistakes cancelled out and every test passed.
     * A real client does not reverse, and the first genuine login attempt failed with
     * "Padding error in decryption".
     */
    fun decrypt(field: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keyPair.private)
        return cipher.doFinal(field)
    }

    /** True when the decrypted challenge equals what we sent - proof the client holds the shared secret. */
    fun challengeMatches(decrypted: ByteArray, expected: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(decrypted, expected)

    /**
     * Builds the AES/CFB8 ciphers both sides use for the rest of the connection.
     *
     * CFB8 with no padding is what the protocol specifies; AES/CFB8/NoPadding in JCE speaks the same
     * stream, so no padding handling is involved.
     */
    fun ciphers(sharedSecret: ByteArray): Pair<javax.crypto.Cipher, javax.crypto.Cipher> {
        val key = javax.crypto.spec.SecretKeySpec(sharedSecret, "AES")
        val iv = javax.crypto.spec.IvParameterSpec(sharedSecret)
        val decrypt = javax.crypto.Cipher.getInstance("AES/CFB8/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, key, iv)
        }
        val encrypt = javax.crypto.Cipher.getInstance("AES/CFB8/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, key, iv)
        }
        return decrypt to encrypt
    }

    /** The UUID a premium identity occupies, used when a handshake cannot complete. */
    fun placeholderUuid(name: String): UUID =
        UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).toByteArray(Charsets.UTF_8))
}
