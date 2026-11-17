package moe.ryuu.kyokouauth.proxy.avatar

import org.junit.jupiter.api.Test
import moe.ryuu.kyokouauth.core.avatar.AvatarUnavailableException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoleAvatarServiceTest {
    private fun freePort() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    private fun png(color: Int): ByteArray {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 64) for (x in 0 until 64) image.setRGB(x, y, color)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
    private fun texture(hash: String = "a".repeat(64)) = Base64.getEncoder().encodeToString(
        """{"textures":{"SKIN":{"url":"https://textures.minecraft.net/texture/$hash"}}}""".toByteArray(),
    )

    @Test
    fun `verified role preparation produces two independent transparent sprites without granting a role`() {
        var requests = 0
        RoleAvatarService(freePort(), downloader = { requests++; png(0xff1122cc.toInt()) }, defaultSkin = { png(0xffcc2211.toInt()) }).use { service ->
            service.start()
            val snapshot = service.prepare(1001L, null, texture()).get(2, TimeUnit.SECONDS)
            assertTrue(snapshot.microsoftDefault)
            assertFalse(snapshot.premiumDefault)
            assertEquals(1, requests)
            assertTrue(snapshot.fingerprint.matches(Regex("[0-9a-f]{64}")))
            assertFalse(snapshot.microsoftPng.contentEquals(snapshot.premiumPng))
            val image = ImageIO.read(snapshot.microsoftPng.inputStream())
            assertEquals(32, image.width)
            assertEquals(32, image.height)
            assertEquals(0, image.getRGB(0, 0))
            assertNotNull(service.snapshot(1001L))
            assertNull(service.snapshot(2002L))
        }
    }

    private fun request(port: Int, target: String, method: String = "GET", headers: String = ""): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().write("$method $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n${headers}\r\n".toByteArray())
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun `GET misses never prepare or download and ready wire has images but no identity credentials`() {
        val port = freePort()
        var calls = 0
        RoleAvatarService(port, downloader = { calls++; png(0xff11bb33.toInt()) }).use { service ->
            service.start()
            val miss = request(port, "/v1/avatars?xuid=1001")
            assertTrue(miss.startsWith("HTTP/1.1 202"))
            assertTrue(miss.endsWith("{\"schema\":1,\"state\":\"not-ready\"}"))
            assertEquals(0, calls)
            val ready = service.prepare(1001L, null, texture()).get(2, TimeUnit.SECONDS)
            val wire = request(port, "/v1/avatars?xuid=1001")
            assertTrue(wire.startsWith("HTTP/1.1 200"))
            assertTrue(wire.contains("\"fingerprint\":\"${ready.fingerprint}\""))
            assertTrue(wire.contains("\"microsoft_default\":true") && wire.contains("\"premium_default\":false"))
            listOf("\"xuid\"", "\"name\"", "cookie", "token", "textures.minecraft.net", "SQL").forEach { forbidden -> assertFalse(wire.contains(forbidden)) }
            assertTrue(wire.toByteArray().size < 24 * 1024 + 1024)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `completion callbacks may read the bridge without waiting on an internal service monitor`() {
        val port = freePort()
        val release = CountDownLatch(1)
        RoleAvatarService(port, downloader = { release.await(); png(0xff112233.toInt()) }).use { service ->
            service.start()
            val response = service.prepare(1001L, texture(), null).thenApply { request(port, "/v1/avatars?xuid=1001") }
            release.countDown()
            assertTrue(response.get(2, TimeUnit.SECONDS).startsWith("HTTP/1.1 200"))
        }
    }

    @Test
    fun `invalid or body-bearing HTTP requests cannot drive prepare or expose other paths`() {
        val port = freePort()
        var calls = 0
        RoleAvatarService(port, downloader = { calls++; png(0xff112233.toInt()) }).use { service ->
            service.start()
            listOf("/v1/avatars?xuid=0", "/v1/avatars?xuid=-1", "/v1/avatars?xuid=1001&xuid=2",
                "/v1/avatars?xuid=%31", "/v1/avatars?xuid=9999999999999999999", "/v1/avatars?name=Steve").forEach { target ->
                assertTrue(request(port, target).startsWith("HTTP/1.1 400"))
            }
            assertTrue(request(port, "/v1/avatars?xuid=1001", "POST").startsWith("HTTP/1.1 405"))
            assertTrue(request(port, "/private").startsWith("HTTP/1.1 404"))
            assertTrue(request(port, "/v1/avatars?xuid=1001", headers = "Content-Length: 10\r\n").startsWith("HTTP/1.1 400"))
            assertEquals(0, calls)
        }
    }

    @Test
    fun `same content survives profile rename timestamps and different XUIDs never share changed sprites`() {
        var calls = 0
        RoleAvatarService(freePort(), downloader = { uri -> calls++; png(if (uri.path.endsWith("a".repeat(64))) 0xff112233.toInt() else 0xffccaa22.toInt()) }).use { service ->
            service.start()
            val first = service.prepare(1001L, null, texture()).get(2, TimeUnit.SECONDS)
            val renamedValue = Base64.getEncoder().encodeToString("""{"timestamp":999,"profileName":"Renamed","textures":{"SKIN":{"url":"https://textures.minecraft.net/texture/${"a".repeat(64)}"}}}""".toByteArray())
            val renamed = service.prepare(1001L, null, renamedValue).get(2, TimeUnit.SECONDS)
            assertEquals(first.fingerprint, renamed.fingerprint)
            assertEquals(1, calls)
            val other = service.prepare(2002L, null, texture("b".repeat(64))).get(2, TimeUnit.SECONDS)
            assertFalse(other.fingerprint == renamed.fingerprint)
            assertEquals(renamed.fingerprint, service.snapshot(1001L)?.fingerprint)
            assertEquals(other.fingerprint, service.snapshot(2002L)?.fingerprint)
            first.microsoftPng.fill(0)
            assertEquals(32, ImageIO.read(service.snapshot(1001L)!!.microsoftPng.inputStream()).width)
        }
    }

    @Test
    fun `bad texture network and PNG failures are not default or stale cached success`() {
        RoleAvatarService(freePort(), downloader = { throw IllegalStateException("secret-upstream-body") }).use { service ->
            service.start()
            service.prepare(1001L, null, null).get(2, TimeUnit.SECONDS)
            val failure = assertFailsWith<ExecutionException> { service.prepare(1001L, null, texture()).get(2, TimeUnit.SECONDS) }
            val sanitized = assertIs<AvatarUnavailableException>(failure.cause)
            assertFalse(sanitized.message.orEmpty().contains("secret"))
            assertNull(sanitized.cause)
            assertNull(service.snapshot(1001L))
            assertFailsWith<ExecutionException> { service.prepare(1002L, "invalid", null).get(2, TimeUnit.SECONDS) }
            assertFailsWith<ExecutionException> { service.prepare(1002L, "", null).get(2, TimeUnit.SECONDS) }
        }
        RoleAvatarService(freePort(), downloader = { ByteArray(256 * 1024 + 1) }).use { service ->
            service.start()
            assertFailsWith<ExecutionException> { service.prepare(1001L, texture(), null).get(2, TimeUnit.SECONDS) }
            assertNull(service.snapshot(1001L))
        }
    }

    @Test
    fun `snapshot cache is bounded to 64 and closing clears snapshots and refuses all new work`() {
        val service = RoleAvatarService(freePort())
        assertFailsWith<ExecutionException> { service.prepare(1001L, null, null).get() }
        service.start()
        for (xuid in 1L..65L) service.prepare(xuid, null, null).get(2, TimeUnit.SECONDS)
        assertNull(service.snapshot(1L))
        assertNotNull(service.snapshot(65L))
        service.close()
        service.close()
        assertNull(service.snapshot(65L))
        assertFailsWith<ExecutionException> { service.prepare(65L, null, null).get() }
        assertFailsWith<AvatarUnavailableException> { service.start() }
    }

    @Test
    fun `one worker plus sixteen queue entries reject overload and close fails every pending future`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = RoleAvatarService(freePort(), downloader = { entered.countDown(); release.await(); png(0xff112233.toInt()) })
        try {
            service.start()
            val active = service.prepare(1L, null, texture())
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val queued = (2L..17L).map { service.prepare(it, null, texture()) }
            assertFailsWith<ExecutionException> { service.prepare(18L, null, texture()).get(1, TimeUnit.SECONDS) }
            service.close()
            (listOf(active) + queued).forEach { future -> assertFailsWith<ExecutionException> { future.get(1, TimeUnit.SECONDS) } }
            assertNull(service.snapshot(1L))
        } finally { release.countDown(); service.close() }
    }

    @Test
    fun `prepare deadline includes queue or upstream time and cannot later populate a snapshot`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        RoleAvatarService(freePort(), downloader = { entered.countDown(); release.await(); png(0xff112233.toInt()) }, prepareTimeoutMillis = 300).use { service ->
            try {
                service.start()
                val result = service.prepare(1001L, texture(), null)
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertFailsWith<ExecutionException> { result.get(1, TimeUnit.SECONDS) }
                assertNull(service.snapshot(1001L))
            } finally { release.countDown() }
        }
    }

    @Test
    fun `SSRF texture locations are rejected before the injected downloader is ever called`() {
        var calls = 0
        RoleAvatarService(freePort(), downloader = { calls++; png(0xff112233.toInt()) }).use { service ->
            service.start()
            val value = Base64.getEncoder().encodeToString("""{"textures":{"SKIN":{"url":"http://127.0.0.1/private"}}}""".toByteArray())
            assertFailsWith<ExecutionException> { service.prepare(1001L, value, null).get(2, TimeUnit.SECONDS) }
            assertEquals(0, calls)
            assertNull(service.snapshot(1001L))
        }
    }

    @Test
    fun `bounded source cache evicts after sixteen distinct official hash locations`() {
        var calls = 0
        val source = png(0xff112233.toInt())
        RoleAvatarService(freePort(), downloader = { calls++; source }).use { service ->
            service.start()
            for (index in 1..17) service.prepare(index.toLong(), texture(index.toString(16).padStart(64, '0')), null).get(2, TimeUnit.SECONDS)
            assertEquals(17, calls)
            service.prepare(1001L, texture("1".padStart(64, '0')), null).get(2, TimeUnit.SECONDS)
            assertEquals(18, calls)
        }
    }

    @Test
    fun `oversized headers and slow incomplete requests close within the bounded bridge deadline`() {
        val port = freePort()
        var calls = 0
        RoleAvatarService(port, downloader = { calls++; png(0xff112233.toInt()) }).use { service ->
            service.start()
            val giant = runCatching { request(port, "/v1/avatars?xuid=1001", headers = "X-Huge: ${"a".repeat(600)}\r\n") }.getOrDefault("")
            assertFalse(giant.startsWith("HTTP/1.1 200") || giant.startsWith("HTTP/1.1 202"))
            val many = (1..12).joinToString("") { "X-$it: ${"a".repeat(390)}\r\n" }
            val aggregate = runCatching { request(port, "/v1/avatars?xuid=1001", headers = many) }.getOrDefault("")
            assertFalse(aggregate.startsWith("HTTP/1.1 200") || aggregate.startsWith("HTTP/1.1 202"))
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write("GET /v1/avatars?xuid=1001 HTTP/1.1\r\n".toByteArray())
                val start = System.nanoTime()
                runCatching { socket.getInputStream().readBytes() }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_800)
            }
            assertEquals(0, calls)
        }
    }

    @Test
    fun `occupied explicit loopback port refuses startup rather than silently rebinding`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            RoleAvatarService(occupied.localPort).use { service -> assertFailsWith<AvatarUnavailableException> { service.start() } }
        }
    }
}
