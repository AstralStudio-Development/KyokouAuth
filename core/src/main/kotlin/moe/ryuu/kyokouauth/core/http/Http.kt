package moe.ryuu.kyokouauth.core.http

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * A deliberately small HTTP client.
 *
 * `java.net.http.HttpClient` is the JDK's own client and the reason this class is not built on
 * `HttpURLConnection`: the older API closes its socket on every `disconnect()`, so each login paid a
 * fresh TCP and TLS handshake against Mojang. With a client that keeps connections alive, the second
 * and every later request on the same host reuse the established one.
 *
 * Each route reuses one process-wide client and its pool; a client per request would restore exactly
 * the behaviour this replaced. DIRECT bypasses Java's proxy selection, not OS-level routing or TUN.
 */
object Http {

    const val FORM = "application/x-www-form-urlencoded"
    const val JSON = "application/json"

    enum class Route { INHERIT, DIRECT }

    private val shared = Client()

    class Response(
        val status: Int,
        val body: String,
        val headers: Map<String, List<String>>,
    ) {
        val isSuccess: Boolean get() = status in 200..299

        fun json(): JsonObject =
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IOException("expected a JSON object, got: ${body.take(200)}")

        fun jsonOrNull(): JsonObject? =
            runCatching { json() }.getOrNull()
    }

    fun encodeForm(pairs: Map<String, String>): String =
        pairs.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }

    fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = 20_000,
    ): Response = shared.post(url, body, contentType, headers, readTimeoutMs)

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = 20_000,
        route: Route = Route.INHERIT,
    ): Response = shared.get(url, headers, readTimeoutMs, route)

    // A separate instance gives tests fresh client builders without replacing the process-wide pool.
    internal class Client {
        fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String> = emptyMap(),
            readTimeoutMs: Int = 20_000,
        ): Response = request("POST", url, body, contentType, headers, readTimeoutMs, Route.INHERIT)

        fun get(
            url: String,
            headers: Map<String, String> = emptyMap(),
            readTimeoutMs: Int = 20_000,
            route: Route = Route.INHERIT,
        ): Response = request("GET", url, null, null, headers, readTimeoutMs, route)

        // Independent lazy builders keep a broken inherited selector from initializing DIRECT.
        private val client: HttpClient by lazy { buildClient(Route.INHERIT) }
        private val directClient: HttpClient by lazy { buildClient(Route.DIRECT) }

        private fun buildClient(route: Route): HttpClient {
            val builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
            if (route == Route.DIRECT) builder.proxy(DIRECT_PROXY_SELECTOR)
            return builder.build()
        }

        private fun request(
            method: String,
            url: String,
            body: String?,
            contentType: String?,
            headers: Map<String, String>,
            readTimeoutMs: Int,
            route: Route,
        ): Response {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(readTimeoutMs.toLong()))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)

            headers.forEach { (name, value) -> builder.header(name, value) }

            val publisher = if (body != null) {
                contentType?.let { builder.header("Content-Type", it) }
                HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
            } else {
                HttpRequest.BodyPublishers.noBody()
            }
            builder.method(method, publisher)

            val selectedClient = when (route) {
                Route.INHERIT -> client
                Route.DIRECT -> directClient
            }
            val response = try {
                selectedClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            } catch (interrupted: InterruptedException) {
                // Restoring the flag is not optional: the caller's thread pool reuses this thread,
                // and a swallowed interrupt leaves the next task unable to be cancelled.
                Thread.currentThread().interrupt()
                throw IOException("HTTP request was interrupted", interrupted)
            }

            // HttpHeaders.map() normalizes header names and excludes HTTP/2 pseudo-headers.
            return Response(
                status = response.statusCode(),
                body = String(response.body(), StandardCharsets.UTF_8),
                headers = response.headers().map(),
            )
        }
    }

    private val DIRECT_PROXY_SELECTOR = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }

    private const val USER_AGENT = "KyokouAuth (Minecraft server login plugin)"
}
