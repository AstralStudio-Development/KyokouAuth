package moe.ryuu.kyokouauth.bukkit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Which language file a player ends up reading.
 *
 * The failure this guards against is silent: a wrong answer does not throw, it just shows a player
 * a language they do not read at the moment they are being rejected.
 */
class MessagesLocaleTest {

    @TempDir
    lateinit var dataFolder: File

    private fun messages(defaultLocale: String = "zh_CN"): Messages =
        Messages(defaultLocale, dataFolder).also { it.load() }

    @Test
    fun `an unknown client locale falls back to the server locale`() {
        val messages = messages("zh_CN")

        assertEquals(
            messages.parsed("login.name-taken").plainText,
            messages.parsed("login.name-taken", "de_de").plainText,
        )
    }

    @Test
    fun `a locale that has a file wins over the server locale`() {
        val messages = messages("zh_CN")

        assertNotEquals(
            messages.parsed("login.name-taken").plainText,
            messages.parsed("login.name-taken", "en_us").plainText,
        )
    }

    @Test
    fun `the client locale is matched case-insensitively`() {
        val messages = messages("zh_CN")

        assertEquals(
            messages.parsed("login.name-taken", "en_us").plainText,
            messages.parsed("login.name-taken", "EN_US").plainText,
        )
    }

    @Test
    fun `the server locale is used when no locale is given`() {
        val messages = messages("en_US")

        assertEquals(
            messages.parsed("login.name-taken", "en_us").plainText,
            messages.parsed("login.name-taken").plainText,
        )
    }

    @Test
    fun `placeholders survive the locale switch`() {
        val messages = messages("zh_CN")

        val zh = messages.parsed("command.unknown-name", null, "name" to "Steve").plainText
        val en = messages.parsed("command.unknown-name", "en_us", "name" to "Steve").plainText

        assertEquals(true, zh.contains("Steve"), zh)
        assertEquals(true, en.contains("Steve"), en)
    }

    @Test
    fun `both files are written out on first load`() {
        messages("zh_CN")

        assertEquals(
            true,
            File(dataFolder, "messages/zh_CN.yml").isFile,
            "the server locale must be extracted",
        )
        assertEquals(
            true,
            File(dataFolder, "messages/en_US.yml").isFile,
            "en_US is always extracted as the fallback",
        )
    }

    @Test
    fun `the usage lines are not one flat colour`() {
        val messages = messages("zh_CN")
        val parsed = messages.parsed("command.usage-whois")

        val command = parsed.spans.firstOrNull { it.text == "whois" }
        assertEquals(
            moe.ryuu.kyokouauth.core.text.RichText.Colour.Hex(0x22D3EE),
            command?.style?.colour,
            "the command name must carry the brand colour",
        )

        val colours = parsed.spans.mapNotNull { it.style.colour }.toSet()
        assertEquals(
            true,
            colours.size >= 3,
            "a line with one or two colours reads as a wall: $colours",
        )
    }

    @Test
    fun `a key the operator file predates comes from the jar, not as its own name`() {
        // Reproduces an upgrade: the operator's file was written before this key existed.
        val messages = messages("zh_CN")
        val expected = messages.parsed("command.setspawn-player-only").plainText

        val file = File(dataFolder, "messages/zh_CN.yml")
        file.writeText(
            file.readText().lines()
                .filterNot { it.contains("setspawn-player-only") }
                .joinToString("\n"),
        )

        val reloaded = Messages("zh_CN", dataFolder).also { it.load() }
        val actual = reloaded.parsed("command.setspawn-player-only").plainText

        assertNotEquals("command.setspawn-player-only", actual, "the key name must never reach a player")
        assertEquals(expected, actual)
    }
}
