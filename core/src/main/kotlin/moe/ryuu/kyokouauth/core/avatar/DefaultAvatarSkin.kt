package moe.ryuu.kyokouauth.core.avatar

import java.security.MessageDigest

/** Portable, public default Steve asset; never reads a live gateway or player-cache directory. */
object DefaultAvatarSkin {
    const val SHA256 = "06628f9cef88520da742ab280a01677ce17c99717f964ed89ed791673121094c"
    private val image: ByteArray by lazy {
        val bytes = DefaultAvatarSkin::class.java.getResourceAsStream("default-steve.png")?.use {
            it.readNBytes(AvatarLimits.SKIN_BYTES + 1)
        } ?: throw AvatarUnavailableException()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        if (bytes.size != 848 || hash != SHA256) throw AvatarUnavailableException()
        bytes
    }
    fun bytes(): ByteArray = image.copyOf()
}
