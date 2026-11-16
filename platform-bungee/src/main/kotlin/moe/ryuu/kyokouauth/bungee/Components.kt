package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.core.text.RichText
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent

/** Sibling spans retain RGB, Unicode and the complete clickable URL without legacy-text conversion. */
internal fun RichText.Parsed.toBungee(): BaseComponent = TextComponent("").also { root ->
    spans.forEach { span ->
        root.addExtra(TextComponent(span.text).also {
            it.color = when (val colour = span.style.colour) {
                is RichText.Colour.Hex -> ChatColor.of("#%06x".format(colour.rgb))
                is RichText.Colour.Named -> ChatColor.of(colour.name.lowercase())
                null -> ChatColor.WHITE
            }
            it.setBold(span.style.bold)
            it.setItalic(span.style.italic)
            it.setUnderlined(span.style.underlined)
            it.setStrikethrough(false)
            it.setObfuscated(false)
            it.clickEvent = span.clickUrl?.let { url -> ClickEvent(ClickEvent.Action.OPEN_URL, url) }
        })
    }
}

internal fun unavailable() = RichText.parse("<red>Authentication is unavailable. Please try again later.</red>")
