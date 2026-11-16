package moe.ryuu.kyokouauth.core.http

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
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
 * One instance lives for the whole process because the pool lives inside it - a client per request
 * would restore exactly the behaviour this replaced.
 */
object Http {

    const val FORM = "application/x-www-form-urlencoded"
    const val JSON = "application/json"

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
    ): Response = request("POST", url, body, contentType, headers, readTimeoutMs)

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = 20_000,
    ): Response = request("GET", url, null, null, headers, readTimeoutMs)

    /**
     * Shared client: connection pool, TLS session cache and HTTP/2 negotiation all live here.
     *
     * The connect timeout is client-wide rather than per request because that is the only place this
     * API exposes it, and every host this plugin talks to is a public API where ten seconds is
     * already generous.
     */
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private fun request(
        method: String,
        url: String,
        body: String?,
        contentType: String?,
        headers: Map<String, String>,
        readTimeoutMs: Int,
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

        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        } catch (interrupted: InterruptedException) {
            // Restoring the flag is not optional: the caller's thread pool reuses this thread, and a
            // swallowed interrupt leaves the next task unable to be cancelled.
            Thread.currentThread().interrupt()
            throw IOException("HTTP request to $url was interrupted", interrupted)
        }

        // `HttpHeaders#map()` keys are lower-cased by the client. Nothing in this plugin reads them,
        // and the alternative - the raw field map - carries null keys for HTTP/2 pseudo-headers.
        return Response(
            status = response.statusCode(),
            body = String(response.body(), StandardCharsets.UTF_8),
            headers = response.headers().map(),
        )
    }

    private const val USER_AGENT = "KyokouAuth (Minecraft server login plugin)"
}
