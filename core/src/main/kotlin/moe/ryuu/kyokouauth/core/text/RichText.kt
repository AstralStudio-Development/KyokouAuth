package moe.ryuu.kyokouauth.core.text

/**
 * The message markup, parsed into a platform-neutral model.
 *
 * ## Why not MiniMessage
 *
 * MiniMessage is the obvious choice and was the original one - until a Paper 1.16.5 run failed with
 * `ClassNotFoundException: net.kyori.adventure.text.minimessage.MiniMessage`. Paper bundles Adventure
 * from 1.16.5 but not the MiniMessage parser, so depending on it silently raised the plugin's floor
 * from 1.16.5 to 1.20.6. That is a third of the supported range lost to a formatting convenience.
 *
 * So this is a **deliberately small subset**, and being a subset is the point: it covers exactly
 * what the plugin's messages use, it depends on nothing, and it is testable without a server.
 *
 * ## Supported
 *
 * | Syntax | Meaning |
 * |---|---|
 * | `<red>` … `</red>` | named colour |
 * | `<#RRGGBB>` … `</#RRGGBB>` | hex colour, 1.16 and later |
 * | `<bold>` `<italic>` `<underline>` `</…>` | decoration |
 * | `<newline>` | line break |
 * | `<click:open_url:URL>…</click>` | clickable span |
 *
 * Anything else is literal text rather than an error: a typo in a translation must never make the
 * plugin unable to print a kick message.
 */
object RichText {

    /** A colour the message markup can name. */
    sealed interface Colour {

        /** The sixteen names every client has understood since before 1.8. */
        enum class Named : Colour {
            BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED, DARK_PURPLE, GOLD, GRAY,
            DARK_GRAY, BLUE, GREEN, AQUA, RED, LIGHT_PURPLE, YELLOW, WHITE,
        }

        /** `<#RRGGBB>`: the reason the plugin's floor is 1.16 and not lower. */
        data class Hex(val rgb: Int) : Colour
    }

    data class Style(
        val colour: Colour? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underlined: Boolean = false,
    )

    data class Span(
        val text: String,
        val style: Style,
        val clickUrl: String? = null,
    )

    class Parsed(val spans: List<Span>) {
        val plainText: String get() = spans.joinToString("") { it.text }

        /** Decides whether a message is worth rendering richly at all. */
        val isClickable: Boolean get() = spans.any { it.clickUrl != null }
    }

    private val COLOURS: Map<String, Colour> = mapOf(
        "black" to Colour.Named.BLACK,
        "dark_blue" to Colour.Named.DARK_BLUE,
        "dark_green" to Colour.Named.DARK_GREEN,
        "dark_aqua" to Colour.Named.DARK_AQUA,
        "dark_red" to Colour.Named.DARK_RED,
        "dark_purple" to Colour.Named.DARK_PURPLE,
        "gold" to Colour.Named.GOLD,
        "gray" to Colour.Named.GRAY,
        "grey" to Colour.Named.GRAY,
        "dark_gray" to Colour.Named.DARK_GRAY,
        "dark_grey" to Colour.Named.DARK_GRAY,
        "blue" to Colour.Named.BLUE,
        "green" to Colour.Named.GREEN,
        "aqua" to Colour.Named.AQUA,
        "red" to Colour.Named.RED,
        "light_purple" to Colour.Named.LIGHT_PURPLE,
        "yellow" to Colour.Named.YELLOW,
        "white" to Colour.Named.WHITE,
    )

    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    /**
     * Replaces `<name>` with [value].
     *
     * Values are stripped of angle brackets first. A player name cannot contain them, but a value
     * that did would otherwise be able to inject a tag and change how everything after it renders.
     */
    fun substitute(template: String, values: Map<String, String>): String {
        if (values.isEmpty()) return template
        var result = template
        values.forEach { (name, raw) ->
            val safe = raw.replace("<", "").replace(">", "")
            result = result.replace("<$name>", safe)
        }
        return result
    }

    fun parse(template: String): Parsed {
        val spans = mutableListOf<Span>()
        val pending = StringBuilder()
        // Styles nest, so a closing tag restores whatever was active before its opener rather than
        // resetting to plain. `<white><name></white>` is the common shape and must leave the text
        // after it unchanged.
        val stack = ArrayDeque<Style>()
        var style = Style()
        var click: String? = null
        var index = 0

        fun flush() {
            if (pending.isEmpty()) return
            spans += Span(pending.toString(), style, click)
            pending.setLength(0)
        }

        fun open(next: Style) {
            flush()
            stack.addLast(style)
            style = next
        }

        fun close() {
            flush()
            style = stack.removeLastOrNull() ?: Style()
        }

        while (index < template.length) {
            val character = template[index]
            if (character != '<') {
                pending.append(character)
                index++
                continue
            }

            val closeBracket = template.indexOf('>', index)
            if (closeBracket < 0) {
                // An unterminated tag is literal text, not a parse failure.
                pending.append(character)
                index++
                continue
            }

            val raw = template.substring(index + 1, closeBracket)
            when (val next = interpret(raw)) {
                is Tag.Colour -> open(style.copy(colour = next.colour))
                is Tag.Decoration -> open(next.apply(style))
                Tag.Pop -> close()

                Tag.Break -> {
                    flush()
                    spans += Span("\n", Style(colour = style.colour), click)
                }

                is Tag.ClickOpen -> {
                    flush()
                    click = next.url
                }

                Tag.CloseClick -> {
                    flush()
                    click = null
                }

                Tag.Unknown -> pending.append(template, index, closeBracket + 1)
            }
            index = closeBracket + 1
        }
        flush()
        return Parsed(spans)
    }

    private sealed class Tag {
        class Colour(val colour: RichText.Colour) : Tag()

        class Decoration(val kind: Kind) : Tag() {
            enum class Kind { BOLD_ON, BOLD_OFF, ITALIC_ON, ITALIC_OFF, UNDERLINE_ON, UNDERLINE_OFF }

            fun apply(style: RichText.Style): RichText.Style = when (kind) {
                Kind.BOLD_ON -> style.copy(bold = true)
                Kind.BOLD_OFF -> style.copy(bold = false)
                Kind.ITALIC_ON -> style.copy(italic = true)
                Kind.ITALIC_OFF -> style.copy(italic = false)
                Kind.UNDERLINE_ON -> style.copy(underlined = true)
                Kind.UNDERLINE_OFF -> style.copy(underlined = false)
            }
        }

        /** Any closing colour or decoration tag; the style stack decides what it restores. */
        object Pop : Tag()
        object Break : Tag()
        class ClickOpen(val url: String) : Tag()
        object CloseClick : Tag()
        object Unknown : Tag()
    }

    private fun interpret(raw: String): Tag {
        val normalised = raw.trim()
        val lower = normalised.lowercase()

        if (lower.startsWith("/")) {
            val name = lower.substring(1)
            return when {
                name in COLOURS || name == "colour" || name == "color" -> Tag.Pop
                HEX.matches(name) -> Tag.Pop
                name == "bold" || name == "italic" || name == "em" ||
                    name == "u" || name == "underlined" -> Tag.Pop
                name == "click" -> Tag.CloseClick
                else -> Tag.Unknown
            }
        }

        COLOURS[lower]?.let { return Tag.Colour(it) }

        if (HEX.matches(lower)) {
            return Tag.Colour(Colour.Hex(lower.substring(1).toInt(16)))
        }

        when (lower) {
            "bold" -> return Tag.Decoration(Tag.Decoration.Kind.BOLD_ON)
            "italic", "em" -> return Tag.Decoration(Tag.Decoration.Kind.ITALIC_ON)
            "u", "underlined" -> return Tag.Decoration(Tag.Decoration.Kind.UNDERLINE_ON)
            "newline", "br" -> return Tag.Break
        }

        if (lower.startsWith("click:")) {
            val parts = normalised.split(':', limit = 3)
            if (parts.size != 3 || !parts[1].equals("open_url", ignoreCase = true)) return Tag.Unknown
            val url = parts[2].trim().trim('\'', '"')
            return if (url.isEmpty()) Tag.Unknown else Tag.ClickOpen(url)
        }

        return Tag.Unknown
    }
}
