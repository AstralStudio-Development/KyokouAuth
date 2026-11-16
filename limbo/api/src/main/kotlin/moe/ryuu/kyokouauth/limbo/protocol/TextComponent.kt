package moe.ryuu.kyokouauth.limbo.protocol

/** Public message value: deliberately contains no protocol-library or proxy types. */
data class TextComponent(
    val text: String,
    val color: String? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underlined: Boolean? = null,
    val strikethrough: Boolean? = null,
    val click: Click? = null,
    val children: List<TextComponent> = emptyList(),
) {
    sealed interface Click {
        val value: String
        data class OpenUrl(override val value: String) : Click
        data class CopyToClipboard(override val value: String) : Click
        data class RunCommand(override val value: String) : Click
    }
    fun child(child: TextComponent): TextComponent = copy(children = children + child)
    companion object { fun of(text: String) = TextComponent(text) }
}
