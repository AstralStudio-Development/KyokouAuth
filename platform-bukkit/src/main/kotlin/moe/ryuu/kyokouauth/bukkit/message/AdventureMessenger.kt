package moe.ryuu.kyokouauth.bukkit.message

import moe.ryuu.kyokouauth.core.text.LegacyRenderer
import moe.ryuu.kyokouauth.core.text.RichText
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Duration

/**
 * The backend for every server that has Adventure - Paper from 1.16.5 onward.
 *
 * This class references Adventure types directly, so it must never be loaded on a server without
 * them. [Messenger.create] guarantees that by checking the classpath first.
 */
internal class AdventureMessenger : Messenger {

    override val backendName: String = "adventure"

    override fun send(sender: CommandSender, message: RichText.Parsed) {
        sender.sendMessage(component(message))
    }

    override fun send(player: Player, message: RichText.Parsed) {
        player.sendMessage(component(message))
    }

    override fun showTitle(player: Player, title: RichText.Parsed, subtitle: RichText.Parsed) {
        player.showTitle(
            Title.title(
                component(title),
                component(subtitle),
                Title.Times.times(
                    Duration.ofMillis(200),
                    Duration.ofSeconds(4),
                    Duration.ofMillis(400),
                ),
            ),
        )
    }

    override fun legacy(message: RichText.Parsed): String = LegacyRenderer.render(message)

    override fun probe(message: RichText.Parsed) {
        // Built and discarded: reaching here at all proves Adventure linked and rendered.
        component(message)
    }

    /**
     * Builds the component without `Component.text()`'s builder `build()`.
     *
     * Adventure changed that method's return type between the 4.x line and the 5.x line; the JVM
     * links on the exact descriptor, so a plugin compiled against one and run on the other throws
     * `NoSuchMethodError`. `Component.empty()` plus `append` has an identical signature on both.
     */
    private fun component(parsed: RichText.Parsed): Component {
        var result: Component = Component.empty()
        parsed.spans.forEach { span ->
            var part: Component = Component.text(span.text)
            span.style.colour?.let { part = part.color(colour(it)) }
            if (span.style.bold) part = part.decorate(TextDecoration.BOLD)
            if (span.style.italic) part = part.decorate(TextDecoration.ITALIC)
            if (span.style.underlined) part = part.decorate(TextDecoration.UNDERLINED)
            span.clickUrl?.let { part = part.clickEvent(ClickEvent.openUrl(it)) }
            result = result.append(part)
        }
        return result
    }

    private fun colour(value: RichText.Colour): NamedTextColor = when (value) {
        RichText.Colour.BLACK -> NamedTextColor.BLACK
        RichText.Colour.DARK_BLUE -> NamedTextColor.DARK_BLUE
        RichText.Colour.DARK_GREEN -> NamedTextColor.DARK_GREEN
        RichText.Colour.DARK_AQUA -> NamedTextColor.DARK_AQUA
        RichText.Colour.DARK_RED -> NamedTextColor.DARK_RED
        RichText.Colour.DARK_PURPLE -> NamedTextColor.DARK_PURPLE
        RichText.Colour.GOLD -> NamedTextColor.GOLD
        RichText.Colour.GRAY -> NamedTextColor.GRAY
        RichText.Colour.DARK_GRAY -> NamedTextColor.DARK_GRAY
        RichText.Colour.BLUE -> NamedTextColor.BLUE
        RichText.Colour.GREEN -> NamedTextColor.GREEN
        RichText.Colour.AQUA -> NamedTextColor.AQUA
        RichText.Colour.RED -> NamedTextColor.RED
        RichText.Colour.LIGHT_PURPLE -> NamedTextColor.LIGHT_PURPLE
        RichText.Colour.YELLOW -> NamedTextColor.YELLOW
        RichText.Colour.WHITE -> NamedTextColor.WHITE
    }
}
