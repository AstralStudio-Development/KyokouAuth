package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.text.RichText
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent

/**
 * The two renderings one message needs.
 *
 * A message reaches a player either through the proxy - a kick screen, which is Adventure - or
 * through the limbo, which speaks NBT components. Both start from the same parsed template, so a
 * translation cannot drift between "the message you get when you are refused" and "the message you
 * get while waiting".
 */

/** Each parsed span is a sibling: colours and decorations cannot leak from the first span. */
fun RichText.Parsed.toLimbo(): TextComponent =
    TextComponent(text = "", children = spans.map { it.toLimbo() })

private fun RichText.Span.toLimbo(): TextComponent = TextComponent(
    text = text,
    color = style.colour?.toLimbo(),
    bold = style.bold.takeIf { it },
    italic = style.italic.takeIf { it },
    underlined = style.underlined.takeIf { it },
    click = clickUrl?.let { TextComponent.Click.OpenUrl(it) },
)

private fun RichText.Colour.toLimbo(): String = when (this) {
    is RichText.Colour.Hex -> "#%06x".format(rgb)
    is RichText.Colour.Named -> name.lowercase()
}