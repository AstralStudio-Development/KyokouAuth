package moe.ryuu.kyokouauth.core.auth

import com.github.benmanes.caffeine.cache.Ticker
import moe.ryuu.kyokouauth.core.http.Http
import moe.ryuu.kyokouauth.core.identity.GameName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficialJavaProfileClientTest {
    private val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private fun response(body: String, status: Int = 200) = Http.Response(status, body, emptyMap())
    private fun body(properties: String = "[]", id: String = "069a79f444e94726a5befca90e38aaf5", name: String = "Notch") =
        """{"id":"$id","name":"$name","properties":$properties,"profileActions":[]}"""

    @Test
    fun `current official Java name and signed texture are returned for the exact UUID only`() {
        val urls = mutableListOf<String>()
        val client = OfficialJavaProfileClient { url ->
            urls += url
            response(body("""[{"name":"textures","value":"opaque-texture-value","signature":"opaque-signature"}]"""))
        }
        assertEquals(OfficialJavaProfile(uuid, GameName.parseOrThrow("Notch"), ProfileTexture("opaque-texture-value", "opaque-signature")), client.lookup(uuid))
        assertEquals(listOf("https://sessionserver.mojang.com/session/minecraft/profile/069a79f444e94726a5befca90e38aaf5?unsigned=false"), urls)
    }

    @Test
    fun `missing textures is an explicit default skin rather than an unknown profile`() {
        listOf(body(), """{"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch"}""", body("""[{"name":"other","value":"opaque"}]""")).forEach { raw ->
            assertEquals(OfficialJavaProfile(uuid, GameName.parseOrThrow("Notch"), null), OfficialJavaProfileClient { response(raw) }.lookup(uuid))
        }
    }

    @Test
    fun `repeated UUID lookups use current name and do not reuse stale texture or success`() {
        val replies = ArrayDeque(listOf(response(body(name = "Before")), response(body(name = "After")), response("", 503)))
        val client = OfficialJavaProfileClient { replies.removeFirst() }
        assertEquals("Before", client.lookup(uuid).name.value)
        assertEquals("After", client.lookup(uuid).name.value)
        assertFailsWith<OfficialJavaProfileUnavailableException> { client.lookup(uuid) }
        assertEquals(0, replies.size)
    }

    @Test
    fun `only a complete HTTP 200 matching UUID and valid Java name is a profile`() {
        listOf(204, 301, 302, 400, 401, 403, 404, 429, 500, 502, 503).forEach { status ->
            assertFailsWith<OfficialJavaProfileUnavailableException>("status=$status") { OfficialJavaProfileClient { response(body(), status) }.lookup(uuid) }
        }
        listOf(
            "", " ", "{}", "null", "[]", "false",
            body(id = "853c80ef3c3749fdaa49938b674adae6"), body(id = uuid.toString()), body(id = "1-2-3-4-5"),
            body(name = ".Notch"), body(name = "No"), body(name = "Invalid Name"),
            body().replace("\"name\":\"Notch\"", "\"name\":null"),
            body().replace("\"name\":\"Notch\"", "\"name\":123"),
            body().replace("\"id\":\"069a79f444e94726a5befca90e38aaf5\"", "\"id\":null"),
        ).forEachIndexed { index, raw ->
            assertFailsWith<OfficialJavaProfileUnavailableException>("invalid profile=$index") { OfficialJavaProfileClient { response(raw) }.lookup(uuid) }
        }
        listOf(UUID(0L, 0L), UUID(0L, 1001L)).forEach { invalid ->
            assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { error("invalid namespace must not request") }.lookup(invalid) }
        }
    }

    @Test
    fun `ambiguous malformed and unsigned textures are unavailable not default skins`() {
        val invalidProperties = listOf(
            "null", "{}", "[null]", "[[]]", "[{}]",
            """[{"name":"textures"}]""",
            """[{"name":"textures","value":"value"}]""",
            """[{"name":"textures","value":"value","signature":null}]""",
            """[{"name":"textures","value":null,"signature":"signed"}]""",
            """[{"name":"textures","value":"","signature":"signed"}]""",
            """[{"name":"textures","value":"value","signature":""}]""",
            """[{"name":"textures","value":123,"signature":"signed"}]""",
            """[{"name":"textures","value":"value","signature":123}]""",
            """[{"name":"textures","name":"other","value":"value","signature":"signed"}]""",
            """[{"name":"textures","value":"value","signature":"one","signature":"two"}]""",
            """[{"name":"textures","value":"one","signature":"one"},{"name":"textures","value":"two","signature":"two"}]""",
        )
        invalidProperties.forEachIndexed { index, properties ->
            assertFailsWith<OfficialJavaProfileUnavailableException>("invalid properties=$index") { OfficialJavaProfileClient { response(body(properties)) }.lookup(uuid) }
        }
    }

    @Test
    fun `texture property and whole response limits are bounded`() {
        val maxValue = "a".repeat(16_384)
        val maxSignature = "b".repeat(4_096)
        fun textures(value: String, signature: String) = """[{"name":"textures","value":"$value","signature":"$signature"}]"""
        assertEquals(ProfileTexture(maxValue, maxSignature), OfficialJavaProfileClient { response(body(textures(maxValue, maxSignature))) }.lookup(uuid).texture)
        listOf(textures(maxValue + "a", "sig"), textures("value", maxSignature + "b"), "[" + List(17) { """{"name":"other","value":"value"}""" }.joinToString(",") + "]").forEach { properties ->
            assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { response(body(properties)) }.lookup(uuid) }
        }
        assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { response(" ".repeat(Http.MAX_RESPONSE_BYTES + 1) + body()) }.lookup(uuid) }
    }

    @Test
    fun `invalid JSON and duplicate fields never become a profile`() {
        listOf(
            body() + "{}", body() + " trailing", "/*comment*/" + body(), body() + "//comment",
            body().replace("\"id\"", "id"), body().replace("\"name\":\"Notch\"", "'name':'Notch'"),
            body().replace("\"name\":\"Notch\"", "\"name\":\"Not\nch\""),
            body().replace("\"name\":\"Notch\"", "\"name\":\"Not\\qch\""),
            body().replace("\"name\":\"Notch\"", "\"name\":\"Notch\",\"name\":\"Other\""),
            body().dropLast(1) + ",}",
            body().replace("\"profileActions\":[]", "\"profileActions\":{}"),
            body().replace("\"profileActions\":[]", "\"profileActions\":[[]]"),
        ).forEachIndexed { index, raw ->
            assertFailsWith<OfficialJavaProfileUnavailableException>("invalid JSON=$index") { OfficialJavaProfileClient { response(raw) }.lookup(uuid) }
        }
    }

    @Test
    fun `request and parser failures expose no sensitive message body cause or suppressed data`() {
        val failures = listOf(IOException("secret-token-body"), Http.ResponseTooLargeException(), IllegalStateException("private-profile"))
        failures.forEach { upstream ->
            val failure = assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { throw upstream }.lookup(uuid) }
            assertFalse(failure.message.orEmpty().contains("secret"))
            assertFalse(failure.message.orEmpty().contains("private"))
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
        }
        val parserFailure = assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { response("{\"secret-token-body\":") }.lookup(uuid) }
        assertFalse(parserFailure.message.orEmpty().contains("secret"))
        assertNull(parserFailure.cause)
        assertTrue(parserFailure.suppressed.isEmpty())
    }

    @Test
    fun `rapid repeated role profile reads reuse a validated signed profile instead of making a second rate limited request`() {
        val replies = ArrayDeque(listOf(
            response(body("""[{"name":"textures","value":"signed-value","signature":"signed-signature"}]""")),
            response("upstream-private-body", 429),
        ))
        val requests = AtomicInteger()
        val client = OfficialJavaProfileClient {
            requests.incrementAndGet()
            replies.removeFirst()
        }
        val expected = OfficialJavaProfile(uuid, GameName.parseOrThrow("Notch"), ProfileTexture("signed-value", "signed-signature"))
        repeat(4) { assertEquals(expected, client.lookupRecent(uuid)) }
        assertEquals(1, requests.get())
        assertEquals(1, replies.size)
    }

    @Test
    fun `fresh lookup bypasses recent profile metadata and still observes renames textures and failures`() {
        val replies = ArrayDeque(listOf(
            response(body("""[{"name":"textures","value":"old-value","signature":"old-signature"}]""", name = "Before")),
            response(body(name = "After")),
            response("private-upstream-body", 503),
        ))
        val client = OfficialJavaProfileClient { replies.removeFirst() }
        assertEquals("Before", client.lookupRecent(uuid).name.value)
        val renamed = client.lookup(uuid)
        assertEquals("After", renamed.name.value)
        assertNull(renamed.texture)
        val failed = assertFailsWith<OfficialJavaProfileUnavailableException> { client.lookup(uuid) }
        assertEquals(OfficialJavaProfileUnavailableException.Reason.HTTP, failed.reason)
        assertEquals(503, failed.httpStatus)
        assertEquals(0, replies.size)
    }

    @Test
    fun `recent metadata expires after sixty seconds from write and expired failure never falls back to success`() {
        val nanos = AtomicLong()
        val replies = ArrayDeque(listOf(response(body(name = "Before")), response("private-upstream-body", 503), response(body(name = "After"))))
        val requests = AtomicInteger()
        val client = OfficialJavaProfileClient(ticker = Ticker { nanos.get() }) {
            requests.incrementAndGet()
            replies.removeFirst()
        }
        assertEquals("Before", client.lookupRecent(uuid).name.value)
        nanos.set(TimeUnit.SECONDS.toNanos(59))
        assertEquals("Before", client.lookupRecent(uuid).name.value)
        assertEquals(1, requests.get())
        nanos.set(TimeUnit.SECONDS.toNanos(60))
        val failed = assertFailsWith<OfficialJavaProfileUnavailableException> { client.lookupRecent(uuid) }
        assertEquals(OfficialJavaProfileUnavailableException.Reason.HTTP, failed.reason)
        assertEquals(503, failed.httpStatus)
        assertEquals(2, requests.get())
        assertEquals("After", client.lookupRecent(uuid).name.value)
        assertEquals(3, requests.get())
    }

    @Test
    fun `recent profile metadata is isolated by exact UUID`() {
        val other = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6")
        val urls = mutableListOf<String>()
        val client = OfficialJavaProfileClient { url ->
            urls += url
            when {
                url.contains("069a79f444e94726a5befca90e38aaf5") -> response(body(name = "FirstRole"))
                url.contains("853c80ef3c3749fdaa49938b674adae6") -> response(body(id = "853c80ef3c3749fdaa49938b674adae6", name = "OtherRole"))
                else -> error("unexpected UUID")
            }
        }
        assertEquals(OfficialJavaProfile(uuid, GameName.parseOrThrow("FirstRole")), client.lookupRecent(uuid))
        assertEquals(OfficialJavaProfile(other, GameName.parseOrThrow("OtherRole")), client.lookupRecent(other))
        assertEquals("FirstRole", client.lookupRecent(uuid).name.value)
        assertEquals("OtherRole", client.lookupRecent(other).name.value)
        assertEquals(2, urls.size)
    }

    @Test
    fun `a recent lookup failure is neither cached nor immediately retried`() {
        val replies = ArrayDeque(listOf(response("private-upstream-body", 429), response(body())))
        val requests = AtomicInteger()
        val client = OfficialJavaProfileClient {
            requests.incrementAndGet()
            replies.removeFirst()
        }
        val failed = assertFailsWith<OfficialJavaProfileUnavailableException> { client.lookupRecent(uuid) }
        assertEquals(OfficialJavaProfileUnavailableException.Reason.HTTP, failed.reason)
        assertEquals(429, failed.httpStatus)
        assertEquals(1, requests.get())
        assertEquals(1, replies.size)
        assertEquals("Notch", client.lookupRecent(uuid).name.value)
        assertEquals(2, requests.get())
    }

    @Test
    fun `overlapping recent requests for the same UUID share one successful upstream lookup`() {
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val attempting = CountDownLatch(8)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val client = OfficialJavaProfileClient {
            requests.incrementAndGet()
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            response(body())
        }
        try {
            val pending = List(8) {
                workers.submit<OfficialJavaProfile> {
                    check(start.await(3, TimeUnit.SECONDS))
                    attempting.countDown()
                    client.lookupRecent(uuid)
                }
            }
            start.countDown()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(attempting.await(3, TimeUnit.SECONDS))
            release.countDown()
            pending.forEach { assertEquals(OfficialJavaProfile(uuid, GameName.parseOrThrow("Notch")), it.get(3, TimeUnit.SECONDS)) }
            assertEquals(1, requests.get())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `a pending recent request does not block a different UUID`() {
        val other = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6")
        val workers = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OfficialJavaProfileClient { url ->
            if (url.contains("069a79f444e94726a5befca90e38aaf5")) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                response(body())
            } else response(body(id = "853c80ef3c3749fdaa49938b674adae6", name = "OtherRole"))
        }
        try {
            val first = workers.submit<OfficialJavaProfile> { client.lookupRecent(uuid) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val second = workers.submit<OfficialJavaProfile> { client.lookupRecent(other) }
            assertEquals("OtherRole", second.get(2, TimeUnit.SECONDS).name.value)
            release.countDown()
            assertEquals("Notch", first.get(3, TimeUnit.SECONDS).name.value)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `HTTP failures retain only fixed classification status and numeric retry after`() {
        listOf(429, 503).forEach { status ->
            val privateData = "secret-token-body https://private.invalid/ $uuid"
            val client = OfficialJavaProfileClient {
                Http.Response(status, privateData, mapOf("rEtRy-AfTeR" to listOf(" 17 "), "X-Private" to listOf(privateData)))
            }
            val failed = assertFailsWith<OfficialJavaProfileUnavailableException> { client.lookup(uuid) }
            assertEquals(OfficialJavaProfileUnavailableException.Reason.HTTP, failed.reason)
            assertEquals(status, failed.httpStatus)
            assertEquals(17L, failed.retryAfterSeconds)
            assertTrue(failed.message.orEmpty().contains("HTTP"))
            assertTrue(failed.message.orEmpty().contains(status.toString()))
            assertSanitized(failed)
        }
    }

    @Test
    fun `invalid ambiguous or unbounded retry after values are not exposed`() {
        val values = listOf(
            listOf("secret-token-body"), listOf("-1"), listOf("+1"), listOf("1.0"),
            listOf("999999999999999999999999999999999999999999"), listOf("5", "7"), emptyList(),
        )
        values.forEach { value ->
            val failed = assertFailsWith<OfficialJavaProfileUnavailableException> {
                OfficialJavaProfileClient { Http.Response(429, "secret-token-body", mapOf("Retry-After" to value)) }.lookup(uuid)
            }
            assertEquals(OfficialJavaProfileUnavailableException.Reason.HTTP, failed.reason)
            assertEquals(429, failed.httpStatus)
            assertNull(failed.retryAfterSeconds)
            assertSanitized(failed)
        }
        val ambiguous = assertFailsWith<OfficialJavaProfileUnavailableException> {
            OfficialJavaProfileClient {
                Http.Response(503, "", mapOf("Retry-After" to listOf("5"), "retry-after" to listOf("7")))
            }.lookup(uuid)
        }
        assertNull(ambiguous.retryAfterSeconds)
        assertSanitized(ambiguous)
    }

    @Test
    fun `request network failures and malformed response failures have distinct safe classifications`() {
        val privateData = "secret-token-body https://private.invalid/ $uuid"
        listOf(IOException(privateData, IOException(privateData)), IllegalStateException(privateData)).forEach { upstream ->
            upstream.addSuppressed(IOException(privateData))
            val failed = assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { throw upstream }.lookup(uuid) }
            assertEquals(OfficialJavaProfileUnavailableException.Reason.NETWORK, failed.reason)
            assertNull(failed.httpStatus)
            assertNull(failed.retryAfterSeconds)
            assertSanitized(failed)
        }
        listOf("{\"secret-token-body\":", body(name = "Invalid Name")).forEach { raw ->
            val failed = assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { response(raw) }.lookup(uuid) }
            assertEquals(OfficialJavaProfileUnavailableException.Reason.INVALID_RESPONSE, failed.reason)
            assertNull(failed.httpStatus)
            assertNull(failed.retryAfterSeconds)
            assertSanitized(failed)
        }
        val oversized = assertFailsWith<OfficialJavaProfileUnavailableException> {
            OfficialJavaProfileClient { throw Http.ResponseTooLargeException() }.lookup(uuid)
        }
        assertEquals(OfficialJavaProfileUnavailableException.Reason.INVALID_RESPONSE, oversized.reason)
        assertSanitized(oversized)
    }

    @Test
    fun `a request injected profile exception cannot smuggle its cause or suppressed data through the safe seam`() {
        val privateData = "secret-token-body https://private.invalid/ $uuid"
        val upstream = OfficialJavaProfileUnavailableException(OfficialJavaProfileUnavailableException.Reason.HTTP, 429, 7L).apply {
            initCause(IOException(privateData))
            addSuppressed(IOException(privateData))
        }
        val failed = assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { throw upstream }.lookup(uuid) }
        assertEquals(OfficialJavaProfileUnavailableException.Reason.NETWORK, failed.reason)
        assertNull(failed.httpStatus)
        assertNull(failed.retryAfterSeconds)
        assertSanitized(failed)
    }

    @Test
    fun `direct and HTTP wrapped interruption retain the worker interrupt flag without leaking causes`() {
        val privateData = "secret-token-body https://private.invalid/ $uuid"
        listOf(InterruptedException(privateData), IOException(privateData, InterruptedException(privateData))).forEach { upstream ->
            Thread.interrupted()
            try {
                val failed = assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { throw upstream }.lookup(uuid) }
                assertEquals(OfficialJavaProfileUnavailableException.Reason.INTERRUPTED, failed.reason)
                assertTrue(Thread.currentThread().isInterrupted)
                assertSanitized(failed)
            } finally { Thread.interrupted() }
        }
    }

    private fun assertSanitized(failure: OfficialJavaProfileUnavailableException) {
        val rendered = failure.stackTraceToString()
        listOf("secret-token-body", "private.invalid", uuid.toString(), uuid.toString().replace("-", "")).forEach { sensitive ->
            assertFalse(rendered.contains(sensitive))
        }
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
