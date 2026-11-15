package moe.ryuu.kyokouauth.bukkit.command

import moe.ryuu.kyokouauth.bukkit.KyokouAuthPlugin
import moe.ryuu.kyokouauth.core.auth.MojangDirectory
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.storage.ClaimResult
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

/**
 * `/kyokouauth` - the operator surface.
 *
 * Every subcommand that touches storage runs asynchronously; the reply is scheduled back onto the
 * main thread because a `CommandSender` is not guaranteed to be thread-safe.
 */
class KyokouAuthCommand(private val plugin: KyokouAuthPlugin) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            plugin.tell(sender, "command.usage")
            return true
        }

        if (!sender.hasPermission("kyokouauth.admin")) {
            plugin.tell(sender, "command.no-permission")
            return true
        }

        when (args[0].lowercase()) {
            "reload" -> reload(sender)
            "status" -> status(sender)
            "whois" -> whois(sender, args)
            "unbind" -> unbind(sender, args)
            "rename" -> rename(sender, args)
            "untrust" -> untrust(sender, args)
            else -> plugin.tell(sender, "command.usage")
        }
        return true
    }

    private fun reload(sender: CommandSender) {
        plugin.reloadSettings()
        plugin.tell(sender, "command.reloaded")
    }

    private fun status(sender: CommandSender) {
        val config = plugin.configuration
        plugin.tell(
            sender,
            "command.status",
            "dialect" to config.database.dialect.displayName,
            "session" to config.sessionHours.toString(),
            "locale" to config.locale,
        )
    }

    private fun whois(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)?.let(GameName::parse)
        if (name == null) {
            plugin.tell(sender, "command.usage")
            return
        }
        plugin.io.execute {
            val record = plugin.core.identities.byName(name)
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (record == null) {
                    plugin.tell(sender, "command.unknown-name", "name" to name.value)
                } else {
                    plugin.tell(
                        sender,
                        "command.whois",
                        "name" to record.name.value,
                        "uuid" to record.uuid.toString(),
                        "kind" to record.kind.name,
                        "subject" to record.subject,
                        "gamertag" to (record.gamertag ?: "-"),
                    )
                }
            })
        }
    }

    private fun unbind(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)?.let(GameName::parse)
        if (name == null) {
            plugin.tell(sender, "command.usage")
            return
        }
        plugin.io.execute {
            val record = plugin.core.identities.byName(name)
            if (record == null) {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    plugin.tell(sender, "command.unknown-name", "name" to name.value)
                })
                return@execute
            }
            plugin.core.auth.revokeSession(record.uuid)
            plugin.core.identities.unbind(record.uuid)
            plugin.server.scheduler.runTask(plugin, Runnable {
                plugin.tell(sender, "command.unbound", "name" to record.name.value)
            })
        }
    }

    private fun rename(sender: CommandSender, args: Array<out String>) {
        val from = args.getOrNull(1)?.let(GameName::parse)
        val to = args.getOrNull(2)?.let(GameName::parse)
        if (from == null || to == null) {
            plugin.tell(sender, "command.usage")
            return
        }
        plugin.io.execute {
            val record = plugin.core.identities.byName(from) ?: run {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    plugin.tell(sender, "command.unknown-name", "name" to from.value)
                })
                return@execute
            }
            val result = plugin.core.identities.rename(record.uuid, to, System.currentTimeMillis())
            plugin.server.scheduler.runTask(plugin, Runnable {
                when (result) {
                    is ClaimResult.Bound -> plugin.tell(sender, "command.renamed", "from" to from.value, "to" to to.value)

                    is ClaimResult.NameTaken -> plugin.tell(
                        sender,
                        "command.rename-taken",
                        "name" to to.value,
                        "owner" to result.by.name.value,
                    )

                    else -> plugin.tell(sender, "command.internal-error")
                }
                // A rename while the player is online would leave them on a name the database no
                // longer agrees with, so they are moved off the server to re-resolve it.
                plugin.server.getPlayer(record.uuid)?.let { player ->
                    plugin.kick(player, "login.identity-renamed")
                }
            })
        }
    }

    private fun untrust(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)?.let(GameName::parse)
        if (name == null) {
            plugin.tell(sender, "command.usage")
            return
        }
        plugin.io.execute {
            val record = plugin.core.identities.byName(name)
            if (record == null) {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    plugin.tell(sender, "command.unknown-name", "name" to name.value)
                })
                return@execute
            }
            plugin.core.auth.revokeSession(record.uuid)
            plugin.server.scheduler.runTask(plugin, Runnable {
                plugin.tell(sender, "command.untrusted", "name" to record.name.value)
            })
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = when {
        args.size == 1 -> listOf("whois", "unbind", "rename", "untrust", "status", "reload")
            .filter { it.startsWith(args[0].lowercase()) }

        args.size == 2 -> plugin.server.onlinePlayers.map { it.name }

        else -> emptyList()
    }
}
