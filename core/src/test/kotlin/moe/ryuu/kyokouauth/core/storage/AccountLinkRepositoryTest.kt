package moe.ryuu.kyokouauth.core.storage

import moe.ryuu.kyokouauth.core.auth.AccountLinkResult
import moe.ryuu.kyokouauth.core.auth.RoleResolution
import moe.ryuu.kyokouauth.core.auth.RoleResolver
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountLinkRepositoryTest {
    private lateinit var directory: File
    private var database: Database? = null
    private val xuid = Xuid(1001L)
    private val javaUuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private val changedJavaUuid = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6")

    @BeforeEach
    fun setUp() {
        // Root Gradle config supplies an absolute workspace .narrafork/test-tmp/core path;
        // the test JVM itself runs from core/, so never resolve .narrafork against its cwd.
        val parent = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")))
        directory = Files.createTempDirectory(parent, "role-link-test-").toFile()
    }

    @AfterEach
    fun tearDown() {
        database?.close()
        directory.deleteRecursively()
    }

    private fun descriptor(dialect: Dialect) = DatabaseDescriptor(dialect, database = File(directory, "links").absolutePath)
    private fun open(dialect: Dialect): Database = Database(descriptor(dialect)).also { database = it }
    private fun link(xuid: Xuid = this.xuid, uuid: UUID = javaUuid, time: Long = 100L) =
        VerifiedAccountLink(xuid, uuid, "floodgate-global", "global", time)

    @ParameterizedTest
    @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `reverification can change Java role without changing or granting Microsoft identities`(dialect: Dialect) {
        val db = open(dialect)
        val identities = IdentityRepository(db)
        val microsoft = assertIs<ClaimResult.Bound>(identities.claim(Identity.Microsoft(GameName.parseOrThrow("OfflineRole"), xuid, "Xbox"), 1L)).record
        val repository = AccountLinkRepository(db)
        assertNull(repository.find(xuid))
        repository.recordVerified(link())
        repository.recordVerified(link())
        assertEquals(link(), repository.find(xuid))
        repository.recordVerified(link(uuid = changedJavaUuid, time = 200L))
        repository.recordVerified(link(time = 99L))
        assertEquals(link(uuid = changedJavaUuid, time = 200L), repository.find(xuid))
        assertEquals(listOf(microsoft), identities.all())
        assertEquals(1, assertIs<RoleResolution.Ready>(RoleResolver(identities).resolve(xuid, "Xbox", AccountLinkResult.NotLinked)).roles.size)
        assertIs<RoleResolution.Failed>(RoleResolver(identities).resolve(xuid, "Xbox", AccountLinkResult.Unavailable("retry")))
        assertTrue(repository.revoke(xuid))
        assertFalse(repository.revoke(xuid))
        assertNull(repository.find(xuid))
        assertEquals(listOf(microsoft), identities.all())
    }

    @ParameterizedTest
    @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `an older not linked observation cannot revoke a more recently verified mapping`(dialect: Dialect) {
        val repository = AccountLinkRepository(open(dialect))
        repository.recordVerified(link(time = 100L))
        repository.recordVerified(link(uuid = changedJavaUuid, time = 200L))
        assertFalse(repository.revoke(xuid, notAfter = 150L))
        assertEquals(link(uuid = changedJavaUuid, time = 200L), repository.find(xuid))
        assertTrue(repository.revoke(xuid, notAfter = 200L))
        assertNull(repository.find(xuid))
        assertFalse(repository.revoke(xuid, notAfter = 200L))
    }

    @ParameterizedTest
    @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `several XUID evidence rows for one Java UUID never grant each others Microsoft role`(dialect: Dialect) {
        val db = open(dialect)
        val repository = AccountLinkRepository(db)
        repository.recordVerified(link())
        repository.recordVerified(link(xuid = Xuid(2002L)))
        assertEquals(javaUuid, repository.find(xuid)?.javaUuid)
        assertEquals(javaUuid, repository.find(Xuid(2002L))?.javaUuid)
        val identities = IdentityRepository(db)
        identities.claim(Identity.Microsoft(GameName.parseOrThrow("FirstRole"), xuid, "One"), 1L)
        identities.claim(Identity.Microsoft(GameName.parseOrThrow("OtherRole"), Xuid(2002L), "Two"), 1L)
        val ready = assertIs<RoleResolution.Ready>(RoleResolver(identities).resolve(xuid, "One", AccountLinkResult.NotLinked))
        assertEquals(listOf(UUID(0L, 1001L)), ready.roles.map { it.identity.uuid })
    }

    @ParameterizedTest
    @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `v1 upgrade preserves identities names and sessions and installs independent v3 table`(dialect: Dialect) {
        seedLegacy(dialect, 1)
        assertLegacyUnchanged(dialect, hasOldLink = false)
    }

    @ParameterizedTest
    @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `v2 upgrade leaves old links intact but never imports their authorization`(dialect: Dialect) {
        seedLegacy(dialect, 2)
        assertLegacyUnchanged(dialect, hasOldLink = true)
    }

    @ParameterizedTest
    @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `database uniqueness rejects duplicate XUID and rolls back earlier writes`(dialect: Dialect) {
        val db = open(dialect)
        val repository = AccountLinkRepository(db)
        repository.recordVerified(link())
        assertFailsWith<SQLException> {
            db.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("UPDATE kyokouauth_account_link SET java_uuid = '$changedJavaUuid' WHERE xuid = '1001'")
                    statement.executeUpdate("INSERT INTO kyokouauth_account_link (xuid, java_uuid, source, scope, verified_at) VALUES ('1001', '$changedJavaUuid', 'floodgate-global', 'global', 200)")
                }
            }
        }
        assertEquals(link(), repository.find(xuid))
        db.close()
        database = null
        assertEquals(link(), AccountLinkRepository(open(dialect)).find(xuid))
    }

    @ParameterizedTest
    @EnumSource(value = Dialect::class, names = ["SQLITE", "H2"])
    fun `concurrent embedded verifications retain the newest complete mapping`(dialect: Dialect) {
        val repository = AccountLinkRepository(open(dialect))
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.invokeAll(listOf(
                Callable { repository.recordVerified(link(time = 100L)) },
                Callable { repository.recordVerified(link(uuid = changedJavaUuid, time = 200L)) },
            )).forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(link(uuid = changedJavaUuid, time = 200L), repository.find(xuid))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun seedLegacy(dialect: Dialect, version: Int) {
        Class.forName(Database.driverClass(dialect))
        DriverManager.getConnection(descriptor(dialect).jdbcUrl()).use { connection ->
            connection.createStatement().use { statement ->
                // Literal shipped-schema fixtures, independent from the current migration list.
                statement.execute("CREATE TABLE kyokouauth_meta (meta_key VARCHAR(64) PRIMARY KEY, meta_value VARCHAR(255))")
                statement.execute("CREATE TABLE kyokouauth_identity (player_uuid VARCHAR(36) PRIMARY KEY, kind VARCHAR(16), subject VARCHAR(64), gamertag VARCHAR(64), created_at BIGINT, last_seen_at BIGINT, UNIQUE (kind, subject))")
                statement.execute("CREATE TABLE kyokouauth_name (name_key VARCHAR(16) PRIMARY KEY, display_name VARCHAR(16), player_uuid VARCHAR(36) UNIQUE, claimed_at BIGINT)")
                statement.execute("CREATE TABLE kyokouauth_session (player_uuid VARCHAR(36) PRIMARY KEY, address VARCHAR(45), issued_at BIGINT, expires_at BIGINT)")
                statement.execute("INSERT INTO kyokouauth_meta VALUES ('schema_version', '$version')")
                statement.execute("INSERT INTO kyokouauth_identity VALUES ('00000000-0000-0000-0000-0000000003e9', 'XUID', '1001', 'OldTag', 12, 34)")
                statement.execute("INSERT INTO kyokouauth_name VALUES ('oldrole', 'OldRole', '00000000-0000-0000-0000-0000000003e9', 12)")
                statement.execute("INSERT INTO kyokouauth_session VALUES ('00000000-0000-0000-0000-0000000003e9', '127.0.0.1', 23, 45)")
                if (version == 2) {
                    statement.execute("CREATE TABLE kyokouauth_bedrock_link (bedrock_uuid VARCHAR(36) PRIMARY KEY, java_uuid VARCHAR(36), java_name VARCHAR(16), linked_at BIGINT)")
                    statement.execute("INSERT INTO kyokouauth_bedrock_link VALUES ('00000000-0000-0000-0000-0000000003e9', '$javaUuid', 'Notch', 67)")
                }
            }
        }
    }

    private fun assertLegacyUnchanged(dialect: Dialect, hasOldLink: Boolean) {
        val migrations = mutableListOf<Int>()
        val db = Database(descriptor(dialect), onMigrated = migrations::add).also { database = it }
        assertEquals(if (hasOldLink) listOf(3) else listOf(2, 3), migrations)
        val identities = IdentityRepository(db)
        val bound = BoundIdentity(UUID(0L, 1001L), GameName.parseOrThrow("OldRole"), BoundIdentity.Kind.XUID, "1001", "OldTag", 12L, 34L)
        assertEquals(bound, identities.byUuid(bound.uuid))
        assertEquals(bound, identities.byName(GameName.parseOrThrow("OldRole")))
        assertEquals(PlaySession(bound.uuid, "127.0.0.1", 23L, 45L), SessionRepository(db).find(bound.uuid))
        assertNull(AccountLinkRepository(db).find(xuid))
        val ready = assertIs<RoleResolution.Ready>(RoleResolver(identities).resolve(xuid, "Tag", AccountLinkResult.NotLinked))
        assertEquals(listOf(bound.uuid), ready.roles.map { it.identity.uuid })
        if (hasOldLink) db.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT bedrock_uuid, java_uuid, java_name, linked_at FROM kyokouauth_bedrock_link").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("00000000-0000-0000-0000-0000000003e9", rows.getString(1))
                    assertEquals(javaUuid.toString(), rows.getString(2))
                    assertEquals("Notch", rows.getString(3))
                    assertEquals(67L, rows.getLong(4))
                    assertFalse(rows.next())
                }
            }
        }
    }
}
