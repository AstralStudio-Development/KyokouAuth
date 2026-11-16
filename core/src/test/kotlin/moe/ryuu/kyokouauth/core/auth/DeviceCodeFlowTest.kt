package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.http.Http
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.http.HttpTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceCodeFlowTest {

    @Test
    fun `a rate limited poll preserves the server retry delay for its scheduler`() {
        val challenge = DeviceCodeFlow.begin(request = { _, _, _, _ -> success() })
        val failure = assertFailsWith<DeviceCodeFlow.DeviceCodeException> {
            DeviceCodeFlow.poll(challenge) { _, _, _, _ -> response(429, mapOf("Retry-After" to listOf("180"))) }
        }
        assertEquals(DeviceCodeFlow.FailureCode.RATE_LIMITED, failure.code)
        assertEquals(180_000L, failure.retryAfterMillis)
        assertSafe(failure)
    }

    @Test
    fun `begin retries a transient IO failure on its calling IO thread`() {
        val caller = Thread.currentThread()
        var attempts = 0
        val sleeps = mutableListOf<Long>()
        val challenge = DeviceCodeFlow.begin(
            request = { _, _, _, _ ->
                assertEquals(caller, Thread.currentThread())
                if (++attempts == 1) throw IOException("sensitive upstream body")
                success()
            },
            sleep = {
                assertEquals(caller, Thread.currentThread())
                sleeps += it
            },
        )

        assertEquals(2, attempts)
        assertEquals(listOf(250L), sleeps)
        assertEquals("test-user-code", challenge.userCode)
    }

    @Test
    fun `begin retries an HTTP timeout before succeeding`() {
        var attempts = 0
        val sleeps = mutableListOf<Long>()
        DeviceCodeFlow.begin(
            request = { _, _, _, _ ->
                if (++attempts < 3) throw HttpTimeoutException("sensitive upstream body")
                success()
            },
            sleep = { sleeps += it },
        )

        assertEquals(3, attempts)
        assertTrue(sleeps.all { it in 1..1_000 })
    }

    @Test
    fun `a successful begin makes one request with a bounded timeout and never caches challenges`() {
        var attempts = 0
        val request: (String, String, String, Int) -> Http.Response = { url, body, contentType, timeout ->
            attempts++
            assertEquals("https://login.live.com/oauth20_connect.srf", url)
            assertEquals(Http.FORM, contentType)
            assertTrue(body.contains("response_type=device_code"))
            assertTrue(body.contains("client_id=${DeviceCodeFlow.CLIENT_ID}"))
            assertEquals(10_000, timeout)
            success()
        }
        val first = DeviceCodeFlow.begin(request) { error("success must not sleep") }
        val second = DeviceCodeFlow.begin(request) { error("success must not sleep") }

        assertEquals(2, attempts)
        assertNotSame(first, second)
        assertEquals("https://example.invalid/link?otc=test-user-code", first.verificationUriComplete)
    }

    @Test
    fun `persistent transport failures stop at three attempts with safe categories`() {
        for (timeout in listOf(false, true)) {
            var attempts = 0
            val sleeps = mutableListOf<Long>()
            val failure = assertFailsWith<DeviceCodeFlow.DeviceCodeException> {
                DeviceCodeFlow.begin(
                    request = { _, _, _, timeoutMillis ->
                        attempts++
                        assertEquals(10_000, timeoutMillis)
                        if (timeout) throw HttpTimeoutException(SECRET)
                        throw IOException(SECRET, IllegalArgumentException(SECRET))
                    },
                    sleep = { sleeps += it },
                )
            }

            assertEquals(3, attempts)
            assertEquals(listOf(250L, 750L), sleeps)
            assertEquals(
                if (timeout) DeviceCodeFlow.FailureCode.TIMEOUT else DeviceCodeFlow.FailureCode.TRANSPORT,
                failure.code,
            )
            assertNull(failure.httpStatus)
            assertSafe(failure)
        }
    }

    @Test
    fun `rate limiting honors a case insensitive Retry-After without polling early`() {
        var attempts = 0
        val sleeps = mutableListOf<Long>()
        DeviceCodeFlow.begin(
            request = { _, _, _, _ ->
                if (++attempts == 1) response(429, mapOf("ReTrY-aFtEr" to listOf("2"))) else success()
            },
            sleep = { sleeps += it },
        )

        assertEquals(2, attempts)
        assertEquals(listOf(2_000L), sleeps)
    }

    @Test
    fun `repeated rate limits stop before exceeding the total five second sleep budget`() {
        var attempts = 0
        val sleeps = mutableListOf<Long>()
        val failure = assertFailsWith<DeviceCodeFlow.DeviceCodeException> {
            DeviceCodeFlow.begin(
                request = { _, _, _, _ ->
                    attempts++
                    response(429, mapOf("Retry-After" to listOf("3")))
                },
                sleep = { sleeps += it },
            )
        }

        assertEquals(2, attempts)
        assertEquals(listOf(3_000L), sleeps)
        assertEquals(DeviceCodeFlow.FailureCode.RATE_LIMITED, failure.code)
        assertEquals(429, failure.httpStatus)
        assertSafe(failure)
    }

    @Test
    fun `long Retry-After values fail boundedly instead of retrying before the requested time`() {
        val farFuture = ZonedDateTime.now().plusYears(10).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        for (retryAfter in listOf("6", "86400", "99999999999999999999999999999", farFuture)) {
            var attempts = 0
            val failure = assertFailsWith<DeviceCodeFlow.DeviceCodeException> {
                DeviceCodeFlow.begin(
                    request = { _, _, _, _ ->
                        attempts++
                        response(429, mapOf("retry-after" to listOf(retryAfter)))
                    },
                    sleep = { error("must not shorten Retry-After to fit the budget") },
                )
            }
            assertEquals(1, attempts)
            assertEquals(DeviceCodeFlow.FailureCode.RATE_LIMITED, failure.code)
            assertSafe(failure)
        }
    }

    @Test
    fun `a missing or invalid rate limit delay uses a conservative short backoff`() {
        for (headers in listOf(emptyMap(), mapOf("Retry-After" to listOf("not-a-delay")))) {
            var attempts = 0
            val sleeps = mutableListOf<Long>()
            DeviceCodeFlow.begin(
                request = { _, _, _, _ -> if (++attempts == 1) response(429, headers) else success() },
                sleep = { sleeps += it },
            )
            assertEquals(2, attempts)
            assertEquals(listOf(1_000L), sleeps)
        }
    }

    @Test
    fun `temporary upstream failures can recover but permanent client errors never retry`() {
        for (status in listOf(408, 500, 502, 503, 504)) {
            var attempts = 0
            DeviceCodeFlow.begin(
                request = { _, _, _, _ -> if (++attempts == 1) response(status) else success() },
                sleep = {},
            )
            assertEquals(2, attempts)
        }
        for (status in listOf(400, 401, 403, 404)) {
            var attempts = 0
            val failure = assertFailsWith<DeviceCodeFlow.DeviceCodeException> {
                DeviceCodeFlow.begin(
                    request = { _, _, _, _ ->
                        attempts++
                        response(status)
                    },
                    sleep = { error("permanent errors must not sleep") },
                )
            }
            assertEquals(1, attempts)
            assertEquals(DeviceCodeFlow.FailureCode.REQUEST_REJECTED, failure.code)
            assertEquals(status, failure.httpStatus)
            assertSafe(failure)
        }
    }

    @Test
    fun `malformed responses never leak the body or parser cause and are not retried`() {
        for (body in listOf(SECRET, "[\"$SECRET\"]", "{\"access_token\":\"$SECRET\",")) {
            var attempts = 0
            val failure = assertFailsWith<DeviceCodeFlow.DeviceCodeException> {
                DeviceCodeFlow.begin(
                    request = { _, _, _, _ ->
                        attempts++
                        Http.Response(200, body, emptyMap())
                    },
                    sleep = { error("malformed responses must not be retried") },
                )
            }
            assertEquals(1, attempts)
            assertEquals(DeviceCodeFlow.FailureCode.MALFORMED_RESPONSE, failure.code)
            assertSafe(failure)
        }
    }

    @Test
    fun `missing or wrongly typed required fields use safe codes`() {
        val cases = listOf(
            "{}" to DeviceCodeFlow.FailureCode.MISSING_USER_CODE,
            """{"user_code":{"secret":"$SECRET"}}""" to DeviceCodeFlow.FailureCode.MISSING_USER_CODE,
            """{"user_code":"test-user-code"}""" to DeviceCodeFlow.FailureCode.MISSING_DEVICE_CODE,
            """{"user_code":"test-user-code","device_code":["$SECRET"]}""" to DeviceCodeFlow.FailureCode.MISSING_DEVICE_CODE,
        )
        for ((body, code) in cases) {
            val failure = assertFailsWith<DeviceCodeFlow.DeviceCodeException> {
                DeviceCodeFlow.begin(
                    request = { _, _, _, _ -> Http.Response(200, body, emptyMap()) },
                    sleep = { error("missing fields must not be retried") },
                )
            }
            assertEquals(code, failure.code)
            assertSafe(failure)
        }
    }

    @Test
    fun `an already interrupted caller does not request or clear its interrupt`() {
        try {
            Thread.currentThread().interrupt()
            val failure = assertFailsWith<DeviceCodeFlow.DeviceCodeException> {
                DeviceCodeFlow.begin(
                    request = { _, _, _, _ -> error("must not request after interruption") },
                    sleep = { error("must not sleep after interruption") },
                )
            }
            assertEquals(DeviceCodeFlow.FailureCode.INTERRUPTED, failure.code)
            assertTrue(Thread.currentThread().isInterrupted)
            assertSafe(failure)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `direct or HTTP wrapped interruption stops without retry and preserves the flag`() {
        val interruptions = listOf(
            InterruptedException(SECRET),
            IOException(SECRET, InterruptedException(SECRET)),
            InterruptedIOException(SECRET),
        )
        for (interruption in interruptions) {
            var attempts = 0
            try {
                val failure = assertFailsWith<DeviceCodeFlow.DeviceCodeException> {
                    DeviceCodeFlow.begin(
                        request = { _, _, _, _ ->
                            attempts++
                            throw interruption
                        },
                        sleep = { error("must not retry an interruption") },
                    )
                }
                assertEquals(1, attempts)
                assertEquals(DeviceCodeFlow.FailureCode.INTERRUPTED, failure.code)
                assertTrue(Thread.currentThread().isInterrupted)
                assertSafe(failure)
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `interruption during backoff cancels the next request`() {
        var attempts = 0
        try {
            val failure = assertFailsWith<DeviceCodeFlow.DeviceCodeException> {
                DeviceCodeFlow.begin(
                    request = { _, _, _, _ ->
                        attempts++
                        throw IOException(SECRET)
                    },
                    sleep = { throw InterruptedException(SECRET) },
                )
            }
            assertEquals(1, attempts)
            assertEquals(DeviceCodeFlow.FailureCode.INTERRUPTED, failure.code)
            assertTrue(Thread.currentThread().isInterrupted)
            assertSafe(failure)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `poll retains its single request contract and known OAuth results`() {
        val challenge = DeviceCodeFlow.begin({ _, _, _, _ -> success() })
        val cases = listOf(
            "authorization_pending" to DeviceCodeFlow.PollResult.Pending,
            "slow_down" to DeviceCodeFlow.PollResult.SlowDown,
            "expired_token" to DeviceCodeFlow.PollResult.Expired,
            "code_expired" to DeviceCodeFlow.PollResult.Expired,
            "authorization_declined" to DeviceCodeFlow.PollResult.Declined,
            "access_denied" to DeviceCodeFlow.PollResult.Declined,
        )
        for ((error, result) in cases) {
            var requests = 0
            assertEquals(
                result,
                DeviceCodeFlow.poll(challenge) { url, body, contentType, timeout ->
                    requests++
                    assertEquals("https://login.live.com/oauth20_token.srf", url)
                    assertTrue(body.contains("device_code=test-device-code"))
                    assertEquals(Http.FORM, contentType)
                    assertEquals(20_000, timeout)
                    Http.Response(400, """{"error":"$error","error_description":"$SECRET"}""", emptyMap())
                },
            )
            assertEquals(1, requests)
        }
        val authorized = DeviceCodeFlow.poll(challenge) { _, _, _, _ ->
            Http.Response(200, """{"access_token":"$SECRET","refresh_token":"$SECRET","expires_in":3600}""", emptyMap())
        } as DeviceCodeFlow.PollResult.Authorized
        assertEquals(SECRET, authorized.accessToken)
        assertEquals(SECRET, authorized.refreshToken)
        assertEquals(3600, authorized.expiresInSeconds)
    }

    @Test
    fun `poll errors exclude OAuth descriptions codes tokens and response bodies`() {
        val challenge = DeviceCodeFlow.begin({ _, _, _, _ -> success() })
        val cases = listOf(
            Http.Response(400, """{"error":"$SECRET","error_description":"test-user-code test-device-code $SECRET"}""", emptyMap()) to DeviceCodeFlow.FailureCode.OAUTH_REJECTED,
            Http.Response(200, """{"refresh_token":"$SECRET"}""", emptyMap()) to DeviceCodeFlow.FailureCode.MISSING_ACCESS_TOKEN,
            Http.Response(200, "[$SECRET]", emptyMap()) to DeviceCodeFlow.FailureCode.MALFORMED_RESPONSE,
            response(429) to DeviceCodeFlow.FailureCode.RATE_LIMITED,
        )
        for ((response, code) in cases) {
            var requests = 0
            val failure = assertFailsWith<DeviceCodeFlow.DeviceCodeException> {
                DeviceCodeFlow.poll(challenge) { _, _, _, _ ->
                    requests++
                    response
                }
            }
            assertEquals(1, requests)
            assertEquals(code, failure.code)
            assertSafe(failure)
        }
        var requests = 0
        val failure = assertFailsWith<DeviceCodeFlow.DeviceCodeException> {
            DeviceCodeFlow.poll(challenge) { _, _, _, _ ->
                requests++
                throw IOException(SECRET)
            }
        }
        assertEquals(1, requests)
        assertEquals(DeviceCodeFlow.FailureCode.TRANSPORT, failure.code)
        assertSafe(failure)
    }

    private fun assertSafe(failure: DeviceCodeFlow.DeviceCodeException) {
        val output = failure.stackTraceToString()
        for (secret in listOf(SECRET, "test-user-code", "test-device-code", "error_description", "access_token")) {
            assertFalse(output.contains(secret), "failure must not expose response data")
        }
        assertTrue(failure.message!!.contains(failure.code.name))
        assertNull(failure.cause)
    }

    private fun response(status: Int, headers: Map<String, List<String>> = emptyMap()) =
        Http.Response(status, """{"error":"$SECRET","error_description":"$SECRET"}""", headers)

    private companion object {
        const val SECRET = "synthetic-sensitive-token-value"
    }

    private fun success() = Http.Response(
        200,
        """{"user_code":"test-user-code","device_code":"test-device-code","verification_uri":"https://example.invalid/link","expires_in":900,"interval":5}""",
        emptyMap(),
    )
}
