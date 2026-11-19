package moe.ryuu.kyokouauth.core.storage

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import java.sql.Connection
import java.util.UUID

/**
 * A persisted identity: the fact that a name belongs to someone.
 *
 * [kind] exists purely so that a Mojang UUID and an XUID can never collide in the `subject` column -
 * they live in different namespaces even though both are "the thing Microsoft/Mojang calls you".
 */
data class BoundIdentity(
    val uuid: UUID,
    val name: GameName,
    val kind: Kind,
    val subject: String,
    val gamertag: String?,
    val createdAt: Long,
    val lastSeenAt: Long,
) {
    enum class Kind { MOJANG, XUID }

    fun toIdentity(): Identity = when (kind) {
        Kind.MOJANG -> Identity.Premium(name, uuid)
        Kind.XUID -> Identity.Microsoft(name, Xuid(subject.toLong()), gamertag.orEmpty(), uuid)
    }

    companion object {
        fun of(identity: Identity, now: Long, previous: BoundIdentity? = null): BoundIdentity =
            when (identity) {
                is Identity.Premium -> BoundIdentity(
                    uuid = identity.uuid,
                    name = identity.name,
                    kind = Kind.MOJANG,
                    subject = identity.mojangUuid.toString(),
                    gamertag = null,
                    createdAt = previous?.createdAt ?: now,
                    lastSeenAt = now,
                )

                is Identity.Microsoft -> BoundIdentity(
                    uuid = identity.uuid,
                    name = identity.name,
                    kind = Kind.XUID,
                    subject = identity.xuid.value.toString(),
                    gamertag = identity.gamertag,
                    createdAt = previous?.createdAt ?: now,
                    lastSeenAt = now,
                )
            }
    }
}

/** Outcome of trying to claim a name. Every branch is a state the login flow must handle differently. */
sealed class ClaimResult {
    /** A committed bind, refresh or authorized rename; previousName is set only after a name move. */
    data class Bound(val record: BoundIdentity, val previousName: GameName? = null) : ClaimResult()

    /** The name belongs to a different identity. Never overridable by the player. */
    data class NameTaken(val by: BoundIdentity) : ClaimResult()

    /** This XUID/Mojang UUID already holds a different name. Only an admin rename can fix it. */
    data class IdentityAlreadyNamed(val existing: BoundIdentity) : ClaimResult()

    /** Nothing is bound to this UUID. */
    data class UnknownIdentity(val uuid: UUID) : ClaimResult()
}

/**
 * All reads and writes about who a name belongs to.
 *
 * Every method here is blocking and must be called from the IO executor.
 */
class IdentityRepository(private val database: Database) : IdentityStore {

    override fun cached(uuid: UUID): BoundIdentity? = byUuid(uuid)

    override fun byName(name: GameName): BoundIdentity? = database.withConnection { connection ->
        connection.prepareStatement("$SELECT WHERE n.name_key = ?").use { statement ->
            statement.setString(1, name.key)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toBoundIdentity() else null }
        }
    }

    override fun byUuid(uuid: UUID): BoundIdentity? = database.withConnection { connection ->
        connection.prepareStatement("$SELECT WHERE n.player_uuid = ?").use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeQuery().use { rows -> if (rows.next()) rows.toBoundIdentity() else null }
        }
    }

    override fun bySubject(kind: BoundIdentity.Kind, subject: String): BoundIdentity? =
        database.withConnection { connection ->
            connection.prepareStatement("$SELECT WHERE i.kind = ? AND i.subject = ?").use { statement ->
                statement.setString(1, kind.name)
                statement.setString(2, subject)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toBoundIdentity() else null }
            }
        }

    /**
     * Binds [identity] to its name, or explains why it cannot.
     *
     * The unique constraints remain authoritative if callers race: conflicting writes roll back,
     * never overwrite another identity. An enabled name move, gamertag refresh and identity update
     * commit in the same transaction, with the old name untouched on failure.
     */
    override fun claim(identity: Identity, now: Long): ClaimResult = claim(identity, now, allowNameChange = false)

    override fun claimRenamingMicrosoft(identity: Identity.Microsoft, now: Long): ClaimResult =
        claim(identity, now, allowNameChange = true)

    private fun claim(identity: Identity, now: Long, allowNameChange: Boolean): ClaimResult = database.transaction { connection ->
        val (kind, subject) = subjectOf(identity)
        val existingBySubject = bySubjectUnsafe(connection, kind, subject)
        if (existingBySubject != null && existingBySubject.uuid != identity.uuid) {
            throw IllegalArgumentException(
                "identity ($kind, $subject) is bound to profile ${existingBySubject.uuid}, cannot claim with ${identity.uuid}",
            )
        }
        val rawUuidForSubject = findIdentityRow(connection, kind, subject)
        if (rawUuidForSubject != null && rawUuidForSubject != identity.uuid.toString()) {
            throw IllegalArgumentException(
                "identity ($kind, $subject) row exists under $rawUuidForSubject, cannot claim with ${identity.uuid}",
            )
        }

        val ownerBySubject = findOwnerBySubject(connection, kind, subject)
        if (ownerBySubject != null && ownerBySubject != identity.uuid.toString()) {
            throw IllegalArgumentException(
                "identity ($kind, $subject) owns profile $ownerBySubject, cannot claim with ${identity.uuid}",
            )
        }

        require(identity !is Identity.Microsoft || identity.uuid == Identity.ofXuid(identity.xuid) || ownerBySubject != null) {
            "A mapped Microsoft profile requires a committed ownership record"
        }
        val ownerByProfile = findOwnerByProfile(connection, identity.uuid)
        if (ownerByProfile != null && ownerByProfile != (kind to subject)) {
            throw IllegalArgumentException(
                "profile ${identity.uuid} is exclusively owned by (${ownerByProfile.first}, ${ownerByProfile.second})",
            )
        }

        val existing = byUuidUnsafe(connection, identity.uuid)
        if (existing != null && (existing.kind != kind || existing.subject != subject)) {
            if (allowNameChange) {
                return@transaction ClaimResult.NameTaken(existing)
            }
            throw IllegalArgumentException(
                "profile ${identity.uuid} is already bound to (${existing.kind}, ${existing.subject})",
            )
        }
        val rawSubjectAtUuid = findSubjectAtUuid(connection, identity.uuid)
        if (rawSubjectAtUuid != null && rawSubjectAtUuid != (kind to subject)) {
            throw IllegalArgumentException(
                "profile ${identity.uuid} row belongs to (${rawSubjectAtUuid.first}, ${rawSubjectAtUuid.second})",
            )
        }
        val effectiveExisting = existing ?: existingBySubject
        val changingName = effectiveExisting != null && effectiveExisting.name.key != identity.name.key
        if (changingName && !allowNameChange) {
            return@transaction ClaimResult.IdentityAlreadyNamed(effectiveExisting)
        }

        val holderUuid = findNameOwner(connection, identity.name.key)
        if (holderUuid != null && holderUuid != identity.uuid.toString()) {
            return@transaction ClaimResult.NameTaken(
                byUuidUnsafe(connection, UUID.fromString(holderUuid))
                    ?: error("name row without identity row: $holderUuid"),
            )
        }

        val record = BoundIdentity.of(identity, now, effectiveExisting)

        upsertIdentity(connection, record)
        upsertName(connection, record)

        ClaimResult.Bound(record, effectiveExisting?.name?.takeIf { changingName })
    }

    /** Records that this identity was seen, without changing anything else. */
    override fun touch(uuid: UUID, now: Long) {
        database.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE ${Schema.IDENTITY} SET last_seen_at = ? WHERE player_uuid = ?",
            ).use { statement ->
                statement.setLong(1, now)
                statement.setString(2, uuid.toString())
                statement.executeUpdate()
            }
        }
    }

    /**
     * Admin-only. Removes the name, identity, and any play session for [uuid], while leaving
     * `kyokouauth_profile_owner` intact so a mapped identity retains exclusive profile ownership and
     * must re-prove itself on next join.
     */
    override fun unbind(uuid: UUID): Boolean = database.transaction { connection ->
        connection.prepareStatement(
            "DELETE FROM ${Schema.SESSION} WHERE player_uuid = ?",
        ).use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeUpdate()
        }
        val removedName = connection.prepareStatement(
            "DELETE FROM ${Schema.NAME} WHERE player_uuid = ?",
        ).use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeUpdate()
        }
        val removedIdentity = connection.prepareStatement(
            "DELETE FROM ${Schema.IDENTITY} WHERE player_uuid = ?",
        ).use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeUpdate()
        }
        removedName > 0 || removedIdentity > 0
    }

    /**
     * Admin-only rename. Moves the name while keeping the identity (and therefore the UUID) intact.
     */
    override fun rename(uuid: UUID, newName: GameName, now: Long): ClaimResult = database.transaction { connection ->
        val holderUuid = findNameOwner(connection, newName.key)
        if (holderUuid != null && holderUuid != uuid.toString()) {
            return@transaction ClaimResult.NameTaken(
                byUuidUnsafe(connection, UUID.fromString(holderUuid))
                    ?: error("name row without identity row: $holderUuid"),
            )
        }
        val current = byUuidUnsafe(connection, uuid)
            ?: return@transaction ClaimResult.UnknownIdentity(uuid)

        val renamed = current.copy(name = newName, lastSeenAt = now)
        upsertName(connection, renamed)
        connection.prepareStatement(
            "UPDATE ${Schema.IDENTITY} SET last_seen_at = ? WHERE player_uuid = ?",
        ).use { statement ->
            statement.setLong(1, now)
            statement.setString(2, uuid.toString())
            statement.executeUpdate()
        }
        ClaimResult.Bound(renamed)
    }

    override fun all(): List<BoundIdentity> = database.withConnection { connection ->
        connection.prepareStatement("$SELECT ORDER BY n.display_name").use { statement ->
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toBoundIdentity()) }
            }
        }
    }

    private fun subjectOf(identity: Identity): Pair<BoundIdentity.Kind, String> = when (identity) {
        is Identity.Premium -> BoundIdentity.Kind.MOJANG to identity.mojangUuid.toString()
        is Identity.Microsoft -> BoundIdentity.Kind.XUID to identity.xuid.value.toString()
    }

    private fun findIdentityRow(connection: Connection, kind: BoundIdentity.Kind, subject: String): String? =
        connection.prepareStatement(
            "SELECT player_uuid FROM ${Schema.IDENTITY} WHERE kind = ? AND subject = ?",
        ).use { statement ->
            statement.setString(1, kind.name)
            statement.setString(2, subject)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun findSubjectAtUuid(connection: Connection, uuid: UUID): Pair<BoundIdentity.Kind, String>? =
        connection.prepareStatement(
            "SELECT kind, subject FROM ${Schema.IDENTITY} WHERE player_uuid = ?",
        ).use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeQuery().use { rows ->
                if (rows.next()) BoundIdentity.Kind.valueOf(rows.getString(1)) to rows.getString(2) else null
            }
        }

    private fun bySubjectUnsafe(connection: Connection, kind: BoundIdentity.Kind, subject: String): BoundIdentity? =
        connection.prepareStatement("$SELECT WHERE i.kind = ? AND i.subject = ?").use { statement ->
            statement.setString(1, kind.name)
            statement.setString(2, subject)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toBoundIdentity() else null }
        }

    private fun findOwnerBySubject(connection: Connection, kind: BoundIdentity.Kind, subject: String): String? =
        connection.prepareStatement(
            "SELECT profile_uuid FROM ${Schema.PROFILE_OWNER} WHERE kind = ? AND subject = ?",
        ).use { statement ->
            statement.setString(1, kind.name)
            statement.setString(2, subject)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun findOwnerByProfile(connection: Connection, uuid: UUID): Pair<BoundIdentity.Kind, String>? =
        connection.prepareStatement(
            "SELECT kind, subject FROM ${Schema.PROFILE_OWNER} WHERE profile_uuid = ?",
        ).use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeQuery().use { rows ->
                if (rows.next()) BoundIdentity.Kind.valueOf(rows.getString(1)) to rows.getString(2) else null
            }
        }

    private fun findNameOwner(connection: Connection, nameKey: String): String? =
        connection.prepareStatement(
            "SELECT player_uuid FROM ${Schema.NAME} WHERE name_key = ?",
        ).use { statement ->
            statement.setString(1, nameKey)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun byUuidUnsafe(connection: Connection, uuid: UUID): BoundIdentity? =
        connection.prepareStatement("$SELECT WHERE n.player_uuid = ?").use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeQuery().use { rows -> if (rows.next()) rows.toBoundIdentity() else null }
        }

    private fun upsertIdentity(connection: Connection, record: BoundIdentity) {
        val updated = connection.prepareStatement(
            "UPDATE ${Schema.IDENTITY} SET gamertag = ?, last_seen_at = ? WHERE player_uuid = ?",
        ).use { statement ->
            statement.setString(1, record.gamertag)
            statement.setLong(2, record.lastSeenAt)
            statement.setString(3, record.uuid.toString())
            statement.executeUpdate()
        }
        if (updated > 0) return

        connection.prepareStatement(
            "INSERT INTO ${Schema.IDENTITY} " +
                "(player_uuid, kind, subject, gamertag, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, record.uuid.toString())
            statement.setString(2, record.kind.name)
            statement.setString(3, record.subject)
            statement.setString(4, record.gamertag)
            statement.setLong(5, record.createdAt)
            statement.setLong(6, record.lastSeenAt)
            statement.executeUpdate()
        }
    }

    private fun upsertName(connection: Connection, record: BoundIdentity) {
        val updated = connection.prepareStatement(
            "UPDATE ${Schema.NAME} SET display_name = ?, name_key = ? WHERE player_uuid = ?",
        ).use { statement ->
            statement.setString(1, record.name.value)
            statement.setString(2, record.name.key)
            statement.setString(3, record.uuid.toString())
            statement.executeUpdate()
        }
        if (updated > 0) return

        connection.prepareStatement(
            "INSERT INTO ${Schema.NAME} (name_key, display_name, player_uuid, claimed_at) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, record.name.key)
            statement.setString(2, record.name.value)
            statement.setString(3, record.uuid.toString())
            statement.setLong(4, record.createdAt)
            statement.executeUpdate()
        }
    }

    /**
     * Reads the projection by position.
     *
     * Column names are ambiguous across the two joined tables and every backend quotes aliases
     * differently (MySQL needs backticks unless ANSI_QUOTES is on), so the projection is read by
     * index and the order in [SELECT] is the contract.
     */
    private fun java.sql.ResultSet.toBoundIdentity(): BoundIdentity = BoundIdentity(
        uuid = UUID.fromString(getString(1)),
        name = GameName.parseOrThrow(getString(2)),
        kind = BoundIdentity.Kind.valueOf(getString(3)),
        subject = getString(4),
        gamertag = getString(5),
        createdAt = getLong(6),
        lastSeenAt = getLong(7),
    )

    private companion object {
        /**
         * One projection, used by every read. Every column is table-qualified because `player_uuid`,
         * `created_at` and `last_seen_at` exist on both joined tables and an unqualified reference is
         * an error rather than a guess in all of the supported backends.
         */
        const val SELECT =
            "SELECT n.player_uuid, n.display_name, i.kind, i.subject, i.gamertag, i.created_at, i.last_seen_at " +
                "FROM ${Schema.NAME} n JOIN ${Schema.IDENTITY} i ON i.player_uuid = n.player_uuid"
    }
}
