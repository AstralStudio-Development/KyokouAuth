package moe.ryuu.kyokouauth.core.storage

import moe.ryuu.kyokouauth.core.identity.GameName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ProfileClaimBatchImportTest {
    @TempDir lateinit var directory: Path

    private fun open() = Database(DatabaseDescriptor(Dialect.SQLITE,
        database = directory.resolve("identities").toAbsolutePath().toString()))

    @Test
    fun `batch preflight classifies duplicate and existing records without writing`() {
        val old = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")
        val other = UUID.fromString("f66564fa-f35f-3ec0-8f67-f28f4ee58a44")
        open().use { db ->
            val claims = ProfileClaimRepository(db)
            claims.importLegacy("cutover", "Already", old)
            val preview = claims.preflightLegacyBatch("cutover", listOf(
                LegacyProfileCandidate("Ready", other),
                LegacyProfileCandidate("Already", old),
                LegacyProfileCandidate("Duplicate", old),
                LegacyProfileCandidate("duplicate", other),
            ))
            assertEquals(listOf(
                LegacyBatchStatus.READY,
                LegacyBatchStatus.ALREADY_REGISTERED,
                LegacyBatchStatus.DUPLICATE_NAME,
                LegacyBatchStatus.DUPLICATE_NAME,
            ), preview.map { it.status })
            assertTrue(claims.listByLegacyName("Ready").isEmpty())
            assertTrue(claims.listByLegacyName("Duplicate").isEmpty())
        }
    }

    @Test
    fun `two names pointing to the same profile are both excluded rather than picking the first`() {
        val old = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")
        open().use { db ->
            val claims = ProfileClaimRepository(db)
            val candidates = listOf(LegacyProfileCandidate("First", old), LegacyProfileCandidate("Second", old))
            assertTrue(claims.preflightLegacyBatch("cutover", candidates).all { it.status == LegacyBatchStatus.CONFLICT })
            assertTrue(claims.importLegacyBatch("cutover", candidates).all { it.status == LegacyBatchStatus.CONFLICT })
            assertNull(claims.byProfile(old))
        }
    }

    @Test
    fun `failed batch database write rolls the entire batch back`() {
        val old = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")
        val other = UUID.fromString("f66564fa-f35f-3ec0-8f67-f28f4ee58a44")
        open().use { db ->
            val claims = ProfileClaimRepository(db)
            db.withConnection { c -> c.createStatement().use { it.execute(
                "CREATE TRIGGER deny_second BEFORE INSERT ON kyokouauth_legacy_profile WHEN NEW.legacy_name = 'Second' BEGIN SELECT RAISE(ABORT, 'controlled failure'); END") } }
            assertFailsWith<ProfileClaimException> { claims.importLegacyBatch("cutover", listOf(
                LegacyProfileCandidate("First", old), LegacyProfileCandidate("Second", other))) }
            assertNull(claims.byProfile(old))
            assertNull(claims.byProfile(other))
        }
    }

    @Test
    fun `confirmed batch imports only safe candidates and is idempotent`() {
        val old = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")
        val other = UUID.fromString("f66564fa-f35f-3ec0-8f67-f28f4ee58a44")
        open().use { db ->
            val claims = ProfileClaimRepository(db)
            val candidates = listOf(LegacyProfileCandidate("First", old), LegacyProfileCandidate("Second", other))
            val imported = claims.importLegacyBatch("cutover", candidates)
            assertEquals(2, imported.count { it.status == LegacyBatchStatus.IMPORTED })
            assertEquals(2, claims.listByLegacyName("first").size + claims.listByLegacyName("second").size)
            val repeat = claims.importLegacyBatch("cutover", candidates)
            assertTrue(repeat.all { it.status == LegacyBatchStatus.ALREADY_REGISTERED })
            assertEquals(GameName.parseOrThrow("First").key, claims.byProfile(old)?.legacyName?.let { GameName.parseOrThrow(it).key })
        }
    }
}
