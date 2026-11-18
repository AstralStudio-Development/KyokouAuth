package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.core.text.RichText
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoleSelectionMessagesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `bundled translations keep matching keys and runtime placeholders`() {
        fun bundle(locale: String) = checkNotNull(javaClass.getResourceAsStream("/messages/$locale.yml"))
            .use(moe.ryuu.kyokouauth.core.config.YamlDocument::load)
        val english = bundle("en_US")
        val chinese = bundle("zh_CN")
        assertEquals(english.keys, chinese.keys)
        val tokens = Regex("<(reason|name|url|code|minutes|from|to|dialect|session|locale|verifying|budget|cached|uuid|kind|subject|gamertag|address|owner)>")
        for (key in english.keys.filterNot { it.startsWith("command.usage-") }) {
            fun placeholders(value: String) = tokens.findAll(value).map { it.groupValues[1] }.toSet()
            assertEquals(placeholders(english.getValue(key)), placeholders(chinese.getValue(key)), key)
        }
    }

    @Test
    fun `verification prompts retain usable links codes and expiration in both languages`() {
        val messages = MessageCatalogue("zh_CN", directory.toFile()).also { it.load() }
        val url = "https://example.invalid/verify?otc=TEST123"
        for (locale in listOf("zh_CN", "en_US")) {
            val prompt = messages.parsed("verification.prompt", locale,
                "url" to url, "code" to "TEST123", "minutes" to "7")
            assertTrue(prompt.spans.any { it.clickUrl == url }, locale)
            assertTrue(prompt.plainText.contains("TEST123"), locale)
            assertTrue(prompt.plainText.contains("7"), locale)
            assertFalse(prompt.plainText.contains('<'), locale)
            assertFalse(prompt.plainText.contains('>'), locale)
        }
    }

    @Test
    fun `selector uses brand labels neutral names and a short action instead of storage jargon`() {
        val messages = MessageCatalogue("zh_CN", directory.toFile()).also { it.load() }
        assertEquals("请选择角色", messages.plain("role-selection.title"))
        assertTrue(messages.parsed("role-selection.title").spans.all { it.style.colour == null },
            "Container titles must inherit the native GUI colour rather than a chat brand colour")
        val microsoft = messages.parsed("role-selection.microsoft", "name" to "SampleU")
        val premium = messages.parsed("role-selection.premium", "name" to "SampleJ")
        assertEquals("微软角色 » SampleU", microsoft.plainText)
        assertEquals("正版角色 » SampleJ", premium.plainText)
        assertEquals(RichText.Colour.Hex(0x22D3EE), microsoft.spans.first().style.colour)
        assertEquals(RichText.Colour.Hex(0x22D3EE), premium.spans.first().style.colour)
        assertFalse(premium.spans.any { it.style.colour == RichText.Colour.Hex(0x4ADE80) }, "Role type is not a success status")
        assertEquals("点击进入服务器", messages.plain("role-selection.enter"))
        assertEquals(RichText.Colour.Named.GRAY, messages.parsed("role-selection.enter").spans.first().style.colour)
        assertEquals(RichText.Colour.Named.GRAY, microsoft.spans.single { it.text == "»" }.style.colour)
        assertEquals(RichText.Colour.Named.GRAY, premium.spans.single { it.text == "»" }.style.colour)
    }
}
