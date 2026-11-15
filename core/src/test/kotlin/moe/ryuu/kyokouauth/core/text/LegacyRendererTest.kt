package moe.ryuu.kyokouauth.core.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The legacy renderer is the only message path that works on a server without Adventure, so the
 * cases that matter are the ones where the §-code stream can go wrong: an unset colour, a decoration
 * that must be turned back off, and text that happens to contain a `§` itself.
 */
class LegacyRendererTest {

    private fun render(template: String) = LegacyRenderer.render(RichText.parse(template))

    @Test
    fun `a coloured span becomes a colour code`() {
        assertEquals("§ca", render("<red>a"))
    }

    @Test
    fun `the style is re-emitted only when it changes`() {
        // Two spans that share a style produce one code, not two.
        assertEquals("§cabc", render("<red>abc"))
    }

    @Test
    fun `decorations are appended after the colour`() {
        assertEquals("§c§la", render("<red><bold>a"))
    }

    @Test
    fun `leaving a decoration re-emits the colour so it switches off`() {
        val rendered = render("<red><bold>a</bold>b")

        // §-codes are stateful: there is no "bold off" without re-stating a style, so the text after
        // the bold span must carry a fresh colour code or it stays bold for the rest of the message.
        assertEquals("§c§la§cb", rendered)
    }

    @Test
    fun `newlines survive as newlines without re-emitting an unchanged style`() {
        // The newline span keeps the surrounding colour, so no second code is emitted.
        assertEquals("§ca\nb", render("<red>a<newline>b"))
    }

    @Test
    fun `a colour change across a newline is emitted after it`() {
        assertEquals("§ca\n§bb", render("<red>a<newline><aqua>b"))
    }

    @Test
    fun `click spans lose their event but keep their text`() {
        val rendered = render("<click:open_url:https://x><white>link</white></click>")

        assertTrue(rendered.contains("link"), rendered)
        assertTrue(!rendered.contains("https"), "a legacy string cannot carry a URL target")
    }

    @Test
    fun `plain rendering strips every code`() {
        val parsed = RichText.parse("<red><bold>hello")

        assertEquals("hello", LegacyRenderer.plain(parsed))
    }
}
