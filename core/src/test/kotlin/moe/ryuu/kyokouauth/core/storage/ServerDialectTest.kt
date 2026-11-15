package moe.ryuu.kyokouauth.core.storage

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The same rules as [DialectTest], against a real database server.
 *
 * The embedded backends prove the SQL is *consistent*; they cannot prove it is *correct* for MySQL
 * or PostgreSQL, whose DDL parsers and type systems differ from H2's emulation. Everything asserted
 * here is something a real server could reject: identifier length, `VARCHAR` sizing under utf8mb4,
 * `CONSTRAINT ... UNIQUE` syntax, `BIGINT` handling.
 *
 * ## Running it
 *
 * A server is described by one environment variable per dialect:
 *
 * ```
 * KYOKOU_TEST_POSTGRESQL=127.0.0.1:25432/kyokouauth_test
 * KYOKOU_TEST_MYSQL=127.0.0.1:3306/kyokouauth_test
 * KYOKOU_TEST_MARIADB=127.0.0.1:3306/kyokouauth_test
 * KYOKOU_TEST_USER=postgres
 * KYOKOU_TEST_PASSWORD=
 * ```
 *
 * A dialect without a variable is skipped rather than failed, so the suite stays usable on a machine
 * with no database at all - the same posture the live Microsoft tests take.
 */
class ServerDialectTest {

    private var database: Database? = null

    @BeforeEach
    fun requireConfigured() {
        // Handled per-dialect in the test body; this hook exists so a future global fixture has a home.
    }

    @AfterEach
    fun tearDown() {
        database?.close()
        database = null
    }

    @ParameterizedTest
    @EnumSource(value = Dialect::class, names = ["MYSQL", "MARIADB", "POSTGRESQL"])
    fun `the schema and its rules hold on a real server`(dialect: Dialect) {
        val descriptor = descriptorFor(dialect)
        Assumptions.assumeTrue(
            descriptor != null,
            "set KYOKOU_TEST_${dialect.name}=host:port/database to run this",
        )

        dropExistingSchema(descriptor!!)

        database = Database(descriptor)
        val repository = IdentityRepository(database!!)

        val first = repository.claim(
            Identity.Microsoft(GameName.parseOrThrow("Steve"), Xuid(1001L), "SteveGT"),
            1_000L,
        )
        val bound = assertIs<ClaimResult.Bound>(first, "${dialect.displayName} rejected a plain insert")
        assertEquals(UUID(0L, 1001L), bound.record.uuid)

        // Case-insensitive uniqueness: the primary key is the lowercased name, and a server that
        // compares case-sensitively here would let "steve" and "Steve" become two accounts.
        val duplicateName = repository.claim(
            Identity.Microsoft(GameName.parseOrThrow("steve"), Xuid(2002L), "Other"),
            2_000L,
        )
        assertIs<ClaimResult.NameTaken>(duplicateName, "${dialect.displayName} did not enforce name uniqueness")

        val secondName = repository.claim(
            Identity.Microsoft(GameName.parseOrThrow("Alex"), Xuid(1001L), "SteveGT"),
            3_000L,
        )
        assertIs<ClaimResult.IdentityAlreadyNamed>(secondName, "${dialect.displayName} allowed one identity to hold two names")

        assertEquals(bound.record.uuid, repository.byName(GameName.parseOrThrow("Steve"))!!.uuid)
        assertEquals(bound.record.uuid, repository.byUuid(bound.record.uuid)!!.uuid)
        assertEquals(
            bound.record.uuid,
            repository.bySubject(BoundIdentity.Kind.XUID, "1001")!!.uuid,
            "${dialect.displayName} could not read back by subject",
        )

        // A rename has to move the primary key, which is the row's only identity.
        val renamed = repository.rename(bound.record.uuid, GameName.parseOrThrow("Notch_"), 4_000L)
        assertIs<ClaimResult.Bound>(renamed, "${dialect.displayName} rejected a rename")
        assertNull(repository.byName(GameName.parseOrThrow("Steve")), "${dialect.displayName} kept the old name after a rename")

        repository.unbind(bound.record.uuid)
        assertNull(repository.byName(GameName.parseOrThrow("Notch_")), "${dialect.displayName} kept a name after unbind")
    }

    @ParameterizedTest
    @EnumSource(value = Dialect::class, names = ["MYSQL", "MARIADB", "POSTGRESQL"])
    fun `sessions survive a reconnect on a real server`(dialect: Dialect) {
        val descriptor = descriptorFor(dialect)
        Assumptions.assumeTrue(
            descriptor != null,
            "set KYOKOU_TEST_${dialect.name}=host:port/database to run this",
        )

        dropExistingSchema(descriptor!!)

        database = Database(descriptor)
        val repository = SessionRepository(database!!)
        val uuid = UUID(0L, 1001L)

        repository.put(PlaySession(uuid, "10.0.0.1", 1_000L, 5_000L))
        assertEquals("10.0.0.1", repository.find(uuid)!!.address)

        // A second connection from the same identity replaces the address, which is an UPDATE on a
        // primary key rather than an INSERT - the case a dialect is most likely to get wrong.
        repository.put(PlaySession(uuid, "10.0.0.2", 2_000L, 6_000L))
        assertEquals("10.0.0.2", repository.find(uuid)!!.address)

        assertEquals(1, repository.purgeExpired(10_000L))
        assertNull(repository.find(uuid))
    }

    /**
     * Empties the plugin's tables so a rerun starts clean.
     *
     * `DROP TABLE IF EXISTS` with no foreign keys between them, so order does not matter. Written as
     * raw SQL against the same connection string the test uses, because the point is to reach the
     * database regardless of whether the plugin's schema code succeeded on it.
     */
    private fun dropExistingSchema(descriptor: DatabaseDescriptor) {
        Class.forName(Database.driverClass(descriptor.dialect))
        DriverManager.getConnection(descriptor.jdbcUrl(), descriptor.username, descriptor.password).use { connection ->
            connection.createStatement().use { statement ->
                listOf(Schema.NAME, Schema.IDENTITY, Schema.SESSION, Schema.META).forEach { table ->
                    runCatching { statement.execute("DROP TABLE IF EXISTS $table") }
                }
            }
        }
    }

    private fun descriptorFor(dialect: Dialect): DatabaseDescriptor? {
        val key = dialect.name.lowercase()
        val spec = System.getProperty("kyokou.test.$key") ?: System.getenv("KYOKOU_TEST_${dialect.name}")
            ?: return null

        val hostPort = spec.substringBefore('/')
        val databaseName = spec.substringAfter('/', "kyokouauth_test")
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "").toIntOrNull()
            ?: error("kyokou.test.$key must be host:port/database, got '$spec'")

        return DatabaseDescriptor(
            dialect = dialect,
            host = host,
            port = port,
            database = databaseName,
            username = System.getProperty("kyokou.test.user") ?: System.getenv("KYOKOU_TEST_USER") ?: "",
            password = System.getProperty("kyokou.test.password") ?: System.getenv("KYOKOU_TEST_PASSWORD") ?: "",
        )
    }

    @Suppress("unused")
    private fun assertReachable(descriptor: DatabaseDescriptor) {
        Class.forName(Database.driverClass(descriptor.dialect))
        DriverManager.getConnection(descriptor.jdbcUrl(), descriptor.username, descriptor.password)
            .use { connection -> assertTrue(connection.isValid(2)) }
    }
}
