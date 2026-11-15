package moe.ryuu.kyokouauth.core.storage

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the schema against a real SQLite file.
 *
 * The point is not to test JDBC but to test the *rules*: the two unique constraints are what stops
 * two identities from sharing a name or one identity from holding two names, and a constraint that
 * does not actually fire in the database is a rule that only exists in a comment.
 */
class IdentityRepositoryTest {

    private lateinit var directory: File
    private lateinit var database: Database
    private lateinit var repository: IdentityRepository

    @BeforeEach
    fun setUp() {
        directory = Files.createTempDirectory("kyokouauth-test").toFile()
        database = Database(
            DatabaseDescriptor(
                dialect = Dialect.SQLITE,
                database = File(directory, "test.db").absolutePath,
            ),
        )
        repository = IdentityRepository(database)
    }

    @AfterEach
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    private fun microsoft(name: String, xuid: Long, gamertag: String = "gt") =
        Identity.Microsoft(GameName.parseOrThrow(name), Xuid(xuid), gamertag)

    @Test
    fun `a fresh xuid binds its name`() {
        val result = repository.claim(microsoft("Steve", 1001L), 1_000L)
        val bound = assertIs<ClaimResult.Bound>(result)

        assertEquals(GameName.parseOrThrow("Steve"), bound.record.name)
        assertEquals(UUID(0L, 1001L), bound.record.uuid)
        assertEquals(BoundIdentity.Kind.XUID, bound.record.kind)
        assertEquals("1001", bound.record.subject)
    }

    @Test
    fun `a name cannot be claimed by two identities`() {
        assertIs<ClaimResult.Bound>(repository.claim(microsoft("Steve", 1001L), 1_000L))

        val second = repository.claim(microsoft("Steve", 2002L), 2_000L)
        val taken = assertIs<ClaimResult.NameTaken>(second)
        assertEquals(UUID(0L, 1001L), taken.by.uuid)
    }

    @Test
    fun `name matching is case insensitive`() {
        assertIs<ClaimResult.Bound>(repository.claim(microsoft("Steve", 1001L), 1_000L))

        val second = repository.claim(microsoft("stEvE", 2002L), 2_000L)
        assertIs<ClaimResult.NameTaken>(second)
    }

    @Test
    fun `an identity cannot hold two different names`() {
        assertIs<ClaimResult.Bound>(repository.claim(microsoft("Steve", 1001L), 1_000L))

        val second = repository.claim(microsoft("Alex", 1001L), 2_000L)
        val blocked = assertIs<ClaimResult.IdentityAlreadyNamed>(second)
        assertEquals(GameName.parseOrThrow("Steve"), blocked.existing.name)
    }

    @Test
    fun `re-claiming the same name is idempotent`() {
        assertIs<ClaimResult.Bound>(repository.claim(microsoft("Steve", 1001L), 1_000L))
        val again = repository.claim(microsoft("Steve", 1001L), 5_000L)

        val bound = assertIs<ClaimResult.Bound>(again)
        assertEquals(1_000L, bound.record.createdAt)
        assertEquals(5_000L, bound.record.lastSeenAt)
    }

    @Test
    fun `a mojang uuid and an xuid never collide`() {
        // A real Mojang UUID is v3 or v4, while UUID(0, xuid) has version 0 - the version bits alone
        // make the two namespaces disjoint, which is what lets both kinds of identity share one
        // table keyed on player_uuid.
        val mojangUuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
        val premium = repository.claim(
            Identity.Premium(GameName.parseOrThrow("Notch"), mojangUuid),
            1_000L,
        )
        assertIs<ClaimResult.Bound>(premium)

        val microsoftClaim = repository.claim(microsoft("Herobrine", 1001L), 2_000L)
        assertIs<ClaimResult.Bound>(microsoftClaim)

        assertNotEquals(
            repository.byName(GameName.parseOrThrow("Notch"))!!.uuid,
            repository.byName(GameName.parseOrThrow("Herobrine"))!!.uuid,
        )
        assertEquals(0, Identity.ofXuid(Xuid(1001L)).version())
        assertEquals(4, mojangUuid.version())
    }

    @Test
    fun `a derived uuid can never equal a real mojang uuid`() {
        // The derivation is only safe because of the version bits. If this ever fails, one table keyed
        // on player_uuid can no longer hold both kinds of identity.
        repeat(1_000) { index ->
            val derived = Identity.ofXuid(Xuid(index.toLong()))
            assertEquals(0, derived.version(), "derived UUID for xuid $index lost its version bits")
            assertEquals(0, derived.variant(), "derived UUID for xuid $index lost its variant bits")
        }
    }

    @Test
    fun `unbind removes the name and the identity`() {
        val bound = assertIs<ClaimResult.Bound>(repository.claim(microsoft("Steve", 1001L), 1_000L))

        assertTrue(repository.unbind(bound.record.uuid))
        assertNull(repository.byName(GameName.parseOrThrow("Steve")))
        assertNull(repository.byUuid(bound.record.uuid))
    }

    @Test
    fun `rename keeps the uuid and frees the old name`() {
        val bound = assertIs<ClaimResult.Bound>(repository.claim(microsoft("Steve", 1001L), 1_000L))
        val renamed = repository.rename(bound.record.uuid, GameName.parseOrThrow("Alex"), 2_000L)

        val result = assertIs<ClaimResult.Bound>(renamed)
        assertEquals(bound.record.uuid, result.record.uuid)
        assertNull(repository.byName(GameName.parseOrThrow("Steve")))
        assertEquals(result.record.uuid, repository.byName(GameName.parseOrThrow("Alex"))!!.uuid)
    }

    @Test
    fun `rename refuses a name that belongs to someone else`() {
        repository.claim(microsoft("Steve", 1001L), 1_000L)
        val other = assertIs<ClaimResult.Bound>(repository.claim(microsoft("Alex", 2002L), 1_000L))

        val result = repository.rename(other.record.uuid, GameName.parseOrThrow("Steve"), 2_000L)
        assertIs<ClaimResult.NameTaken>(result)
    }

    @Test
    fun `schema migration is idempotent across reopens`() {
        repository.claim(microsoft("Steve", 1001L), 1_000L)
        database.close()

        // Re-opening runs the migration path again; it must not try to recreate the tables.
        database = Database(
            DatabaseDescriptor(
                dialect = Dialect.SQLITE,
                database = File(directory, "test.db").absolutePath,
            ),
        )
        repository = IdentityRepository(database)

        assertEquals(GameName.parseOrThrow("Steve"), repository.byName(GameName.parseOrThrow("Steve"))!!.name)
    }
}

class GameNameTest {

    @Test
    fun `accepts the vanilla alphabet`() {
        assertEquals("Steve_123", GameName.parse("Steve_123")!!.value)
    }

    @Test
    fun `rejects names outside 3-16 characters`() {
        assertNull(GameName.parse("ab"))
        assertNull(GameName.parse("a".repeat(17)))
        assertEquals(16, GameName.parse("a".repeat(16))!!.value.length)
    }

    @Test
    fun `rejects characters a vanilla client cannot send`() {
        assertNull(GameName.parse("steve "))
        assertNull(GameName.parse("Steve-123"))
        assertNull(GameName.parse("史蒂夫"))
    }

    @Test
    fun `key ignores case but value preserves it`() {
        val name = GameName.parse("StEvE")!!
        assertEquals("steve", name.key)
        assertEquals("StEvE", name.value)
    }

    @Test
    fun `parseOrThrow refuses invalid input`() {
        assertThrows<IllegalArgumentException> { GameName.parseOrThrow("no!") }
    }
}
