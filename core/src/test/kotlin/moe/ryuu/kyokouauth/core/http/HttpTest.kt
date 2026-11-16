package moe.ryuu.kyokouauth.core.http

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Isolated("Temporarily replaces the JVM default ProxySelector")
class HttpTest {
    @Test fun `inherit uses the JVM proxy while direct never consults it`() {
        LocalEndpoint("origin").use { origin ->
            LocalEndpoint("proxy").use { proxy ->
                val selector = RecordingProxySelector(proxy.address)
                withDefaultProxy(selector) {
                    val client = Http.Client()
                    assertEquals("proxy", client.get(origin.url, readTimeoutMs = 2_000).body)
                    assertEquals(1, proxy.requests.size)
                    assertEquals(0, origin.requests.size)
                    val inheritedSelections = selector.selections.get()
                    assertEquals(1, inheritedSelections)

                    assertEquals("origin", client.get(origin.url, readTimeoutMs = 2_000, route = Http.Route.DIRECT).body)
                    assertEquals(inheritedSelections, selector.selections.get())
                    assertEquals(1, proxy.requests.size)
                    assertEquals(1, origin.requests.size)
                }
            }
        }
    }

    @Test fun `post still inherits the proxy after a direct get and preserves its payload`() {
        LocalEndpoint("origin").use { origin ->
            LocalEndpoint("proxy").use { proxy ->
                val selector = RecordingProxySelector(proxy.address)
                withDefaultProxy(selector) {
                    val client = Http.Client()
                    assertEquals("origin", client.get(origin.url, route = Http.Route.DIRECT).body)
                    val response = client.post(origin.url, "name=Notch", Http.FORM, mapOf("X-Test" to "post"), 2_000)
                    assertEquals("proxy", response.body)
                    assertEquals(1, selector.selections.get())
                    assertEquals(1, origin.requests.size)
                    val request = proxy.requests.single()
                    assertEquals("POST", request.method)
                    assertEquals(URI.create(origin.url), request.uri)
                    assertEquals("name=Notch", request.body)
                    assertEquals(Http.FORM, request.headers["Content-type"]?.single())
                    assertEquals("post", request.headers["X-test"]?.single())
                }
            }
        }
    }

    @Test fun `one failing global proxy selection does not affect direct or subsequent inherited requests`() {
        LocalEndpoint("origin").use { origin ->
            LocalEndpoint("proxy").use { proxy ->
                val selector = RecordingProxySelector(proxy.address, failFirst = true)
                withDefaultProxy(selector) {
                    val client = Http.Client()
                    assertFails { client.get(origin.url, readTimeoutMs = 2_000) }
                    assertEquals(1, selector.selections.get())
                    assertEquals("origin", client.get(origin.url, readTimeoutMs = 2_000, route = Http.Route.DIRECT).body)
                    assertEquals(1, selector.selections.get())
                    assertEquals("proxy", client.get(origin.url, readTimeoutMs = 2_000).body)
                    assertEquals(2, selector.selections.get())
                    assertEquals(1, origin.requests.size)
                    assertEquals(1, proxy.requests.size)
                }
            }
        }
    }

    @Test fun `public direct get never initializes a broken inherited proxy route`() {
        LocalEndpoint("origin").use { origin ->
            val selector = RecordingProxySelector(origin.address, failFirst = true)
            withDefaultProxy(selector) {
                val response = Http.get(origin.url, mapOf("X-Test" to "direct"), 2_000, Http.Route.DIRECT)
                assertEquals("origin", response.body)
                assertEquals(0, selector.selections.get())
                assertEquals("direct", origin.requests.single().headers["X-test"]?.single())
            }
        }
    }

    @Test fun `both routes reuse their own connection pool and inherited selector snapshot`() {
        LocalEndpoint("origin").use { origin ->
            LocalEndpoint("proxy").use { proxy ->
                val selector = RecordingProxySelector(proxy.address)
                withDefaultProxy(selector) {
                    val client = Http.Client()
                    assertEquals("proxy", client.get(origin.url, readTimeoutMs = 2_000).body)
                    val replacement = RecordingProxySelector(origin.address, failFirst = true)
                    withDefaultProxy(replacement) {
                        assertEquals("proxy", client.get(origin.url, readTimeoutMs = 2_000).body)
                        repeat(2) {
                            assertEquals("origin", client.get(origin.url, readTimeoutMs = 2_000, route = Http.Route.DIRECT).body)
                        }
                    }
                    assertEquals(2, selector.selections.get())
                    assertEquals(0, replacement.selections.get())
                    assertEquals(2, origin.requests.size)
                    assertEquals(2, proxy.requests.size)
                    assertEquals(1, origin.requests.map { it.remoteAddress }.toSet().size)
                    assertEquals(1, proxy.requests.map { it.remoteAddress }.toSet().size)
                }
            }
        }
    }

    @Test fun `neither route follows redirects`() {
        LocalEndpoint("must not be fetched").use { destination ->
            LocalEndpoint("redirect", status = 302, responseHeaders = mapOf("Location" to destination.url)).use { redirect ->
                withDefaultProxy(RecordingProxySelector(redirect.address)) {
                    val client = Http.Client()
                    for (route in Http.Route.entries) {
                        val response = client.get(redirect.url, readTimeoutMs = 2_000, route = route)
                        assertEquals(302, response.status)
                        assertEquals("redirect", response.body)
                        assertEquals(listOf(destination.url), response.headers["location"])
                    }
                    assertEquals(2, redirect.requests.size)
                    assertEquals(0, destination.requests.size)
                }
            }
        }
    }

    @Test fun `both routes bound a stalled response by the request timeout`() {
        for (route in Http.Route.entries) {
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            LocalEndpoint("late", beforeResponse = {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
            }).use { endpoint ->
                withDefaultProxy(RecordingProxySelector(endpoint.address)) {
                    val pool = Executors.newSingleThreadExecutor()
                    try {
                        val client = Http.Client()
                        val request = pool.submit<Http.Response> {
                            client.get(endpoint.url, readTimeoutMs = 1_000, route = route)
                        }
                        assertTrue(arrived.await(2, TimeUnit.SECONDS))
                        val failure = assertFailsWith<ExecutionException> { request.get(3, TimeUnit.SECONDS) }
                        assertIs<HttpTimeoutException>(failure.cause)
                    } finally {
                        release.countDown()
                        pool.shutdownNow()
                        assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
                    }
                }
            }
        }
    }

    @Test fun `both routes preserve interrupts without exposing the request URL`() {
        LocalEndpoint("origin").use { origin ->
            withDefaultProxy(RecordingProxySelector(origin.address)) {
                val client = Http.Client()
                for (route in Http.Route.entries) {
                    client.get(origin.url, readTimeoutMs = 2_000, route = route)
                    val previouslyInterrupted = Thread.currentThread().isInterrupted
                    try {
                        Thread.currentThread().interrupt()
                        val failure = assertFailsWith<IOException> {
                            client.get("${origin.url}?access_token=private-test-token", readTimeoutMs = 2_000, route = route)
                        }
                        assertTrue(Thread.currentThread().isInterrupted)
                        assertIs<InterruptedException>(failure.cause)
                        assertEquals("HTTP request was interrupted", failure.message)
                    } finally {
                        Thread.interrupted()
                        if (previouslyInterrupted) Thread.currentThread().interrupt()
                    }
                }
            }
        }
    }

    private fun <T> withDefaultProxy(selector: ProxySelector, block: () -> T): T {
        val previous = ProxySelector.getDefault()
        try {
            ProxySelector.setDefault(selector)
            return block()
        } finally {
            ProxySelector.setDefault(previous)
        }
    }

    private class RecordingProxySelector(address: InetSocketAddress, failFirst: Boolean = false) : ProxySelector() {
        val selections = AtomicInteger()
        private val proxy = Proxy(Proxy.Type.HTTP, address)
        private val failNext = AtomicBoolean(failFirst)

        override fun select(uri: URI): List<Proxy> {
            selections.incrementAndGet()
            check(!failNext.getAndSet(false)) { "test proxy selection unavailable" }
            return listOf(proxy)
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }

    private data class RecordedRequest(
        val method: String,
        val uri: URI,
        val body: String,
        val headers: Map<String, List<String>>,
        val remoteAddress: SocketAddress,
    )

    private class LocalEndpoint(
        response: String,
        status: Int = 200,
        responseHeaders: Map<String, String> = emptyMap(),
        beforeResponse: () -> Unit = {},
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<RecordedRequest>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    requests.add(RecordedRequest(
                        it.requestMethod,
                        it.requestURI,
                        it.requestBody.readBytes().decodeToString(),
                        it.requestHeaders.toMap(),
                        it.remoteAddress,
                    ))
                    beforeResponse()
                    responseHeaders.forEach { (name, value) -> it.responseHeaders.add(name, value) }
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    it.sendResponseHeaders(status, bytes.size.toLong())
                    it.responseBody.write(bytes)
                }
            }
            start()
        }
        val address: InetSocketAddress get() = server.address
        val url: String get() = "http://127.0.0.1:${address.port}/lookup"

        override fun close() = server.stop(0)
    }
}
