package moe.ryuu.kyokouauth.core.http

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A deliberately small HTTP client.
 *
 * Java 8 is the bytecode floor, so `java.net.http.HttpClient` (11+) is unavailable and adding an
 * HTTP library would add another artifact whose Java baseline has to be verified. `HttpURLConnection`
 * is in every JVM this plugin will ever run on and is entirely adequate for the handful of
 * request/response exchanges the Microsoft flow needs.
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
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 20_000,
    ): Response = request("POST", url, body, contentType, headers, connectTimeoutMs, readTimeoutMs)

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 20_000,
    ): Response = request("GET", url, null, null, headers, connectTimeoutMs, readTimeoutMs)

    private fun request(
        method: String,
        url: String,
        body: String?,
        contentType: String?,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }

            if (body != null) {
                contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.toByteArray(StandardCharsets.UTF_8).size)
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }

            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            return Response(status, stream?.readText() ?: "", connection.headerFields.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readText(): String {
        val out = ByteArrayOutputStream()
        use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    private const val USER_AGENT = "KyokouAuth (Minecraft server login plugin)"
}
