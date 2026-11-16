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
            usage(sender)
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
            "verify" -> verify(sender, args)
            "session" -> session(sender, args)
            "setspawn" -> setSpawn(sender)
            else -> plugin.tell(sender, "command.unknown-subcommand", "name" to args[0])
        }
        return true
    }

    /**
     * The command list, one line per subcommand.
     *
     * A single colour for every line is what made this read as a wall: the eye had nothing to land
     * on. The name is aqua, the arguments are dim, the description is grey and a `»` marks the
     * start, so the name is findable without reading the line.
     */
    private fun usage(sender: CommandSender) {
        listOf(
            "usage-header",
            "usage-whois",
            "usage-session",
            "usage-verify",
            "usage-unbind",
            "usage-rename",
            "usage-untrust",
            "usage-setspawn",
            "usage-status",
            "usage-reload",
        ).forEach { plugin.tell(sender, "command.$it") }
    }

    /** One line: the subcommand's own signature, shown when its arguments are missing. */
    private fun missingArgument(sender: CommandSender, usageKey: String) {
        plugin.tell(sender, "command.$usageKey")
    }

    private fun reload(sender: CommandSender) {
        plugin.reloadSettings()
        plugin.tell(sender, "command.reloaded")
    }

    private fun status(sender: CommandSender) {
        val config = plugin.configuration
        val limit = config.directoryBudget
        val used = MojangDirectory.budgetUsage()

        plugin.tell(
            sender,
            "command.status",
            "dialect" to config.database.dialect.displayName,
            "session" to config.sessionHours.toString(),
            "locale" to config.locale,
            "verifying" to plugin.verification.pendingCount().toString(),
            "budget" to if (limit <= 0) "$used（不限）" else "$used/$limit",
            "cached" to plugin.core.identities.size().toString(),
        )
    }

    private fun whois(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)?.let(GameName::parse)
        if (name == null) {
            missingArgument(sender, "usage-whois")
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
                        "kind" to when (record.kind) {
                            moe.ryuu.kyokouauth.core.storage.BoundIdentity.Kind.MOJANG -> "premium"
                            moe.ryuu.kyokouauth.core.storage.BoundIdentity.Kind.XUID -> "microsoft"
                        },
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
            missingArgument(sender, "usage-unbind")
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
            missingArgument(sender, "usage-rename")
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
            missingArgument(sender, "usage-untrust")
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

    /**
     * Issues a fresh device code for a player who is already stuck in Limbo.
     *
     * The player's own remedy is to reconnect, which works but costs them whatever provisional
     * progress they had. This does the same thing without the disconnect.
     */
    private fun verify(sender: CommandSender, args: Array<out String>) {
        val target = args.getOrNull(1)?.let { plugin.server.getPlayerExact(it) }
        if (target == null) {
            plugin.tell(sender, "command.unknown-player", "name" to (args.getOrNull(1) ?: ""))
            return
        }

        val uuid = target.uniqueId
        val name = target.name

        if (plugin.verification.pendingFor(uuid) == null) {
            plugin.tell(sender, "command.not-verifying", "name" to name)
            return
        }

        // Asks Microsoft for a code, so it cannot run on the main thread.
        plugin.io.execute {
            plugin.verification.restart(uuid)
            plugin.server.scheduler.runTask(plugin, Runnable {
                plugin.tell(sender, "command.verify-resent", "name" to name)
            })
        }
    }

    /** Shows whether a player still holds a trusted session, and from where. */
    private fun session(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)?.let(GameName::parse)
        if (name == null) {
            missingArgument(sender, "usage-session")
            return
        }

        plugin.io.execute {
            val record = plugin.core.identities.byName(name)
            val session = record?.let { plugin.core.sessions.find(it.uuid) }

            plugin.server.scheduler.runTask(plugin, Runnable {
                when {
                    record == null ->
                        plugin.tell(sender, "command.unknown-name", "name" to name.value)

                    session == null ->
                        plugin.tell(sender, "command.session-none", "name" to name.value)

                    else -> {
                        val minutes = ((session.expiresAt - System.currentTimeMillis()) / 60_000L)
                            .coerceAtLeast(0L)
                        plugin.tell(
                            sender,
                            "command.session",
                            "name" to name.value,
                            "address" to session.address,
                            "minutes" to minutes.toString(),
                        )
                    }
                }
            })
        }
    }

    /** Records the operator's current position as the place held players are put. */
    private fun setSpawn(sender: CommandSender) {
        val player = sender as? Player
        if (player == null) {
            plugin.tell(sender, "command.setspawn-player-only")
            return
        }

        val location = player.location
        plugin.setLimboSpawn(location)

        plugin.tell(
            sender,
            "command.setspawn-done",
            "world" to (location.world?.name ?: "?"),
            "x" to "%.1f".format(location.x),
            "y" to "%.1f".format(location.y),
            "z" to "%.1f".format(location.z),
        )
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = when {
        args.size == 1 -> listOf(
            "whois", "unbind", "rename", "untrust", "status", "reload", "verify", "session", "setspawn",
        ).filter { it.startsWith(args[0].lowercase()) }

        args.size == 2 -> plugin.server.onlinePlayers.map { it.name }

        else -> emptyList()
    }
}
