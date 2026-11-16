package moe.ryuu.kyokouauth.core.auth

import com.google.gson.JsonObject
import moe.ryuu.kyokouauth.core.http.Http
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/**
 * The Microsoft OAuth device-code flow.
 *
 * A device code, not an authorisation-code-with-redirect, because the server has no browser: the
 * player is the browser. The server prints a short code, the player types it into Microsoft's site
 * (or, more usually, clicks a link with the code already filled in) and the server learns the result
 * by polling.
 *
 * ## The client id
 *
 * v1 uses Microsoft's own first-party Java launcher id. It is chosen deliberately over a
 * self-registered Azure application because a self-registered app cannot reach the Minecraft
 * services API without Mojang approval, which would make the premium-fallback path impossible, and
 * because a first-party id has no per-server registration burden. The trade-off is that this is not
 * a sanctioned use of the id; it is the same trade-off every third-party launcher-adjacent tool
 * makes. Nothing here reads a Minecraft token from a self-registered app.
 */
object DeviceCodeFlow {

    /** Microsoft's first-party Minecraft (Java) launcher client id. */
    const val CLIENT_ID = "00000000402b5328"

    private const val SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"

    /** The MSA endpoint family that understands the Xbox Live scope for consumer accounts. */
    private const val DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf"
    private const val TOKEN_URL = "https://login.live.com/oauth20_token.srf"

    /**
     * What the player is shown. [verificationUriComplete] already contains the code, so the link can
     * be clicked from chat instead of retyped.
     */
    class Challenge(
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresInSeconds: Int,
        val intervalSeconds: Int,
        internal val deviceCode: String,
    )

    sealed class PollResult {
        /** The player has not finished yet. Keep polling. */
        object Pending : PollResult()

        /** Microsoft asks the client to slow down; the next poll must wait the new interval. */
        object SlowDown : PollResult()

        /** The code expired before the player used it. */
        object Expired : PollResult()

        /** The player explicitly declined on Microsoft's site. */
        object Declined : PollResult()

        class Authorized(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Int) : PollResult()
    }

    /** Fixed, log-safe categories: never an upstream error string, body, code or token. */
    enum class FailureCode {
        TRANSPORT,
        TIMEOUT,
        INTERRUPTED,
        RATE_LIMITED,
        UPSTREAM_UNAVAILABLE,
        REQUEST_REJECTED,
        MALFORMED_RESPONSE,
        MISSING_USER_CODE,
        MISSING_DEVICE_CODE,
        MISSING_ACCESS_TOKEN,
        OAUTH_REJECTED,
        EXPIRED,
        DECLINED,
    }

    /** Raw causes are deliberately not retained: even parser/transport messages may contain secrets. */
    class DeviceCodeException(val code: FailureCode, val httpStatus: Int? = null, val retryAfterMillis: Long? = null) : IOException(
        "Microsoft device code: ${code.name}" + (httpStatus?.let { " (HTTP $it)" } ?: ""),
    )

    private const val BEGIN_MAX_ATTEMPTS = 3
    private const val BEGIN_REQUEST_TIMEOUT_MILLIS = 10_000
    private const val BEGIN_MAX_SLEEP_MILLIS = 5_000L

    /**
     * Starts a fresh verification on the caller's IO thread. Success performs exactly one request;
     * transient failures allow at most three 10-second requests and five seconds of total backoff.
     * No challenge is shared or reused between calls. Long Retry-After values fail without retrying
     * early, so the begin operation cannot hold the caller for an unbounded server-supplied delay.
     */
    fun begin(): Challenge = begin(
        request = { url, body, contentType, timeout ->
            Http.post(url, body, contentType, readTimeoutMs = timeout)
        },
    )

    internal fun begin(
        request: (String, String, String, Int) -> Http.Response,
        sleep: (Long) -> Unit = { TimeUnit.MILLISECONDS.sleep(it) },
    ): Challenge {
        val body = Http.encodeForm(
            mapOf(
                "client_id" to CLIENT_ID,
                "scope" to SCOPE,
                "response_type" to "device_code",
            ),
        )
        var sleptMillis = 0L
        for (attempt in 1..BEGIN_MAX_ATTEMPTS) {
            val response = try {
                requestSafely(request, DEVICE_CODE_URL, body, BEGIN_REQUEST_TIMEOUT_MILLIS)
            } catch (failure: DeviceCodeException) {
                if (failure.code !in setOf(FailureCode.TRANSPORT, FailureCode.TIMEOUT) ||
                    attempt == BEGIN_MAX_ATTEMPTS
                ) {
                    throw failure
                }
                val delay = retryBackoffMillis(attempt)
                if (delay > BEGIN_MAX_SLEEP_MILLIS - sleptMillis) throw failure
                sleepSafely(delay, sleep)
                sleptMillis += delay
                continue
            }

            if (response.isSuccess) return challengeFrom(response)
            val failure = DeviceCodeException(httpFailureCode(response.status), response.status)
            if (!isRetryableStatus(response.status) || attempt == BEGIN_MAX_ATTEMPTS) throw failure
            val delay = maxOf(
                retryBackoffMillis(attempt),
                retryAfterMillis(response) ?: if (response.status == 429) 1_000L else 0L,
            )
            if (delay > BEGIN_MAX_SLEEP_MILLIS - sleptMillis) throw failure
            sleepSafely(delay, sleep)
            sleptMillis += delay
        }
        error("unreachable device-code attempt")
    }

    private fun challengeFrom(response: Http.Response): Challenge {
        val json = response.jsonOrNull()
            ?: throw DeviceCodeException(FailureCode.MALFORMED_RESPONSE, response.status)
        val userCode = json.stringOrNull("user_code")
            ?: throw DeviceCodeException(FailureCode.MISSING_USER_CODE, response.status)
        val deviceCode = json.stringOrNull("device_code")
            ?: throw DeviceCodeException(FailureCode.MISSING_DEVICE_CODE, response.status)
        val verificationUri = json.stringOrNull("verification_uri") ?: "http://microsoft.com/link"

        return Challenge(
            userCode = userCode,
            verificationUri = verificationUri,
            verificationUriComplete = "$verificationUri?otc=$userCode",
            expiresInSeconds = json.intOrNull("expires_in") ?: 900,
            intervalSeconds = json.intOrNull("interval") ?: 5,
            deviceCode = deviceCode,
        )
    }

    /**
     * One poll. Callers own the loop and the interval: this method performs exactly one request and
     * reports what Microsoft said, so a caller that is also driving a Bukkit scheduler stays in
     * control of when the next tick happens.
     */
    fun poll(challenge: Challenge): PollResult = poll(challenge) { url, body, contentType, timeout ->
        Http.post(url, body, contentType, readTimeoutMs = timeout)
    }

    internal fun poll(
        challenge: Challenge,
        request: (String, String, String, Int) -> Http.Response,
    ): PollResult {
        val response = requestSafely(
            request,
            TOKEN_URL,
            Http.encodeForm(
                mapOf(
                    "client_id" to CLIENT_ID,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code" to challenge.deviceCode,
                ),
            ),
            20_000,
        )

        if (isRetryableStatus(response.status)) {
            throw DeviceCodeException(httpFailureCode(response.status), response.status, retryAfterMillis(response))
        }
        val json = response.jsonOrNull()
            ?: throw DeviceCodeException(FailureCode.MALFORMED_RESPONSE, response.status)

        if (response.isSuccess) {
            val token = json.stringOrNull("access_token")
                ?: throw DeviceCodeException(FailureCode.MISSING_ACCESS_TOKEN, response.status)
            return PollResult.Authorized(
                accessToken = token,
                refreshToken = json.stringOrNull("refresh_token"),
                expiresInSeconds = json.intOrNull("expires_in") ?: 3600,
            )
        }

        return when (json.stringOrNull("error")) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "expired_token", "code_expired" -> PollResult.Expired
            "authorization_declined", "access_denied" -> PollResult.Declined
            else -> throw DeviceCodeException(FailureCode.OAUTH_REJECTED, response.status)
        }
    }

    /**
     * Drives a whole flow to completion, blocking up to the code's lifetime.
     *
     * Intended for the IO executor only. The server-side login path uses [begin]/[poll] directly so
     * that it can interleave with the Bukkit scheduler; this exists for tooling and tests.
     */
    fun awaitAuthorized(challenge: Challenge, sleep: (Long) -> Unit = { TimeUnit.MILLISECONDS.sleep(it) }): String {
        var interval = challenge.intervalSeconds.toLong()
        val deadline = System.currentTimeMillis() + challenge.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            sleep(interval * 1000)
            when (val result = poll(challenge)) {
                is PollResult.Authorized -> return result.accessToken
                PollResult.Pending -> Unit
                PollResult.SlowDown -> interval += 5
                PollResult.Expired -> throw DeviceCodeException(FailureCode.EXPIRED)
                PollResult.Declined -> throw DeviceCodeException(FailureCode.DECLINED)
            }
        }
        throw DeviceCodeException(FailureCode.EXPIRED)
    }

    private fun requestSafely(
        request: (String, String, String, Int) -> Http.Response,
        url: String,
        body: String,
        timeoutMillis: Int,
    ): Http.Response {
        ensureNotInterrupted()
        val response = try {
            request(url, body, Http.FORM, timeoutMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw DeviceCodeException(FailureCode.INTERRUPTED)
        } catch (failure: IOException) {
            if (Thread.currentThread().isInterrupted || failure.cause is InterruptedException ||
                (failure is InterruptedIOException && failure !is SocketTimeoutException)
            ) {
                Thread.currentThread().interrupt()
                throw DeviceCodeException(FailureCode.INTERRUPTED)
            }
            val code = if (failure is HttpTimeoutException || failure is SocketTimeoutException) {
                FailureCode.TIMEOUT
            } else {
                FailureCode.TRANSPORT
            }
            throw DeviceCodeException(code)
        }
        ensureNotInterrupted()
        return response
    }

    private fun ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw DeviceCodeException(FailureCode.INTERRUPTED)
    }

    private fun sleepSafely(delayMillis: Long, sleep: (Long) -> Unit) {
        ensureNotInterrupted()
        try {
            sleep(delayMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw DeviceCodeException(FailureCode.INTERRUPTED)
        }
        ensureNotInterrupted()
    }

    private fun retryBackoffMillis(attempt: Int): Long = if (attempt == 1) 250L else 750L

    private fun isRetryableStatus(status: Int): Boolean = status in setOf(408, 429, 500, 502, 503, 504)

    private fun httpFailureCode(status: Int): FailureCode = when (status) {
        429 -> FailureCode.RATE_LIMITED
        408, in 500..599 -> FailureCode.UPSTREAM_UNAVAILABLE
        else -> FailureCode.REQUEST_REJECTED
    }

    /** Return the actual server delay; begin refuses waits over its budget, while polls can schedule them. */
    private fun retryAfterMillis(response: Http.Response): Long? {
        val value = response.headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.firstOrNull()?.trim() ?: return null
        value.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return null
            return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
        }
        if (value.isNotEmpty() && value.all { it in '0'..'9' }) return Long.MAX_VALUE
        val instant = try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            return null
        }
        val duration = Duration.between(Instant.now(), instant)
        return when {
            duration.isNegative -> 0L
            duration.seconds > Long.MAX_VALUE / 1000 -> Long.MAX_VALUE
            else -> duration.toMillis()
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()
}
