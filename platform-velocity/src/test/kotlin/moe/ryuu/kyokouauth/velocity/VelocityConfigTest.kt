package moe.ryuu.kyokouauth.velocity

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The configuration an operator actually edits.
 *
 * Two things are worth pinning: a missing file is written out from the jar, so a first start always
 * produces something to edit; and a missing *key* is a default rather than a crash, so an upgrade
 * that adds a setting never leaves the plugin refusing to boot.
 */
class VelocityConfigTest {

    private fun tempDir(): File = Files.createTempDirectory("kyokou-config").toFile()

    @Test
    fun `a missing file is written from the bundled copy`() {
        val folder = tempDir()

        val config = VelocityConfig.load(folder)

        assertTrue(File(folder, "config.yml").isFile, "the operator needs a file to edit")
        assertEquals("sqlite", config.database.dialect.name.lowercase())
        assertEquals(600, config.directoryBudget)
        assertEquals(moe.ryuu.kyokouauth.core.http.Http.Route.INHERIT, config.directoryNetwork)
        assertEquals(12L, config.sessionHours)
        assertEquals(false, config.autoRenameMicrosoft)
        assertEquals(30L, config.sessionFailureBackoffSeconds)
        assertEquals(120L, config.sessionFailureMaxBackoffSeconds)
    }

    @Test
    fun `values in the file win, and a sqlite database sits next to it`() {
        val folder = tempDir()
        File(folder, "config.yml").writeText(
            """
            database:
              type: "sqlite"
              database: "identities.db"
              pool-size: 2
            session:
              hours: 3
              bind-address: false
            verification:
              auto-rename: true
              timeout-seconds: 90
              poll-interval-seconds: 2
            premium:
              directory-limit: 0
              directory-network: direct
            limbo:
              spawn:
                x: 1.5
                y: 80.0
                z: -2.5
            """.trimIndent(),
        )

        val config = VelocityConfig.load(folder)

        assertEquals(2, config.poolSize)
        assertEquals(3L, config.sessionHours)
        assertEquals(false, config.sessionBindsAddress)
        assertEquals(90, config.verificationTimeoutSeconds)
        assertEquals(true, config.autoRenameMicrosoft)
        assertEquals(2L, config.pollIntervalSeconds)
        assertEquals(0, config.directoryBudget)
        assertEquals(moe.ryuu.kyokouauth.core.http.Http.Route.DIRECT, config.directoryNetwork)
        assertEquals(1.5, config.limbo.spawn.x)
        assertEquals(80.0, config.limbo.spawn.y)
        assertEquals(-2.5, config.limbo.spawn.z)

        // A relative sqlite path is resolved against the plugin's own folder, never the process's
        // working directory: a proxy started from anywhere else would otherwise create a second,
        // empty database and look like every player had lost their binding.
        assertEquals(File(folder, "identities.db").absolutePath, config.database.database)
    }

    @Test
    fun `a server database needs no path resolution`() {
        val folder = tempDir()
        File(folder, "config.yml").writeText(
            """
            database:
              type: "postgresql"
              database: "kyokouauth"
              host: "db.internal"
              port: 5433
              username: "kyokou"
              password: "secret"
            """.trimIndent(),
        )

        val config = VelocityConfig.load(folder)

        assertEquals("kyokouauth", config.database.database)
        assertEquals("db.internal", config.database.host)
        assertEquals(5433, config.database.port)
    }
}
