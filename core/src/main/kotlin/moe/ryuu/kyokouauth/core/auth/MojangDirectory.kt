package moe.ryuu.kyokouauth.core.auth

import com.github.benmanes.caffeine.cache.Caffeine
import moe.ryuu.kyokouauth.core.http.Http
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Answers one question: does Mojang own this name?
 *
 * That answer is what decides whether an incoming connection must prove itself with a Mojang
 * handshake (and therefore whether a non-premium client may not use this name at all). Getting it
 * wrong in the "no" direction is an impersonation hole, so the failure mode is asymmetric:
 *
 *  - A name Mojang has never heard of is cached for a short time, since such names become premium
 *    names when someone buys the game.
 *  - A network failure is **never** cached as "not premium". It is reported as [Lookup.Failed] and
 *    the caller refuses the login.
 */
object MojangDirectory {

    private const val PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/"

    sealed class Lookup {
        /** Mojang owns this name. [uuid] is the un-dashed Mojang UUID. */
        class Premium(val uuid: UUID, val name: String) : Lookup()

        /** Mojang has no account with this name. */
        object Free : Lookup()

        /** Mojang could not be reached or answered with something unusable. Never treated as [Free]. */
        class Failed(val reason: String) : Lookup()
    }

    /**
     * Guard against spending more than Mojang allows.
     *
     * Disabled by default so tests and the plugin before configuration never throttle; the plugin
     * enables it from config at startup.
     */
    @Volatile
    private var budget: RequestBudget = RequestBudget(0, 1)

    @Volatile
    private var budgetLimit: Int = 0

    @Volatile
    private var budgetWindowMinutes: Int = 0

    /** Mojang allows roughly 600 lookups per ten minutes per address; 600/10 is the default. */
    fun configureBudget(maxRequests: Int, windowMinutes: Int) {
        budgetLimit = maxRequests
        budgetWindowMinutes = windowMinutes
        budget = RequestBudget(
            maxRequests = maxRequests,
            windowMillis = windowMinutes.coerceAtLeast(1) * 60_000L,
        )
    }

    fun budgetUsage(): Int = budget.used()

    /**
     * Positive answers, kept for the life of the process.
     *
     * A name does not stop being premium. The six-hour expiry this used to carry only produced a
     * repeat lookup per player per session - which is exactly the traffic that earns a 429 from an
     * endpoint that allows roughly 600 requests per ten minutes per address.
     */
    private val premiumCache = Caffeine.newBuilder()
        .maximumSize(20_000)
        .build<String, UUID>()

    /** Negative answers move: someone can buy the name five minutes from now. */
    private val freeCache = Caffeine.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .maximumSize(20_000)
        .build<String, Boolean>()

    /**
     * Until when this source stops asking.
     *
     * A 429 is not an answer, and retrying during one extends it. Without this the next join - and
     * every join after it - asked again, so a short limit became a long one.
     */
    @Volatile
    private var blockedUntil: Long = 0L

    fun lookup(nameKey: String): Lookup {
        premiumCache.getIfPresent(nameKey)?.let { return Lookup.Premium(it, nameKey) }
        if (freeCache.getIfPresent(nameKey) != null) return Lookup.Free

        val now = System.currentTimeMillis()
        if (now < blockedUntil) {
            return Lookup.Failed("Mojang 限流中，${(blockedUntil - now) / 1000 + 1} 秒后重试")
        }

        // Only real requests are counted, which is exactly what Mojang counts. A cached answer costs
        // nothing on either side.
        if (!budget.tryConsume()) {
            return Lookup.Failed("本地配额已用尽（每 ${budgetWindowMinutes} 分钟 ${budgetLimit} 次），暂不查询 Mojang")
        }

        val response = try {
            Http.get(PROFILE_URL + nameKey)
        } catch (failure: Exception) {
            return Lookup.Failed("Mojang API unreachable: ${failure.message}")
        }

        return when (response.status) {
            200 -> {
                val json = response.jsonOrNull()
                    ?: return Lookup.Failed("Mojang API returned a non-JSON body.")
                val id = json.get("id")?.takeIf { !it.isJsonNull }?.asString
                    ?: return Lookup.Failed("Mojang API returned no id.")
                val uuid = parseUndashedUuid(id)
                    ?: return Lookup.Failed("Mojang API returned a malformed id: $id")
                premiumCache.put(nameKey, uuid)
                Lookup.Premium(uuid, nameKey)
            }

            // 204 No Content is Mojang's documented "this name does not exist" answer. 404 shows up
            // for some proxies and means the same thing.
            204, 404 -> {
                freeCache.put(nameKey, true)
                Lookup.Free
            }

            // 429 is a rate limit, not an answer. Retrying immediately deepens the limit and
            // answering "free" would open an impersonation window, so neither: stay quiet for a
            // minute and report it.
            429 -> {
                blockedUntil = now + 60_000L
                Lookup.Failed("Mojang 返回 429，60 秒内不再查询")
            }

            else -> Lookup.Failed("Mojang API answered HTTP ${response.status}.")
        }
    }

    /** Seeds the positive cache with a UUID already proven by a Mojang handshake. */
    fun rememberPremium(nameKey: String, uuid: UUID) {
        premiumCache.put(nameKey, uuid)
        freeCache.invalidate(nameKey)
    }

    fun forget(nameKey: String) {
        premiumCache.invalidate(nameKey)
        freeCache.invalidate(nameKey)
    }

    internal fun parseUndashedUuid(raw: String): UUID? {
        if (raw.length != 32) return null
        return runCatching {
            UUID.fromString(
                raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16) + "-" +
                    raw.substring(16, 20) + "-" + raw.substring(20, 32),
            )
        }.getOrNull()
    }
}

