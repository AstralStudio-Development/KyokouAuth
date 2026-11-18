package moe.ryuu.kyokouauth.core.avatar

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** Fixed 32x32 nearest-neighbour head: Java inventory orientation, not a pixel-identical vanilla render. */
object RoleAvatarRenderer {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun sprite(sourcePng: ByteArray): ByteArray = try {
        val clean = validateSkin(sourcePng)
        val reader = ImageIO.getImageReadersByFormatName("PNG").asSequence().firstOrNull() ?: invalid()
        val skin = try {
            MemoryCacheImageInputStream(ByteArrayInputStream(clean)).use { input ->
                reader.setInput(input, false, true)
                if (reader.getWidth(0) != 64 || reader.getHeight(0) !in setOf(32, 64)) invalid()
                reader.read(0)
            }
        } finally { reader.dispose() }
        val sprite = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 32) for (x in 0 until 32) {
            val px = x + 0.5
            val py = y + 0.5
            // Vanilla template_skull GUI [30,45,0] with skull yaw 180: the player's
            // right side is on screen LEFT, the front on RIGHT. Top U points up-right
            // and V down-right; mirroring the old sprite would not preserve these UVs.
            val topU = px / 32.0 - (py - 8.0) / 16.0
            val topV = px / 32.0 + (py - 8.0) / 16.0
            val rightU = px / 16.0
            val rightV = (py - 8.0 - rightU * 8.0) / 16.0
            val frontU = (px - 16.0) / 16.0
            val frontV = (py - 16.0 + frontU * 8.0) / 16.0
            val sample = when {
                inside(topU, topV) -> pixel(skin, 8, 0, 40, 0, topU, topV)
                inside(rightU, rightV) -> pixel(skin, 0, 8, 32, 8, rightU, rightV)
                inside(frontU, frontV) -> pixel(skin, 8, 8, 40, 8, frontU, frontV)
                else -> 0
            }
            sprite.setRGB(x, y, sample)
        }
        skin.flush()
        val output = LimitedOutput(AvatarLimits.SPRITE_BYTES)
        if (!ImageIO.write(sprite, "png", output)) invalid()
        sprite.flush()
        output.bytes()
    } catch (_: Exception) { throw AvatarUnavailableException() }

    /** IHDR dimensions are checked before an ImageIO reader or decompressor is instantiated. */
    private fun validateSkin(bytes: ByteArray): ByteArray {
        if (bytes.size !in 33..AvatarLimits.SKIN_BYTES || !bytes.copyOfRange(0, 8).contentEquals(signature)) invalid()
        if (integer(bytes, 8) != 13L || String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR" ||
            integer(bytes, 16) != 64L || integer(bytes, 20) !in setOf(32L, 64L)) invalid()
        var offset = 8
        var chunks = 0
        var idat = false
        var ended = false
        val clean = ByteArrayOutputStream(bytes.size)
        clean.write(signature)
        while (offset < bytes.size) {
            if (++chunks > 128 || bytes.size - offset < 12) invalid()
            val size = integer(bytes, offset)
            val end = offset.toLong() + 12L + size
            if (end > bytes.size || size > AvatarLimits.SKIN_BYTES) invalid()
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val length = size.toInt()
            val crc = CRC32().also { it.update(bytes, offset + 4, length + 4) }.value
            if (crc != integer(bytes, offset + 8 + length)) invalid()
            if (type == "IHDR" && offset != 8) invalid()
            if (type == "PLTE" && length !in 3..768 || type == "tRNS" && length !in 1..256) invalid()
            if (type == "IDAT") idat = true
            if (type == "IEND") {
                if (!idat || length != 0 || end != bytes.size.toLong()) invalid()
                ended = true
            }
            if (type in setOf("IHDR", "PLTE", "tRNS", "IDAT", "IEND")) {
                clean.write(bytes, offset, length + 12)
            } else if (bytes[offset + 4].toInt() and 32 == 0) invalid()
            // All other ancillary metadata, including compressed profiles/text, is dropped before
            // ImageIO. ignoreMetadata is still set so no attacker-controlled metadata is decoded.
            offset = end.toInt()
        }
        if (!ended) invalid()
        return clean.toByteArray()
    }

    private fun integer(bytes: ByteArray, at: Int): Long = ByteBuffer.wrap(bytes, at, 4).int.toLong() and 0xffffffffL
    private fun inside(u: Double, v: Double) = u >= 0 && u < 1 && v >= 0 && v < 1
    private fun pixel(image: BufferedImage, x: Int, y: Int, hatX: Int, hatY: Int, u: Double, v: Double): Int {
        val dx = (u * 8).toInt().coerceIn(0, 7)
        val dy = (v * 8).toInt().coerceIn(0, 7)
        return over(image.getRGB(x + dx, y + dy), image.getRGB(hatX + dx, hatY + dy))
    }

    private fun over(base: Int, hat: Int): Int {
        val a = hat ushr 24
        val b = base ushr 24
        val alpha = a * 255 + b * (255 - a)
        if (alpha == 0) return 0
        fun channel(shift: Int): Int = (((hat ushr shift and 255) * a * 255 +
            (base ushr shift and 255) * b * (255 - a) + alpha / 2) / alpha).coerceIn(0, 255)
        return ((alpha + 127) / 255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private class LimitedOutput(private val maximum: Int) : OutputStream() {
        private val target = ByteArrayOutputStream(4096)
        override fun write(value: Int) { if (target.size() >= maximum) invalid(); target.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > maximum - target.size()) invalid()
            target.write(bytes, offset, length)
        }
        fun bytes(): ByteArray = target.toByteArray()
    }
    private fun invalid(): Nothing = throw AvatarUnavailableException()
}
