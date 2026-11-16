package moe.ryuu.kyokouauth.bukkit

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Config parsing is the one place where an operator's typo decides whether authentication runs at
 * all, so the shapes that must fail loudly are tested as carefully as the ones that must succeed.
 */
class ConfigurationTest {

    private val dataFolder = File("build/tmp/configuration-test")

    private fun parse(yaml: String): Configuration {
        val root = YamlConfiguration()
        root.loadFromString(yaml)
        return Configuration(root, dataFolder, dataFolder)
    }

    @Test
    fun `the shipped defaults are what an operator gets`() {
        val config = parse(
            """
            database:
              type: sqlite
              database: kyokouauth
            """.trimIndent(),
        )

        assertEquals("SQLite", config.database.dialect.displayName)
        assertEquals(12L, config.sessionHours)
        assertTrue(config.sessionBindsAddress, "address binding must default to on")
        assertEquals(300, config.verificationTimeoutSeconds)
        assertEquals(600, config.directoryBudget)
        assertTrue(config.requireDirectConnections)
        assertTrue(config.limbo.hidePlayers, "a held player must not be visible by default")
        assertEquals(10, config.connectionsPerMinute)
    }

    @Test
    fun `an unknown dialect is a startup error, not a silent fallback`() {
        val failure = assertFailsWith<IllegalStateException> {
            parse(
                """
                database:
                  type: oracle
                """.trimIndent(),
            )
        }

        assertTrue(failure.message!!.contains("oracle"), failure.message)
    }

    @Test
    fun `a missing database section is a startup error`() {
        assertFailsWith<IllegalStateException> { parse("session:\n  hours: 1") }
    }

    @Test
    fun `a relative sqlite path lands under the plugin data folder`() {
        val config = parse(
            """
            database:
              type: sqlite
              database: data/kyokouauth.db
            """.trimIndent(),
        )

        val expected = File(dataFolder, "data/kyokouauth.db").absolutePath
        assertEquals(expected, config.database.database)
    }

    @Test
    fun `a server dialect keeps the database name untouched`() {
        val config = parse(
            """
            database:
              type: postgresql
              database: kyokouauth
              host: db.internal
            """.trimIndent(),
        )

        assertEquals("kyokouauth", config.database.database)
        assertEquals("db.internal", config.database.host)
        assertEquals(5432, config.database.port, "the dialect's default port must fill in")
    }

    @Test
    fun `address binding can be turned off for shared addresses`() {
        val config = parse(
            """
            database:
              type: sqlite
            session:
              bind-address: false
            """.trimIndent(),
        )

        assertEquals(false, config.sessionBindsAddress)
    }

    @Test
    fun `the connection limit and player hiding can be turned off`() {
        val config = parse(
            """
            database:
              type: sqlite
            safety:
              connections-per-minute: 0
            limbo:
              hide-players: false
            """.trimIndent(),
        )

        assertEquals(0, config.connectionsPerMinute, "0 must disable the limiter, not forbid everything")
        assertEquals(false, config.limbo.hidePlayers)
    }
}
