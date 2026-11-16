package moe.ryuu.kyokouauth.limbo.session

import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

/** The protocol library serializes Adventure, including modern click_event and nameless network NBT. */
internal fun TextComponent.toLibrary(): Component {
    var result = Component.text(text)
    color?.let { raw ->
        result = result.color(TextColor.fromHexString(raw) ?: NamedTextColor.NAMES.value(raw)
            ?: error("Invalid message colour: $raw"))
    }
    bold?.let { result = result.decoration(TextDecoration.BOLD, it) }
    italic?.let { result = result.decoration(TextDecoration.ITALIC, it) }
    underlined?.let { result = result.decoration(TextDecoration.UNDERLINED, it) }
    strikethrough?.let { result = result.decoration(TextDecoration.STRIKETHROUGH, it) }
    click?.let {
        result = result.clickEvent(when (it) {
            is TextComponent.Click.OpenUrl -> ClickEvent.openUrl(it.value)
            is TextComponent.Click.CopyToClipboard -> ClickEvent.copyToClipboard(it.value)
            is TextComponent.Click.RunCommand -> ClickEvent.runCommand(it.value)
        })
    }
    children.forEach { result = result.append(it.toLibrary()) }
    return result
}
