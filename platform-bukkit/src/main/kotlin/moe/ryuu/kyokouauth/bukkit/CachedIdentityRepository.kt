package moe.ryuu.kyokouauth.bukkit

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.storage.ClaimResult
import moe.ryuu.kyokouauth.core.storage.IdentityStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
 */
class CachedIdentityRepository(private val delegate: IdentityStore) : IdentityStore by delegate {

    private val cache = ConcurrentHashMap<UUID, BoundIdentity>()

    override fun cached(uuid: UUID): BoundIdentity? {
        cache[uuid]?.let { return it }
        val loaded = delegate.byUuid(uuid) ?: return null
        cache[uuid] = loaded
        return loaded
    }

    /**
     * Writes bypass the cache and invalidate it.
     *
     * Invalidating rather than updating keeps one rule: the cache is only ever filled from the
     * database, so it cannot drift into holding something the database would have refused.
     */
    override fun claim(identity: Identity, now: Long): ClaimResult =
        delegate.claim(identity, now).also { invalidate(identity.uuid) }

    override fun rename(uuid: UUID, newName: GameName, now: Long): ClaimResult =
        delegate.rename(uuid, newName, now).also { invalidate(uuid) }

    override fun unbind(uuid: UUID): Boolean =
        delegate.unbind(uuid).also { invalidate(uuid) }

    override fun touch(uuid: UUID, now: Long) = delegate.touch(uuid, now)

    fun invalidate(uuid: UUID) {
        cache.remove(uuid)
    }
}
