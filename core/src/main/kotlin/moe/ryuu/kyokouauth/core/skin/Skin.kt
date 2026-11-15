package moe.ryuu.kyokouauth.core.skin

import com.github.benmanes.caffeine.cache.Caffeine
import moe.ryuu.kyokouauth.core.http.Http
import java.util.concurrent.TimeUnit

/**
 * A skin, ready to be handed to the server as a profile property.
 *
 * `value` is the base64 texture blob and `signature` is Mojang's signature over it. A unsigned skin
 * is accepted by the server but some clients will not render it, so both travel together.
 */
data class Skin(val value: String, val signature: String)

/**
 * Fetches the Bedrock skin belonging to an Xbox account.
 *
 * Geyser maintains a public registry mapping a XUID to the skin that account uses on Bedrock. It is
 * the only source of a real skin for a player who does not own Java Edition, and using it is what
 * makes a Microsoft player look like themselves rather than like a default Steve.
 *
 * The lookup is best-effort by design: a player with no Bedrock history simply has no entry, and
 * that is a normal outcome rather than an error.
 */
object BedrockSkinSource {

    private const val ENDPOINT = "https://api.geysermc.org/v2/skin/"

    sealed class Result {
        class Found(val skin: Skin) : Result()

        /** This account has never played Bedrock, so no skin exists. */
        object None : Result()

        class Failed(val reason: String) : Result()
    }

    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build<Long, Result>()

    /** A missing entry is cached too: re-asking for an account that has no skin wastes a request per join. */
    fun lookup(xuid: Long): Result {
        cache.getIfPresent(xuid)?.let { return it }
        val result = fetch(xuid)
        cache.put(xuid, result)
        return result
    }

    fun invalidate(xuid: Long) {
        cache.invalidate(xuid)
    }

    private fun fetch(xuid: Long): Result {
        val response = try {
            Http.get(ENDPOINT + xuid)
        } catch (failure: Exception) {
            return Result.Failed("skin service unreachable: ${failure.message}")
        }

        if (response.status == 404) return Result.None
        if (!response.isSuccess) return Result.Failed("skin service answered HTTP ${response.status}")

        val json = response.jsonOrNull() ?: return Result.Failed("skin service returned non-JSON")
        val value = json.get("value")?.takeIf { !it.isJsonNull }?.asString ?: return Result.None
        val signature = json.get("signature")?.takeIf { !it.isJsonNull }?.asString ?: return Result.None
        if (value.isEmpty() || signature.isEmpty()) return Result.None

        return Result.Found(Skin(value, signature))
    }
}

/**
 * Fetches a premium player's own skin from Mojang.
 *
 * The response carries the `textures` property **with Mojang's signature**, which is why this is
 * preferred over synthesizing the property from a bare URL: a signed texture is verifiable by
 * clients, an unsigned one is merely rendered.
 *
 * Asking by UUID rather than by name matters - a player who renamed would otherwise get nothing.
 */
object MojangSkinSource {

    private const val ENDPOINT = "https://sessionserver.mojang.com/session/minecraft/profile/"

    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build<java.util.UUID, Skin?>()

    fun lookup(uuid: java.util.UUID): Skin? {
        // The cache is nullable-valued, so a miss and a cached "no skin" look identical through
        // getIfPresent. `get` with a loader distinguishes them, and avoids re-asking for an account
        // that simply has no skin.
        return cache.get(uuid) { fetch(it) }
    }

    fun invalidate(uuid: java.util.UUID) {
        cache.invalidate(uuid)
    }

    /**
     * Until when this source stays quiet after Mojang refused to answer.
     *
     * A rate limit is not an answer, and retrying during one only extends it. A join during the
     * window simply gets no skin, which is the same outcome as an account that has none.
     */
    @Volatile
    private var blockedUntil: Long = 0L

    private fun fetch(uuid: java.util.UUID): Skin? {
        if (System.currentTimeMillis() < blockedUntil) return null

        val response = try {
            Http.get(ENDPOINT + uuid.toString().replace("-", "") + "?unsigned=false")
        } catch (_: Exception) {
            return null
        }
        if (response.status == 429) {
            blockedUntil = System.currentTimeMillis() + 60_000L
            return null
        }
        if (!response.isSuccess) return null

        val properties = response.jsonOrNull()?.getAsJsonArray("properties") ?: return null
        val textures = properties.map { it.asJsonObject }
            .firstOrNull { it.get("name")?.asString == "textures" } ?: return null

        val value = textures.get("value")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val signature = textures.get("signature")?.takeIf { !it.isJsonNull }?.asString ?: return null

        return Skin(value, signature)
    }
}

/**
 * A skin supplied by the server operator, keyed by name.
 *
 * This is the last resort: a player who does not own Java Edition and has never played Bedrock has
 * no skin anywhere. Letting the operator map such a name to a texture URL closes that gap without
 * the plugin having to run an image service.
 */
class OperatorSkinSource(raw: Map<String, String>) {

    private val byName: Map<String, String> = raw.mapKeys { it.key.lowercase() }

    fun lookup(nameKey: String): String? = byName[nameKey]

    val isEmpty: Boolean get() = byName.isEmpty()
}
