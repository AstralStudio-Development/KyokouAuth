package moe.ryuu.kyokouauth.core.http

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
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
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    /** Authentication JSON responses are limited to 1 MiB of body bytes, before UTF-8 decoding. */
    const val MAX_RESPONSE_BYTES = 1024 * 1024

    class ResponseTooLargeException : IOException("HTTP response exceeds $MAX_RESPONSE_BYTES bytes")

    // Do not retain a parser cause: Gson error paths can themselves contain sensitive JSON keys.
    class InvalidJsonResponseException : IOException("HTTP response is not a valid JSON object")

    enum class Route { INHERIT, DIRECT }

    private val shared = Client()

    class Response(
        val status: Int,
        val body: String,
        val headers: Map<String, List<String>>,
    ) {
        val isSuccess: Boolean get() = status in 200..299

        fun json(): JsonObject {
            val parsed = try {
                JsonParser.parseString(body)
            } catch (_: JsonParseException) {
                throw InvalidJsonResponseException()
            }
            if (!parsed.isJsonObject) throw InvalidJsonResponseException()
            return parsed.asJsonObject
        }

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
            var pending: CompletableFuture<HttpResponse<ByteArray>>? = null
            val response = try {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                pending = selectedClient.sendAsync(builder.build()) { info ->
                    val declaredLength = info.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull() ?: -1L
                    BoundedBodySubscriber(declaredLength)
                }
                // The JDK request timeout alone can stop at headers. This future completes only
                // after the subscriber has consumed the entire bounded body, so slow bodies also
                // share this deadline; no blocking InputStream or per-request timer is needed.
                pending.get(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                pending?.cancel(true)
                // Restoring the flag is not optional: the caller's thread pool reuses this thread,
                // and a swallowed interrupt leaves the next task unable to be cancelled.
                Thread.currentThread().interrupt()
                throw IOException("HTTP request was interrupted", interrupted)
            } catch (_: TimeoutException) {
                pending?.cancel(true)
                throw HttpTimeoutException("HTTP request timed out")
            } catch (failed: ExecutionException) {
                throw when (val cause = failed.cause) {
                    is IOException -> cause
                    is RuntimeException -> cause
                    is Error -> cause
                    else -> IOException("HTTP request failed", cause)
                }
            }

            // HttpHeaders.map() normalizes header names and excludes HTTP/2 pseudo-headers.
            return Response(
                status = response.statusCode(),
                body = String(response.body(), StandardCharsets.UTF_8),
                headers = response.headers().map(),
            )
        }
    }

    private class BoundedBodySubscriber(declaredLength: Long) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private var subscription: Flow.Subscription? = null
        private var bytes = ByteArray(0)
        private var size = 0

        init {
            if (declaredLength > MAX_RESPONSE_BYTES) result.completeExceptionally(ResponseTooLargeException())
        }

        override fun getBody(): CompletionStage<ByteArray> = result

        override fun onSubscribe(subscription: Flow.Subscription) {
            if (this.subscription != null || result.isDone) {
                subscription.cancel()
                return
            }
            this.subscription = subscription
            subscription.request(1)
        }

        override fun onNext(items: List<ByteBuffer>) {
            if (result.isDone) return
            val incoming = items.sumOf { it.remaining().toLong() }
            if (incoming > MAX_RESPONSE_BYTES - size) {
                bytes = ByteArray(0)
                result.completeExceptionally(ResponseTooLargeException())
                subscription?.cancel()
                return
            }
            val required = size + incoming.toInt()
            if (required > bytes.size) {
                bytes = bytes.copyOf(maxOf(8192, required, (bytes.size * 2).coerceAtMost(MAX_RESPONSE_BYTES)))
            }
            for (item in items) {
                val count = item.remaining()
                item.get(bytes, size, count)
                size += count
            }
            subscription?.request(1)
        }

        override fun onError(throwable: Throwable) {
            bytes = ByteArray(0)
            result.completeExceptionally(throwable)
        }

        override fun onComplete() {
            if (result.isDone) return
            result.complete(bytes.copyOf(size))
            bytes = ByteArray(0)
        }
    }

    private val DIRECT_PROXY_SELECTOR = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }

    private const val USER_AGENT = "KyokouAuth (Minecraft server login plugin)"
}
