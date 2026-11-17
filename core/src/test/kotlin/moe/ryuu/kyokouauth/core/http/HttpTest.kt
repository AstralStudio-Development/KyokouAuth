package moe.ryuu.kyokouauth.core.http

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

    @Test fun `JSON failures never expose response tokens or codes`() {
        val token = "private-test-token"
        val code = "private-test-code"
        for (body in listOf("[\"$token\",\"$code\"]", "{\"$token\":{\"$code\":")) {
            val response = Http.Response(200, body, emptyMap())
            val failure = assertFailsWith<Http.InvalidJsonResponseException> { response.json() }
            assertEquals(null, response.jsonOrNull())
            assertTrue(token !in failure.stackTraceToString())
            assertTrue(code !in failure.stackTraceToString())
        }
    }

    @Test fun `both routes reject oversized declared and chunked response bodies`() {
        for (route in Http.Route.entries) {
            for (chunked in listOf(false, true)) {
                LocalEndpoint("x".repeat(Http.MAX_RESPONSE_BYTES + 1), chunked = chunked).use { endpoint ->
                    withDefaultProxy(RecordingProxySelector(endpoint.address)) {
                        assertFailsWith<Http.ResponseTooLargeException> {
                            Http.Client().get(endpoint.url, readTimeoutMs = 3_000, route = route)
                        }
                    }
                }
            }
        }
    }

    @Test fun `both routes time out while a response body is stalled after headers`() {
        for (route in Http.Route.entries) {
            val bodyStarted = CountDownLatch(1)
            val release = CountDownLatch(1)
            LocalEndpoint("body", beforeBodyRemainder = {
                bodyStarted.countDown()
                release.await(5, TimeUnit.SECONDS)
            }).use { endpoint ->
                withDefaultProxy(RecordingProxySelector(endpoint.address)) {
                    val pool = Executors.newSingleThreadExecutor()
                    try {
                        val request = pool.submit<Http.Response> {
                            Http.Client().get(endpoint.url, readTimeoutMs = 1_000, route = route)
                        }
                        assertTrue(bodyStarted.await(2, TimeUnit.SECONDS))
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

    @Test fun `one MiB of UTF-8 bytes is accepted for declared and chunked bodies`() {
        val body = "é".repeat(1024 * 512)
        for (chunked in listOf(false, true)) {
            LocalEndpoint(body, chunked = chunked).use { endpoint ->
                assertEquals(body, Http.Client().get(endpoint.url, route = Http.Route.DIRECT).body)
            }
        }
        LocalEndpoint(body + "é", chunked = true).use { endpoint ->
            assertFailsWith<Http.ResponseTooLargeException> {
                Http.Client().get(endpoint.url, route = Http.Route.DIRECT)
            }
        }
    }

    @Test fun `oversized content length is rejected before waiting for the body`() {
        for (route in Http.Route.entries) {
            val release = CountDownLatch(1)
            LocalEndpoint("x".repeat(Http.MAX_RESPONSE_BYTES + 1), beforeBodyRemainder = {
                release.await(5, TimeUnit.SECONDS)
            }).use { endpoint ->
                withDefaultProxy(RecordingProxySelector(endpoint.address)) {
                    val pool = Executors.newSingleThreadExecutor()
                    try {
                        val request = pool.submit<Http.Response> {
                            Http.Client().get(endpoint.url, readTimeoutMs = 10_000, route = route)
                        }
                        val failure = assertFailsWith<ExecutionException> { request.get(2, TimeUnit.SECONDS) }
                        assertIs<Http.ResponseTooLargeException>(failure.cause)
                    } finally {
                        release.countDown()
                        pool.shutdownNow()
                        assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
                    }
                }
            }
        }
    }

    @Test fun `oversized chunked response cancels its upstream socket`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2_000
            val pool = Executors.newSingleThreadExecutor()
            val accepted = AtomicReference<Socket>()
            try {
                val peerClosed = pool.submit {
                    server.accept().use { socket ->
                        accepted.set(socket)
                        socket.soTimeout = 2_000
                        val input = socket.getInputStream()
                        val requestHeaders = StringBuilder()
                        while (!requestHeaders.endsWith("\r\n\r\n")) {
                            check(requestHeaders.length < 8192) { "test request headers exceeded their limit" }
                            val next = input.read()
                            check(next >= 0) { "client closed before sending request headers" }
                            requestHeaders.append(next.toChar())
                        }
                        assertTrue(requestHeaders.startsWith("GET /lookup HTTP/1.1\r\n"))
                        try {
                            val output = socket.getOutputStream()
                            output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            val chunk = ByteArray(8192)
                            val chunkHeader = "2000\r\n".toByteArray(Charsets.US_ASCII)
                            val chunkEnd = "\r\n".toByteArray(Charsets.US_ASCII)
                            repeat(Http.MAX_RESPONSE_BYTES / chunk.size) {
                                output.write(chunkHeader)
                                output.write(chunk)
                                output.write(chunkEnd)
                            }
                            output.write("1\r\nx\r\n".toByteArray(Charsets.US_ASCII))
                            output.flush()
                            // Leave the chunked response unfinished, but stop writing at limit + 1.
                            // HttpServer's blocking SocketChannel.write can stall behind Windows
                            // TCP buffers instead of throwing. Reading observes the actual peer
                            // close (FIN or reset), not whether a flooded writer has woken up yet.
                            assertEquals(-1, input.read(), "oversized response socket was not closed")
                        } catch (_: SocketException) {
                            // A peer reset is also a real upstream connection cancellation.
                        }
                    }
                }
                assertFailsWith<Http.ResponseTooLargeException> {
                    Http.Client().get("http://127.0.0.1:${server.localPort}/lookup", readTimeoutMs = 3_000, route = Http.Route.DIRECT)
                }
                peerClosed.get(2, TimeUnit.SECONDS)
            } finally {
                server.close()
                accepted.get()?.close()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `interrupt during a response body cancels the request and restores the flag`() {
        for (route in Http.Route.entries) {
            val bodyStarted = CountDownLatch(1)
            val release = CountDownLatch(1)
            LocalEndpoint("body", beforeBodyRemainder = {
                bodyStarted.countDown()
                release.await(5, TimeUnit.SECONDS)
            }).use { endpoint ->
                withDefaultProxy(RecordingProxySelector(endpoint.address)) {
                    val pool = Executors.newSingleThreadExecutor()
                    val worker = AtomicReference<Thread>()
                    try {
                        val request = pool.submit {
                            worker.set(Thread.currentThread())
                            try {
                                val failure = assertFailsWith<IOException> {
                                    Http.Client().get("${endpoint.url}?code=private-test-code", readTimeoutMs = 10_000, route = route)
                                }
                                assertTrue(Thread.currentThread().isInterrupted)
                                assertIs<InterruptedException>(failure.cause)
                                assertEquals("HTTP request was interrupted", failure.message)
                                assertTrue("private-test-code" !in failure.stackTraceToString())
                            } finally {
                                Thread.interrupted()
                            }
                        }
                        assertTrue(bodyStarted.await(2, TimeUnit.SECONDS))
                        worker.get().interrupt()
                        request.get(3, TimeUnit.SECONDS)
                    } finally {
                        release.countDown()
                        pool.shutdownNow()
                        assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
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
        chunked: Boolean = false,
        beforeBodyRemainder: (() -> Unit)? = null,
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
                    it.sendResponseHeaders(status, if (chunked) 0 else bytes.size.toLong())
                    try {
                        if (beforeBodyRemainder != null) {
                            it.responseBody.write(bytes, 0, 1)
                            it.responseBody.flush()
                            beforeBodyRemainder()
                            it.responseBody.write(bytes, 1, bytes.size - 1)
                        } else {
                            it.responseBody.write(bytes)
                        }
                    } catch (_: IOException) {
                        // A size limit, timeout or interrupt is expected to close the client socket.
                    }
                }
            }
            start()
        }
        val address: InetSocketAddress get() = server.address
        val url: String get() = "http://127.0.0.1:${address.port}/lookup"

        override fun close() = server.stop(0)
    }
}
