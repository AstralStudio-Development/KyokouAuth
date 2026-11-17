package moe.ryuu.kyokouauth.core.avatar

import java.io.IOException
import java.util.UUID
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Presentation data only; neither a snapshot nor an offered pack is an identity credential. */
class RoleAvatarSnapshot(
    val fingerprint: String,
    microsoftPng: ByteArray,
    premiumPng: ByteArray,
    val microsoftDefault: Boolean,
    val premiumDefault: Boolean,
) {
    init {
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Invalid avatar fingerprint" }
        require(microsoftPng.size in 1..AvatarLimits.SPRITE_BYTES && premiumPng.size in 1..AvatarLimits.SPRITE_BYTES) { "Avatar sprite exceeds budget" }
    }
    private val microsoft = microsoftPng.copyOf()
    private val premium = premiumPng.copyOf()
    val microsoftPng: ByteArray get() = microsoft.copyOf()
    val premiumPng: ByteArray get() = premium.copyOf()
    val byteSize: Int get() = microsoft.size + premium.size

    companion object {
        /** Versioned content only: never XUID, names, timestamps, URLs or default flags. */
        fun create(microsoftPng: ByteArray, premiumPng: ByteArray, microsoftDefault: Boolean, premiumDefault: Boolean): RoleAvatarSnapshot {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("kyokouauth-role-avatar-v1\u0000".toByteArray(Charsets.US_ASCII))
            for (png in listOf(microsoftPng, premiumPng)) {
                digest.update(ByteBuffer.allocate(4).putInt(png.size).array())
                digest.update(png)
            }
            val fingerprint = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            return RoleAvatarSnapshot(fingerprint, microsoftPng, premiumPng, microsoftDefault, premiumDefault)
        }
    }
}

object AvatarLimits {
    const val SKIN_BYTES = 256 * 1024
    const val SPRITE_BYTES = 8192
    const val SNAPSHOT_WIRE_BYTES = 24 * 1024
    const val STATUS_WIRE_BYTES = 4 * 1024
}

data class RoleAvatarOffer(val nonce: UUID, val fingerprint: String?, val fallback: Boolean)

/** No source URL, texture JSON, XUID, exception cause or response body escapes this boundary. */
class AvatarUnavailableException : IOException("Role avatar presentation is unavailable or invalid")
