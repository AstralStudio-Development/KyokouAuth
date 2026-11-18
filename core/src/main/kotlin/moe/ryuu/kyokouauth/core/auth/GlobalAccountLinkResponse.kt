package moe.ryuu.kyokouauth.core.auth

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import moe.ryuu.kyokouauth.core.http.Http
import moe.ryuu.kyokouauth.core.http.hasRawJsonStringControl
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Xuid
import java.io.StringReader
import java.util.UUID

/**
 * Fixed official global-link response contract. The official OpenAPI at
 * https://api.geysermc.org/openapi documents HTTP 200 with an empty object as the sole unlinked
 * response for /v2/link/bedrock/{xuid}; an empty body, 204 or 404 does not establish that fact.
 * JSON-library types remain inside core, not exposed to the proxy adapter.
 */
object GlobalAccountLinkResponse {
    private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val UNSIGNED_INTEGER = Regex("0|[1-9][0-9]*")

    fun decode(xuid: Xuid, response: Http.Response): AccountLinkResult {
        if (xuid.value <= 0) return unavailable("Invalid authenticated XUID")
        if (response.status != 200) return unavailable("Global account link returned HTTP ${response.status}")
        val body = response.body
        if (body.isBlank()) return unavailable("Global account link returned an empty body")
        if (body.length > Http.MAX_RESPONSE_BYTES || body.toByteArray(Charsets.UTF_8).size > Http.MAX_RESPONSE_BYTES) {
            return unavailable("Global account link response exceeded its size limit")
        }
        return try {
            // Gson 2.10's non-lenient reader still accepts raw control characters in strings.
            // Reject those lexically before its structural and escape validation.
            if (hasRawJsonStringControl(body)) return unavailable("Invalid global account link JSON")
            JsonReader(StringReader(body)).use { reader ->
                reader.isLenient = false
                if (reader.peek() != JsonToken.BEGIN_OBJECT) return unavailable("Invalid global account link JSON")
                reader.beginObject()
                val fields = mutableSetOf<String>()
                var bedrockId: Long? = null
                var javaId: String? = null
                var javaName: String? = null
                var lastNameUpdate: Long? = null
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (!fields.add(key)) return unavailable("Duplicate global account link field")
                    when (key) {
                        "bedrock_id" -> bedrockId = integer(reader) ?: return unavailable("Invalid linked XUID")
                        "java_id" -> javaId = string(reader) ?: return unavailable("Invalid linked Java UUID")
                        "java_name" -> javaName = string(reader) ?: return unavailable("Invalid linked Java name")
                        "last_name_update" -> lastNameUpdate = integer(reader) ?: return unavailable("Invalid link verification metadata")
                        else -> return unavailable("Unexpected global account link field")
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) return unavailable("Invalid global account link JSON")
                if (fields.isEmpty()) return AccountLinkResult.NotLinked
                if (bedrockId != xuid.value || lastNameUpdate == null || lastNameUpdate < 0) {
                    return unavailable("Global account link does not match the authenticated XUID")
                }
                val rawId = javaId ?: return unavailable("Missing linked Java UUID")
                if (!UUID_TEXT.matches(rawId)) return unavailable("Invalid linked Java UUID")
                val uuid = UUID.fromString(rawId)
                if (uuid.version() !in setOf(3, 4) || uuid.variant() != 2) return unavailable("Invalid linked Java UUID namespace")
                val name = javaName?.let(GameName::parse) ?: return unavailable("Invalid linked Java name")
                AccountLinkResult.Linked(LinkedJavaProfile(uuid, name))
            }
        } catch (_: Exception) {
            // Parser exception messages and causes may contain response data. Never retain them.
            unavailable("Invalid global account link response")
        }
    }

    private fun string(reader: JsonReader): String? =
        if (reader.peek() == JsonToken.STRING) reader.nextString() else null

    private fun integer(reader: JsonReader): Long? {
        if (reader.peek() != JsonToken.NUMBER) return null
        val raw = reader.nextString()
        return if (UNSIGNED_INTEGER.matches(raw)) raw.toLongOrNull() else null
    }

    private fun unavailable(reason: String) = AccountLinkResult.Unavailable(reason)
}
