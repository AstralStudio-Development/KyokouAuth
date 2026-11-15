package moe.ryuu.kyokouauth.bukkit.api

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired after a connection has been resolved to an identity, before it enters the world.
 *
 * **Fired from the async pre-login thread**, and declared async because of it. Bukkit throws
 * `IllegalStateException: ... may only be triggered synchronously` when a synchronous event is
 * fired off the main thread - the exception then aborts the login handler *before* the profile is
 * applied, which would leave every player on the offline UUID. Listeners must not touch the world.
 *
 * Deliberately carries the resulting [uuid] and [name] rather than a mutable profile: a listener
 * that could change them would be one that could break the invariant that a UUID is a pure function
 * of the identity. Plugins that need to react to a first join should key off [firstBind].
 */
class KyokouAuthLoginEvent(
    val uuid: UUID,
    val name: GameName,
    /** True when this identity had no binding before this login. */
    val firstBind: Boolean,
    /** True when the player still owes a verification and will enter Limbo. */
    val requiresVerification: Boolean,
    /**
     * True when the identity was proven by a Mojang handshake or an existing trusted session rather
     * than by a fresh Microsoft sign-in.
     */
    val trusted: Boolean,
    val record: BoundIdentity?,
) : Event(true), Cancellable {

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    /**
     * Cancelling sends the player away. Allowed so that a permission plugin can refuse a login, but
     * a cancelled event is never treated as "already verified".
     */
    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = companionHandlers

    companion object {
        private val companionHandlers = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = companionHandlers
    }
}

/** Fired once a verification has succeeded and the identity is bound. Cannot be cancelled. */
class KyokouAuthVerifyEvent(
    val uuid: UUID,
    val name: GameName,
    /** True when the player proved ownership of a Mojang account rather than only an Xbox profile. */
    val premium: Boolean,
    val gamertag: String?,
    val record: BoundIdentity,
) : Event(true) {

    override fun getHandlers(): HandlerList = companionHandlers

    companion object {
        private val companionHandlers = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = companionHandlers
    }
}
