package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.ProxyServer
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.proxy.AuthService
import moe.ryuu.kyokouauth.proxy.ProxyConfig
import moe.ryuu.kyokouauth.proxy.KyokouAuthCommand as SharedCommand

/** Native command wrapper; validation, IO and administrative semantics are shared. */
class KyokouAuthCommand(
    server: ProxyServer,
    config: ProxyConfig,
    messages: MessageCatalogue,
    auth: AuthService,
    private val gate: LoginGate,
    reloadMessages: () -> Unit,
) : SimpleCommand {
    private val command = SharedCommand(gate.ports, config, messages, auth, gate.common, reloadMessages)
    override fun hasPermission(invocation: SimpleCommand.Invocation) = command.hasPermission(gate.ports.commandSource(invocation.source()))
    override fun execute(invocation: SimpleCommand.Invocation) = command.execute(gate.ports.commandSource(invocation.source()), invocation.arguments())
    override fun suggest(invocation: SimpleCommand.Invocation) = command.suggest(invocation.arguments())
    companion object {
        const val NAME = SharedCommand.NAME
        const val ALIAS = SharedCommand.ALIAS
        const val PERMISSION = SharedCommand.PERMISSION
    }
}
