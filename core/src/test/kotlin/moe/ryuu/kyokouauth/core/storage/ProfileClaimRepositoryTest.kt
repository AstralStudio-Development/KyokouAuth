package moe.ryuu.kyokouauth.core.storage

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.MojangDirectory
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ProfileClaimRepositoryTest {
    @TempDir lateinit var directory: Path
    private val old = UUID.nameUUIDFromBytes("OfflinePlayer:OldSteve".toByteArray())
    private val name = GameName.parseOrThrow("NewSteve")
    private val xuid = Xuid(13579L)
    private val session = UUID.randomUUID()

    private fun open(dialect: Dialect) = Database(DatabaseDescriptor(dialect,
        database = directory.resolve("identities").toAbsolutePath().toString()))

    @ParameterizedTest @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `password-proven grants retain their distinct audit origin when committed`(dialect: Dialect) {
        open(dialect).use { db ->
            val claims = ProfileClaimRepository(db)
            val source = claims.importLegacy("authme-backup", "OldSteve", old)
            val grant = claims.issue(source.id, session, name, proofMethod = LegacyProofMethod.AUTHME_PASSWORD)
            val bound = claims.commit(grant.token, session, name, xuid, "SteveGT")
            assertEquals(old, bound.uuid)
            val event = db.withConnection { c -> c.createStatement().use { statement ->
                statement.executeQuery("SELECT event_type FROM kyokouauth_profile_claim_audit").use { rows ->
                    assertTrue(rows.next())
                    rows.getString(1).also { assertFalse(rows.next()) }
                }
            } }
            assertEquals("AUTHME_PASSWORD_CLAIM", event)
        }
    }

    @ParameterizedTest @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `claim preserves old profile across reload rename unbind and fresh proof`(dialect: Dialect) {
        var now = 1_000_000L
        open(dialect).use { db ->
            val claims = ProfileClaimRepository(db, { now })
            val source = claims.importLegacy("authme-backup", "OldSteve", old)
            assertEquals(LegacyProfile.Status.AVAILABLE, source.status)
            val grant = claims.issue(source.id, session, name)
            assertFalse(grant.toString().contains(grant.token))
            val claimed = claims.commit(grant.token, session, name, xuid, "SteveGT")
            assertEquals(old, claimed.uuid)
            assertEquals(old, claimed.toIdentity().uuid)
            assertEquals(xuid, claims.byId(source.id)?.claimedXuid)
            assertEquals(old, claims.resolveMicrosoftProfile(xuid))
            assertFailsWith<ProfileClaimException> { claims.commit(grant.token, session, name, xuid, "SteveGT") }
        }
        now += 1_000
        open(dialect).use { db ->
            val claims = ProfileClaimRepository(db, { now })
            val identities = IdentityRepository(db)
            val sessions = SessionRepository(db)
            val auth = AuthCoordinator(identities, sessions, clock = { now }, sessionMillis = 60_000,
                autoRenameMicrosoft = true, directory = { MojangDirectory.Lookup.Free },
                microsoftProfile = { claims.resolveMicrosoftProfile(it) ?: Identity.ofXuid(it) })
            assertEquals(old, claims.resolveMicrosoftProfile(xuid))
            auth.grantSession(old, "127.0.0.1")
            val admitted = assertIs<AuthCoordinator.Decision.Admit>(auth.decide(name, "127.0.0.1"))
            assertEquals(old, admitted.identity.uuid)
            assertEquals(old, assertIs<AuthCoordinator.ClaimOutcome.Bound>(auth.bindMicrosoft(
                GameName.parseOrThrow("Renamed"), xuid, "NewGT")).record.uuid)
            assertTrue(identities.unbind(old))
            assertNull(sessions.find(old))
            assertEquals(old, claims.resolveMicrosoftProfile(xuid))
            assertEquals(old, assertIs<AuthCoordinator.ClaimOutcome.Bound>(auth.bindMicrosoft(name, xuid, "SteveGT")).record.uuid)
            assertFails { identities.claim(Identity.Microsoft(name, xuid, "SteveGT"), now) }
            assertFails { identities.claim(Identity.Microsoft(GameName.parseOrThrow("Intruder"), Xuid(24680), "Other", old), now) }
        }
    }

    @ParameterizedTest @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `legacy import preflight is read only and checks the same conflicts as import`(dialect: Dialect) {
        open(dialect).use { db ->
            val claims = ProfileClaimRepository(db)
            // The historical UUID is supplied from trusted records, never derived from this display name.
            claims.preflightLegacy("backup", "ArchivedName", old)
            assertNull(claims.byProfile(old))
            assertTrue(claims.listByLegacyName("ArchivedName").isEmpty())
            assertNull(claims.ownerByProfile(old))
            assertTrue(IdentityRepository(db).all().isEmpty())
            assertFailsWith<ProfileClaimException> { claims.preflightLegacy("backup", "ArchivedName", UUID.randomUUID()) }
            val source = claims.importLegacy("backup", "ArchivedName", old)
            assertFailsWith<ProfileClaimException> { claims.preflightLegacy("backup", "ArchivedName", old) }
            assertEquals(source, claims.byProfile(old))
            val other = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")
            assertFailsWith<ProfileClaimException> { claims.preflightLegacy("backup2", "archivedname", other) }
            assertNull(claims.byProfile(other))
        }
    }

    @ParameterizedTest @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `one historical name cannot import several candidate profiles across batches or casing`(dialect: Dialect) {
        open(dialect).use { db ->
            val claims = ProfileClaimRepository(db)
            val source = claims.importLegacy("first-backup", "OldSteve", old)
            val otherProfile = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")
            val failure = assertFailsWith<ProfileClaimException> {
                claims.importLegacy("second-backup", "oldsteve", otherProfile)
            }
            assertEquals(ProfileClaimFailure.PROFILE_CONFLICT, failure.reason)
            assertEquals(listOf(source), claims.listByLegacyName("OLDSTEVE"))
            assertNull(claims.byProfile(otherProfile))
        }
    }

    @ParameterizedTest @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `wrong session wrong name expiry and conflicts never consume an available source`(dialect: Dialect) {
        var now = 100L
        open(dialect).use { db ->
            val claims = ProfileClaimRepository(db, { now })
            val source = claims.importLegacy("batch", "OldSteve", old)
            val grant = claims.issue(source.id, session, name, 100)
            assertFailsWith<ProfileClaimException> { claims.commit(grant.token, UUID.randomUUID(), name, xuid, "SteveGT") }
            assertFailsWith<ProfileClaimException> { claims.commit(grant.token, session, GameName.parseOrThrow("Other"), xuid, "SteveGT") }
            now = 200
            assertFailsWith<ProfileClaimException> { claims.commit(grant.token, session, name, xuid, "SteveGT") }
            assertEquals(LegacyProfile.Status.AVAILABLE, claims.byId(source.id)?.status)
            assertNull(claims.resolveMicrosoftProfile(xuid))
            assertFailsWith<ProfileClaimException> { claims.importLegacy("another", "OldSteve", old) }
            assertFailsWith<ProfileClaimException> { claims.importLegacy("batch", "Invalid name", UUID.randomUUID()) }
            val good = claims.issue(source.id, session, name)
            IdentityRepository(db).claim(Identity.Microsoft(name, Xuid(999), "Other"), now)
            assertFailsWith<ProfileClaimException> { claims.commit(good.token, session, name, xuid, "SteveGT") }
            assertEquals(LegacyProfile.Status.AVAILABLE, claims.byId(source.id)?.status)
            assertNull(claims.ownerByProfile(old))
        }
    }

    @ParameterizedTest @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `connection lost inside final transaction rolls back the claim`(dialect: Dialect) {
        open(dialect).use { db ->
            val claims = ProfileClaimRepository(db)
            val source = claims.importLegacy("batch", "OldSteve", old)
            val grant = claims.issue(source.id, session, name)
            var checks = 0
            assertFailsWith<ProfileClaimException> {
                claims.commit(grant.token, session, name, xuid, "SteveGT", current = { ++checks == 1 })
            }
            assertEquals(LegacyProfile.Status.AVAILABLE, claims.byId(source.id)?.status)
            assertNull(claims.ownerByProfile(old))
            assertTrue(IdentityRepository(db).all().isEmpty())
            assertEquals(old, claims.commit(grant.token, session, name, xuid, "SteveGT").uuid)
        }
    }

    @ParameterizedTest @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `parallel imports cannot reserve several profiles for the same historical name`(dialect: Dialect) {
        open(dialect).use { db ->
            val claims = ProfileClaimRepository(db)
            val other = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")
            val start = java.util.concurrent.CyclicBarrier(2)
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val jobs = listOf(old to "OldSteve", other to "oldsteve").mapIndexed { index, (profile, legacyName) ->
                    java.util.concurrent.Callable {
                        start.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        runCatching { claims.importLegacy("backup-$index", legacyName, profile) }.getOrNull()
                    }
                }
                val winners = executor.invokeAll(jobs).mapNotNull { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals(1, winners.size)
                assertEquals(winners, claims.listByLegacyName("OLDSTEVE"))
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

    @ParameterizedTest @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `parallel owners cannot consume the same old profile`(dialect: Dialect) {
        open(dialect).use { db ->
            val claims = ProfileClaimRepository(db)
            val source = claims.importLegacy("batch", "OldSteve", old)
            val session2 = UUID.randomUUID()
            val name2 = GameName.parseOrThrow("OtherSteve")
            val a = claims.issue(source.id, session, name)
            val b = claims.issue(source.id, session2, name2)
            val start = java.util.concurrent.CyclicBarrier(2)
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val jobs = listOf(java.util.concurrent.Callable {
                    start.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    runCatching { claims.commit(a.token, session, name, xuid, "SteveGT") }.getOrNull()
                }, java.util.concurrent.Callable {
                    start.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    runCatching { claims.commit(b.token, session2, name2, Xuid(67890L), "OtherGT") }.getOrNull()
                })
                val winners = executor.invokeAll(jobs).mapNotNull { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals(1, winners.size)
                assertEquals(old, winners.single().uuid)
                assertEquals(winners.single().subject, claims.ownerByProfile(old)?.subject)
                assertEquals(1, IdentityRepository(db).all().size)
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

    @org.junit.jupiter.api.Test
    fun `failed final audit write rolls back ownership grant consumption and binding together`() {
        open(Dialect.SQLITE).use { db ->
            val claims = ProfileClaimRepository(db)
            val source = claims.importLegacy("batch", "OldSteve", old)
            val grant = claims.issue(source.id, session, name)
            db.withConnection { c -> c.createStatement().use { it.execute(
                "CREATE TRIGGER deny_claim_audit BEFORE INSERT ON kyokouauth_profile_claim_audit BEGIN SELECT RAISE(ABORT, 'controlled audit failure'); END") } }
            assertFailsWith<ProfileClaimException> { claims.commit(grant.token, session, name, xuid, "SteveGT") }
            assertNull(claims.ownerByProfile(old))
            assertEquals(LegacyProfile.Status.AVAILABLE, claims.byId(source.id)?.status)
            assertTrue(IdentityRepository(db).all().isEmpty())
            db.withConnection { c -> c.createStatement().use { it.execute("DROP TRIGGER deny_claim_audit") } }
            assertEquals(old, claims.commit(grant.token, session, name, xuid, "SteveGT").uuid)
        }
    }

    @ParameterizedTest @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `an existing subject cannot take a second legacy profile`(dialect: Dialect) {
        open(dialect).use { db ->
            val claims = ProfileClaimRepository(db)
            val source = claims.importLegacy("batch", "OldSteve", old)
            val grant = claims.issue(source.id, session, name)
            IdentityRepository(db).claim(Identity.Microsoft(GameName.parseOrThrow("Already"), xuid, "SteveGT"), 10L)
            assertFailsWith<ProfileClaimException> { claims.commit(grant.token, session, name, xuid, "SteveGT") }
            assertEquals(LegacyProfile.Status.AVAILABLE, claims.byId(source.id)?.status)
            assertNull(claims.ownerByProfile(old))
        }
    }
}
