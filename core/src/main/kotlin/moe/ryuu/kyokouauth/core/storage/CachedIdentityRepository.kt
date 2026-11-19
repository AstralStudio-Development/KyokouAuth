package moe.ryuu.kyokouauth.core.storage

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import java.time.Duration
import java.util.Optional
import java.util.UUID

/**
 * A read-through cache in front of [IdentityStore] for the one caller that cannot afford a query.
 *
 * ## What is cached and what is not
 *
 * Only [cached] answers from memory. Every method that participates in an access decision -
 * `byName`, `claim`, `rename`, `unbind` - goes straight to the database, because a stale answer
 * there is a security bug rather than a performance one. Specifically:
 *
 *  - `byName` decides whether a name is already taken and whether its owner is premium.
 *  - `claim` must see the committed row to refuse a duplicate.
 *
 * The cache exists because PlaceholderAPI expansions are invoked from the main thread by scoreboard
 * and tab plugins, often several times per second, and a bound identity never changes during a
 * session.
 *
 * ## Why it is bounded
 *
 * A plain map here would keep one entry per UUID the server has ever seen, for the lifetime of the
 * process. The bound is not about memory pressure on a large server - it is about a server that
 * runs for months, where the map is the only structure in the plugin that never releases anything.
 * Ten minutes is long enough to cover a session and short enough that a revoked binding stops being
 * served from memory shortly after `unbind`.
 *
 * ## Why "no row" is cached too
 *
 * Most players on a server with both Java and Bedrock players have no row under the UUID they are
 * querying - a Bedrock player's `UUID(0, xuid)` is never in the table. Caching only the hits meant
 * every scoreboard refresh for such a player ran a query, from the main thread, several times a
 * second. The miss is cached as [Optional.empty] with the same lifetime, and `claim` invalidates it
 * the moment a row is created.
 */
class CachedIdentityRepository(private val delegate: IdentityStore) : IdentityStore by delegate {

    private val cache: Cache<UUID, Optional<BoundIdentity>> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(10))
        .maximumSize(10_000)
        .build()

    override fun cached(uuid: UUID): BoundIdentity? {
        cache.getIfPresent(uuid)?.let { return it.orElse(null) }
        val loaded = delegate.byUuid(uuid)
        cache.put(uuid, Optional.ofNullable(loaded))
        return loaded
    }

    /**
     * Writes bypass the cache and invalidate it.
     *
     * Invalidating rather than updating keeps one rule: the cache is only ever filled from the
     * database, so it cannot drift into holding something the database would have refused.
     */
    override fun claim(identity: Identity, now: Long): ClaimResult =
        delegate.claim(identity, now).also { result ->
            invalidate(identity.uuid)
            if (result is ClaimResult.Bound) invalidate(result.record.uuid)
        }

    override fun claimRenamingMicrosoft(identity: Identity.Microsoft, now: Long): ClaimResult =
        delegate.claimRenamingMicrosoft(identity, now).also { result ->
            invalidate(identity.uuid)
            if (result is ClaimResult.Bound) invalidate(result.record.uuid)
        }

    override fun rename(uuid: UUID, newName: GameName, now: Long): ClaimResult =
        delegate.rename(uuid, newName, now).also { invalidate(uuid) }

    override fun unbind(uuid: UUID): Boolean =
        delegate.unbind(uuid).also { invalidate(uuid) }

    override fun touch(uuid: UUID, now: Long) = delegate.touch(uuid, now)

    fun invalidate(uuid: UUID) {
        cache.invalidate(uuid)
    }

    /** Live entry count, for the operator's status line. */
    fun size(): Long = cache.estimatedSize()
}
