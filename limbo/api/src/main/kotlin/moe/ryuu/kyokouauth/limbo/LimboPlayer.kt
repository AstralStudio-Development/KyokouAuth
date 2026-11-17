package moe.ryuu.kyokouauth.limbo

import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import java.util.UUID

/**
 * A player who has finished joining the limbo and is standing in it.
 *
 * The platform sees this handle rather than a channel: everything it needs to say is a message, a
 * title or a disconnect, and all of them are safe to call from any thread. `core` never sees this
 * type, and the protocol layer never learns what the messages mean, which is what keeps the login
 * flow testable without a proxy.
 */
interface LimboPlayer {

    val name: String

    val uuid: UUID

    /** Proxy connection identity, carried by the authenticated forwarding payload. */
    val connectionId: UUID

    /**
     * The address the player connected from, as the proxy forwarded it.
     *
     * Not the socket's address: with modern forwarding the proxy reports the client's real address,
     * and a trusted session is bound to it, so this is the only address worth recording.
     */
    val address: String

    /** A chat line. Rendered as a system message, so it is not affected by chat signing. */
    fun sendMessage(message: TextComponent)

    /**
     * The big text in the middle of the screen.
     *
     * [stay] is in ticks, like the Bukkit scheduler this replaces. Repeating the call restarts the
     * animation, which is how a flashing prompt is built.
     */
    fun showTitle(
        title: TextComponent,
        subtitle: TextComponent? = null,
        fadeIn: Int = 10,
        stay: Int = 60,
        fadeOut: Int = 10,
    )

    fun clearTitle()

    /** Opens the connection-local, server-authoritative two-role menu. Unsupported engines fail closed. */
    fun openRoleSelector(menu: RoleSelectorMenu) {
        throw UnsupportedOperationException("This Limbo engine does not support role selection")
    }

    /** Invalidates any current selector without selecting a role. Safe for legacy player fakes. */
    fun closeRoleSelector() = Unit

    /** Kicks the player with [reason]. Does nothing once the connection has already closed. */
    fun disconnect(reason: TextComponent)

    /** Closes the connection quietly. The player is sent back to where the proxy puts them. */
    fun close()
}

/**
 * Told when a player arrives in the limbo and when they leave it.
 *
 * Calls are serialized on Limbo's lifecycle executor. Implementations must hand blocking network or
 * storage work to their own executor. onJoin follows the original client's player-loaded signal,
 * not merely the server's transmission of a JoinGame packet.
 */
interface LimboListener {

    fun onJoin(player: LimboPlayer)

    /**
     * The connection is gone, whatever the reason: the player was admitted, kicked, or dropped.
     *
     * Any per-connection state - a pending device code, a polling task - must be released here, and
     * only here, so a code can never outlive the player it was issued to.
     */
    fun onLeave(player: LimboPlayer)

    /** Only server-owned option IDs from the current menu are delivered, once, on the lifecycle executor. */
    fun onRoleSelect(player: LimboPlayer, generation: UUID, optionId: String) = Unit

    companion object {
        val None: LimboListener = object : LimboListener {
            override fun onJoin(player: LimboPlayer) = Unit
            override fun onLeave(player: LimboPlayer) = Unit
        }
    }
}

/** Where the limbo writes what went wrong. Kept as one call so a platform can route it to its own log. */
fun interface LimboLogger {

    fun log(message: String, error: Throwable?)

    companion object {
        /** Falls back to the JDK logger, which a proxy setup will already be capturing. */
        val Default: LimboLogger = LimboLogger { message, error ->
            val logger = java.util.logging.Logger.getLogger("KyokouAuth-Limbo")
            if (error != null) logger.log(java.util.logging.Level.WARNING, message, error) else logger.fine(message)
        }
    }
}

/** A message with nothing to attach to it. */
fun LimboLogger.log(message: String) = log(message, null)
