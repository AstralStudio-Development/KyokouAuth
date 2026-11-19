package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.authme.AuthMeVerification
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.storage.Database
import moe.ryuu.kyokouauth.core.storage.DatabaseDescriptor
import moe.ryuu.kyokouauth.core.storage.Dialect
import moe.ryuu.kyokouauth.core.storage.ProfileClaimRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class AuthMeInboxMigrationTest {
    @TempDir lateinit var root: Path
    private val old = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")
    private val name = GameName.parseOrThrow("ArchivedAlice")
    private val hash = "\$SHA\$11aa0706173d7272\$dbba96681c2ae4e0bfdf226d70fbbc5e4ee3d8071faa613bc533fe8a64817d10"

    @Test
    fun `fixed inbox creates a local snapshot and claims survive removing the old source and restarting`() {
        val migrationRoot = root.resolve("migration")
        val oldDatabase = prepareInbox(migrationRoot)
        val sourceBefore = Files.readAllBytes(oldDatabase)
        var enabled = false
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            val claims = ProfileClaimRepository(db)
            AuthMeInboxMigration(migrationRoot, claims, { null }, { enabled = it }).use { migration ->
                val preview = migration.scan().get(10, TimeUnit.SECONDS)
                assertEquals(1, preview.report.ready)
                assertEquals(1, migration.status().processed)
                assertFalse(migration.available())
                assertFalse(enabled)
                assertNull(claims.byProfile(old))
                assertContentEquals(sourceBefore, Files.readAllBytes(oldDatabase))
                Files.delete(oldDatabase)
                Files.delete(oldDatabase.parent.resolve("config.yml"))
                assertEquals(1, migration.confirm(preview.token).get(10, TimeUnit.SECONDS).imported)
                assertTrue(enabled)
                assertTrue(migration.available())
                assertEquals(AuthMeVerification.VERIFIED, migration.verify(name, old, "password".toCharArray()) { true })
                assertEquals(AuthMeVerification.REJECTED, migration.verify(name, UUID.randomUUID(), "password".toCharArray()) { true })
                assertNull(claims.ownerByProfile(old))
            }
            enabled = false
            AuthMeInboxMigration(migrationRoot, claims, { dialect ->
                assertEquals(Dialect.SQLITE, dialect, "Restart must never prepare a remote source driver")
                null
            }, { enabled = it }).use { restored ->
                assertTrue(enabled)
                assertTrue(restored.available())
                assertEquals(AuthMeVerification.VERIFIED, restored.verify(name, old, "password".toCharArray()) { true })
            }
        }
    }

    @Test
    fun `disabled local migration stays disabled after restart even if the snapshot was removed`() {
        val migrationRoot = root.resolve("migration")
        prepareInbox(migrationRoot)
        var enabled = false
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            val claims = ProfileClaimRepository(db)
            AuthMeInboxMigration(migrationRoot, claims, { null }, { enabled = it }).use { migration ->
                val preview = migration.scan().get(10, TimeUnit.SECONDS)
                migration.confirm(preview.token).get(10, TimeUnit.SECONDS)
                migration.disable().get(10, TimeUnit.SECONDS)
                assertFalse(enabled)
                assertFalse(migration.available())
                val password = "password".toCharArray()
                assertEquals(AuthMeVerification.UNAVAILABLE, migration.verify(name, old, password) { true })
                assertTrue(password.all { it == '\u0000' })
            }
            Files.list(migrationRoot.resolve("snapshots")).use { paths -> paths.forEach { directory ->
                Files.delete(directory.resolve("authme.db"))
            } }
            AuthMeInboxMigration(migrationRoot, claims, { error("Disabled snapshots must not prepare a driver") }, { enabled = it }).use { restored ->
                assertFalse(enabled)
                assertTrue(restored.hasLocalState())
                assertFalse(restored.available())
                assertNotNull(claims.byProfile(old))
            }
        }
    }

    @Test
    fun `only one background scan runs and cancellation preserves the active snapshot`() {
        val migrationRoot = root.resolve("migration")
        prepareInbox(migrationRoot)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val block = java.util.concurrent.atomic.AtomicBoolean(false)
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            val claims = ProfileClaimRepository(db)
            AuthMeInboxMigration(migrationRoot, claims, {
                if (block.get()) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                null
            }, {}).use { migration ->
                val preview = migration.scan().get(10, TimeUnit.SECONDS)
                migration.confirm(preview.token).get(10, TimeUnit.SECONDS)
                block.set(true)
                val pending = migration.scan()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                try {
                    assertEquals(AuthMeImportPhase.SCANNING_SOURCE, migration.status().phase)
                    val overload = assertFailsWith<java.util.concurrent.ExecutionException> { migration.scan().get(1, TimeUnit.SECONDS) }
                    assertIs<java.util.concurrent.RejectedExecutionException>(overload.cause)
                    assertTrue(migration.cancel())
                } finally { release.countDown() }
                assertFails { pending.get(10, TimeUnit.SECONDS) }
                assertEquals(AuthMeImportPhase.CANCELLED, migration.status().phase)
                assertTrue(migration.available())
                assertEquals(AuthMeVerification.VERIFIED, migration.verify(name, old, "password".toCharArray()) { true })
                Files.list(migrationRoot.resolve("staging")).use { assertEquals(0L, it.count()) }
            }
        }
    }

    @Test
    fun `failed rescan keeps the active local proof and never reconnects during claims`() {
        val migrationRoot = root.resolve("migration")
        val source = prepareInbox(migrationRoot)
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            AuthMeInboxMigration(migrationRoot, ProfileClaimRepository(db), { null }, {}).use { migration ->
                val preview = migration.scan().get(10, TimeUnit.SECONDS)
                migration.confirm(preview.token).get(10, TimeUnit.SECONDS)
                Files.delete(source)
                assertFails { migration.scan().get(10, TimeUnit.SECONDS) }
                assertEquals(AuthMeImportPhase.FAILED, migration.status().phase)
                assertTrue(migration.status().active)
                assertEquals(AuthMeVerification.VERIFIED, migration.verify(name, old, "password".toCharArray()) { true })
                Files.list(migrationRoot.resolve("staging")).use { assertEquals(0L, it.count()) }
            }
        }
    }

    @Test
    fun `new snapshot cannot authenticate a formerly available profile excluded by the new source`() {
        val migrationRoot = root.resolve("migration")
        val source = prepareInbox(migrationRoot)
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            val claims = ProfileClaimRepository(db)
            AuthMeInboxMigration(migrationRoot, claims, { null }, {}).use { migration ->
                val first = migration.scan().get(10, TimeUnit.SECONDS)
                migration.confirm(first.token).get(10, TimeUnit.SECONDS)
                assertEquals(AuthMeVerification.VERIFIED, migration.verify(name, old, "password".toCharArray()) { true })
                DriverManager.getConnection("jdbc:sqlite:$source").use { c -> c.createStatement().use {
                    it.execute("UPDATE old_accounts SET mfa = 'TOTP_PRIVATE_SENTINEL'")
                } }
                val replacement = migration.scan().get(10, TimeUnit.SECONDS)
                assertEquals("TOTP_REQUIRED", replacement.report.issues.single().reason)
                migration.confirm(replacement.token).get(10, TimeUnit.SECONDS)
                assertNotNull(claims.byProfile(old))
                assertEquals(AuthMeVerification.REJECTED, migration.verify(name, old, "password".toCharArray()) { true })
                Files.list(migrationRoot.resolve("snapshots")).use { paths -> paths.forEach { directory ->
                    for (file in listOf("authme.db", "manifest.properties", "profiles.properties")) {
                        val contents = Files.readAllBytes(directory.resolve(file)).toString(Charsets.ISO_8859_1)
                        assertFalse(contents.contains("TOTP_PRIVATE_SENTINEL"))
                        assertFalse(contents.contains("DATABASE_PRIVATE_SENTINEL"))
                    }
                } }
            }
        }
    }

    @Test
    fun `slow claim grant does not block submitting scans or cancelling an unconfirmed preview`() {
        val migrationRoot = root.resolve("migration")
        prepareInbox(migrationRoot)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
                AuthMeInboxMigration(migrationRoot, ProfileClaimRepository(db), { null }, {}).use { migration ->
                    val first = migration.scan().get(10, TimeUnit.SECONDS)
                    migration.confirm(first.token).get(10, TimeUnit.SECONDS)
                    val proof = workers.submit(java.util.concurrent.Callable {
                        migration.verifyAndUse(name, old, "password".toCharArray(), { true }) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            "granted"
                        }
                    })
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    try {
                        val submission = workers.submit(java.util.concurrent.Callable { migration.scan() })
                        val scan = submission.get(1, TimeUnit.SECONDS)
                        val preview = scan.get(5, TimeUnit.SECONDS)
                        assertTrue(workers.submit(java.util.concurrent.Callable { migration.cancel() }).get(1, TimeUnit.SECONDS))
                        assertNull(migration.report())
                        assertFails { migration.confirm(preview.token).get(5, TimeUnit.SECONDS) }
                        assertTrue(migration.available())
                    } finally { release.countDown() }
                    assertEquals("granted", proof.get(5, TimeUnit.SECONDS))
                }
            }
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `expired preview is revoked and its staged password material is reclaimed without replacing active data`() {
        val migrationRoot = root.resolve("migration")
        prepareInbox(migrationRoot)
        var now = 1_000L
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            AuthMeInboxMigration(migrationRoot, ProfileClaimRepository(db), { null }, {}, { now }).use { migration ->
                val first = migration.scan().get(10, TimeUnit.SECONDS)
                migration.confirm(first.token).get(10, TimeUnit.SECONDS)
                val expiring = migration.scan().get(10, TimeUnit.SECONDS)
                now = expiring.expiresAt
                assertNull(migration.report())
                assertEquals("EXPIRED", migration.status().phase.name)
                assertFalse(migration.cancel(), "An already expired preview must no longer be cancellable or confirmable")
                assertFails { migration.confirm(expiring.token).get(5, TimeUnit.SECONDS) }
                Files.list(migrationRoot.resolve("staging")).use { assertEquals(0L, it.count()) }
                assertTrue(migration.available())
                assertEquals(AuthMeVerification.VERIFIED, migration.verify(name, old, "password".toCharArray()) { true })
            }
        }
    }

    @Test
    fun `disable waits for an existing grant but cannot be cancelled as if it were a scan`() {
        val migrationRoot = root.resolve("migration")
        prepareInbox(migrationRoot)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
                AuthMeInboxMigration(migrationRoot, ProfileClaimRepository(db), { null }, {}).use { migration ->
                    val first = migration.scan().get(10, TimeUnit.SECONDS)
                    migration.confirm(first.token).get(10, TimeUnit.SECONDS)
                    val grant = worker.submit(java.util.concurrent.Callable {
                        migration.verifyAndUse(name, old, "password".toCharArray(), { true }) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            "existing-grant"
                        }
                    })
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val disabled = migration.disable()
                    try {
                        assertFalse(disabled.isDone)
                        assertEquals(AuthMeImportPhase.DISABLING, migration.status().phase)
                        assertFalse(migration.cancel())
                    } finally { release.countDown() }
                    assertEquals("existing-grant", grant.get(5, TimeUnit.SECONDS))
                    disabled.get(5, TimeUnit.SECONDS)
                    assertFalse(migration.available())
                    var newGrants = 0
                    assertNull(migration.verifyAndUse(name, old, "password".toCharArray(), { true }) { ++newGrants })
                    assertEquals(0, newGrants)
                }
            }
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `independent proven claims can enter their grant callbacks concurrently`() {
        val migrationRoot = root.resolve("migration")
        prepareInbox(migrationRoot)
        val entered = java.util.concurrent.CountDownLatch(2)
        val release = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
                AuthMeInboxMigration(migrationRoot, ProfileClaimRepository(db), { null }, {}).use { migration ->
                    val first = migration.scan().get(10, TimeUnit.SECONDS)
                    migration.confirm(first.token).get(10, TimeUnit.SECONDS)
                    val proofs = (1..2).map { id -> workers.submit(java.util.concurrent.Callable {
                        migration.verifyAndUse(name, old, "password".toCharArray(), { true }) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            id
                        }
                    }) }
                    try { assertTrue(entered.await(3, TimeUnit.SECONDS), "Unrelated claim writes must not be globally serialized") }
                    finally { release.countDown() }
                    assertEquals(listOf(1, 2), proofs.map { it.get(5, TimeUnit.SECONDS) })
                }
            }
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `closing a ready preview cancels its expiry timer and cleans its private stage promptly`() {
        val migrationRoot = root.resolve("migration")
        val source = prepareInbox(migrationRoot)
        val before = Files.readAllBytes(source)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
                val migration = AuthMeInboxMigration(migrationRoot, ProfileClaimRepository(db), { null }, {})
                try {
                    val preview = migration.scan().get(10, TimeUnit.SECONDS)
                    worker.submit { migration.close() }.get(3, TimeUnit.SECONDS)
                    assertNull(migration.report())
                    assertFails { migration.confirm(preview.token).get(3, TimeUnit.SECONDS) }
                    Files.list(migrationRoot.resolve("staging")).use { assertEquals(0L, it.count()) }
                    assertContentEquals(before, Files.readAllBytes(source))
                } finally { migration.close() }
            }
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `missing copied configuration reports an actionable safe reason instead of a generic failure`() {
        val migrationRoot = root.resolve("migration")
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            AuthMeInboxMigration(migrationRoot, ProfileClaimRepository(db), { null }, {}).use { migration ->
                val failed = assertFailsWith<java.util.concurrent.ExecutionException> { migration.scan().get(5, TimeUnit.SECONDS) }
                assertEquals("AuthMe migration failed: MISSING_INBOX_CONFIG", failed.cause?.message)
                assertNull(failed.cause?.cause)
                assertEquals(AuthMeImportPhase.FAILED, migration.status().phase)
                assertFalse(migration.available())
                Files.list(migrationRoot.resolve("staging")).use { assertEquals(0L, it.count()) }
            }
        }
    }

    @Test
    fun `invalid source configuration is classified without retaining its private values`() {
        val migrationRoot = root.resolve("migration")
        val source = prepareInbox(migrationRoot)
        Files.writeString(source.parent.resolve("config.yml"), "DataSource:\n  backend: PRIVATE_INVALID_CONFIG_SENTINEL\n")
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            AuthMeInboxMigration(migrationRoot, ProfileClaimRepository(db), { null }, {}).use { migration ->
                val failed = assertFailsWith<java.util.concurrent.ExecutionException> { migration.scan().get(5, TimeUnit.SECONDS) }
                val safe = assertIs<AuthMeImportException>(failed.cause)
                assertEquals(AuthMeImportFailure.INVALID_SOURCE_CONFIG, safe.reason)
                assertNull(safe.cause)
                assertFalse(safe.toString().contains("PRIVATE_INVALID_CONFIG_SENTINEL"))
                assertEquals(safe.reason, migration.status().failure)
                Files.list(migrationRoot.resolve("staging")).use { assertEquals(0L, it.count()) }
            }
        }
    }

    @Test
    fun `driver failures expose only the safe source stage and erase the original secret-bearing cause`() {
        val migrationRoot = root.resolve("migration")
        prepareInbox(migrationRoot)
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            AuthMeInboxMigration(migrationRoot, ProfileClaimRepository(db), {
                throw IllegalStateException("jdbc:mysql://private:PRIVATE_SQL_PASSWORD_SENTINEL@database/source")
            }, {}).use { migration ->
                val failed = assertFailsWith<java.util.concurrent.ExecutionException> { migration.scan().get(5, TimeUnit.SECONDS) }
                val safe = assertIs<AuthMeImportException>(failed.cause)
                assertEquals(AuthMeImportFailure.SOURCE_UNAVAILABLE, safe.reason)
                assertNull(safe.cause)
                assertFalse(safe.toString().contains("PRIVATE_SQL_PASSWORD_SENTINEL"))
                assertFalse(migration.status().toString().contains("PRIVATE_SQL_PASSWORD_SENTINEL"))
                assertEquals(safe.reason, migration.status().failure)
                Files.list(migrationRoot.resolve("staging")).use { assertEquals(0L, it.count()) }
            }
        }
    }

    @Test
    fun `invalid confirmation is classified without consuming the valid preview and success clears the reason`() {
        val migrationRoot = root.resolve("migration")
        prepareInbox(migrationRoot)
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            AuthMeInboxMigration(migrationRoot, ProfileClaimRepository(db), { null }, {}).use { migration ->
                val preview = migration.scan().get(5, TimeUnit.SECONDS)
                val failed = assertFailsWith<java.util.concurrent.ExecutionException> {
                    migration.confirm("PRIVATE_CONFIRM_TOKEN_SENTINEL").get(5, TimeUnit.SECONDS)
                }
                val safe = assertIs<AuthMeImportException>(failed.cause)
                assertEquals(AuthMeImportFailure.INVALID_CONFIRMATION, safe.reason)
                assertFalse(safe.toString().contains("PRIVATE_CONFIRM_TOKEN_SENTINEL"))
                assertNull(safe.cause)
                assertEquals(preview.token, migration.report()?.token)
                assertEquals(1, migration.confirm(preview.token).get(5, TimeUnit.SECONDS).imported)
                assertTrue(migration.available())
                assertNull(migration.status().failure)
            }
        }
    }

    @Test
    fun `corrupt unconfirmed snapshot reports integrity failure while the old active proof remains valid`() {
        val migrationRoot = root.resolve("migration")
        prepareInbox(migrationRoot)
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            AuthMeInboxMigration(migrationRoot, ProfileClaimRepository(db), { null }, {}).use { migration ->
                val first = migration.scan().get(5, TimeUnit.SECONDS)
                migration.confirm(first.token).get(5, TimeUnit.SECONDS)
                val replacement = migration.scan().get(5, TimeUnit.SECONDS)
                val stage = Files.list(migrationRoot.resolve("staging")).use { it.findFirst().orElseThrow() }
                Files.writeString(stage.resolve("authme.db"), "PRIVATE_CORRUPT_SNAPSHOT_SENTINEL")
                val failed = assertFailsWith<java.util.concurrent.ExecutionException> { migration.confirm(replacement.token).get(5, TimeUnit.SECONDS) }
                val safe = assertIs<AuthMeImportException>(failed.cause)
                assertEquals(AuthMeImportFailure.SNAPSHOT_INVALID, safe.reason)
                assertNull(safe.cause)
                assertFalse(safe.toString().contains("PRIVATE_CORRUPT_SNAPSHOT_SENTINEL"))
                assertTrue(migration.status().active)
                assertEquals(safe.reason, migration.status().failure)
                assertEquals(AuthMeVerification.VERIFIED, migration.verify(name, old, "password".toCharArray()) { true })
            }
        }
    }

    @Test
    fun `disabling before any local activation reports that normal login requires no migration action`() {
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            AuthMeInboxMigration(root.resolve("migration"), ProfileClaimRepository(db), { null }, {}).use { migration ->
                val failed = assertFailsWith<java.util.concurrent.ExecutionException> { migration.disable().get(5, TimeUnit.SECONDS) }
                assertEquals(AuthMeImportFailure.NO_ACTIVE_SNAPSHOT, assertIs<AuthMeImportException>(failed.cause).reason)
                assertFalse(migration.hasLocalState())
                assertFalse(migration.available())
            }
        }
    }

    private fun prepareInbox(migrationRoot: Path): Path {
        val inbox = migrationRoot.resolve("inbox")
        val authme = Files.createDirectories(inbox.resolve("AuthMe"))
        Files.writeString(authme.resolve("config.yml"), """
            DataSource:
              backend: SQLITE
              mySQLDatabase: legacy
              mySQLPassword: DATABASE_PRIVATE_SENTINEL
              mySQLTablename: old_accounts
              mySQLColumnName: login_name
              mySQLColumnPassword: old_hash
              mySQLtotpKey: mfa
        """.trimIndent())
        val snapshot = authme.resolve("legacy.db")
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$snapshot").use { c -> c.createStatement().use { sql ->
            sql.execute("CREATE TABLE old_accounts(login_name TEXT,old_hash TEXT,mfa TEXT)")
            sql.execute("INSERT INTO old_accounts VALUES ('archivedalice','$hash','')")
        } }
        val playerdata = Files.createDirectories(inbox.resolve("playerdata")).resolve("$old.dat")
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(playerdata))).use { out ->
            out.writeByte(10); out.writeUTF("")
            out.writeByte(11); out.writeUTF("UUID"); out.writeInt(4)
            out.writeInt((old.mostSignificantBits shr 32).toInt()); out.writeInt(old.mostSignificantBits.toInt())
            out.writeInt((old.leastSignificantBits shr 32).toInt()); out.writeInt(old.leastSignificantBits.toInt())
            out.writeByte(10); out.writeUTF("bukkit")
            out.writeByte(8); out.writeUTF("lastKnownName"); out.writeUTF(name.value)
            out.writeByte(0); out.writeByte(0)
        }
        return snapshot
    }
}
