package moe.ryuu.kyokouauth.velocity

import moe.ryuu.kyokouauth.core.text.RichText
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import moe.ryuu.kyokouauth.proxy.toLimbo as toSharedLimbo
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * The two renderings one message needs.
 *
 * A message reaches a player either through the proxy - a kick screen, which is Adventure - or
 * through the limbo, which speaks NBT components. Both start from the same parsed template, so a
 * translation cannot drift between "the message you get when you are refused" and "the message you
 * get while waiting".
 */

/** Each parsed span is a sibling: colours and decorations cannot leak from the first span. */
internal fun RichText.Parsed.toLimbo(): TextComponent = toSharedLimbo()


/** The proxy's component model, used wherever Velocity renders text itself. */
internal fun RichText.Parsed.toAdventure(): Component {
    val builder = Component.text()
    spans.forEach { span ->
        builder.append(
            Component.text(span.text)
                .color(span.style.colour?.toAdventure())
                .decoration(TextDecoration.BOLD, span.style.bold)
                .decoration(TextDecoration.ITALIC, span.style.italic)
                .decoration(TextDecoration.UNDERLINED, span.style.underlined)
                .clickEvent(span.clickUrl?.let { ClickEvent.openUrl(it) }),
        )
    }
    return builder.build()
}

private fun RichText.Colour.toAdventure(): TextColor = when (this) {
    is RichText.Colour.Hex -> TextColor.color(rgb)
    is RichText.Colour.Named -> NamedTextColor.NAMES.value(name.lowercase()) ?: NamedTextColor.WHITE
}
