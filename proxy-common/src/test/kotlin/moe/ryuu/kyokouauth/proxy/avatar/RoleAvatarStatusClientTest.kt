package moe.ryuu.kyokouauth.proxy.avatar

import moe.ryuu.kyokouauth.core.avatar.AvatarHttpResponse
import moe.ryuu.kyokouauth.core.avatar.AvatarUnavailableException
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoleAvatarStatusClientTest {
    private val endpoint = URI("http://127.0.0.1:24683/v1/packs/status")
    private val nonce = "830e811b-79dd-4ba0-824f-d84ee1b57244"
    private val hash = "a".repeat(64)
    private fun body(state: String = "offered", fingerprint: String = "\"$hash\"") =
        """{"schema":1,"state":"$state","nonce":"$nonce","fingerprint":$fingerprint,"fallback":false}""".toByteArray()

    @Test
    fun `read-only exact loopback GET returns typed offered presentation without implying rendered success`() {
        val requests = mutableListOf<URI>()
        val client = RoleAvatarStatusClient(endpoint) { uri -> requests += uri; AvatarHttpResponse(200, body()) }
        assertEquals(RoleAvatarOffer(UUID.fromString(nonce), hash, false), client.status(1001L))
        assertEquals(listOf(URI("http://127.0.0.1:24683/v1/packs/status?xuid=1001")), requests)
        assertEquals(RoleAvatarOffer(UUID.fromString(nonce), null, false), RoleAvatarStatusClient(endpoint) { AvatarHttpResponse(200, body(fingerprint = "null")) }.status(1001L))
        assertNull(RoleAvatarStatusClient(endpoint) { AvatarHttpResponse(404, """{"schema":1,"state":"not-found"}""".toByteArray()) }.status(1001L))
    }

    @Test
    fun `hostnames nonliteral loopback arbitrary paths redirects and appended queries cannot define endpoint`() {
        listOf("http://localhost:24683/v1/packs/status", "http://[::1]:24683/v1/packs/status", "http://127.0.0.2:24683/v1/packs/status",
            "https://127.0.0.1:24683/v1/packs/status", "http://127.0.0.1/v1/packs/status", "http://127.0.0.1:0/v1/packs/status",
            "http://user@127.0.0.1:24683/v1/packs/status", "http://127.0.0.1:24683/other",
            "http://127.0.0.1:24683/v1/packs/status?xuid=2", "http://127.0.0.1:24683/v1/packs/status#fragment").forEach { raw ->
            assertFailsWith<IllegalArgumentException> { RoleAvatarStatusClient(URI(raw)) }
        }
    }

    @Test
    fun `unknown not-found malformed and excessive responses fail with no sensitive cause or fake offer`() {
        listOf(404 to ByteArray(0), 404 to "{}".toByteArray(), 302 to body(), 500 to body(),
            200 to body(state = "applied"), 200 to body(fingerprint = "\"wrong\""), 200 to ByteArray(4097)).forEach { (status, bytes) ->
            val failure = assertFailsWith<AvatarUnavailableException> { RoleAvatarStatusClient(endpoint) { AvatarHttpResponse(status, bytes) }.status(1001L) }
            assertNull(failure.cause)
        }
        val client = RoleAvatarStatusClient(endpoint) { throw IllegalStateException("private-body-role-cookie") }
        val failure = assertFailsWith<AvatarUnavailableException> { client.status(1001L) }
        assertFalse(failure.message.orEmpty().contains("private"))
        assertFalse(failure.message.orEmpty().contains("cookie"))
        assertNull(failure.cause)
        assertFailsWith<AvatarUnavailableException> { RoleAvatarStatusClient(endpoint) { error("no request for invalid XUID") }.status(0L) }
    }

    @Test
    fun `whole response deadline cancels a loopback peer that never completes headers`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val release = CountDownLatch(1)
            val accepted = CountDownLatch(1)
            val peer = Thread {
                try { listener.accept().use { accepted.countDown(); release.await(2, TimeUnit.SECONDS) } }
                catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
            val client = RoleAvatarStatusClient(URI("http://127.0.0.1:${listener.localPort}/v1/packs/status"))
            val before = System.nanoTime()
            try {
                assertFailsWith<AvatarUnavailableException> { client.status(1001L) }
                assertTrue(accepted.await(1, TimeUnit.SECONDS))
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1_500)
            } finally { release.countDown(); peer.join(2_000) }
        }
    }
}
