package moe.ryuu.kyokouauth.bukkit.message

import moe.ryuu.kyokouauth.core.text.RichText
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Duration

/**
 * Delivers rendered messages through Adventure.
 *
 * The 1.16 floor is what makes a single backend enough: every server this plugin loads on ships
 * Adventure, so there is no classpath probe and no second implementation to keep in step.
 */
class Messenger {

    fun send(sender: CommandSender, message: RichText.Parsed) {
        sender.sendMessage(component(message))
    }

    fun showTitle(player: Player, title: RichText.Parsed, subtitle: RichText.Parsed) {
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

    /**
     * The `§`-coded form, for the places that can only take a String.
     *
     * Chiefly the login-phase Disconnect packet, whose text field is raw JSON and cannot carry a
     * Component. Clients honour `§` codes there, hex colours included since 1.16.
     */
    fun legacy(message: RichText.Parsed): String = LEGACY.serialize(component(message))

    /**
     * Renders without delivering, for the startup self-check.
     *
     * The check exists to prove the backend can turn a message into something this server
     * understands. Delivering it to the console instead would print an instruction ("click this link
     * to sign in") that no operator asked for and every operator will misread.
     */
    fun probe(message: RichText.Parsed) {
        component(message)
    }

    /**
     * Builds the component without `Component.text()`'s builder `build()`.
     *
     * Adventure changed that method's return type between the 4.x line and the 5.x line; the JVM
     * links on the exact descriptor, so a plugin compiled against one and run on the other throws
     * `NoSuchMethodError`. `Component.empty()` plus `append` has an identical signature on both.
     */
    fun component(parsed: RichText.Parsed): Component {
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

    private fun colour(value: RichText.Colour): TextColor = when (value) {        is RichText.Colour.Hex -> TextColor.color(value.rgb)
        is RichText.Colour.Named -> when (value) {
            RichText.Colour.Named.BLACK -> NamedTextColor.BLACK
            RichText.Colour.Named.DARK_BLUE -> NamedTextColor.DARK_BLUE
            RichText.Colour.Named.DARK_GREEN -> NamedTextColor.DARK_GREEN
            RichText.Colour.Named.DARK_AQUA -> NamedTextColor.DARK_AQUA
            RichText.Colour.Named.DARK_RED -> NamedTextColor.DARK_RED
            RichText.Colour.Named.DARK_PURPLE -> NamedTextColor.DARK_PURPLE
            RichText.Colour.Named.GOLD -> NamedTextColor.GOLD
            RichText.Colour.Named.GRAY -> NamedTextColor.GRAY
            RichText.Colour.Named.DARK_GRAY -> NamedTextColor.DARK_GRAY
            RichText.Colour.Named.BLUE -> NamedTextColor.BLUE
            RichText.Colour.Named.GREEN -> NamedTextColor.GREEN
            RichText.Colour.Named.AQUA -> NamedTextColor.AQUA
            RichText.Colour.Named.RED -> NamedTextColor.RED
            RichText.Colour.Named.LIGHT_PURPLE -> NamedTextColor.LIGHT_PURPLE
            RichText.Colour.Named.YELLOW -> NamedTextColor.YELLOW
            RichText.Colour.Named.WHITE -> NamedTextColor.WHITE
        }
    }

    private companion object {
        /**
         * `§`-coded, with hex enabled.
         *
         * `legacySection()` alone does **not** enable hex: it silently maps `#4FD6E0` onto the
         * nearest named colour, so the disconnect screen would show a different shade from the one
         * the chat message uses. `useUnusualXRepeatedCharacterHexFormat` is the `§x§R§R§G§G§B§B`
         * escape a 1.16 client reads - the reason this plugin's floor is 1.16 at all.
         */
        val LEGACY: LegacyComponentSerializer = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build()
    }
}
