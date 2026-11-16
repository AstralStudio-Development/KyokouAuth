package moe.ryuu.kyokouauth.core.auth

import com.github.benmanes.caffeine.cache.Caffeine
import moe.ryuu.kyokouauth.core.http.Http
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Name ownership selects an authentication channel; neither a UUID lookup nor its cache is proof. */
object MojangDirectory {
    sealed class Lookup {
        class Premium(val uuid: UUID, val name: String) : Lookup()
        object Free : Lookup()
        class Failed(val reason: String, val withheld: Boolean = false) : Lookup()
    }
    @Volatile private var network = Http.Route.INHERIT
    private val sources = MojangDirectorySources(request = { url ->
        Http.get(url, readTimeoutMs = 5_000, route = network)
    })
    private val client = MojangDirectoryClient(request = sources)
    fun configureNetwork(route: Http.Route) { network = route }
    fun lookup(nameKey: String): Lookup = client.lookup(nameKey)
    fun configureBudget(maxRequests: Int, windowMinutes: Int) = client.configureBudget(maxRequests, windowMinutes)
    fun budgetUsage(): Int = client.budgetUsage()
    fun rememberPremium(nameKey: String, uuid: UUID) = client.rememberPremium(nameKey, uuid)
    fun forget(nameKey: String) = client.forget(nameKey)
    internal fun parseUndashedUuid(raw: String): UUID? {
        if (raw.length != 32 || !raw.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        return runCatching { UUID.fromString("${raw.take(8)}-${raw.substring(8,12)}-${raw.substring(12,16)}-${raw.substring(16,20)}-${raw.substring(20)}") }.getOrNull()
    }
}

/** Bounded single-flight/cache/budget policy with an offline-testable upstream seam. */
internal class MojangDirectoryClient(
    private val request: DirectoryRequest,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(request: (String) -> Http.Response, clock: () -> Long = System::currentTimeMillis) :
        this(DirectoryRequest { name, permit ->
            if (!permit()) throw DirectoryBudgetExhausted()
            request(name)
        }, clock)

    private val locks = Array(64) { Any() }
    private val premium = Caffeine.newBuilder().maximumSize(20_000).build<String, UUID>()
    private val free = Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).maximumSize(20_000).build<String, Boolean>()
    private data class Failure(val result: MojangDirectory.Lookup.Failed, val until: Long)
    private val failed = Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.SECONDS).maximumSize(20_000).build<String, Failure>()
    @Volatile private var budget = RequestBudget(0, 1, clock)
    @Volatile private var budgetLimit = 0
    @Volatile private var budgetWindowMinutes = 0
    @Volatile private var blockedUntil = 0L

    fun configureBudget(maxRequests: Int, windowMinutes: Int) {
        budgetLimit = maxRequests
        budgetWindowMinutes = windowMinutes
        budget = RequestBudget(maxRequests, windowMinutes.coerceAtLeast(1) * 60_000L, clock)
    }
    fun budgetUsage() = budget.used()

    fun lookup(raw: String): MojangDirectory.Lookup {
        val key = raw.lowercase(Locale.ROOT)
        // Same-name misses are serialized. Cached answers and errors are rechecked after waiting,
        // so a reconnect burst cannot spend one request per connection before the first reply arrives.
        synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
            premium.getIfPresent(key)?.let { return MojangDirectory.Lookup.Premium(it, key) }
            if (free.getIfPresent(key) != null) return MojangDirectory.Lookup.Free
            val now = clock()
            failed.getIfPresent(key)?.takeIf { now < it.until }?.let { return it.result }
            if (now < blockedUntil) {
                val remaining = blockedUntil - now
                val seconds = remaining / 1000 + if (remaining % 1000 > 0) 1 else 0
                return MojangDirectory.Lookup.Failed("官方名字查询源仍在限流，$seconds 秒后重试", withheld = true)
            }
            val response = try { request.fetch(key, budget::tryConsume) } catch (_: DirectoryBudgetExhausted) {
                return MojangDirectory.Lookup.Failed(
                    "本地名字查询预算已达上限（每 $budgetWindowMinutes 分钟 $budgetLimit 次）", withheld = true)
            } catch (failure: Exception) {
                if (failure is InterruptedException || Thread.currentThread().isInterrupted) Thread.currentThread().interrupt()
                val result = MojangDirectory.Lookup.Failed("Mojang 名字查询暂时不可用 (${failure.javaClass.simpleName})")
                failed.put(key, Failure(result, clock() + 5_000))
                return result
            }
            val result = when (response.status) {
                200 -> {
                    val id = runCatching { response.jsonOrNull()?.get("id")?.asString }.getOrNull()
                    val uuid = id?.let(MojangDirectory::parseUndashedUuid)
                    if (uuid == null) MojangDirectory.Lookup.Failed("Mojang 返回无效名字资料")
                    else {
                        premium.put(key, uuid)
                        MojangDirectory.Lookup.Premium(uuid, key)
                    }
                }
                204, 404 -> { free.put(key, true); MojangDirectory.Lookup.Free }
                429 -> {
                    // Sources returns 429 only when every official origin is throttled. A single
                    // throttled origin must not stop healthy-origin lookups. Honor an explicit short
                    // retry delay too; only a missing/invalid header uses the conservative 60s default.
                    val receivedAt = clock()
                    val delay = (MojangDirectorySources.retryDelay(response, receivedAt) ?: 60_000L).coerceAtLeast(1_000L)
                    blockedUntil = if (delay > Long.MAX_VALUE - receivedAt) Long.MAX_VALUE else receivedAt + delay
                    val seconds = delay / 1000 + if (delay % 1000 > 0) 1 else 0
                    MojangDirectory.Lookup.Failed("官方名字查询源均限流，至少 $seconds 秒后重试；缓存命中不受影响", withheld = true)
                }
                else -> MojangDirectory.Lookup.Failed("Mojang 名字查询失败 (HTTP ${response.status})")
            }
            if (result is MojangDirectory.Lookup.Failed && !result.withheld) failed.put(key, Failure(result, clock() + 5_000))
            return result
        }
    }

    fun rememberPremium(raw: String, uuid: UUID) {
        val key = raw.lowercase(Locale.ROOT)
        synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
            premium.put(key, uuid); free.invalidate(key); failed.invalidate(key)
        }
    }
    fun forget(raw: String) {
        val key = raw.lowercase(Locale.ROOT)
        synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
            premium.invalidate(key); free.invalidate(key); failed.invalidate(key)
        }
    }
}
