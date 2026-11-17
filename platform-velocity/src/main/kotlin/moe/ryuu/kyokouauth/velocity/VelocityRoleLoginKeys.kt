package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.network.ProtocolState
import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.LoginPhaseConnection
import java.util.UUID

/**
 * Read-only guard for role/chooser profile construction, not the ordinary Mojang session path.
 * A signed key is never cleared, reassigned or carried to a different UUID. Opaque initial
 * connections cannot establish that a key is absent and therefore cannot use role mutation.
 */
internal object VelocityRoleLoginKeys {
    fun verify(connection: InboundConnection, roleId: UUID) {
        check(connection is LoginPhaseConnection) { "Role key inspection requires a native login-phase connection" }
        check(connection.isActive && connection.protocolState == ProtocolState.LOGIN) { "Role key inspection must remain in active LOGIN" }
        val key = connection.identifiedKey ?: return
        check(key.signatureHolder == roleId) { "A different or unbound signed key cannot be carried to this role" }
        check(key.isSignatureValid && !key.hasExpired()) { "Role signed key is invalid or expired" }
    }
}
