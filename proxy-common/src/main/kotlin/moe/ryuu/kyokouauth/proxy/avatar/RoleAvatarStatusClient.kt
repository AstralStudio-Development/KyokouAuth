package moe.ryuu.kyokouauth.proxy.avatar

import moe.ryuu.kyokouauth.core.avatar.AvatarHttp
import moe.ryuu.kyokouauth.core.avatar.AvatarHttpResponse
import moe.ryuu.kyokouauth.core.avatar.AvatarJson
import moe.ryuu.kyokouauth.core.avatar.AvatarLimits
import moe.ryuu.kyokouauth.core.avatar.AvatarUnavailableException
import java.net.URI

/** Offered presentation only, never a render ACK or an authorization decision. */
class RoleAvatarStatusClient(
    private val endpoint: URI,
    private val request: (URI) -> AvatarHttpResponse = { AvatarHttp.get(it, AvatarLimits.STATUS_WIRE_BYTES, 400) },
) {
    init {
        require(endpoint.scheme == "http" && endpoint.host == "127.0.0.1" && endpoint.port in 1..65535 &&
            endpoint.rawAuthority == "127.0.0.1:${endpoint.port}" && endpoint.rawPath == "/v1/packs/status" &&
            endpoint.rawQuery == null && endpoint.rawFragment == null && endpoint.rawUserInfo == null) {
            "Avatar status endpoint must be an explicit literal-loopback HTTP endpoint"
        }
    }
    fun status(xuid: Long): RoleAvatarOffer? {
        if (xuid <= 0) throw AvatarUnavailableException()
        return try {
            val response = request(URI("http", null, "127.0.0.1", endpoint.port, "/v1/packs/status", "xuid=$xuid", null))
            AvatarJson.offered(response.status, response.body)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AvatarUnavailableException()
        } catch (_: Exception) { throw AvatarUnavailableException() }
    }
}
