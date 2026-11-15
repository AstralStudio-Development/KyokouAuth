package moe.ryuu.kyokouauth.core.storage

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Runs the same rules against every embedded backend.
 *
 * The dialects do not differ in the SQL they need here, but they do differ in how strictly they
 * enforce it - H2 rejects some column types SQLite silently accepts, and PostgreSQL-flavoured H2
 * rejects a `SELECT` that names an unqualified ambiguous column. A schema that only ever runs on
 * SQLite is a schema whose portability claims are untested.
 *
 * MySQL, MariaDB and PostgreSQL are not covered here because they need a running server; their SQL
 * differs only in the dialect helpers in [Dialect], which the embedded backends exercise.
 */
class DialectTest {

    private lateinit var directory: File
    private var database: Database? = null

    @BeforeEach
    fun setUp() {
        directory = Files.createTempDirectory("kyokouauth-dialect").toFile()
    }

    @AfterEach
    fun tearDown() {
        database?.close()
        directory.deleteRecursively()
    }

    private fun open(dialect: Dialect): Database = Database(
        DatabaseDescriptor(
            dialect = dialect,
            database = File(directory, "kyokouauth-${dialect.name.lowercase()}").absolutePath,
        ),
    )

    @ParameterizedTest
    @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `the schema and its rules hold on every embedded backend`(dialect: Dialect) {
        database = open(dialect)
        val repository = IdentityRepository(database!!)

        val first = repository.claim(
            Identity.Microsoft(GameName.parseOrThrow("Steve"), Xuid(1001L), "SteveGT"),
            1_000L,
        )
        val bound = assertIs<ClaimResult.Bound>(first)
        assertEquals(UUID(0L, 1001L), bound.record.uuid)

        val duplicateName = repository.claim(
            Identity.Microsoft(GameName.parseOrThrow("steve"), Xuid(2002L), "Other"),
            2_000L,
        )
        assertIs<ClaimResult.NameTaken>(duplicateName)

        val secondName = repository.claim(
            Identity.Microsoft(GameName.parseOrThrow("Alex"), Xuid(1001L), "SteveGT"),
            3_000L,
        )
        assertIs<ClaimResult.IdentityAlreadyNamed>(secondName)

        assertEquals(bound.record.uuid, repository.byName(GameName.parseOrThrow("Steve"))!!.uuid)
        assertEquals(bound.record.uuid, repository.byUuid(bound.record.uuid)!!.uuid)

        repository.unbind(bound.record.uuid)
        assertNull(repository.byName(GameName.parseOrThrow("Steve")))
    }

    @ParameterizedTest
    @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `sessions survive a reopen on every embedded backend`(dialect: Dialect) {
        database = open(dialect)
        val repository = SessionRepository(database!!)
        val uuid = UUID(0L, 1001L)

        repository.put(PlaySession(uuid, "10.0.0.1", 1_000L, 5_000L))
        assertEquals("10.0.0.1", repository.find(uuid)!!.address)

        database!!.close()
        database = open(dialect)
        val reopened = SessionRepository(database!!)

        assertEquals("10.0.0.1", reopened.find(uuid)!!.address)
        assertEquals(1, reopened.purgeExpired(10_000L))
        assertNull(reopened.find(uuid))
    }
}
