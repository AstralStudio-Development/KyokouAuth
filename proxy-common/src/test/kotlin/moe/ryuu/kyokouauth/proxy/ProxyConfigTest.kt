package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.config.YamlDocument
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * The configuration an operator actually edits.
 *
 * Two things are worth pinning: a missing file is written out from the jar, so a first start always
 * produces something to edit; and a missing *key* is a default rather than a crash, so an upgrade
 * that adds a setting never leaves the plugin refusing to boot.
 */
class ProxyConfigTest {

    private fun tempDir(): File = Files.createTempDirectory("kyokou-config").toFile()

    @Test
    fun `a missing file is written from the bundled copy`() {
        val folder = tempDir()

        val config = ProxyConfig.load(folder)

        assertTrue(File(folder, "config.yml").isFile, "the operator needs a file to edit")
        assertEquals("sqlite", config.database.dialect.name.lowercase())
        assertEquals(600, config.directoryBudget)
        assertEquals(moe.ryuu.kyokouauth.core.http.Http.Route.INHERIT, config.directoryNetwork)
        assertEquals(12L, config.sessionHours)
        assertEquals(true, config.autoRenameMicrosoft)
        assertEquals(30L, config.sessionFailureBackoffSeconds)
        assertEquals(120L, config.sessionFailureMaxBackoffSeconds)
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    fun `first launch localizes comments without changing any configuration values`() {
        val previous = Locale.getDefault()
        val display = Locale.getDefault(Locale.Category.DISPLAY)
        val format = Locale.getDefault(Locale.Category.FORMAT)
        try {
            fun generate(tag: String): File {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val folder = tempDir()
                ProxyConfig.load(folder)
                return File(folder, "config.yml")
            }
            val chinese = generate("zh-CN")
            val english = generate("en-US")
            assertTrue(chinese.readText().contains("配置在代理启动时读取"))
            assertTrue(english.readText().contains("Settings are loaded at startup"))
            assertEquals(YamlDocument.load(english), YamlDocument.load(chinese))
            for (tag in listOf("fr-FR", "zh-Hant-TW")) assertEquals(english.readText(), generate(tag).readText())

            val customized = chinese.readText().replace("# 配置在代理启动时读取", "# 保留现有注释，配置在代理启动时读取")
                .replace("hours: 12", "hours: 4")
            chinese.writeText(customized)
            assertEquals(4L, ProxyConfig.load(chinese.parentFile).sessionHours)
            assertEquals(customized, chinese.readText())
        } finally {
            Locale.setDefault(previous)
            Locale.setDefault(Locale.Category.DISPLAY, display)
            Locale.setDefault(Locale.Category.FORMAT, format)
        }
    }

    @Test
    fun `Bedrock role selection is opt in without a Transfer target or image bridge`() {
        val defaults = ProxyConfig(emptyMap(), tempDir())
        assertEquals(false, defaults.rolesEnabled)
        assertEquals(60L, defaults.roleSelectionTimeoutSeconds)
        assertEquals(30L, defaults.roleHintTtlSeconds)
        val enabled = ProxyConfig(mapOf("role-selection.enabled" to "true", "bedrock.enabled" to "true"), tempDir())
        assertTrue(enabled.rolesEnabled)
        assertEquals(false, enabled.hasRetiredRoleSettings)
    }

    @Test
    fun `hint TTL accepts its legacy duration alias without reviving retired transports`() {
        assertEquals(45L, ProxyConfig(mapOf("role-selection.transfer-ttl-seconds" to "45"), tempDir()).roleHintTtlSeconds)
        assertEquals(20L, ProxyConfig(mapOf("role-selection.hint-ttl-seconds" to "20",
            "role-selection.transfer-ttl-seconds" to "45"), tempDir()).roleHintTtlSeconds)
        assertTrue(ProxyConfig(mapOf("role-selection.reconnect-host" to "obsolete",
            "role-selection.avatars.enabled" to "true"), tempDir()).hasRetiredRoleSettings)
        for (value in listOf("4", "121", "bad")) {
            assertFailsWith<IllegalArgumentException> {
                ProxyConfig(mapOf("role-selection.hint-ttl-seconds" to value), tempDir())
            }
        }
    }

    @Test
    fun `automatic rename defaults on but an explicit opt out remains off`() {
        assertTrue(ProxyConfig(emptyMap(), tempDir()).autoRenameMicrosoft)
        val folder = tempDir()
        File(folder, "config.yml").writeText("verification:\n  auto-rename: false\n")
        assertEquals(false, ProxyConfig.load(folder).autoRenameMicrosoft)
    }

    @Test
    fun `blocked commands default to server and the operator list replaces that default`() {
        assertEquals(setOf("server"), ProxyConfig(emptyMap(), tempDir()).blockedCommands)
        val folder = tempDir()
        File(folder, "config.yml").writeText("verification:\n  blocked-commands:\n    - /SERVER\n    - example:Msg\n    - msg\n")
        assertEquals(setOf("server", "msg"), ProxyConfig.load(folder).blockedCommands)
        File(folder, "config.yml").writeText("verification:\n  blocked-commands: []\n")
        assertEquals(emptySet(), ProxyConfig.load(folder).blockedCommands)
    }

    @Test
    fun `command roots support literal aliases and wildcard but never command arguments`() {
        val folder = tempDir()
        assertEquals(setOf("?", "server"), ProxyConfig(mapOf("verification.blocked-commands" to "/?\nSERVER"), folder).blockedCommands)
        assertEquals(setOf("*"), ProxyConfig(mapOf("verification.blocked-commands" to "*"), folder).blockedCommands)
        for (value in listOf("server lobby", "ser*", "server\u0000")) {
            val failure = assertFailsWith<IllegalArgumentException> {
                ProxyConfig(mapOf("verification.blocked-commands" to value), folder)
            }
            assertTrue(failure.message!!.contains("verification.blocked-commands"))
        }
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

        val config = ProxyConfig.load(folder)

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

        val config = ProxyConfig.load(folder)

        assertEquals("kyokouauth", config.database.database)
        assertEquals("db.internal", config.database.host)
        assertEquals(5433, config.database.port)
    }

    @Test
    fun `explicit malformed values fail with their key instead of silently disabling safety`() {
        val invalid = mapOf(
            "verification.auto-rename" to "certainly-sensitive-marker",
            "session.hours" to "9223372036854775807",
            "database.pool-size" to "0",
            "database.port" to "65536",
            "safety.connections-per-minute" to "-1",
            "safety.pending-login-limit" to "0",
            "safety.io-workers" to "0",
            "safety.io-queue-capacity" to "0",
            "premium.directory-limit" to "not-a-number",
            "premium.directory-limit-window-minutes" to "0",
            "premium.session-retry-seconds" to "0",
            "limbo.view-distance" to "99",
            "limbo.spawn.x" to "NaN",
            "limbo.spawn.pitch" to "Infinity",
            "limbo.spawn.y" to "999",
            "messages.locale" to "../../outside",
            "database.driver-repository" to "https://user:secret@example.invalid/repo",
            "verification.poll-interval-seconds" to "0",
        )
        invalid.forEach { (key, value) ->
            val failure = assertFailsWith<IllegalArgumentException>(key) { ProxyConfig(mapOf(key to value), File("unused")) }
            assertTrue(failure.message!!.contains(key), key)
            assertTrue(!failure.message!!.contains("certainly-sensitive-marker"))
            assertTrue(!failure.message!!.contains("user:secret"))
        }
    }

    @Test
    fun `valid explicit disable values and very long title deadlines remain supported`() {
        val config = ProxyConfig(mapOf("session.hours" to "0", "safety.connections-per-minute" to "0",
            "premium.directory-limit" to "0", "premium.session-handshakes-per-minute" to "0",
            "verification.timeout-seconds" to Int.MAX_VALUE.toString(), "verification.auto-rename" to "yes"), File("unused"))
        assertEquals(0L, config.sessionHours)
        assertEquals(0, config.connectionsPerMinute)
        assertEquals(0, config.directoryBudget)
        assertEquals(Int.MAX_VALUE, config.verificationTimeoutSeconds)
        assertTrue(config.autoRenameMicrosoft)
        assertEquals(4, config.ioWorkers)
        assertEquals(256, config.ioQueueCapacity)
        assertEquals(4096, config.pendingLoginLimit)
    }

    @Test
    fun `invalid backoff ordering fails rather than being coerced`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyConfig(mapOf("premium.session-failure-backoff-seconds" to "300",
                "premium.session-failure-max-backoff-seconds" to "120"), File("unused"))
        }
    }

    @Test
    fun `unwritable configuration location cannot fall back to empty defaults`() {
        val file = File(tempDir(), "not-a-directory").apply { writeText("existing") }
        assertFailsWith<IllegalStateException> { ProxyConfig.load(file) }
        assertEquals("existing", file.readText())
    }
}
