package moe.ryuu.kyokouauth.core.auth

import com.google.gson.JsonObject
import moe.ryuu.kyokouauth.core.http.Http
import java.io.IOException

/**
 * Turns a Microsoft access token into an Xbox Live identity.
 *
 * Two hops are required by Microsoft and there is no shortcut:
 *   1. `user.auth.xboxlive.com` — MSA access token -> Xbox Live user token (carries `uhs`).
 *   2. `xsts.auth.xboxlive.com` — user token -> XSTS token for a relying party (carries `xid`, `gtg`).
 *
 * The `xid` claim is the XUID and is the only thing KyokouAuth keeps. Every token produced here is
 * dropped as soon as this object returns: nothing token-shaped is cached or written to storage.
 */
object XboxLive {

    /** Only the XBox profile is needed: we want `xid` and `gtg`, not game access. */
    const val RELYING_PARTY_PROFILE = "http://xboxlive.com"

    /** Needed only for the premium-fallback path, to reach the Minecraft services API. */
    const val RELYING_PARTY_MINECRAFT = "rp://api.minecraftservices.com/"

    private const val USER_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val XBL_TITLE_ID = "00000000402b5328"

    class XstsToken(
        val userHash: String,
        val xuid: Long,
        val gamertag: String,
        val token: String,
    ) {
        /** The `Authorization` header value both Minecraft services and XSTS protected endpoints take. */
        val authorization: String get() = "XBL3.0 x=$userHash;$token"
    }

    /**
     * @throws XboxAuthException when Microsoft rejects the account for a reason the player can act on
     *   (no Xbox profile, region block, family consent, ...), with a message already in human form.
     */
    fun exchange(msaAccessToken: String, relyingParty: String): XstsToken {
        val userToken = authenticateUser(msaAccessToken)
        return authorizeXsts(userToken.token, relyingParty)
    }

    private class UserToken(val token: String)

    private fun authenticateUser(msaAccessToken: String): UserToken {
        val payload = JsonObject().apply {
            add("Properties", JsonObject().apply {
                addProperty("AuthMethod", "RPS")
                addProperty("SiteName", "user.auth.xboxlive.com")
                addProperty("RpsTicket", "d=$msaAccessToken")
            })
            addProperty("RelyingParty", "http://auth.xboxlive.com")
            addProperty("TokenType", "JWT")
        }

        val response = Http.post(USER_AUTH_URL, payload.toString(), Http.JSON)
        if (!response.isSuccess) {
            // A 401 here means the MSA token itself is not usable, not that the account is barred.
            throw XboxAuthException("Microsoft token was rejected by Xbox Live (HTTP ${response.status}).")
        }
        return UserToken(response.json().getAsJsonPrimitive("Token").asString)
    }

    private fun authorizeXsts(userToken: String, relyingParty: String): XstsToken {
        val payload = JsonObject().apply {
            add("Properties", JsonObject().apply {
                addProperty("SandboxId", "RETAIL")
                add("UserTokens", com.google.gson.JsonArray().apply { add(userToken) })
            })
            addProperty("RelyingParty", relyingParty)
            addProperty("TokenType", "JWT")
        }

        val response = Http.post(XSTS_URL, payload.toString(), Http.JSON)

        if (response.status == 401) {
            val json = response.jsonOrNull()
            val xerr = json?.get("XErr")?.takeIf { !it.isJsonNull }?.asLong
            throw XboxAuthException(xerrMessage(xerr), xerr)
        }
        if (!response.isSuccess) {
            throw XboxAuthException("Xbox Live declined the sign-in (HTTP ${response.status}).")
        }

        val json = response.json()
        val claims = json.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
            .firstOrNull()?.asJsonObject
            ?: throw XboxAuthException("Xbox Live returned no identity claims.")

        val xuid = claims.get("xid")?.asString?.toLongOrNull()
            ?: throw XboxAuthException("Xbox Live returned no XUID.")
        val gamertag = claims.get("gtg")?.asString ?: ""
        val token = json.getAsJsonPrimitive("Token").asString
        val uhs = claims.get("uhs").asString

        return XstsToken(uhs, xuid, gamertag, token)
    }

    /**
     * Microsoft's `XErr` codes. Every one of these is something the *player* has to fix on their
     * own account, so the message has to name the fix rather than report a failure.
     */
    private fun xerrMessage(xerr: Long?): String = when (xerr) {
        2148916227L -> "This Microsoft account has been banned from Xbox Live."
        2148916229L -> "This is a child account. A parent must grant it permission to play online at account.microsoft.com/family."
        2148916233L -> "This Microsoft account has no Xbox profile yet. Sign in once at xbox.com to create one, then try again."
        2148916234L -> "This Microsoft account has not accepted the Xbox Live terms of service."
        2148916235L -> "Xbox Live is not available in this account's country or region."
        2148916236L -> "This Microsoft account needs age verification (South Korea)."
        2148916237L -> "This Microsoft account has reached its play-time limit or needs age verification (South Korea)."
        2148916238L -> "This is a child account that is not part of a Microsoft family. A parent must add it at account.microsoft.com/family."
        else -> "Xbox Live rejected this sign-in (XErr ${xerr ?: "unknown"})."
    }

    /** The title id is only used to label the sign-in; the device code flow does not need Mojang approval. */
    @Suppress("unused")
    val titleId: String get() = XBL_TITLE_ID
}

class XboxAuthException(
    override val message: String,
    val xerr: Long? = null,
) : IOException(message)
