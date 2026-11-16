package moe.ryuu.kyokouauth.core.text

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalogue's resolution order, which is the part operators notice.
 *
 * A message that is missing from a translation must fall back to English rather than print its own
 * key, and a key nobody has must still be printable: both are messages a player sees at the moment
 * they are being refused, where a raw key is worse than an untranslated line.
 */
class MessageCatalogueTest {

    private fun catalogue(configure: (File) -> Unit = {}): MessageCatalogue {
        val root = Files.createTempDirectory("kyokou-messages").toFile()
        val messages = File(root, "messages").apply { mkdirs() }

        File(messages, "en_US.yml").writeText(
            """
            login:
              name-taken: "This name is not yours"
              with-placeholder: "Valid for <minutes> minutes"
              rich: "<#ff0000>red</#ff0000>"
            """.trimIndent(),
        )
        File(messages, "zh_CN.yml").writeText(
            """
            login:
              name-taken: "这个名字不属于你"
            """.trimIndent(),
        )

        configure(messages)
        return MessageCatalogue(defaultLocale = "zh_CN", directory = messages).also { it.load() }
    }

    @Test
    fun `nested keys resolve by dotted path`() {
        assertEquals("This name is not yours", catalogue().plain("login.name-taken", "en_us"))
    }

    @Test
    fun `a client locale picks its own file`() {
        val catalogue = catalogue()

        assertEquals("这个名字不属于你", catalogue.plain("login.name-taken", "zh_cn"))
        assertEquals("This name is not yours", catalogue.plain("login.name-taken", "en_US"))
    }

    @Test
    fun `a key missing from a translation falls back to english`() {
        // The zh_CN file above has one key; the other two exist only in English. Showing the key name
        // to a Chinese player would be worse than showing English.
        val catalogue = catalogue()

        assertEquals("Valid for 5 minutes", catalogue.plain("login.with-placeholder", "zh_cn", "minutes" to "5"))
        assertEquals("red", catalogue.plain("login.rich", "zh_cn"))
    }

    @Test
    fun `a key nobody has is printed as itself`() {
        assertEquals("login.nobody-wrote-this", catalogue().plain("login.nobody-wrote-this"))
    }

    @Test
    fun `the default locale is used when the client has none`() {
        assertEquals("这个名字不属于你", catalogue().plain("login.name-taken"))
    }

    @Test
    fun `markup survives parsing`() {
        val parsed = catalogue().parsed("login.rich", "en_us")

        assertEquals("red", parsed.plainText)
        assertTrue(parsed.spans.any { it.style.colour is RichText.Colour.Hex })
    }
}
