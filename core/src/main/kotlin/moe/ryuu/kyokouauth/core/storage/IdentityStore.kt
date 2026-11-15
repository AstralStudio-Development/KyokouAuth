package moe.ryuu.kyokouauth.core.storage

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import java.util.UUID

/**
 * The reads and writes the login flow needs, separated from the JDBC implementation so that a
 * caching layer can sit in front of it without changing any caller.
 */
interface IdentityStore {

    fun byName(name: GameName): BoundIdentity?

    fun byUuid(uuid: UUID): BoundIdentity?

    fun bySubject(kind: BoundIdentity.Kind, subject: String): BoundIdentity?

    fun claim(identity: Identity, now: Long): ClaimResult

    fun touch(uuid: UUID, now: Long)

    fun unbind(uuid: UUID): Boolean

    fun rename(uuid: UUID, newName: GameName, now: Long): ClaimResult

    fun all(): List<BoundIdentity>

    /**
     * A read that may be answered from memory.
     *
     * PlaceholderAPI expansions are queried from the main thread by scoreboard and tab plugins, so
     * they cannot afford a database round trip. Everything that decides access control uses the
     * uncached reads above.
     */
    fun cached(uuid: UUID): BoundIdentity?
}
