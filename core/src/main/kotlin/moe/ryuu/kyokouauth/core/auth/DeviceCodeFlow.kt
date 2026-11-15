package moe.ryuu.kyokouauth.core.auth

import com.google.gson.JsonObject
import moe.ryuu.kyokouauth.core.http.Http
import java.io.IOException
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

    class DeviceCodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Starts a verification. The caller shows the code to the player and then polls [poll].
     */
    fun begin(): Challenge {
        val response = Http.post(
            DEVICE_CODE_URL,
            Http.encodeForm(
                mapOf(
                    "client_id" to CLIENT_ID,
                    "scope" to SCOPE,
                    "response_type" to "device_code",
                ),
            ),
            Http.FORM,
        )

        if (!response.isSuccess) {
            throw DeviceCodeException("Microsoft refused to start a sign-in (HTTP ${response.status}).")
        }

        val json = response.json()
        val userCode = json.stringOrNull("user_code")
            ?: throw DeviceCodeException("Microsoft returned no user code.")
        val deviceCode = json.stringOrNull("device_code")
            ?: throw DeviceCodeException("Microsoft returned no device code.")
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
    fun poll(challenge: Challenge): PollResult {
        val response = Http.post(
            TOKEN_URL,
            Http.encodeForm(
                mapOf(
                    "client_id" to CLIENT_ID,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code" to challenge.deviceCode,
                ),
            ),
            Http.FORM,
        )

        val json = response.jsonOrNull() ?: throw DeviceCodeException("Malformed response from Microsoft.")

        if (response.isSuccess) {
            val token = json.stringOrNull("access_token")
                ?: throw DeviceCodeException("Microsoft returned no access token.")
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
            else -> {
                val description = json.stringOrNull("error_description") ?: ""
                throw DeviceCodeException(
                    "Microsoft rejected the sign-in: ${json.stringOrNull("error") ?: "unknown"} $description".trim(),
                )
            }
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
                PollResult.Expired -> throw DeviceCodeException("The sign-in code expired before it was used.")
                PollResult.Declined -> throw DeviceCodeException("The player declined the sign-in.")
            }
        }
        throw DeviceCodeException("The sign-in code expired before it was used.")
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { !it.isJsonNull }?.asInt
}
