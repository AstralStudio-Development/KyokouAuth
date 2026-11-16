package moe.ryuu.kyokouauth.core.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parser is the only thing standing between a translation file and a kick message, so the cases
 * here are the ones that would otherwise fail in front of a player: an unclosed tag, a tag that was
 * never meant to be a tag, and the click span that the whole Limbo flow depends on.
 */
class RichTextTest {

    private fun parse(template: String, vararg values: Pair<String, String>) =
        RichText.parse(RichText.substitute(template, values.toMap()))

    @Test
    fun `plain text is one span`() {
        val parsed = parse("hello")
        assertEquals(1, parsed.spans.size)
        assertEquals("hello", parsed.spans[0].text)
        assertNull(parsed.spans[0].style.colour)
    }

    @Test
    fun `a colour applies until it is replaced`() {
        val parsed = parse("<red>a<blue>b")

        assertEquals(2, parsed.spans.size)
        assertEquals("a", parsed.spans[0].text)
        assertEquals(RichText.Colour.Named.RED, parsed.spans[0].style.colour)
        assertEquals("b", parsed.spans[1].text)
        assertEquals(RichText.Colour.Named.BLUE, parsed.spans[1].style.colour)
    }

    @Test
    fun `decorations are independent of colour`() {
        val parsed = parse("<red><bold>x</bold>y")

        assertEquals("x", parsed.spans[0].text)
        assertTrue(parsed.spans[0].style.bold)
        assertEquals("y", parsed.spans[1].text)
        assertEquals(false, parsed.spans[1].style.bold)
        assertEquals(RichText.Colour.Named.RED, parsed.spans[1].style.colour)
    }

    @Test
    fun `newline becomes its own span`() {
        val parsed = parse("a<newline>b")

        assertEquals(listOf("a", "\n", "b"), parsed.spans.map { it.text })
        assertEquals("a\nb", parsed.plainText)
    }

    @Test
    fun `a click span carries its url on every span inside it`() {
        val parsed = parse("<click:open_url:https://example.com><white>link</white></click>")

        assertTrue(parsed.isClickable)
        assertEquals("link", parsed.plainText)
        assertTrue(parsed.spans.all { it.clickUrl == "https://example.com" })
    }

    @Test
    fun `text after a closed click span is no longer clickable`() {
        val parsed = parse("<click:open_url:https://a><red>x</click>y")

        assertEquals("https://a", parsed.spans.first { it.text == "x" }.clickUrl)
        assertNull(parsed.spans.first { it.text == "y" }.clickUrl)
    }

    @Test
    fun `quoted urls are accepted`() {
        val parsed = parse("<click:open_url:'https://example.com/?a=1'>x</click>")

        assertEquals("https://example.com/?a=1", parsed.spans[0].clickUrl)
    }

    @Test
    fun `an unknown tag is literal text, not an error`() {
        val parsed = parse("<not_a_tag>hello")

        assertEquals("<not_a_tag>hello", parsed.plainText)
    }

    @Test
    fun `an unterminated tag is literal text`() {
        val parsed = parse("a < b")

        assertEquals("a < b", parsed.plainText)
    }

    @Test
    fun `placeholders are substituted before parsing`() {
        val parsed = parse("<white><name></white> joined", "name" to "Steve")

        assertEquals("Steve joined", parsed.plainText)
        assertEquals("Steve", parsed.spans.first { it.text == "Steve" }.text)
    }

    @Test
    fun `a placeholder value cannot inject a tag`() {
        // The value would otherwise close the colour and turn itself bold. Stripping the brackets
        // leaves the words behind as inert text, which is the safe failure direction.
        val parsed = parse("<white><name></white>", "name" to "a</white><bold>b")

        assertEquals(false, parsed.spans.any { it.style.bold }, "a substituted value must not change styling")
        assertTrue(parsed.plainText.startsWith("a"), parsed.plainText)
        assertTrue(parsed.plainText.endsWith("b"), parsed.plainText)
    }

    @Test
    fun `a closing colour restores the enclosing colour`() {
        val parsed = parse("<red>a<white>b</white>c")

        assertEquals("c", parsed.spans.first { it.text == "c" }.let { it.text })
        assertEquals(
            RichText.Colour.Named.RED,
            parsed.spans.first { it.text == "c" }.style.colour,
            "text after </white> must return to the enclosing red",
        )
    }

    @Test
    fun `a hex colour parses and closes like a named one`() {
        val parsed = parse("<#12AbEf>a</#12AbEf>b")

        assertEquals(RichText.Colour.Hex(0x12ABEF), parsed.spans[0].style.colour)
        assertNull(parsed.spans[1].style.colour, "text after the closing tag must return to no colour")
    }

    @Test
    fun `a malformed hex tag stays literal text`() {
        val parsed = parse("<#12345>x")

        assertEquals("<#12345>x", parsed.plainText)
    }

    @Test
    fun `an empty template yields no spans`() {
        assertEquals(0, parse("").spans.size)
    }

    @Test
    fun `the real prompt template renders as expected`() {
        val template = "<aqua><bold>请完成验证</bold></aqua><newline>" +
            "<click:open_url:<url>><u><white><url></white></u></click><newline>" +
            "<gray>代码 <yellow><code></yellow>"

        val parsed = parse(template, "url" to "https://microsoft.com/link?otc=ABC123", "code" to "ABC123")

        assertTrue(parsed.isClickable, "the prompt must be clickable or Limbo has no purpose")
        assertTrue(parsed.plainText.contains("ABC123"))
        assertTrue(parsed.plainText.contains("https://microsoft.com/link?otc=ABC123"))
    }
}
