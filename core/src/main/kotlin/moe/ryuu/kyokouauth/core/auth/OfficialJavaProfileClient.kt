package moe.ryuu.kyokouauth.core.auth

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import moe.ryuu.kyokouauth.core.http.Http
import moe.ryuu.kyokouauth.core.http.hasRawJsonStringControl
import moe.ryuu.kyokouauth.core.identity.GameName
import java.io.IOException
import java.io.StringReader
import java.time.Duration
import java.util.UUID

/** Opaque signed texture property; never an authentication credential. */
data class ProfileTexture(val value: String, val signature: String)

data class OfficialJavaProfile(val uuid: UUID, val name: GameName, val texture: ProfileTexture? = null)

/** Deliberately retains no upstream message, body, URL, cause or suppressed parser exception. */
class OfficialJavaProfileUnavailableException(
    val reason: Reason = Reason.INVALID_RESPONSE,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long? = null,
) : IOException("Official Java profile unavailable: $reason" +
    (httpStatus?.let { " HTTP=$it" } ?: "") +
    (retryAfterSeconds?.let { " RetryAfterSeconds=$it" } ?: "")) {
    enum class Reason { HTTP, NETWORK, INVALID_RESPONSE, INTERRUPTED }
}

/** Public profile/texture lookup after role authentication. This lookup itself proves no ownership. */
class OfficialJavaProfileClient internal constructor(
    ticker: Ticker,
    private val request: (String) -> Http.Response,
) {
    constructor(request: (String) -> Http.Response = { url ->
        Http.get(url, readTimeoutMs = 5_000, route = Http.Route.DIRECT)
    }) : this(Ticker.systemTicker(), request)

    private val recentProfiles: Cache<UUID, OfficialJavaProfile> = Caffeine.newBuilder()
        .maximumSize(128)
        .expireAfterWrite(Duration.ofSeconds(60))
        .ticker(ticker)
        .build()

    /**
     * Reuses only successful public name/signed-texture metadata for at most 60 seconds from write.
     * Each caller must separately prove the current subject and freshly verify its account link.
     * Loading is atomic per UUID; failures are not cached and expired metadata is never a fallback.
     */
    fun lookupRecent(uuid: UUID): OfficialJavaProfile = recentProfiles.get(uuid) { lookup(it) }

    /** Always requests a fresh profile, even when [lookupRecent] has usable metadata. */
    fun lookup(uuid: UUID): OfficialJavaProfile {
        if (uuid.version() !in setOf(3, 4) || uuid.variant() != 2) invalid()
        val id = uuid.toString().replace("-", "")
        val response = try {
            request("$ENDPOINT$id?unsigned=false")
        } catch (failure: Exception) {
            val reason = when {
                failure is InterruptedException || failure.cause is InterruptedException || Thread.currentThread().isInterrupted -> {
                    Thread.currentThread().interrupt()
                    OfficialJavaProfileUnavailableException.Reason.INTERRUPTED
                }
                failure is Http.ResponseTooLargeException || failure is Http.InvalidJsonResponseException ->
                    OfficialJavaProfileUnavailableException.Reason.INVALID_RESPONSE
                else -> OfficialJavaProfileUnavailableException.Reason.NETWORK
            }
            // Even a request-injected profile exception is rebuilt without its cause/suppressed data.
            throw OfficialJavaProfileUnavailableException(reason)
        }
        return try {
            decode(uuid, response)
        } catch (failure: OfficialJavaProfileUnavailableException) {
            // Only our own decoder produces these safe classifications; do not erase HTTP details.
            throw failure
        } catch (_: Exception) {
            // Parser failures are distinct from request failures, but never retain parser data.
            invalid()
        }
    }

    private fun decode(uuid: UUID, response: Http.Response): OfficialJavaProfile {
        if (response.status != 200) throw OfficialJavaProfileUnavailableException(
            OfficialJavaProfileUnavailableException.Reason.HTTP, response.status, retryAfterSeconds(response))
        val body = response.body
        if (body.isBlank() || body.length > Http.MAX_RESPONSE_BYTES ||
            body.toByteArray(Charsets.UTF_8).size > Http.MAX_RESPONSE_BYTES || hasRawJsonStringControl(body)) invalid()
        return JsonReader(StringReader(body)).use { reader ->
            reader.isLenient = false
            if (reader.peek() != JsonToken.BEGIN_OBJECT) invalid()
            reader.beginObject()
            val fields = mutableSetOf<String>()
            var id: String? = null
            var name: GameName? = null
            var texture: ProfileTexture? = null
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (!fields.add(key)) invalid()
                when (key) {
                    "id" -> id = string(reader, 32)
                    "name" -> name = GameName.parse(string(reader, 16)) ?: invalid()
                    "properties" -> texture = properties(reader)
                    "profileActions" -> profileActions(reader)
                    else -> invalid()
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) invalid()
            val requestedId = uuid.toString().replace("-", "")
            if (id == null || !UNDASHED_UUID.matches(id) || !id.equals(requestedId, ignoreCase = true)) invalid()
            OfficialJavaProfile(uuid, name ?: invalid(), texture)
        }
    }

    private fun properties(reader: JsonReader): ProfileTexture? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) invalid()
        reader.beginArray()
        var count = 0
        var texture: ProfileTexture? = null
        while (reader.hasNext()) {
            if (++count > MAX_PROPERTIES || reader.peek() != JsonToken.BEGIN_OBJECT) invalid()
            reader.beginObject()
            val fields = mutableSetOf<String>()
            var name: String? = null
            var value: String? = null
            var signature: String? = null
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (!fields.add(key)) invalid()
                when (key) {
                    "name" -> name = string(reader, 64)
                    "value" -> value = string(reader, MAX_TEXTURE_VALUE)
                    "signature" -> signature = string(reader, MAX_TEXTURE_SIGNATURE)
                    else -> invalid()
                }
            }
            reader.endObject()
            val propertyName = name ?: invalid()
            val propertyValue = value ?: invalid()
            if (!PROPERTY_NAME.matches(propertyName) || propertyValue.isBlank()) invalid()
            if (propertyName == "textures") {
                if (texture != null) invalid()
                val propertySignature = signature?.takeUnless { it.isBlank() } ?: invalid()
                texture = ProfileTexture(propertyValue, propertySignature)
            }
        }
        reader.endArray()
        return texture
    }

    private fun profileActions(reader: JsonReader) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) invalid()
        reader.beginArray()
        var count = 0
        while (reader.hasNext()) {
            if (++count > 16) invalid()
            string(reader, 64)
        }
        reader.endArray()
    }

    private fun string(reader: JsonReader, max: Int): String {
        if (reader.peek() != JsonToken.STRING) invalid()
        return reader.nextString().also { if (it.length !in 1..max) invalid() }
    }

    /** Only an unambiguous bounded delta-seconds value is diagnostic data, never raw header text. */
    private fun retryAfterSeconds(response: Http.Response): Long? = response.headers.entries
        .singleOrNull { it.key.equals("Retry-After", ignoreCase = true) }
        ?.value?.singleOrNull()?.takeIf { it.length <= 64 }?.trim()
        ?.takeIf { it.length in 1..19 && it.all { char -> char in '0'..'9' } }
        ?.toLongOrNull()

    private fun invalid(): Nothing = throw OfficialJavaProfileUnavailableException()

    private companion object {
        const val ENDPOINT = "https://sessionserver.mojang.com/session/minecraft/profile/"
        const val MAX_PROPERTIES = 16
        const val MAX_TEXTURE_VALUE = 16_384
        const val MAX_TEXTURE_SIGNATURE = 4_096
        val UNDASHED_UUID = Regex("[0-9a-fA-F]{32}")
        val PROPERTY_NAME = Regex("[A-Za-z0-9_]{1,64}")
    }
}
