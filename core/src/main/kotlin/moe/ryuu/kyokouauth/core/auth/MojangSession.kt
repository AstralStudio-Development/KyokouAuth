package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.http.Http
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.util.UUID

/**
 * The server half of a Mojang login handshake.
 *
 * A premium client proves who it is by asking Mojang to vouch for it: the server sends a challenge,
 * the client encrypts the shared secret with the server's public key *and* tells Mojang "I am
 * joining this server, here is the hash", and the server then asks Mojang to confirm.
 *
 * This class is only the arithmetic and the confirmation call. It never touches a socket, which is
 * what lets the hash - the part every implementation gets subtly wrong - be tested directly.
 */
object MojangSession {

    /** Mojang's own session server. Overridable for networks that mirror the API. */
    const val DEFAULT_SESSION_SERVER = "https://sessionserver.mojang.com"

    class Profile(val uuid: UUID, val name: String, val textures: String?, val texturesSignature: String?)

    /**
     * Computes the session hash a client sends to Mojang when it joins.
     *
     * SHA-1 over `serverId` (empty in modern versions) followed by the shared secret and the DER
     * encoded public key, rendered as **signed** hexadecimal.
     *
     * The signed rendering is the classic trap: `BigInteger(digest).toString(16)` produces a leading
     * `-` for roughly half of all digests, and a client that computes it unsigned will disagree with
     * a server that does the opposite. Minecraft has always used the signed form, so this must too.
     */
    fun serverHash(serverId: String, sharedSecret: ByteArray, publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(serverId.toByteArray(Charsets.ISO_8859_1))
        digest.update(sharedSecret)
        digest.update(publicKey.encoded)
        return BigInteger(digest.digest()).toString(16)
    }

    /**
     * Asks Mojang whether [name] really completed a join with this [hash].
     *
     * A null result means "not verified" and covers every failure - wrong hash, expired session,
     * unknown player, network error. The caller must treat all of them the same way: the claim was
     * not proven.
     *
     * [baseUrl] exists so a network that mirrors Mojang's API can point at its own copy, and so the
     * success path can be exercised without a real Minecraft account. Nothing secret is sent: the
     * query carries the player name and a hash of the shared secret, never the secret itself.
     */
    fun hasJoined(name: String, hash: String, baseUrl: String = DEFAULT_SESSION_SERVER): Profile? {
        val url = "${baseUrl.trimEnd('/')}/session/minecraft/hasJoined?username=" +
            urlEncode(name) + "&serverId=" + urlEncode(hash)

        val response = try {
            Http.get(url)
        } catch (_: Exception) {
            return null
        }

        // 204 No Content is Mojang's "this session does not exist".
        if (response.status != 200) return null

        val json = response.jsonOrNull() ?: return null
        val id = json.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val resolvedName = json.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val uuid = MojangDirectory.parseUndashedUuid(id.replace("-", "")) ?: return null

        val textures = json.getAsJsonArray("properties")
            ?.map { it.asJsonObject }
            ?.firstOrNull { it.get("name")?.asString == "textures" }

        return Profile(
            uuid = uuid,
            name = resolvedName,
            textures = textures?.get("value")?.takeIf { !it.isJsonNull }?.asString,
            texturesSignature = textures?.get("signature")?.takeIf { !it.isJsonNull }?.asString,
        )
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
