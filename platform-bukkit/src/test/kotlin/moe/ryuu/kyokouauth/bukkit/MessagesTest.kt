package moe.ryuu.kyokouauth.bukkit

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two locale files must stay in step.
 *
 * A key that exists in one file and not the other is invisible until the moment it is needed, and
 * then it prints itself instead of a message - which for a kick reason means the player is
 * disconnected with the text `login.name-taken` on screen.
 */
class MessagesTest {

    private fun keys(tag: String): Set<String> {
        val stream = javaClass.classLoader.getResourceAsStream("messages/$tag.yml")
            ?: error("messages/$tag.yml is not on the test classpath")
        val yaml = YamlConfiguration()
        stream.use { yaml.load(InputStreamReader(it, StandardCharsets.UTF_8)) }
        return yaml.getKeys(true)
    }

    @Test
    fun `both locales define exactly the same keys`() {
        val zh = keys("zh_CN")
        val en = keys("en_US")

        assertEquals(
            emptySet(),
            en - zh,
            "keys present in en_US but missing from zh_CN",
        )
        assertEquals(
            emptySet(),
            zh - en,
            "keys present in zh_CN but missing from en_US",
        )
    }

    @Test
    fun `no message is empty`() {
        listOf("zh_CN", "en_US").forEach { tag ->
            val stream = javaClass.classLoader.getResourceAsStream("messages/$tag.yml")!!
            val yaml = YamlConfiguration()
            stream.use { yaml.load(InputStreamReader(it, StandardCharsets.UTF_8)) }

            yaml.getKeys(true)
                .filterNot { yaml.isConfigurationSection(it) }
                .forEach { key ->
                    assertTrue(
                        yaml.getString(key).orEmpty().isNotBlank(),
                        "$tag: `$key` is blank",
                    )
                }
        }
    }
}
