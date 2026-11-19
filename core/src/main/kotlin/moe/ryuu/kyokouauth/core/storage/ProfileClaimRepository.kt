package moe.ryuu.kyokouauth.core.storage

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Base64
import java.util.UUID

/** Imported historical metadata, not evidence that a particular connection owns the account. */
data class LegacyProfile(val id: UUID, val batch: String, val legacyName: String, val profileUuid: UUID,
                         val status: Status, val source: String, val claimedXuid: Xuid?) {
    enum class Status { AVAILABLE, CLAIMED }
}

data class ProfileOwner(val profileUuid: UUID, val kind: BoundIdentity.Kind, val subject: String, val version: Long)

data class LegacyProfileCandidate(val name: String, val profileUuid: UUID)

enum class LegacyBatchStatus { READY, IMPORTED, ALREADY_REGISTERED, DUPLICATE_NAME, CONFLICT, INVALID }

data class LegacyBatchEntry(val candidate: LegacyProfileCandidate, val status: LegacyBatchStatus, val recordId: UUID? = null)

/** Only the server-side claim flow receives the clear capability; database and logs never do. */
class Grant(val token: String, val recordId: UUID, val sessionId: UUID, val loginName: GameName, val expiresAt: Long) {
    override fun toString(): String = "Grant[REDACTED]"
}

enum class LegacyProofMethod { MANUAL, AUTHME_PASSWORD }

enum class ProfileClaimFailure {
    INVALID_ARGUMENT, PROFILE_CONFLICT, LEGACY_NOT_FOUND, LEGACY_UNAVAILABLE, NAME_TAKEN,
    GRANT_INVALID, GRANT_EXPIRED, GRANT_CONSUMED, SESSION_MISMATCH, SUBJECT_ALREADY_BOUND, TARGET_HAS_SESSION,
}

class ProfileClaimException(val reason: ProfileClaimFailure) : IllegalStateException("Profile claim refused: $reason")

/** Only AuthMe -> previously unbound Java Microsoft roles; no filesystem writes or Java UUID aliasing. */
class ProfileClaimRepository(
    private val database: Database,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onProfileChanged: (UUID) -> Unit = {},
) {
    private val random = SecureRandom()

    fun byId(id: UUID): LegacyProfile? = database.withConnection { legacy(it, "id", id) }
    fun byProfile(profileUuid: UUID): LegacyProfile? = database.withConnection { legacy(it, "profile_uuid", profileUuid) }
    fun listByLegacyName(name: String): List<LegacyProfile> = database.withConnection { connection ->
        connection.prepareStatement("SELECT * FROM ${Schema.LEGACY_PROFILE} WHERE LOWER(legacy_name) = ?").use { sql ->
            sql.setString(1, name.lowercase(java.util.Locale.ROOT))
            sql.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.legacy()) } }
        }
    }
    fun ownerByProfile(profileUuid: UUID): ProfileOwner? = database.withConnection { connection ->
        one(connection, "SELECT * FROM ${Schema.PROFILE_OWNER} WHERE profile_uuid = ?", profileUuid.toString()) { it.owner() }
    }
    fun ownerBySubject(kind: BoundIdentity.Kind, subject: String): ProfileOwner? = database.withConnection { connection ->
        one(connection, "SELECT * FROM ${Schema.PROFILE_OWNER} WHERE kind = ? AND subject = ?", kind.name, subject) { it.owner() }
    }

    fun resolveMicrosoftProfile(xuid: Xuid): UUID? {
        val owner = ownerBySubject(BoundIdentity.Kind.XUID, xuid.value.toString()) ?: return null
        val source = byProfile(owner.profileUuid)
        requireClaim(source?.status == LegacyProfile.Status.CLAIMED && source.claimedXuid == xuid, ProfileClaimFailure.PROFILE_CONFLICT)
        return owner.profileUuid
    }

    /** A read-only eligibility check, not a reservation or evidence of source-account ownership. */
    fun preflightLegacy(batch: String, name: String, profileUuid: UUID) {
        validateLegacy(batch, name, profileUuid)
        constrained { database.withConnection { checkLegacyImport(it, name, profileUuid) } }
    }

    fun importLegacy(batch: String, name: String, profileUuid: UUID): LegacyProfile {
        validateLegacy(batch, name, profileUuid)
        return constrained {
            database.transaction { connection ->
                checkLegacyImport(connection, name, profileUuid)
                val record = LegacyProfile(UUID.randomUUID(), batch, name, profileUuid, LegacyProfile.Status.AVAILABLE, "authme", null)
                execute(connection, "INSERT INTO ${Schema.LEGACY_PROFILE} (id,batch,legacy_name,name_key,profile_uuid,status,source,claimed_xuid) VALUES (?,?,?,?,?,?,?,?)",
                    record.id.toString(), batch, name, GameName.parseOrThrow(name).key, profileUuid.toString(), "AVAILABLE", "authme", null)
                record
            }
        }
    }

    /** Read-only classification for an automatically discovered, operator-selected frozen backup. */
    fun preflightLegacyBatch(batch: String, candidates: List<LegacyProfileCandidate>): List<LegacyBatchEntry> {
        require(candidates.size <= MAX_BATCH_CANDIDATES) { "Too many legacy profile candidates" }
        val normalized = normalizeBatchCandidates(batch, candidates)
        val duplicateKeys = duplicateKeys(normalized)
        val duplicateProfiles = normalized.filter { GameName.parseOrThrow(it.name).key !in duplicateKeys }
            .groupingBy { it.profileUuid }.eachCount().filterValues { it > 1 }.keys
        return constrained {
            database.withConnection { connection -> normalized.map { candidate -> classifyBatchCandidate(connection, batch, candidate, duplicateKeys, duplicateProfiles) } }
        }
    }

    /** Atomically imports all READY candidates; existing equal mappings remain idempotent. */
    fun importLegacyBatch(batch: String, candidates: List<LegacyProfileCandidate>): List<LegacyBatchEntry> {
        require(candidates.size <= MAX_BATCH_CANDIDATES) { "Too many legacy profile candidates" }
        val normalized = normalizeBatchCandidates(batch, candidates)
        val duplicateKeys = duplicateKeys(normalized)
        val duplicateProfiles = normalized.filter { GameName.parseOrThrow(it.name).key !in duplicateKeys }
            .groupingBy { it.profileUuid }.eachCount().filterValues { it > 1 }.keys
        return constrained {
            database.transaction { connection ->
                normalized.map { candidate ->
                    val entry = classifyBatchCandidate(connection, batch, candidate, duplicateKeys, duplicateProfiles)
                    when (entry.status) {
                        LegacyBatchStatus.READY -> {
                            val record = insertLegacy(connection, batch, candidate.name, candidate.profileUuid)
                            LegacyBatchEntry(candidate, LegacyBatchStatus.IMPORTED, record.id)
                        }
                        else -> entry
                    }
                }
            }
        }
    }

    private fun normalizeBatchCandidates(batch: String, candidates: List<LegacyProfileCandidate>): List<LegacyProfileCandidate> {
        require(batch.isNotBlank() && batch.length <= 128 && batch.none(Char::isISOControl))
        candidates.forEach { validateLegacy(batch, it.name, it.profileUuid) }
        return candidates
    }

    private fun duplicateKeys(candidates: List<LegacyProfileCandidate>): Set<String> = candidates.groupingBy {
        GameName.parseOrThrow(it.name).key
    }.eachCount().filterValues { it > 1 }.keys

    private fun classifyBatchCandidate(connection: Connection, batch: String, candidate: LegacyProfileCandidate,
                                       duplicateKeys: Set<String>, duplicateProfiles: Set<UUID>): LegacyBatchEntry {
        val key = GameName.parseOrThrow(candidate.name).key
        if (key in duplicateKeys) return LegacyBatchEntry(candidate, LegacyBatchStatus.DUPLICATE_NAME)
        val existingByName = one(connection, "SELECT * FROM ${Schema.LEGACY_PROFILE} WHERE name_key = ?", key) { it.legacy() }
        if (existingByName != null) {
            return if (existingByName.profileUuid == candidate.profileUuid && existingByName.batch == batch) {
                LegacyBatchEntry(candidate, LegacyBatchStatus.ALREADY_REGISTERED, existingByName.id)
            } else LegacyBatchEntry(candidate, LegacyBatchStatus.CONFLICT, existingByName.id)
        }
        if (candidate.profileUuid in duplicateProfiles ||
            exists(connection, "SELECT 1 FROM ${Schema.IDENTITY} WHERE player_uuid = ?", candidate.profileUuid.toString()) ||
            exists(connection, "SELECT 1 FROM ${Schema.PROFILE_OWNER} WHERE profile_uuid = ?", candidate.profileUuid.toString()) ||
            exists(connection, "SELECT 1 FROM ${Schema.LEGACY_PROFILE} WHERE profile_uuid = ?", candidate.profileUuid.toString())) {
            return LegacyBatchEntry(candidate, LegacyBatchStatus.CONFLICT)
        }
        return LegacyBatchEntry(candidate, LegacyBatchStatus.READY)
    }

    private fun insertLegacy(connection: Connection, batch: String, name: String, profileUuid: UUID): LegacyProfile {
        val record = LegacyProfile(UUID.randomUUID(), batch, name, profileUuid, LegacyProfile.Status.AVAILABLE, "authme", null)
        execute(connection, "INSERT INTO ${Schema.LEGACY_PROFILE} (id,batch,legacy_name,name_key,profile_uuid,status,source,claimed_xuid) VALUES (?,?,?,?,?,?,?,?)",
            record.id.toString(), batch, name, GameName.parseOrThrow(name).key, profileUuid.toString(), "AVAILABLE", "authme", null)
        return record
    }

    private fun validateLegacy(batch: String, name: String, profileUuid: UUID) {
        requireClaim(batch.isNotBlank() && batch.length <= 128 && batch.none(Char::isISOControl) &&
            GameName.parse(name) != null && profileUuid.version() == 3 && profileUuid.variant() == 2,
            ProfileClaimFailure.INVALID_ARGUMENT)
    }

    private fun checkLegacyImport(connection: Connection, name: String, profileUuid: UUID) {
        requireClaim(!exists(connection, "SELECT 1 FROM ${Schema.IDENTITY} WHERE player_uuid = ?", profileUuid.toString()) &&
            !exists(connection, "SELECT 1 FROM ${Schema.PROFILE_OWNER} WHERE profile_uuid = ?", profileUuid.toString()) &&
            !exists(connection, "SELECT 1 FROM ${Schema.LEGACY_PROFILE} WHERE name_key = ?", GameName.parseOrThrow(name).key) &&
            legacy(connection, "profile_uuid", profileUuid) == null, ProfileClaimFailure.PROFILE_CONFLICT)
    }

    /** Called only after an operator or the read-only verifier proves the old account for this exact session. */
    fun issue(recordId: UUID, sessionId: UUID, loginName: GameName, ttlMillis: Long = 600_000,
              proofMethod: LegacyProofMethod = LegacyProofMethod.MANUAL): Grant {
        requireClaim(sessionId != UUID(0, 0) && ttlMillis in 1..900_000, ProfileClaimFailure.INVALID_ARGUMENT)
        val now = nowMillis()
        val expiry = if (now > Long.MAX_VALUE - ttlMillis) Long.MAX_VALUE else now + ttlMillis
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        return constrained {
            database.transaction { connection ->
                val source = legacy(connection, "id", recordId) ?: refuse(ProfileClaimFailure.LEGACY_NOT_FOUND)
                requireClaim(source.status == LegacyProfile.Status.AVAILABLE, ProfileClaimFailure.LEGACY_UNAVAILABLE)
                requireClaim(!exists(connection, "SELECT 1 FROM ${Schema.NAME} WHERE name_key = ?", loginName.key), ProfileClaimFailure.NAME_TAKEN)
                execute(connection, "DELETE FROM ${Schema.CLAIM_GRANT} WHERE expires_at <= ?", now)
                val active = one(connection, "SELECT COUNT(*) FROM ${Schema.CLAIM_GRANT}") { it.getLong(1) } ?: 0
                requireClaim(active < 4096, ProfileClaimFailure.PROFILE_CONFLICT)
                execute(connection, "INSERT INTO ${Schema.CLAIM_GRANT} (token_hash,record_id,session_id,login_name,expires_at,consumed,proof_method) VALUES (?,?,?,?,?,?,?)",
                    hash(token), recordId.toString(), sessionId.toString(), loginName.value, expiry, 0L, proofMethod.name)
                Grant(token, recordId, sessionId, loginName, expiry)
            }
        }
    }

    /** Fresh Microsoft proof is supplied by the Gate, not established by this persistence module. */
    fun commit(token: String, sessionId: UUID, loginName: GameName, xuid: Xuid, gamertag: String,
               now: Long = nowMillis(), current: () -> Boolean = { true }): BoundIdentity {
        requireClaim(TOKEN.matches(token) && sessionId != UUID(0, 0) && xuid.value > 0 &&
            gamertag.isNotBlank() && gamertag.length <= 64 && gamertag.none(Char::isISOControl), ProfileClaimFailure.INVALID_ARGUMENT)
        val committed = constrained {
            database.transaction { connection ->
                requireClaim(current(), ProfileClaimFailure.SESSION_MISMATCH)
                val digest = hash(token)
                val (recordId, proofMethod) = one(connection, "SELECT * FROM ${Schema.CLAIM_GRANT} WHERE token_hash = ?", digest) { rows ->
                    requireClaim(rows.getLong("consumed") == 0L, ProfileClaimFailure.GRANT_CONSUMED)
                    requireClaim(rows.getString("session_id") == sessionId.toString() && rows.getString("login_name") == loginName.value,
                        ProfileClaimFailure.SESSION_MISMATCH)
                    requireClaim(now < rows.getLong("expires_at"), ProfileClaimFailure.GRANT_EXPIRED)
                    val method = LegacyProofMethod.entries.singleOrNull { it.name == rows.getString("proof_method") }
                        ?: refuse(ProfileClaimFailure.GRANT_INVALID)
                    UUID.fromString(rows.getString("record_id")) to method
                } ?: refuse(ProfileClaimFailure.GRANT_INVALID)
                val source = legacy(connection, "id", recordId) ?: refuse(ProfileClaimFailure.LEGACY_NOT_FOUND)
                requireClaim(source.status == LegacyProfile.Status.AVAILABLE, ProfileClaimFailure.LEGACY_UNAVAILABLE)
                val subject = xuid.value.toString()
                requireClaim(!exists(connection, "SELECT 1 FROM ${Schema.IDENTITY} WHERE kind = ? AND subject = ?", "XUID", subject) &&
                    !exists(connection, "SELECT 1 FROM ${Schema.PROFILE_OWNER} WHERE kind = ? AND subject = ?", "XUID", subject),
                    ProfileClaimFailure.SUBJECT_ALREADY_BOUND)
                requireClaim(!exists(connection, "SELECT 1 FROM ${Schema.NAME} WHERE name_key = ?", loginName.key), ProfileClaimFailure.NAME_TAKEN)
                requireClaim(!exists(connection, "SELECT 1 FROM ${Schema.IDENTITY} WHERE player_uuid = ?", source.profileUuid.toString()) &&
                    !exists(connection, "SELECT 1 FROM ${Schema.PROFILE_OWNER} WHERE profile_uuid = ?", source.profileUuid.toString()),
                    ProfileClaimFailure.PROFILE_CONFLICT)
                requireClaim(!exists(connection, "SELECT 1 FROM ${Schema.SESSION} WHERE player_uuid = ?", Identity.ofXuid(xuid).toString()),
                    ProfileClaimFailure.TARGET_HAS_SESSION)
                requireClaim(execute(connection, "UPDATE ${Schema.CLAIM_GRANT} SET consumed = 1 WHERE token_hash = ? AND consumed = 0 AND expires_at > ?",
                    digest, now) == 1, ProfileClaimFailure.GRANT_CONSUMED)
                requireClaim(execute(connection, "UPDATE ${Schema.LEGACY_PROFILE} SET status = 'CLAIMED', claimed_xuid = ? WHERE id = ? AND status = 'AVAILABLE'",
                    subject, source.id.toString()) == 1, ProfileClaimFailure.LEGACY_UNAVAILABLE)
                execute(connection, "INSERT INTO ${Schema.PROFILE_OWNER} (profile_uuid,kind,subject,version) VALUES (?,?,?,?)",
                    source.profileUuid.toString(), "XUID", subject, 1L)
                execute(connection, "DELETE FROM ${Schema.SESSION} WHERE player_uuid = ?", source.profileUuid.toString())
                val bound = BoundIdentity(source.profileUuid, loginName, BoundIdentity.Kind.XUID, subject, gamertag, now, now)
                execute(connection, "INSERT INTO ${Schema.IDENTITY} (player_uuid,kind,subject,gamertag,created_at,last_seen_at) VALUES (?,?,?,?,?,?)",
                    bound.uuid.toString(), "XUID", subject, gamertag, now, now)
                execute(connection, "INSERT INTO ${Schema.NAME} (name_key,display_name,player_uuid,claimed_at) VALUES (?,?,?,?)",
                    loginName.key, loginName.value, bound.uuid.toString(), now)
                execute(connection, "INSERT INTO ${Schema.PROFILE_CLAIM_AUDIT} (id,record_id,profile_uuid,kind,subject,owner_version,session_id,login_name,event_type,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), source.id.toString(), bound.uuid.toString(), "XUID", subject, 1L,
                    sessionId.toString(), loginName.value,
                    if (proofMethod == LegacyProofMethod.AUTHME_PASSWORD) "AUTHME_PASSWORD_CLAIM" else "MANUAL_AUTHME_CLAIM", now)
                requireClaim(current() && nowMillis() < (one(connection, "SELECT expires_at FROM ${Schema.CLAIM_GRANT} WHERE token_hash = ?", digest) {
                    it.getLong(1)
                } ?: 0L), ProfileClaimFailure.SESSION_MISMATCH)
                bound
            }
        }
        onProfileChanged(committed.uuid)
        return committed
    }

    private fun legacy(connection: Connection, column: String, uuid: UUID): LegacyProfile? =
        one(connection, "SELECT * FROM ${Schema.LEGACY_PROFILE} WHERE $column = ?", uuid.toString()) { it.legacy() }

    private fun ResultSet.legacy() = LegacyProfile(UUID.fromString(getString("id")), getString("batch"), getString("legacy_name"),
        UUID.fromString(getString("profile_uuid")), LegacyProfile.Status.valueOf(getString("status")), getString("source"),
        getString("claimed_xuid")?.let { Xuid(it.toLong()) })

    private fun ResultSet.owner() = ProfileOwner(UUID.fromString(getString("profile_uuid")),
        BoundIdentity.Kind.valueOf(getString("kind")), getString("subject"), getLong("version"))

    private fun execute(connection: Connection, sql: String, vararg values: Any?): Int =
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }

    private fun <T> one(connection: Connection, sql: String, vararg values: Any?, read: (ResultSet) -> T): T? =
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
        }

    private fun exists(connection: Connection, sql: String, vararg values: Any?): Boolean =
        one(connection, sql, *values) { true } == true

    private fun <T> constrained(action: () -> T): T = try { action() } catch (_: SQLException) {
        // Source bodies and JDBC messages must not expose grants or imported identity details.
        refuse(ProfileClaimFailure.PROFILE_CONFLICT)
    }

    private companion object {
        const val MAX_BATCH_CANDIDATES = 100_000
        val TOKEN = Regex("[A-Za-z0-9_-]{43}")
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
        fun refuse(reason: ProfileClaimFailure): Nothing = throw ProfileClaimException(reason)
        fun requireClaim(condition: Boolean, reason: ProfileClaimFailure) { if (!condition) refuse(reason) }
    }
}
