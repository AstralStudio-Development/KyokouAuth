package moe.ryuu.kyokouauth.core.text

/**
 * Renders [RichText] into the `§`-coded string that every Minecraft version understands.
 *
 * This is the universal fallback. It loses click events, which is why chat uses a richer backend
 * where one exists - but it is what makes kick messages work from 1.8 to 26.3 without a single
 * Adventure type being present on the classpath.
 */
object LegacyRenderer {

    private val CODES: Map<RichText.Colour, Char> = mapOf(
        RichText.Colour.BLACK to '0',
        RichText.Colour.DARK_BLUE to '1',
        RichText.Colour.DARK_GREEN to '2',
        RichText.Colour.DARK_AQUA to '3',
        RichText.Colour.DARK_RED to '4',
        RichText.Colour.DARK_PURPLE to '5',
        RichText.Colour.GOLD to '6',
        RichText.Colour.GRAY to '7',
        RichText.Colour.DARK_GRAY to '8',
        RichText.Colour.BLUE to '9',
        RichText.Colour.GREEN to 'a',
        RichText.Colour.AQUA to 'b',
        RichText.Colour.RED to 'c',
        RichText.Colour.LIGHT_PURPLE to 'd',
        RichText.Colour.YELLOW to 'e',
        RichText.Colour.WHITE to 'f',
    )

    /**
     * Flattens spans into one §-coded string.
     *
     * Style is re-emitted whenever it changes rather than once per span: a span boundary that only
     * exists because of a colour change would otherwise carry redundant codes.
     */
    fun render(parsed: RichText.Parsed): String {
        val out = StringBuilder()
        var current: RichText.Style? = null

        parsed.spans.forEach { span ->
            if (span.style != current) {
                CODES[span.style.colour ?: RichText.Colour.WHITE]?.let { out.append('§').append(it) }
                if (span.style.bold) out.append("§l")
                if (span.style.italic) out.append("§o")
                if (span.style.underlined) out.append("§n")
                current = span.style
            }
            out.append(span.text)
        }
        return out.toString()
    }

    /**
     * Renders without any formatting.
     *
     * Used where even `§` codes are unwelcome - chiefly version-pinned debug output and placeholders.
     */
    fun plain(parsed: RichText.Parsed): String = parsed.plainText
}
