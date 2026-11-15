package moe.ryuu.kyokouauth.bukkit.message

import moe.ryuu.kyokouauth.core.text.RichText
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Delivers rendered messages, abstracting over what the server actually provides.
 *
 * Adventure exists from Paper 1.16.5 and is what gives a chat line a clickable URL. A 1.8 server has
 * neither Adventure nor Paper's chat event, but Spigot does ship BungeeChat - which also carries
 * click events. Two backends, one interface, chosen once at startup by what is on the classpath.
 *
 * Titles are the one operation with no universal equivalent: 1.8 has no Bukkit API for them at all.
 * The legacy backend drops them rather than reaching into NMS, because the same information is
 * already in the chat message.
 */
interface Messenger {

    fun send(sender: CommandSender, message: RichText.Parsed)

    fun send(player: Player, message: RichText.Parsed)

    fun showTitle(player: Player, title: RichText.Parsed, subtitle: RichText.Parsed)

    /**
     * The `§`-coded form, for the places that can only take a String - chiefly
     * `AsyncPlayerPreLoginEvent#disallow`, which accepts a String on every version and a Component
     * only on Paper.
     */
    fun legacy(message: RichText.Parsed): String

    /**
     * Renders without delivering, for the startup self-check.
     *
     * The check exists to prove the backend can turn a message into something this server
     * understands. Delivering it to the console instead would print an instruction ("click this link
     * to sign in") that no operator asked for and every operator will misread.
     */
    fun probe(message: RichText.Parsed)

    /**
     * Which backend this is, for the startup line.
     *
     * Reported rather than logged here: the console line goes through the locale file like every
     * other operator-facing message, and this class has no business knowing about the locale.
     */
    val backendName: String

    companion object {
        /**
         * Picks the richest backend the server can support.
         *
         * The probe is a class lookup rather than a version comparison: a server can have Adventure
         * without being a Paper build this plugin has ever seen, and a version number would get that
         * wrong in both directions.
         */
        fun create(): Messenger {
            val hasAdventure = runCatching {
                Class.forName("net.kyori.adventure.text.Component")
            }.isSuccess

            val hasBungeeChat = runCatching {
                Class.forName("net.md_5.bungee.api.chat.TextComponent")
            }.isSuccess

            return when {
                hasAdventure -> AdventureMessenger()
                hasBungeeChat -> BungeeChatMessenger()
                else -> PlainMessenger()
            }
        }
    }
}
