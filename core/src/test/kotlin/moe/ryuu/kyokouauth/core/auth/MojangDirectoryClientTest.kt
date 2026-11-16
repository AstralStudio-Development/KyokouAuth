package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.http.Http
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MojangDirectoryClientTest {
    private val premium = Http.Response(200, """{"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch"}""", emptyMap())

    @Test fun `parallel cold lookups for one name spend exactly one upstream request`() {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = MojangDirectoryClient(request = {
            calls.incrementAndGet(); started.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); premium
        })
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = List(8) { pool.submit<MojangDirectory.Lookup> { client.lookup("notch") } }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            release.countDown()
            futures.forEach { assertIs<MojangDirectory.Lookup.Premium>(it.get(3, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `cached premium answers remain usable when the unknown-name budget is empty`() {
        var calls = 0
        val client = MojangDirectoryClient(request = { calls++; premium })
        client.configureBudget(1, 10)
        assertIs<MojangDirectory.Lookup.Premium>(client.lookup("notch"))
        repeat(20) { assertIs<MojangDirectory.Lookup.Premium>(client.lookup("Notch")) }
        assertEquals(1, calls)
        assertTrue(assertIs<MojangDirectory.Lookup.Failed>(client.lookup("unexamined")).withheld)
        assertEquals(1, calls)
    }

    @Test fun `429 respects Retry-After instead of issuing one request per reconnect`() {
        var now = 1_000_000L
        var calls = 0
        val client = MojangDirectoryClient(request = {
            calls++
            Http.Response(429, "", mapOf("Retry-After" to listOf("180")))
        }, clock = { now })
        assertTrue(assertIs<MojangDirectory.Lookup.Failed>(client.lookup("first")).withheld)
        now += 61_000
        repeat(20) { assertTrue(assertIs<MojangDirectory.Lookup.Failed>(client.lookup("other$it")).withheld) }
        assertEquals(1, calls)
        now += 120_000
        client.lookup("again")
        assertEquals(2, calls)
    }

    @Test fun `a transient failure is briefly coalesced but never cached as a free name`() {
        var now = 1_000_000L
        var calls = 0
        val client = MojangDirectoryClient(request = { calls++; if (calls == 1) error("private upstream detail") else premium }, clock = { now })
        repeat(10) { assertIs<MojangDirectory.Lookup.Failed>(client.lookup("notch")) }
        assertEquals(1, calls)
        now += 5_001
        assertIs<MojangDirectory.Lookup.Premium>(client.lookup("notch"))
        assertEquals(2, calls)
    }
}
