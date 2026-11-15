package moe.ryuu.kyokouauth.bukkit.message

import moe.ryuu.kyokouauth.core.text.LegacyRenderer
import moe.ryuu.kyokouauth.core.text.RichText
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * The backend for Spigot servers that predate Adventure.
 *
 * Spigot has shipped BungeeChat since 1.8, and it carries click events - so the sign-in link stays
 * clickable all the way down. Everything is reflective because those classes are not on
 * `paper-api`'s classpath, and adding a second Bukkit API as a compile dependency would make the
 * build fragile in exactly the way this plugin is trying not to be.
 *
 * Method handles are resolved once at construction; a login sends a handful of messages, but
 * re-resolving a dozen methods per message would still be gratuitous.
 */
internal class BungeeChatMessenger : Messenger {

    override val backendName: String = "bungeechat"

    private val textComponent: Class<*> = Class.forName("net.md_5.bungee.api.chat.TextComponent")
    private val baseComponent: Class<*> = Class.forName("net.md_5.bungee.api.chat.BaseComponent")
    private val chatColor: Class<*> = Class.forName("net.md_5.bungee.api.ChatColor")
    private val clickEvent: Class<*> = Class.forName("net.md_5.bungee.api.chat.ClickEvent")

    private val textConstructor = textComponent.getConstructor(String::class.java)
    private val addExtra = textComponent.getMethod("addExtra", baseComponent)
    private val setColor = textComponent.getMethod("setColor", chatColor)
    private val setBold = textComponent.getMethod("setBold", java.lang.Boolean::class.java)
    private val setItalic = textComponent.getMethod("setItalic", java.lang.Boolean::class.java)
    private val setUnderlined = textComponent.getMethod("setUnderlined", java.lang.Boolean::class.java)
    private val setClickEvent = textComponent.getMethod("setClickEvent", clickEvent)

    private val clickConstructor = runCatching {
        clickEvent.getConstructor(
            Class.forName("net.md_5.bungee.api.chat.ClickEvent\$Action"),
            String::class.java,
        )
    }.getOrNull()

    private val openUrlAction = runCatching {
        java.lang.Enum.valueOf(
            Class.forName("net.md_5.bungee.api.chat.ClickEvent\$Action") as Class<out Enum<*>>,
            "OPEN_URL",
        )
    }.getOrNull()

    /** `ChatColor.valueOf(name)` - the enum constant names match our own exactly. */
    private val colours: Map<RichText.Colour, Any> = RichText.Colour.entries.associateWith { colour ->
        val valueOf = chatColor.getMethod("valueOf", String::class.java)
        valueOf.invoke(null, colour.name)
    }

    override fun send(sender: CommandSender, message: RichText.Parsed) {
        try {
            val component = toComponent(message)
            val spigot = sender.javaClass.methods.firstOrNull {
                it.name == "spigot" && it.parameterCount == 0
            }?.invoke(sender) ?: return

            val sendMessage = spigot.javaClass.methods.firstOrNull {
                it.name == "sendMessage" && it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(baseComponent)
            }
            if (sendMessage != null) {
                sendMessage.invoke(spigot, component)
            } else {
                // No BungeeChat delivery path on this object: fall back to the plain text.
                sender.sendMessage(legacy(message))
            }
        } catch (_: Throwable) {
            // A formatting failure must never cost the player their kick reason or their link.
            sender.sendMessage(legacy(message))
        }
    }

    override fun send(player: Player, message: RichText.Parsed) = send(player as CommandSender, message)

    /**
     * Dropped on legacy servers.
     *
     * Bukkit exposed no title API before 1.11, and reaching into NMS to draw one would mean a
     * per-version packet class for the sake of an effect the chat message already conveys.
     */
    override fun showTitle(player: Player, title: RichText.Parsed, subtitle: RichText.Parsed) = Unit

    override fun legacy(message: RichText.Parsed): String = LegacyRenderer.render(message)

    override fun probe(message: RichText.Parsed) {
        // Built and discarded; the reflection here is what the self-check is meant to exercise.
        toComponent(message)
    }

    private fun toComponent(parsed: RichText.Parsed): Any {
        val root = textConstructor.newInstance("")
        parsed.spans.forEach { span ->
            val part = textConstructor.newInstance(span.text)
            span.style.colour?.let { setColor.invoke(part, colours[it]) }
            if (span.style.bold) setBold.invoke(part, true)
            if (span.style.italic) setItalic.invoke(part, true)
            if (span.style.underlined) setUnderlined.invoke(part, true)

            val url = span.clickUrl
            if (url != null && clickConstructor != null && openUrlAction != null) {
                runCatching {
                    setClickEvent.invoke(part, clickConstructor?.newInstance(openUrlAction, url))
                }
            }
            addExtra.invoke(root, part)
        }
        return root
    }
}
