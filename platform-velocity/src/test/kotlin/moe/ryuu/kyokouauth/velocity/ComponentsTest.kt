package moe.ryuu.kyokouauth.velocity

import moe.ryuu.kyokouauth.core.text.RichText
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One message, two renderings.
 *
 * The proxy renders a kick screen with Adventure and the limbo renders chat with its own NBT
 * component, so a message can silently lose its colour or its link on one of the two paths without
 * any test noticing. Both conversions are asserted on the same input for that reason.
 */
class ComponentsTest {

    private val parsed = RichText.parse(
        "<#ff0000>danger</#ff0000> <click:open_url:'https://example.com'>link</click>",
    )

    @Test
    fun `the limbo component keeps colour and link`() {
        val component = parsed.toLimbo()

        assertEquals("", component.text)
        assertEquals(null, component.color, "a neutral parent must not leak red into following spans")
        assertEquals(3, component.children.size)
        assertEquals("danger", component.children[0].text)
        assertEquals("#ff0000", component.children[0].color)

        val linked = component.children[2]
        assertEquals("link", linked.text)
        assertEquals(TextComponent.Click.OpenUrl("https://example.com"), linked.click)
    }

    @Test
    fun `the adventure component keeps colour and link`() {
        val component = parsed.toAdventure()

        val children = component.children()
        assertEquals(3, children.size)

        assertEquals(TextColor.color(0xff0000), children[0].color())

        // The target itself is asserted in the limbo conversion above; what matters here is that the
        // click survives at all, because a link rendered as plain text cannot be used.
        assertNotNull(children[2].clickEvent())
    }

    @Test
    fun `named colours map by name`() {
        val component = RichText.parse("<aqua>tip</aqua>").toAdventure()

        assertEquals(NamedTextColor.AQUA, component.children()[0].color())
    }

    @Test
    fun `decorations survive`() {
        val component = RichText.parse("<bold>loud</bold>").toAdventure()

        assertEquals(
            TextDecoration.State.TRUE,
            component.children()[0].decoration(TextDecoration.BOLD),
        )
        assertTrue(component.children()[0].decoration(TextDecoration.ITALIC) != TextDecoration.State.TRUE)
    }
}
