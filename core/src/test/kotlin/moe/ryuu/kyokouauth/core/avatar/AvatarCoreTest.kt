package moe.ryuu.kyokouauth.core.avatar

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarCoreTest {
    private fun skin(height: Int = 64, hat: Boolean = false): ByteArray {
        val image = BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB)
        fun face(x0: Int, y0: Int, color: Int) { for (y in y0 until y0 + 8) for (x in x0 until x0 + 8) image.setRGB(x, y, color) }
        face(8, 0, 0xff00ff00.toInt()) // top: green
        face(8, 8, 0xffff0000.toInt()) // front: red, visible on the screen's RIGHT
        face(0, 8, 0xff0000ff.toInt()) // player's right side: blue, visible on screen LEFT
        face(16, 8, 0xffff00ff.toInt()) // player's left side: magenta, NOT visible in Java's GUI
        face(24, 8, 0xff00ffff.toInt()) // back: cyan, also not visible
        if (hat) {
            face(40, 8, 0x80ffffff.toInt()) // front hat, half white -> opaque pink over red
            face(32, 8, 0xffffff00.toInt()) // player's right-side hat, opaque yellow
            face(48, 8, 0xff000000.toInt()) // hidden left-side hat must not be sampled
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** A face ID plus two different channels identify every texel, including the hidden faces. */
    private fun gridSkin(height: Int = 64, hat: Boolean = false): ByteArray {
        val image = BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB)
        val faces = listOf(Triple(8, 0, 0x10), Triple(0, 8, 0x20), Triple(8, 8, 0x30),
            Triple(16, 8, 0x40), Triple(24, 8, 0x50), Triple(16, 0, 0x60))
        for ((x, y, face) in faces) for (v in 0 until 8) for (u in 0 until 8) {
            val color = 0xff000000.toInt() or (face shl 16) or (u shl 8) or v
            image.setRGB(x + u, y + v, color)
            if (hat) image.setRGB(x + 32 + u, y + v, color or 0x00800000)
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun value(url: String, extra: String = "") = Base64.getEncoder().encodeToString(
        """{"textures":{"SKIN":{"url":"$url"}}$extra}""".toByteArray(),
    )

    @Test
    fun `Java inventory view shows top above player right side on screen left and front on screen right`() {
        val png = RoleAvatarRenderer.sprite(skin())
        val image = ImageIO.read(png.inputStream())
        assertEquals(32, image.width)
        assertEquals(32, image.height)
        assertEquals(6, png[25].toInt()) // PNG RGBA colour type, not an opaque RGB image
        assertEquals(0, image.getRGB(0, 0))
        assertEquals(0xff00ff00.toInt(), image.getRGB(16, 5))
        assertEquals(0xff0000ff.toInt(), image.getRGB(8, 19))
        assertEquals(0xffff0000.toInt(), image.getRGB(24, 19))
        assertTrue(png.size <= AvatarLimits.SPRITE_BYTES)
    }

    @Test
    fun `asymmetric face grids preserve Java skull UV orientation including the hat and legacy layout`() {
        // Independent vanilla oracle: 1.21.1 item/player_head -> item/template_skull;
        // 1.21.4/1.21.5 items/player_head uses special head/player with the same base.
        // template_skull GUI rotation [30,45,0], SkullSpecialRenderer yaw 180 and
        // SkullBlockRenderer scale [-1,-1,1] expose model NORTH/front, WEST/player-right,
        // and DOWN/top. ModelPart.Cube/Polygon's non-mirrored UV corners give, in this
        // fixed 32px outline (UV coordinates here are face-local boundaries 0..8):
        // top:   (0,0)->(0,8),  (8,0)->(16,0), (8,8)->(32,8),  (0,8)->(16,16)
        // right: (0,0)->(0,8),  (8,0)->(16,16),(8,8)->(16,32), (0,8)->(0,24)
        // front: (0,0)->(16,16),(8,0)->(32,8), (8,8)->(32,24), (0,8)->(16,32)
        // Literal interior pixel samples below are hand-derived from those corners,
        // not from the renderer's inverse projection. Each face has four corner texels
        // and two off-diagonal texels: horizontal/vertical flips and U/V swaps all fail.
        val samples = listOf(
            Triple(2, 8, 0x100000), Triple(16, 1, 0x100700),
            Triple(30, 8, 0x100707), Triple(16, 15, 0x100007),
            Triple(12, 5, 0x100401), Triple(16, 11, 0x100205),
            Triple(1, 9, 0x200000), Triple(15, 16, 0x200700),
            Triple(15, 30, 0x200707), Triple(1, 23, 0x200007),
            Triple(9, 15, 0x200401), Triple(5, 21, 0x200205),
            Triple(17, 16, 0x300000), Triple(31, 9, 0x300700),
            Triple(31, 23, 0x300707), Triple(17, 30, 0x300007),
            Triple(25, 14, 0x300401), Triple(21, 24, 0x300205),
        )
        for (hat in listOf(false, true)) {
            val png = RoleAvatarRenderer.sprite(gridSkin(hat = hat))
            val image = ImageIO.read(png.inputStream())
            for ((x, y, rgb) in samples) {
                val expected = 0xff000000.toInt() or rgb or (if (hat) 0x00800000 else 0)
                assertEquals(expected, image.getRGB(x, y), "pixel ($x,$y), hat=$hat")
            }
            assertContentEquals(png, RoleAvatarRenderer.sprite(gridSkin(32, hat)))
        }
    }

    @Test
    fun `transparent and opaque hat layers compose on their Java visible face`() {
        val image = ImageIO.read(RoleAvatarRenderer.sprite(skin(hat = true)).inputStream())
        assertEquals(0xffffff00.toInt(), image.getRGB(8, 19))
        assertEquals(0xffff8080.toInt(), image.getRGB(24, 19))
        assertEquals(0xff00ff00.toInt(), image.getRGB(16, 5))
        assertContentEquals(RoleAvatarRenderer.sprite(skin(64)), RoleAvatarRenderer.sprite(skin(32)))
    }

    @Test
    fun `PNG size IHDR dimensions corruption and non PNG input fail before large decode`() {
        val huge = skin().copyOf().also { ByteBuffer.wrap(it, 16, 4).putInt(Int.MAX_VALUE) }
        listOf(huge, byteArrayOf(1, 2, 3), ByteArray(AvatarLimits.SKIN_BYTES + 1), skin().dropLast(1).toByteArray(),
            skin().copyOf().also { it[29] = (it[29].toInt() xor 1).toByte() }).forEach { bytes ->
            assertFailsWith<AvatarUnavailableException> { RoleAvatarRenderer.sprite(bytes) }
        }
    }

    @Test
    fun `portable default skin matches the independently verified public asset and returns copies`() {
        val first = DefaultAvatarSkin.bytes()
        assertEquals(848, first.size)
        first.fill(0)
        assertEquals(64, ImageIO.read(DefaultAvatarSkin.bytes().inputStream()).width)
        assertEquals(32, ImageIO.read(RoleAvatarRenderer.sprite(DefaultAvatarSkin.bytes()).inputStream()).width)
    }

    @Test
    fun `only fixed official skin host and exact hash path can become an HTTPS download`() {
        val hash = "a".repeat(64)
        assertEquals(URI("https://textures.minecraft.net/texture/$hash"), AvatarJson.skinLocation(value("http://textures.minecraft.net/texture/$hash")))
        val invalid = listOf("https://127.0.0.1/texture/$hash", "https://textures.minecraft.net.evil/texture/$hash",
            "https://evil@textures.minecraft.net/texture/$hash", "https://textures.minecraft.net:443/texture/$hash",
            "file:///texture/$hash", "https://textures.minecraft.net/texture/$hash?q=1",
            "https://textures.minecraft.net/texture/$hash#fragment", "https://textures.minecraft.net/texture/%61$hash",
            "https://textures.minecraft.net/texture/../$hash", "https://textures.minecraft.net/texture/short")
        invalid.forEach { url -> assertFailsWith<AvatarUnavailableException> { AvatarJson.skinLocation(value(url)) } }
        listOf("invalid-base64", Base64.getEncoder().encodeToString("{}".toByteArray()),
            value("https://textures.minecraft.net/texture/$hash", ",\"textures\":{}"), "a".repeat(16_385)).forEach { raw ->
            assertFailsWith<AvatarUnavailableException> { AvatarJson.skinLocation(raw) }
        }
    }

    @Test
    fun `fingerprint is versioned framed content and all byte-array views are defensive`() {
        val a = byteArrayOf(1, 2)
        val b = byteArrayOf(3)
        val snapshot = RoleAvatarSnapshot.create(a, b, true, false)
        assertEquals("a3e1cc6d2f0e1532d5afddaac26a847718f38ee9df82daaf47b9bb6912b91556", snapshot.fingerprint)
        a.fill(0)
        snapshot.premiumPng.fill(0)
        assertContentEquals(byteArrayOf(1, 2), snapshot.microsoftPng)
        assertContentEquals(byteArrayOf(3), snapshot.premiumPng)
        assertEquals(snapshot.fingerprint, RoleAvatarSnapshot.create(byteArrayOf(1, 2), byteArrayOf(3), false, true).fingerprint)
        assertFalse(snapshot.fingerprint == RoleAvatarSnapshot.create(byteArrayOf(1), byteArrayOf(2, 3), true, false).fingerprint)
        val wire = AvatarJson.ready(snapshot).toString(Charsets.UTF_8)
        assertTrue(wire.contains("\"schema\":1") && wire.contains("\"microsoft_png\":\"AQI=\""))
        assertFalse(wire.contains("xuid") || wire.contains("name") || wire.contains("token") || wire.contains("url"))
    }

    @Test
    fun `offered status is not a render ACK and only documented not found is null`() {
        val nonce = "830e811b-79dd-4ba0-824f-d84ee1b57244"
        val hash = "a".repeat(64)
        fun body(value: String) = value.toByteArray()
        assertEquals(RoleAvatarOffer(UUID.fromString(nonce), hash, false), AvatarJson.offered(200,
            body("""{"schema":1,"state":"offered","nonce":"$nonce","fingerprint":"$hash","fallback":false}""")))
        assertNull(AvatarJson.offered(404, body("""{"schema":1,"state":"not-found"}""")))
        listOf(200 to "{}", 404 to "", 404 to "{}", 202 to "{}", 500 to "{}",
            200 to """{"schema":1,"state":"applied","nonce":"$nonce","fingerprint":null,"fallback":true}""",
            200 to """{"schema":1,"state":"offered","nonce":"1-2-3-4-5","fingerprint":null,"fallback":true}""",
            200 to """{"schema":1,"state":"offered","nonce":"$nonce","fingerprint":"bad","fallback":true}""",
            200 to """{"schema":1,"state":"offered","nonce":"$nonce","fingerprint":null,"fallback":"true"}""",
            200 to """{"schema":1,"schema":1,"state":"not-found"}""").forEach { (status, raw) ->
            assertFailsWith<AvatarUnavailableException> { AvatarJson.offered(status, body(raw)) }
        }
        assertFailsWith<AvatarUnavailableException> { AvatarJson.offered(200, ByteArray(4097)) }
    }

    @Test
    fun `bounded subscriber cancels before oversized chunks or declared bodies are copied`() {
        class Subscription : Flow.Subscription {
            var cancelled = false
            override fun request(n: Long) = Unit
            override fun cancel() { cancelled = true }
        }
        val declared = AvatarHttp.BoundedSubscriber(8, 9)
        val first = Subscription()
        declared.onSubscribe(first)
        assertTrue(first.cancelled)
        assertFailsWith<ExecutionException> { declared.getBody().toCompletableFuture().get() }
        val chunks = AvatarHttp.BoundedSubscriber(8)
        val second = Subscription()
        chunks.onSubscribe(second)
        chunks.onNext(listOf(ByteBuffer.wrap(ByteArray(9))))
        assertTrue(second.cancelled)
        assertFailsWith<ExecutionException> { chunks.getBody().toCompletableFuture().get() }
        val valid = AvatarHttp.BoundedSubscriber(3)
        valid.onSubscribe(Subscription())
        valid.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1)), ByteBuffer.wrap(byteArrayOf(2, 3))))
        valid.onComplete()
        assertContentEquals(byteArrayOf(1, 2, 3), valid.getBody().toCompletableFuture().get())
    }
}
