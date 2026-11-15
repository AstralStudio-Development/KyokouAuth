package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.identity.GameName
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The short-lived record of "this UUID was admitted at pre-login and owes something at join".
 *
 * Two things make this necessary rather than convenient:
 *
 *  - **Admission and join are separate events.** A decision made in the async pre-login has to
 *    survive until the same connection reaches the play phase, and nothing else carries that state.
 *  - **The same UUID can arrive twice.** Vanilla disconnects the older session when a newer one
 *    presents the same UUID, so an attacker who knows a bound name can open a second connection
 *    while the real player is still joining. If both connections shared one slot, the second would
 *    overwrite the first, and whichever joined second would find nothing waiting for it - and,
 *    crucially, would be let into the world without ever verifying. Reserving atomically and
 *    refusing the duplicate closes that hole.
 *
 * Entries expire because a connection that is admitted but never joins leaves no other trace: there
 * is no quit event for a connection that never became a player.
 */
class LoginRegistry(
    private val ttlMillis: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * @property verification non-null when this connection must pass through Limbo. A null value
     *   means the session was already trusted and the player may play immediately.
     */
    class Pending(
        val uuid: UUID,
        val name: GameName,
        val address: String,
        val verification: AuthCoordinator.Decision.Verify?,
        val reservedAt: Long,
    )

    sealed class Reservation {
        object Granted : Reservation()

        /**
         * Another connection for the same UUID is already between pre-login and join. Refusing is
         * the safe answer: allowing it would mean one of the two ends up unverified.
         */
        object AlreadyInFlight : Reservation()
    }

    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun reserve(entry: Pending): Reservation {
        evictStale()
        return if (pending.putIfAbsent(entry.uuid, entry) == null) {
            Reservation.Granted
        } else {
            Reservation.AlreadyInFlight
        }
    }

    /**
     * Takes the entry for this UUID, if it is still fresh.
     *
     * A null result must be treated by the caller as "let this connection play unverified", which is
     * why it is never null for a connection the registry admitted: see [AuthCoordinator].
     */
    fun consume(uuid: UUID): Pending? {
        val entry = pending.remove(uuid) ?: return null
        return if (clock() - entry.reservedAt > ttlMillis) null else entry
    }

    /** Drops an entry whose connection died before it could join. */
    fun release(uuid: UUID) {
        pending.remove(uuid)
    }

    fun size(): Int = pending.size

    private fun evictStale() {
        val now = clock()
        pending.entries.removeAll { now - it.value.reservedAt > ttlMillis }
    }
}
