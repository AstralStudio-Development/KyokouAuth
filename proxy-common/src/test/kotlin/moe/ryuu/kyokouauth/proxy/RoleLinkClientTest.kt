package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.auth.AccountLinkResult
import moe.ryuu.kyokouauth.core.auth.LinkedJavaProfile
import moe.ryuu.kyokouauth.core.http.Http
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Xuid
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class RoleLinkClientTest {
    private val xuid = Xuid(2535432196048835L)
    private val javaId = "d34eb447-6e90-4c78-9281-600df88aef1d"
    private fun response(body: String, status: Int = 200) = Http.Response(status, body, emptyMap())
    private fun body(id: String = javaId, name: String = "Tim203", bedrock: String = "2535432196048835", updated: String = "1700000000000") =
        """{"bedrock_id":$bedrock,"java_id":"$id","java_name":"$name","last_name_update":$updated}"""

    @Test
    fun `official complete link is looked up only by authenticated XUID at fixed HTTPS global endpoint`() {
        val urls = mutableListOf<String>()
        val client = RoleLinkClient { url -> urls += url; response(body()) }
        assertEquals(AccountLinkResult.Linked(LinkedJavaProfile(UUID.fromString(javaId), GameName.parseOrThrow("Tim203"))), client.lookup(xuid))
        assertEquals(listOf("https://api.geysermc.org/v2/link/bedrock/2535432196048835"), urls)
    }

    @Test
    fun `only documented HTTP 200 empty object proves not linked`() {
        assertEquals(AccountLinkResult.NotLinked, RoleLinkClient { response("{}") }.lookup(xuid))
        assertEquals(AccountLinkResult.NotLinked, RoleLinkClient { response(" \r\n { \t } \n") }.lookup(xuid))
        listOf(204, 301, 302, 400, 401, 403, 404, 429, 500, 502, 503).forEach { status ->
            assertIs<AccountLinkResult.Unavailable>(RoleLinkClient { response("{}", status) }.lookup(xuid), "status=$status")
        }
    }

    @Test
    fun `empty partial malformed or ambiguous JSON cannot become not linked`() {
        val invalid = listOf(
            "", " ", "null", "[]", "false", "[{}]", "{", "{}{}", "{} trailing", "{} // comment", "/*comment*/{}",
            "{\"bedrock_id\":2535432196048835}",
            "{\"error\":\"not found\"}",
            "{\"java_id\":null}",
            body().replace("\"bedrock_id\"", "bedrock_id"),
            body().replace("\"java_name\":\"Tim203\"", "'java_name':'Tim203'"),
            body().replace("\"java_name\":\"Tim203\"", "\"java_name\":\"Tim\n203\""),
            body().replace("\"java_name\":\"Tim203\"", "\"java_name\":\"Tim\\q203\""),
            body().replace("\"java_name\":\"Tim203\"", "\"java_name\":\"Tim203\",\"java_name\":\"Other\""),
            body().dropLast(1) + ",}",
            body().dropLast(1) + ",\"extra\":{}}",
            body().replace(",\"last_name_update\":1700000000000", ""),
        )
        invalid.forEachIndexed { index, raw ->
            assertIs<AccountLinkResult.Unavailable>(RoleLinkClient { response(raw) }.lookup(xuid), "invalid case=$index")
        }
    }

    @Test
    fun `wrong XUID number shape UUID namespace and Java name all fail closed`() {
        val invalid = listOf(
            body(bedrock = "2535432196048836"),
            body(bedrock = "\"2535432196048835\""),
            body(bedrock = "2535432196048835.0"),
            body(bedrock = "2.535432196048835e15"),
            body(bedrock = "02535432196048835"),
            body(bedrock = "9223372036854775808"),
            body(bedrock = "-2535432196048835"),
            body(updated = "null"), body(updated = "-1"), body(updated = "\"1700000000000\""),
            body(id = "d34eb4476e904c789281600df88aef1d"),
            body(id = "not-a-uuid"), body(id = "1-2-3-4-5"),
            body(id = "00000000-0000-0000-0000-0000000003e9"),
            body(id = "d34eb447-6e90-1c78-9281-600df88aef1d"),
            body(id = "d34eb447-6e90-4c78-0281-600df88aef1d"),
            body(name = ".Tim203"), body(name = "Ti"), body(name = "Tim 203"), body(name = "SeventeenLettersXX"),
            body().replace("\"java_name\":\"Tim203\"", "\"java_name\":203"),
        )
        invalid.forEachIndexed { index, raw ->
            assertIs<AccountLinkResult.Unavailable>(RoleLinkClient { response(raw) }.lookup(xuid), "invalid profile=$index")
        }
    }

    @Test
    fun `network limits and failure text do not escape or become unlinked`() {
        listOf(IOException("secret-token-body"), Http.ResponseTooLargeException(), IllegalStateException("private-response-body")).forEach { failure ->
            val result = assertIs<AccountLinkResult.Unavailable>(RoleLinkClient { throw failure }.lookup(xuid))
            assertFalse(result.reason.contains("secret"))
            assertFalse(result.reason.contains("private"))
        }
        val oversized = " ".repeat(Http.MAX_RESPONSE_BYTES + 1) + "{}"
        assertIs<AccountLinkResult.Unavailable>(RoleLinkClient { response(oversized) }.lookup(xuid))
        assertIs<AccountLinkResult.Unavailable>(RoleLinkClient { error("invalid XUID must not perform IO") }.lookup(Xuid(0L)))
    }

    @Test
    fun `a previously linked result cannot survive unlink outage or rebind without a fresh request`() {
        val replies = ArrayDeque(listOf(response(body()), response("{}"), response("", 503), response(body(name = "ChangedJava", id = "069a79f4-44e9-4726-a5be-fca90e38aaf5"))))
        val client = RoleLinkClient { replies.removeFirst() }
        assertIs<AccountLinkResult.Linked>(client.lookup(xuid))
        assertEquals(AccountLinkResult.NotLinked, client.lookup(xuid))
        assertIs<AccountLinkResult.Unavailable>(client.lookup(xuid))
        val changed = assertIs<AccountLinkResult.Linked>(client.lookup(xuid))
        assertEquals("ChangedJava", changed.profile.name.value)
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), changed.profile.uuid)
        assertEquals(0, replies.size)
    }
}
