package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.http.Http
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.*

/** Replays upstream failures without spending real Mojang requests. */
class MojangDirectorySourcesTest {
    private var now = 1_000_000L
    private fun missing(url: String): Http.Response {
        val path = java.net.URI.create(url).path
        val name = path.substringAfterLast('/')
        return Http.Response(404, """{"path":"$path","errorMessage":"Couldn't find any profile with name $name"}""", emptyMap())
    }
    private fun limited(seconds: String = "180") = Http.Response(429, "", mapOf("retry-after" to listOf(seconds)))
    private fun premium(name: String = "Notch") = Http.Response(200,
        """{"id":"069a79f444e94726a5befca90e38aaf5","name":"$name"}""", emptyMap())

    @Test fun `one throttled origin does not globally reject new names when the other official origin is healthy`() {
        val calls = mutableListOf<String>()
        val sources = MojangDirectorySources({ url ->
            calls += url
            if (url.startsWith("https://api.minecraftservices.com/")) limited() else missing(url)
        }, { now })
        val client = MojangDirectoryClient(sources) { now }
        client.configureBudget(10, 10)
        assertIs<MojangDirectory.Lookup.Free>(client.lookup("Alpha_123"))
        assertIs<MojangDirectory.Lookup.Free>(client.lookup("Other_123"))
        assertEquals(1, calls.count { it.contains("api.minecraftservices.com") })
        assertEquals(2, calls.count { it.contains("api.mojang.com") })
        assertEquals(3, client.budgetUsage(), "count actual HTTP calls, including a failed primary")
        assertIs<MojangDirectory.Lookup.Free>(client.lookup("alpha_123"))
        assertEquals(3, calls.size, "cached names cost no request")
    }

    @Test fun `both origins limited wait until the earliest genuine retry deadline not an unconditional minute`() {
        var servicesCalls = 0
        var legacyCalls = 0
        val sources = MojangDirectorySources({ url ->
            if (url.contains("api.minecraftservices.com")) {
                if (++servicesCalls == 1) limited("2") else missing(url)
            } else { legacyCalls++; limited("5") }
        }, { now })
        val client = MojangDirectoryClient(sources) { now }
        assertTrue(assertIs<MojangDirectory.Lookup.Failed>(client.lookup("FirstName")).withheld)
        now += 1_500
        assertTrue(assertIs<MojangDirectory.Lookup.Failed>(client.lookup("OtherName")).withheld)
        assertEquals(1, servicesCalls)
        assertEquals(1, legacyCalls)
        now += 501
        assertIs<MojangDirectory.Lookup.Free>(client.lookup("OtherName"))
        assertEquals(2, servicesCalls)
        assertEquals(1, legacyCalls)
    }

    @Test fun `fallback cannot hide extra requests outside the local budget`() {
        var calls = 0
        val client = MojangDirectoryClient(MojangDirectorySources({ calls++; limited() }, { now })) { now }
        client.configureBudget(1, 10)
        val first = assertIs<MojangDirectory.Lookup.Failed>(client.lookup("Alpha_123"))
        assertTrue(first.withheld)
        assertTrue(first.reason.contains("本地"))
        assertEquals(1, calls)
        val second = assertIs<MojangDirectory.Lookup.Failed>(client.lookup("Other_123"))
        assertTrue(second.withheld)
        assertEquals(1, calls)
    }

    @Test fun `outage and arbitrary 404 pages never turn a premium name into a free name`() {
        for (response in listOf(Http.Response(404, "<html>edge route unavailable</html>", emptyMap()),
            Http.Response(404, "{}", emptyMap()), Http.Response(503, "", emptyMap()),
            Http.Response(404, """{"path":"/wrong","errorMessage":"Couldn't find any profile with name Notch"}""", emptyMap()))) {
            val client = MojangDirectoryClient(MojangDirectorySources({ response }, { now })) { now }
            assertIs<MojangDirectory.Lookup.Failed>(client.lookup("Notch"))
        }
    }

    @Test fun `malformed or wrong-name profile falls back but is never trusted itself`() {
        val sources = MojangDirectorySources({ url ->
            if (url.contains("api.minecraftservices.com")) premium("NotTheRequestedName") else premium()
        }, { now })
        assertIs<MojangDirectory.Lookup.Premium>(MojangDirectoryClient(sources) { now }.lookup("notch"))
        val invalid = MojangDirectoryClient(MojangDirectorySources({ premium("OtherName") }, { now })) { now }
        assertIs<MojangDirectory.Lookup.Failed>(invalid.lookup("notch"))
    }

    @Test fun `network failure tries the second official origin once without retrying the broken route on every name`() {
        var brokenCalls = 0
        val sources = MojangDirectorySources({ url ->
            if (url.contains("api.minecraftservices.com")) { brokenCalls++; throw IOException("transport unavailable") }
            missing(url)
        }, { now })
        val client = MojangDirectoryClient(sources) { now }
        assertIs<MojangDirectory.Lookup.Free>(client.lookup("FirstName"))
        assertIs<MojangDirectory.Lookup.Free>(client.lookup("OtherName"))
        assertEquals(1, brokenCalls)
    }

    @Test fun `an interrupted lookup does not spend a second upstream request`() {
        var calls = 0
        val client = MojangDirectoryClient(MojangDirectorySources({ calls++; throw InterruptedException() }, { now })) { now }
        try {
            assertIs<MojangDirectory.Lookup.Failed>(client.lookup("Notch"))
            assertEquals(1, calls)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `an overflowing Retry-After is not silently shortened to one minute`() {
        assertEquals(Long.MAX_VALUE, MojangDirectorySources.retryDelay(limited("999999999999999999999999"), now))
    }

    @Test fun `invalid names do not reach any upstream`() {
        var calls = 0
        val client = MojangDirectoryClient(MojangDirectorySources({ calls++; premium() }, { now })) { now }
        assertIs<MojangDirectory.Lookup.Failed>(client.lookup("../Notch"))
        assertEquals(0, calls)
    }
}
