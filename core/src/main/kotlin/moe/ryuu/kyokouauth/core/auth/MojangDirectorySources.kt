package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.http.Http
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The callback must acquire a permit for each actual HTTP request, not for each logical lookup. */
internal fun interface DirectoryRequest {
    fun fetch(name: String, permit: () -> Boolean): Http.Response
}
internal class DirectoryBudgetExhausted : IOException("Local name lookup budget exhausted")

/** Two fixed official HTTPS origins, each with its own outage/throttle state; no IP rotation or mirrors. */
internal class MojangDirectorySources(
    private val request: (String) -> Http.Response,
    private val clock: () -> Long = System::currentTimeMillis,
) : DirectoryRequest {
    private data class Source(val origin: String, val path: String, val legacy: Boolean)
    private data class Pause(val until: Long, val rateLimited: Boolean)
    private val sources = listOf(
        Source("https://api.minecraftservices.com", "/minecraft/profile/lookup/name/", false),
        Source("https://api.mojang.com", "/users/profiles/minecraft/", true),
    )
    private val pauses = arrayOfNulls<Pause>(sources.size)

    override fun fetch(name: String, permit: () -> Boolean): Http.Response {
        require(VALID_NAME.matches(name)) { "Invalid profile lookup name" }
        sources.forEachIndexed { index, source ->
            val now = clock()
            val paused = synchronized(pauses) { pauses[index]?.until?.let { now < it } == true }
            if (paused) return@forEachIndexed
            if (!permit()) throw DirectoryBudgetExhausted()
            val response = try { request(source.origin + source.path + name) } catch (failure: Exception) {
                if (failure is InterruptedException || Thread.currentThread().isInterrupted) {
                    Thread.currentThread().interrupt()
                    throw IOException("Name lookup interrupted")
                }
                pause(index, 5_000L, limited = false)
                return@forEachIndexed
            }
            if (validAnswer(source, name, response)) return response
            if (response.status == 429) {
                pause(index, retryDelay(response, clock()) ?: 60_000L, limited = true)
            } else {
                // Unexpected/HTML 404s, malformed profiles and upstream failures never mean "free".
                pause(index, 5_000L, limited = false)
            }
        }
        val now = clock()
        val active = synchronized(pauses) { pauses.mapNotNull { it?.takeIf { p -> p.until > now } } }
        return if (active.size == sources.size && active.all { it.rateLimited }) {
            val delay = active.minOf { it.until } - now
            val seconds = delay / 1_000 + if (delay % 1_000 > 0) 1 else 0
            Http.Response(429, "", mapOf("retry-after" to listOf(seconds.coerceAtLeast(1).toString())))
        } else {
            Http.Response(503, "", emptyMap())
        }
    }

    private fun validAnswer(source: Source, name: String, response: Http.Response): Boolean {
        if (response.status == 204) return source.legacy
        if (response.status !in setOf(200, 404)) return false
        val json = response.jsonOrNull() ?: return false
        fun string(key: String) = json.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return if (response.status == 200) {
            val id = string("id") ?: return false
            MojangDirectory.parseUndashedUuid(id) != null && string("name")?.equals(name, ignoreCase = true) == true
        } else {
            // The application-specific not-found envelope proves this is a name lookup miss, not
            // a missing route, WAF response or arbitrary 404 page at the official origin.
            string("path")?.equals(source.path + name, ignoreCase = true) == true &&
                string("errorMessage")?.equals("Couldn't find any profile with name $name", ignoreCase = true) == true
        }
    }

    private fun pause(index: Int, millis: Long, limited: Boolean) {
        val now = clock()
        val delay = millis.coerceAtLeast(1_000L)
        val until = if (delay > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delay
        synchronized(pauses) {
            val previous = pauses[index]
            if (previous == null || previous.until <= now || until >= previous.until) pauses[index] = Pause(until, limited)
        }
    }

    companion object {
        private val VALID_NAME = Regex("[A-Za-z0-9_]{3,16}")

        internal fun retryDelay(response: Http.Response, now: Long): Long? {
            val value = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }
                ?.value?.firstOrNull()?.trim() ?: return null
            value.toLongOrNull()?.takeIf { it >= 0 }?.let {
                return if (it > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else it * 1_000
            }
            if (value.isNotEmpty() && value.all { it in '0'..'9' }) return Long.MAX_VALUE
            return runCatching {
                (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0)
            }.getOrNull()
        }
    }
}
