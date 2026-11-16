package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.proxy.KyokouAuthCommand
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.TabExecutor

internal class BungeeCommand(private val platform: BungeePlatform, private val command: KyokouAuthCommand) :
    Command(KyokouAuthCommand.NAME, KyokouAuthCommand.PERMISSION, KyokouAuthCommand.ALIAS), TabExecutor {
    override fun hasPermission(sender: CommandSender) = command.hasPermission(platform.source(sender))
    override fun execute(sender: CommandSender, args: Array<out String>) { command.execute(platform.source(sender), args) }
    override fun onTabComplete(sender: CommandSender, args: Array<out String>): Iterable<String> =
        if (hasPermission(sender)) command.suggest(args) else emptyList()
}
