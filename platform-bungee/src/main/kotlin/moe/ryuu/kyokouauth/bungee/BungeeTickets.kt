package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.limbo.PrivateTicketAuthority
import moe.ryuu.kyokouauth.limbo.TicketIdentity
import moe.ryuu.kyokouauth.proxy.GameProfile
import moe.ryuu.kyokouauth.proxy.RegisteredServer
import net.md_5.bungee.api.connection.PendingConnection
import java.util.function.BooleanSupplier

internal interface ProfileAccess {
    fun read(pending: PendingConnection): GameProfile
    fun apply(pending: PendingConnection, profile: GameProfile)
}

/** Adds credentials only at the final private-engine connect event, strips before every other hop. */
internal class BungeeTickets(
    private val platform: BungeePlatform,
    private val profiles: ProfileAccess,
    private val authority: PrivateTicketAuthority,
) {
    fun prepare(player: BungeePlayer, target: RegisteredServer) = synchronized(player) {
        val protocol = platform.engineProtocol(target)
        if (protocol == null) {
            strip(player)
        } else {
            check(player.isActive && player.loggedIn && player.native != null) { "Frontend is not admitted" }
            check(protocol == player.protocolVersion && platform.nativeServer(target) != null) { "Wrong private engine" }
            check(player.engineAttempt == null) { "Concurrent private engine attempt" }
            val profile = profiles.read(player.pending)
            check(profile.id == player.uniqueId && profile.name == player.username) { "Actual profile identity changed" }
            check(profile.properties.none { it.name == authority.propertyKey }) { "Unexpected pre-existing private ticket" }
            player.engineAttempt = target
            val ticket = try {
                authority.issue(TicketIdentity(player.sessionId, player.uniqueId, player.username,
                    player.remoteAddress.address.hostAddress, protocol), BooleanSupplier {
                    player.isActive && player.loggedIn && player.engineAttempt === target &&
                        platform.engineProtocol(target) == protocol && platform.nativeServer(target) != null
                })
            } catch (failure: Exception) {
                player.engineAttempt = null
                throw failure
            }
            try {
                profiles.apply(player.pending, profile.withProperties(profile.properties +
                    GameProfile.Property(authority.propertyKey, ticket, "")))
            } catch (failure: Exception) {
                authority.revokeTicket(ticket)
                player.engineAttempt = null
                throw failure
            }
        }
    }

    /** Caller must disconnect on any failure, before Bungee can serialize a real backend handshake. */
    fun strip(player: BungeePlayer) = synchronized(player) {
        authority.revoke(player.sessionId)
        player.engineAttempt = null
        val profile = profiles.read(player.pending)
        if (profile.properties.any { it.name == authority.propertyKey || it.name == "kyokouauth:connection-id" }) {
            profiles.apply(player.pending, profile.withProperties(profile.properties.filterNot {
                it.name == authority.propertyKey || it.name == "kyokouauth:connection-id"
            }))
        }
        check(profiles.read(player.pending).properties.none { it.name == authority.propertyKey }) { "Could not strip private ticket" }
    }

    fun revoke(player: BungeePlayer) = synchronized(player) {
        authority.revoke(player.sessionId)
        player.engineAttempt = null
    }

    companion object {
        /** Native LoginSuccess can share the pending profile array. Replace only the packet's view. */
        fun stripClientProperties(packet: net.md_5.bungee.protocol.packet.LoginSuccess) {
            val properties = packet.properties ?: return
            val visible = properties.filterNot {
                it.name == "kyokouauth:private-ticket" || it.name == "kyokouauth:connection-id"
            }
            if (visible.size != properties.size) packet.properties = visible.toTypedArray()
        }
    }
}
