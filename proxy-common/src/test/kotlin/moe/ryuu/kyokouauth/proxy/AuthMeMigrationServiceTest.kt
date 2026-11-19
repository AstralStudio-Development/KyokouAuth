package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.authme.AuthMeLegacyVerifier
import moe.ryuu.kyokouauth.core.authme.AuthMeSourceConfig
import moe.ryuu.kyokouauth.core.storage.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class AuthMeMigrationServiceTest {
    @TempDir lateinit var root: Path
    private val old = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")
    private val hash = "\$SHA\$11aa0706173d7272\$dbba96681c2ae4e0bfdf226d70fbbc5e4ee3d8071faa613bc533fe8a64817d10"

    @Test
    fun `preview is read only and a one-use confirmation imports automatically discovered profiles`() {
        val source = prepareSource()
        history("ArchivedAlice", old)
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            val claims = ProfileClaimRepository(db)
            AuthMeLegacyVerifier(source).use { verifier ->
                val migration = AuthMeMigrationService(AuthMeClaimSettings(source, "cutover", root.resolve("config.yml"), root), verifier, claims)
                val preview = migration.preview()
                assertEquals(1, preview.report.ready)
                assertTrue(claims.listByLegacyName("ArchivedAlice").isEmpty())
                assertFails { migration.confirm("invalid-token") }
                val imported = migration.confirm(preview.token)
                assertEquals(1, imported.imported)
                assertEquals(old, claims.listByLegacyName("ArchivedAlice").single().profileUuid)
                assertFails { migration.confirm(preview.token) }
                assertEquals(1, migration.preview().report.alreadyRegistered)
            }
        }
    }

    @Test
    fun `missing ambiguous two factor and unsupported accounts remain actionable issues not guessed mappings`() {
        val source = prepareSource()
        history("ArchivedAlice", old)
        history("ArchivedAlice", UUID.fromString("f66564fa-f35f-3ec0-8f67-f28f4ee58a44"))
        DriverManager.getConnection("jdbc:sqlite:${source.location}").use { c -> c.createStatement().use { sql ->
            sql.execute("INSERT INTO authme VALUES ('Missing','$hash','')")
            sql.execute("INSERT INTO authme VALUES ('TwoFactor','$hash','active')")
            sql.execute("INSERT INTO authme VALUES ('Unsupported','not-supported','')")
        } }
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            val claims = ProfileClaimRepository(db)
            AuthMeLegacyVerifier(source).use { verifier ->
                val migration = AuthMeMigrationService(AuthMeClaimSettings(source, "cutover", root.resolve("config.yml"), root), verifier, claims)
                val preview = migration.preview()
                assertEquals(0, preview.report.ready)
                assertEquals(setOf("AMBIGUOUS_HISTORY", "HISTORY_NOT_FOUND", "TOTP_REQUIRED", "UNSUPPORTED_HASH"),
                    preview.report.issues.map { it.reason }.toSet())
                assertEquals(0, migration.confirm(preview.token).imported)
                assertNull(claims.byProfile(old))
            }
        }
    }

    @Test
    fun `expired preview and changed source cannot import an unseen candidate list`() {
        val source = prepareSource()
        history("ArchivedAlice", old)
        var now = 1000L
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            val claims = ProfileClaimRepository(db)
            AuthMeLegacyVerifier(source).use { verifier ->
                val migration = AuthMeMigrationService(AuthMeClaimSettings(source, "cutover", root.resolve("config.yml"), root), verifier, claims) { now }
                val expired = migration.preview()
                now += 300_000
                assertFails { migration.confirm(expired.token) }
                assertNull(claims.byProfile(old))
                val preview = migration.preview()
                history("ChangedName", old)
                assertFails { migration.confirm(preview.token) }
                assertNull(claims.byProfile(old))
                assertFails { migration.confirm(preview.token) }
            }
        }
    }

    @Test
    fun `a changed target after preflight requires another explicit scan`() {
        val source = prepareSource()
        history("ArchivedAlice", old)
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            val claims = ProfileClaimRepository(db)
            AuthMeLegacyVerifier(source).use { verifier ->
                val migration = AuthMeMigrationService(AuthMeClaimSettings(source, "cutover", root.resolve("config.yml"), root), verifier, claims)
                val preview = migration.preview()
                claims.importLegacy("different-batch", "Other", old)
                assertFails { migration.confirm(preview.token) }
                assertEquals("Other", claims.byProfile(old)?.legacyName)
            }
        }
    }

    @Test
    fun `bounded target transactions can stop and resume idempotently without creating identity ownership`() {
        val source = prepareSource()
        val other = UUID.fromString("f66564fa-f35f-3ec0-8f67-f28f4ee58a44")
        history("ArchivedAlice", old)
        history("Other", other)
        DriverManager.getConnection("jdbc:sqlite:${source.location}").use { c -> c.createStatement().use {
            it.execute("INSERT INTO authme VALUES ('other','$hash','')")
        } }
        Database(DatabaseDescriptor(Dialect.H2, database = root.resolve("identities").toString())).use { db ->
            val claims = ProfileClaimRepository(db)
            AuthMeLegacyVerifier(source).use { verifier ->
                val migration = AuthMeMigrationService(AuthMeClaimSettings(source, "cutover", root.resolve("config.yml"), root), verifier, claims)
                val first = migration.preview()
                var running = true
                assertFails {
                    migration.confirm(first.token, transactionSize = 1, stillCurrent = { running }) { count ->
                        assertEquals(1, count)
                        running = false
                    }
                }
                assertNotNull(claims.byProfile(old))
                assertNull(claims.byProfile(other))
                assertNull(claims.ownerByProfile(old))
                val retry = migration.preview()
                assertEquals(1, retry.report.alreadyRegistered)
                assertEquals(1, retry.report.ready)
                assertEquals(1, migration.confirm(retry.token).imported)
                assertNotNull(claims.byProfile(other))
                assertNull(claims.ownerByProfile(other))
            }
        }
    }

    private fun prepareSource(): AuthMeSourceConfig {
        Class.forName("org.sqlite.JDBC")
        val snapshot = root.resolve("authme.db")
        DriverManager.getConnection("jdbc:sqlite:$snapshot").use { c -> c.createStatement().use { sql ->
            sql.execute("CREATE TABLE authme(username TEXT,password TEXT,totp TEXT)")
            sql.execute("INSERT INTO authme VALUES ('archivedalice','$hash','')")
        } }
        return AuthMeSourceConfig(Dialect.SQLITE, snapshot.toString())
    }

    private fun history(name: String, uuid: UUID) {
        val folder = Files.createDirectories(root.resolve("world/playerdata"))
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(folder.resolve("$uuid.dat")))).use { out ->
            out.writeByte(10); out.writeUTF("")
            out.writeByte(11); out.writeUTF("UUID"); out.writeInt(4)
            out.writeInt((uuid.mostSignificantBits shr 32).toInt()); out.writeInt(uuid.mostSignificantBits.toInt())
            out.writeInt((uuid.leastSignificantBits shr 32).toInt()); out.writeInt(uuid.leastSignificantBits.toInt())
            out.writeByte(10); out.writeUTF("bukkit")
            out.writeByte(8); out.writeUTF("lastKnownName"); out.writeUTF(name)
            out.writeByte(0); out.writeByte(0)
        }
    }
}
