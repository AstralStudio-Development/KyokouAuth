package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.auth.AccountLinkResult
import moe.ryuu.kyokouauth.core.auth.GlobalAccountLinkResponse
import moe.ryuu.kyokouauth.core.http.Http
import moe.ryuu.kyokouauth.core.identity.Xuid

/**
 * Blocking lookup for a freshly proved connection XUID. Call on the bounded authentication IO
 * executor; this client has no positive/negative cache and never uses stored links as credentials.
 */
class RoleLinkClient(
    private val request: (String) -> Http.Response = { url -> Http.get(url, readTimeoutMs = 5_000) },
) {
    fun lookup(xuid: Xuid): AccountLinkResult {
        if (xuid.value <= 0) return AccountLinkResult.Unavailable("Invalid authenticated XUID")
        return try {
            GlobalAccountLinkResponse.decode(xuid, request("$ENDPOINT${xuid.value}"))
        } catch (_: Exception) {
            // Request failures may contain URLs or response data: neither message nor cause escapes.
            AccountLinkResult.Unavailable("Global account link request failed")
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.geysermc.org/v2/link/bedrock/"
    }
}
