package moe.ryuu.kyokouauth.bukkit.message

import moe.ryuu.kyokouauth.core.text.LegacyRenderer
import moe.ryuu.kyokouauth.core.text.RichText
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * The last-resort backend: no Adventure, no BungeeChat.
 *
 * The sign-in link stops being clickable here, which matters - the whole reason Limbo exists is that
 * a clickable URL is the only usable way to hand someone a device code. This backend therefore also
 * makes the messages spell the URL out in full, so the player can still type it.
 */
internal class PlainMessenger : Messenger {

    override val backendName: String = "plain"

    override fun send(sender: CommandSender, message: RichText.Parsed) {
        sender.sendMessage(legacy(message))
    }

    override fun send(player: Player, message: RichText.Parsed) = send(player as CommandSender, message)

    override fun showTitle(player: Player, title: RichText.Parsed, subtitle: RichText.Parsed) = Unit

    override fun legacy(message: RichText.Parsed): String = LegacyRenderer.render(message)

    override fun probe(message: RichText.Parsed) {
        // Nothing richer to exercise: the §-code renderer is the only thing this backend does.
        LegacyRenderer.render(message)
    }
}
