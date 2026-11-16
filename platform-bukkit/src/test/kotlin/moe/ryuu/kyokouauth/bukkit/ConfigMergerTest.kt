package moe.ryuu.kyokouauth.bukkit

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An upgrade must leave the operator's settings and the file's explanations intact.
 *
 * Both halves matter: dropping a value silently breaks a server, and dropping the comments is what
 * makes an old config unreadable in the first place.
 */
class ConfigMergerTest {

    private val template = """
        # KyokouAuth
        # 离线模式服务端的微软账户认证
        config-version: 7

        database:
          # sqlite | h2 | mysql | mariadb | postgresql
          type: sqlite
          port: 3306
          password: ""

        limbo:
          spawn:
            world: ""
            x: 0.5

        post-login:
          # 空为不传送
          server: ""
    """.trimIndent()

    private fun existing(yaml: String): YamlConfiguration =
        YamlConfiguration().apply { loadFromString(yaml) }

    @Test
    fun `operator values replace the template defaults`() {
        val merged = ConfigMerger.merge(
            template,
            existing(
                """
                config-version: 3
                database:
                  type: mysql
                  port: 9999
                  password: hunter2
                limbo:
                  spawn:
                    world: world_nether
                    x: 12.5
                """.trimIndent(),
            ),
        )

        assertTrue(merged.contains("type: mysql"), merged)
        assertTrue(merged.contains("port: 9999"), merged)
        assertTrue(merged.contains("password: hunter2"), merged)
        assertTrue(merged.contains("world: world_nether"), merged)
        assertTrue(merged.contains("x: 12.5"), merged)
    }

    @Test
    fun `comments survive the merge`() {
        val merged = ConfigMerger.merge(template, existing("config-version: 3"))

        assertTrue(merged.contains("# KyokouAuth"), merged)
        assertTrue(merged.contains("# sqlite | h2 | mysql"), merged)
        assertTrue(merged.contains("# 空为不传送"), merged)
    }

    @Test
    fun `the schema version always comes from the template`() {
        val merged = ConfigMerger.merge(template, existing("config-version: 3"))

        assertTrue(merged.contains("config-version: 7"), merged)
        assertFalse(merged.contains("config-version: 3"), merged)
    }

    @Test
    fun `a key the template dropped does not survive`() {
        val merged = ConfigMerger.merge(
            template,
            existing("config-version: 3\nenforce-bound-name: true"),
        )

        assertFalse(merged.contains("enforce-bound-name"), merged)
    }

    @Test
    fun `a key the template added keeps its default`() {
        val merged = ConfigMerger.merge(template, existing("config-version: 3\ndatabase:\n  type: mysql"))

        assertTrue(merged.contains("server: \"\""), merged)
        assertTrue(merged.contains("x: 0.5"), merged)
    }

    @Test
    fun `values with YAML metacharacters are quoted`() {
        val merged = ConfigMerger.merge(
            template,
            existing(
                """
                config-version: 3
                database:
                  password: "p#ss:word"
                """.trimIndent(),
            ),
        )

        assertTrue(merged.contains("password: \"p#ss:word\""), merged)
    }

    @Test
    fun `an empty value stays empty`() {
        val merged = ConfigMerger.merge(
            template,
            existing("config-version: 3\ndatabase:\n  password: \"\""),
        )

        assertTrue(merged.contains("password: \"\""), merged)
        assertEquals(1, merged.lines().count { it.trimStart().startsWith("password:") })
    }

    @Test
    fun `the merged text is still readable YAML`() {
        val merged = ConfigMerger.merge(
            template,
            existing(
                """
                config-version: 3
                database:
                  type: postgresql
                  port: 5432
                """.trimIndent(),
            ),
        )

        val reparsed = YamlConfiguration().apply { loadFromString(merged) }

        assertEquals("postgresql", reparsed.getString("database.type"))
        assertEquals(5432, reparsed.getInt("database.port"))
        assertEquals(7, reparsed.getInt("config-version"))
    }
}
