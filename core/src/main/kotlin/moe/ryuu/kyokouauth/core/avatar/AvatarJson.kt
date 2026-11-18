package moe.ryuu.kyokouauth.core.avatar

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import moe.ryuu.kyokouauth.core.http.hasRawJsonStringControl
import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

/** Strict, bounded presentation codecs; no JSON-library types cross the core boundary. */
object AvatarJson {
    private val fingerprint = Regex("[0-9a-f]{64}")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val texturePath = Regex("/texture/[0-9a-fA-F]{64}")
    private data class Number(val text: String)

    fun skinLocation(textureValue: String): URI = sanitized {
        if (textureValue.length !in 1..16_384) invalid()
        val root = objectValue(Base64.getDecoder().decode(textureValue), 16_384)
        val textures = root["textures"] as? Map<*, *> ?: invalid()
        val skin = textures["SKIN"] as? Map<*, *> ?: invalid()
        val location = skin["url"] as? String ?: invalid()
        canonicalSkinLocation(URI(location))
    }

    fun canonicalSkinLocation(location: URI): URI = sanitized {
        if (location.scheme?.lowercase() !in setOf("http", "https") ||
            !location.rawAuthority.orEmpty().equals("textures.minecraft.net", true) ||
            location.rawUserInfo != null || location.port != -1 || location.rawQuery != null ||
            location.rawFragment != null || !texturePath.matches(location.rawPath.orEmpty())) invalid()
        URI("https://textures.minecraft.net" + location.rawPath.lowercase())
    }

    fun ready(snapshot: RoleAvatarSnapshot): ByteArray {
        val microsoft = Base64.getEncoder().encodeToString(snapshot.microsoftPng)
        val premium = Base64.getEncoder().encodeToString(snapshot.premiumPng)
        val encoded = """{"schema":1,"state":"ready","fingerprint":"${snapshot.fingerprint}","microsoft_png":"$microsoft","premium_png":"$premium","microsoft_default":${snapshot.microsoftDefault},"premium_default":${snapshot.premiumDefault}}""".toByteArray(Charsets.UTF_8)
        if (encoded.size > AvatarLimits.SNAPSHOT_WIRE_BYTES) invalid()
        return encoded
    }

    fun notReady(): ByteArray = """{"schema":1,"state":"not-ready"}""".toByteArray(Charsets.UTF_8)

    fun offered(status: Int, body: ByteArray): RoleAvatarOffer? = sanitized {
        if (status != 200 && status != 404) invalid()
        val root = objectValue(body, AvatarLimits.STATUS_WIRE_BYTES)
        if ((root["schema"] as? Number)?.text != "1") invalid()
        if (status == 404) {
            if (root.keys != setOf("schema", "state") || root["state"] != "not-found") invalid()
            null
        } else {
            if (root.keys != setOf("schema", "state", "nonce", "fingerprint", "fallback") || root["state"] != "offered") invalid()
            val rawNonce = root["nonce"] as? String ?: invalid()
            if (!uuid.matches(rawNonce)) invalid()
            val rawFingerprint = root["fingerprint"]
            if (rawFingerprint != null && (rawFingerprint !is String || !fingerprint.matches(rawFingerprint))) invalid()
            RoleAvatarOffer(UUID.fromString(rawNonce), rawFingerprint as String?, root["fallback"] as? Boolean ?: invalid())
        }
    }

    private fun objectValue(body: ByteArray, maximum: Int): Map<String, Any?> {
        if (body.size !in 1..maximum) invalid()
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
        if (hasRawJsonStringControl(text)) invalid()
        return JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            var nodes = 0
            fun value(depth: Int): Any? {
                if (++nodes > 256 || depth > 8) invalid()
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        val map = linkedMapOf<String, Any?>()
                        while (reader.hasNext()) {
                            if (map.size >= 64) invalid()
                            val key = reader.nextName()
                            if (key.length > 128 || map.containsKey(key)) invalid()
                            map[key] = value(depth + 1)
                        }
                        reader.endObject()
                        map
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray()
                        val list = mutableListOf<Any?>()
                        while (reader.hasNext()) {
                            if (list.size >= 16) invalid()
                            list += value(depth + 1)
                        }
                        reader.endArray()
                        list
                    }
                    JsonToken.STRING -> reader.nextString().also { if (it.length > 16_384) invalid() }
                    JsonToken.NUMBER -> Number(reader.nextString())
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> { reader.nextNull(); null }
                    else -> invalid()
                }
            }
            @Suppress("UNCHECKED_CAST")
            val root = value(0) as? Map<String, Any?> ?: invalid()
            if (reader.peek() != JsonToken.END_DOCUMENT) invalid()
            root
        }
    }

    private fun <T> sanitized(block: () -> T): T = try { block() } catch (_: Exception) { throw AvatarUnavailableException() }
    private fun invalid(): Nothing = throw AvatarUnavailableException()
}
