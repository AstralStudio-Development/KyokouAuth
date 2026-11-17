package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.http.Http
import moe.ryuu.kyokouauth.core.identity.GameName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficialJavaProfileClientTest {
    private val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private fun response(body: String, status: Int = 200) = Http.Response(status, body, emptyMap())
    private fun body(properties: String = "[]", id: String = "069a79f444e94726a5befca90e38aaf5", name: String = "Notch") =
        """{"id":"$id","name":"$name","properties":$properties,"profileActions":[]}"""

    @Test
    fun `current official Java name and signed texture are returned for the exact UUID only`() {
        val urls = mutableListOf<String>()
        val client = OfficialJavaProfileClient { url ->
            urls += url
            response(body("""[{"name":"textures","value":"opaque-texture-value","signature":"opaque-signature"}]"""))
        }
        assertEquals(OfficialJavaProfile(uuid, GameName.parseOrThrow("Notch"), ProfileTexture("opaque-texture-value", "opaque-signature")), client.lookup(uuid))
        assertEquals(listOf("https://sessionserver.mojang.com/session/minecraft/profile/069a79f444e94726a5befca90e38aaf5?unsigned=false"), urls)
    }

    @Test
    fun `missing textures is an explicit default skin rather than an unknown profile`() {
        listOf(body(), """{"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch"}""", body("""[{"name":"other","value":"opaque"}]""")).forEach { raw ->
            assertEquals(OfficialJavaProfile(uuid, GameName.parseOrThrow("Notch"), null), OfficialJavaProfileClient { response(raw) }.lookup(uuid))
        }
    }

    @Test
    fun `repeated UUID lookups use current name and do not reuse stale texture or success`() {
        val replies = ArrayDeque(listOf(response(body(name = "Before")), response(body(name = "After")), response("", 503)))
        val client = OfficialJavaProfileClient { replies.removeFirst() }
        assertEquals("Before", client.lookup(uuid).name.value)
        assertEquals("After", client.lookup(uuid).name.value)
        assertFailsWith<OfficialJavaProfileUnavailableException> { client.lookup(uuid) }
        assertEquals(0, replies.size)
    }

    @Test
    fun `only a complete HTTP 200 matching UUID and valid Java name is a profile`() {
        listOf(204, 301, 302, 400, 401, 403, 404, 429, 500, 502, 503).forEach { status ->
            assertFailsWith<OfficialJavaProfileUnavailableException>("status=$status") { OfficialJavaProfileClient { response(body(), status) }.lookup(uuid) }
        }
        listOf(
            "", " ", "{}", "null", "[]", "false",
            body(id = "853c80ef3c3749fdaa49938b674adae6"), body(id = uuid.toString()), body(id = "1-2-3-4-5"),
            body(name = ".Notch"), body(name = "No"), body(name = "Invalid Name"),
            body().replace("\"name\":\"Notch\"", "\"name\":null"),
            body().replace("\"name\":\"Notch\"", "\"name\":123"),
            body().replace("\"id\":\"069a79f444e94726a5befca90e38aaf5\"", "\"id\":null"),
        ).forEachIndexed { index, raw ->
            assertFailsWith<OfficialJavaProfileUnavailableException>("invalid profile=$index") { OfficialJavaProfileClient { response(raw) }.lookup(uuid) }
        }
        listOf(UUID(0L, 0L), UUID(0L, 1001L)).forEach { invalid ->
            assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { error("invalid namespace must not request") }.lookup(invalid) }
        }
    }

    @Test
    fun `ambiguous malformed and unsigned textures are unavailable not default skins`() {
        val invalidProperties = listOf(
            "null", "{}", "[null]", "[[]]", "[{}]",
            """[{"name":"textures"}]""",
            """[{"name":"textures","value":"value"}]""",
            """[{"name":"textures","value":"value","signature":null}]""",
            """[{"name":"textures","value":null,"signature":"signed"}]""",
            """[{"name":"textures","value":"","signature":"signed"}]""",
            """[{"name":"textures","value":"value","signature":""}]""",
            """[{"name":"textures","value":123,"signature":"signed"}]""",
            """[{"name":"textures","value":"value","signature":123}]""",
            """[{"name":"textures","name":"other","value":"value","signature":"signed"}]""",
            """[{"name":"textures","value":"value","signature":"one","signature":"two"}]""",
            """[{"name":"textures","value":"one","signature":"one"},{"name":"textures","value":"two","signature":"two"}]""",
        )
        invalidProperties.forEachIndexed { index, properties ->
            assertFailsWith<OfficialJavaProfileUnavailableException>("invalid properties=$index") { OfficialJavaProfileClient { response(body(properties)) }.lookup(uuid) }
        }
    }

    @Test
    fun `texture property and whole response limits are bounded`() {
        val maxValue = "a".repeat(16_384)
        val maxSignature = "b".repeat(4_096)
        fun textures(value: String, signature: String) = """[{"name":"textures","value":"$value","signature":"$signature"}]"""
        assertEquals(ProfileTexture(maxValue, maxSignature), OfficialJavaProfileClient { response(body(textures(maxValue, maxSignature))) }.lookup(uuid).texture)
        listOf(textures(maxValue + "a", "sig"), textures("value", maxSignature + "b"), "[" + List(17) { """{"name":"other","value":"value"}""" }.joinToString(",") + "]").forEach { properties ->
            assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { response(body(properties)) }.lookup(uuid) }
        }
        assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { response(" ".repeat(Http.MAX_RESPONSE_BYTES + 1) + body()) }.lookup(uuid) }
    }

    @Test
    fun `invalid JSON and duplicate fields never become a profile`() {
        listOf(
            body() + "{}", body() + " trailing", "/*comment*/" + body(), body() + "//comment",
            body().replace("\"id\"", "id"), body().replace("\"name\":\"Notch\"", "'name':'Notch'"),
            body().replace("\"name\":\"Notch\"", "\"name\":\"Not\nch\""),
            body().replace("\"name\":\"Notch\"", "\"name\":\"Not\\qch\""),
            body().replace("\"name\":\"Notch\"", "\"name\":\"Notch\",\"name\":\"Other\""),
            body().dropLast(1) + ",}",
            body().replace("\"profileActions\":[]", "\"profileActions\":{}"),
            body().replace("\"profileActions\":[]", "\"profileActions\":[[]]"),
        ).forEachIndexed { index, raw ->
            assertFailsWith<OfficialJavaProfileUnavailableException>("invalid JSON=$index") { OfficialJavaProfileClient { response(raw) }.lookup(uuid) }
        }
    }

    @Test
    fun `request and parser failures expose no sensitive message body cause or suppressed data`() {
        val failures = listOf(IOException("secret-token-body"), Http.ResponseTooLargeException(), IllegalStateException("private-profile"))
        failures.forEach { upstream ->
            val failure = assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { throw upstream }.lookup(uuid) }
            assertFalse(failure.message.orEmpty().contains("secret"))
            assertFalse(failure.message.orEmpty().contains("private"))
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
        }
        val parserFailure = assertFailsWith<OfficialJavaProfileUnavailableException> { OfficialJavaProfileClient { response("{\"secret-token-body\":") }.lookup(uuid) }
        assertFalse(parserFailure.message.orEmpty().contains("secret"))
        assertNull(parserFailure.cause)
        assertTrue(parserFailure.suppressed.isEmpty())
    }
}
