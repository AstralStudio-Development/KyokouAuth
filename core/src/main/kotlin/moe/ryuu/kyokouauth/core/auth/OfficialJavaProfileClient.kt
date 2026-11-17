package moe.ryuu.kyokouauth.core.auth

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import moe.ryuu.kyokouauth.core.http.Http
import moe.ryuu.kyokouauth.core.identity.GameName
import java.io.IOException
import java.io.StringReader
import java.util.UUID

/** Opaque signed texture property; never an authentication credential. */
data class ProfileTexture(val value: String, val signature: String)

data class OfficialJavaProfile(val uuid: UUID, val name: GameName, val texture: ProfileTexture? = null)

/** Deliberately retains no upstream message, body, URL, cause or suppressed parser exception. */
class OfficialJavaProfileUnavailableException : IOException("Official Java profile is unavailable or invalid")

/** Public profile/texture lookup after role authentication. This lookup itself proves no ownership. */
class OfficialJavaProfileClient(
    private val request: (String) -> Http.Response = { url ->
        Http.get(url, readTimeoutMs = 5_000, route = Http.Route.DIRECT)
    },
) {
    fun lookup(uuid: UUID): OfficialJavaProfile {
        if (uuid.version() !in setOf(3, 4) || uuid.variant() != 2) invalid()
        return try {
            val id = uuid.toString().replace("-", "")
            decode(uuid, request("$ENDPOINT$id?unsigned=false"))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OfficialJavaProfileUnavailableException()
        } catch (_: Exception) {
            // Rebuild even our own validation exception: no parser/request data or suppressed cause
            // can escape through the public seam, and no failure is represented as an empty skin.
            throw OfficialJavaProfileUnavailableException()
        }
    }

    private fun decode(uuid: UUID, response: Http.Response): OfficialJavaProfile {
        if (response.status != 200) invalid()
        val body = response.body
        if (body.isBlank() || body.length > Http.MAX_RESPONSE_BYTES ||
            body.toByteArray(Charsets.UTF_8).size > Http.MAX_RESPONSE_BYTES || hasRawStringControl(body)) invalid()
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

    private fun hasRawStringControl(body: String): Boolean {
        var quoted = false
        var escaped = false
        for (char in body) {
            if (quoted && char.code < 0x20) return true
            if (escaped) {
                escaped = false
            } else if (quoted && char == '\\') {
                escaped = true
            } else if (char == '"') {
                quoted = !quoted
            }
        }
        return false
    }

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
