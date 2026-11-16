package moe.ryuu.kyokouauth.bukkit.verification

import moe.ryuu.kyokouauth.bukkit.KyokouAuthCore
import moe.ryuu.kyokouauth.bukkit.KyokouAuthPlugin
import moe.ryuu.kyokouauth.bukkit.Messages
import moe.ryuu.kyokouauth.bukkit.limbo.LimboManager
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.XboxAuthException
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Xuid
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one device-code verification at a time per connection.
 *
 * The lifecycle is bound to the connection, not to the name: a verification whose player has
 * disconnected is dropped immediately, so a code can never be completed by someone else and then
 * applied to a later login.
 */
class VerificationService(
    private val plugin: KyokouAuthPlugin,
    private val core: KyokouAuthCore,
    private val messages: Messages,
    private val limbo: LimboManager,
    private val pollIntervalSeconds: Long,
    private val timeoutSeconds: Int,
) {

    private val pending = ConcurrentHashMap<UUID, Pending>()

    class Pending(
        val playerUuid: UUID,
        val name: GameName,
        val address: String,
        /** True when the UUID the player is currently using is a placeholder. */
        val provisional: Boolean,
        internal val challenge: DeviceCodeFlow.Challenge,
    ) {
        @Volatile
        var finished = false

        @Volatile
        internal var task: org.bukkit.scheduler.BukkitTask? = null

        @Volatile
        internal var intervalTicks: Long = 0L

        /**
         * Held for the duration of one poll.
         *
         * The scheduler runs this on a pool, so two ticks can overlap when a request is slow. Without
         * this guard both would reach [complete] and bind the same account twice.
         */
        internal val polling = AtomicBoolean(false)
    }

    fun pendingFor(uuid: UUID): Pending? = pending[uuid]

    /** How many sign-ins are in flight, for the operator's status line. */
    fun pendingCount(): Int = pending.size

    /**
     * Issues a fresh code for a player who is already waiting in Limbo.
     *
     * The remedy for a code that never worked - a closed browser tab, a mistyped link, a phone that
     * never loaded the page - would otherwise be to disconnect and reconnect, which costs the player
     * their provisional data and their place in the queue. [start] already replaces the old attempt,
     * so this only has to find the connection and hand it over.
     *
     * Blocking: it asks Microsoft for a code. Call it from an IO thread.
     */
    fun restart(uuid: UUID) {
        val state = pending[uuid] ?: return
        val player = plugin.server.getPlayer(uuid) ?: return
        start(player, state.name, state.address, state.provisional)
    }

    /**
     * Starts verification for a player who is already in the world and in Limbo.
     *
     * Fails loudly (and releases the player) if Microsoft cannot even issue a code - leaving someone
     * frozen with no code to type is worse than letting them in on the next attempt.
     */
    fun start(player: Player, name: GameName, address: String, provisional: Boolean) {
        val challenge = try {
            DeviceCodeFlow.begin()
        } catch (failure: Exception) {
            plugin.logger.warning("Could not start a Microsoft sign-in for ${player.name}: ${failure.message}")
            plugin.kick(player, "verification.start-failed")
            return
        }

        // A previous attempt for this UUID is replaced, so its timer has to stop first or it keeps
        // polling a code nobody will ever type.
        pending.put(player.uniqueId, Pending(player.uniqueId, name, address, provisional, challenge))
            ?.let { stale -> stale.finished = true; stale.task?.cancel() }

        val state = pending[player.uniqueId]!!

        plugin.tell(
            player,
            "verification.prompt",
            "url" to challenge.verificationUriComplete,
            "code" to challenge.userCode,
            "minutes" to (timeoutSeconds / 60).toString(),
        )

        state.intervalTicks = (pollIntervalSeconds * 20L).coerceAtLeast(20L)
        schedule(state)

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (pending.remove(player.uniqueId, state)) {
                state.finished = true
                state.task?.cancel()
                plugin.server.getPlayer(player.uniqueId)?.let { stale ->
                    plugin.kick(stale, "verification.expired")
                }
            }
        }, timeoutSeconds * 20L)
    }

    /**
     * (Re)schedules the polling task at the state's current interval.
     *
     * Separate from [start] because Microsoft can ask for a slower cadence mid-flow, and a fixed
     * period cannot honour that.
     */
    private fun schedule(state: Pending) {
        state.task?.cancel()
        state.task = plugin.server.scheduler.runTaskTimerAsynchronously(
            plugin,
            Runnable { poll(state) },
            state.intervalTicks,
            state.intervalTicks,
        )
    }

    private fun poll(state: Pending) {
        if (state.finished) return

        // One poll at a time. A slow request must not let the next tick start a second one.
        if (!state.polling.compareAndSet(false, true)) return
        try {
            val player = plugin.server.getPlayer(state.playerUuid)
            if (player == null) {
                cancel(state)
                return
            }

            val result = try {
                DeviceCodeFlow.poll(state.challenge)
            } catch (failure: Exception) {
                plugin.logger.warning("Microsoft sign-in poll failed for ${state.name}: ${failure.message}")
                return
            }

            when (result) {
                is DeviceCodeFlow.PollResult.Pending -> Unit

                is DeviceCodeFlow.PollResult.SlowDown -> {
                    // Microsoft asked for a slower cadence. Ignoring it escalates the throttle and can
                    // end with the client id blocked, which would break sign-in for every server using
                    // this plugin. Back off, capped so the code still expires on time.
                    if (!state.finished) {
                        state.intervalTicks = backoffTicks(state.intervalTicks)
                        plugin.server.scheduler.runTask(plugin, Runnable { schedule(state) })
                    }
                }

                is DeviceCodeFlow.PollResult.Expired -> {
                    cancel(state)
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        plugin.kick(player, "verification.expired")
                    })
                }

                is DeviceCodeFlow.PollResult.Declined -> {
                    cancel(state)
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        plugin.kick(player, "verification.declined")
                    })
                }

                is DeviceCodeFlow.PollResult.Authorized -> {
                    state.finished = true
                    complete(state, result.accessToken)
                }
            }
        } finally {
            state.polling.set(false)
        }
    }

    private fun complete(state: Pending, accessToken: String) {
        val xsts = try {
            XboxLive.exchange(accessToken, XboxLive.RELYING_PARTY_PROFILE)
        } catch (failure: XboxAuthException) {
            fail(state, "verification.xbox-failed", "reason" to failure.message)
            return
        } catch (failure: Exception) {
            plugin.logger.warning("Xbox token exchange failed for ${state.name}: ${failure.message}")
            fail(state, "verification.retry")
            return
        }

        val xuid = Xuid(xsts.xuid)

        // A device code only ever produces a Microsoft identity. Names owned by Mojang never reach
        // this point - `AuthCoordinator.decide` rejects them before Limbo, because a premium name has
        // exactly one door: the Mojang handshake. There is no "the account also owns Java" branch
        // here, and that is deliberate; see RejectReason.PREMIUM_NAME.
        val outcome = core.auth.bindMicrosoft(state.name, xuid, xsts.gamertag)

        when (outcome) {
            is moe.ryuu.kyokouauth.core.auth.AuthCoordinator.ClaimOutcome.Bound -> {
                core.auth.grantSession(outcome.record.uuid, state.address)
                plugin.server.pluginManager.callEvent(
                    moe.ryuu.kyokouauth.bukkit.api.KyokouAuthVerifyEvent(
                        uuid = outcome.record.uuid,
                        name = outcome.record.name,
                        premium = outcome.record.kind == moe.ryuu.kyokouauth.core.storage.BoundIdentity.Kind.MOJANG,
                        gamertag = outcome.record.gamertag,
                        record = outcome.record,
                    ),
                )
                plugin.server.scheduler.runTask(plugin, Runnable { admit(state, outcome.record) })
            }

            is moe.ryuu.kyokouauth.core.auth.AuthCoordinator.ClaimOutcome.Refused ->
                fail(state, "verification.refused", "reason" to outcome.detail)
        }
    }

    private fun admit(state: Pending, record: moe.ryuu.kyokouauth.core.storage.BoundIdentity) {
        val player = plugin.server.getPlayer(state.playerUuid) ?: return
        cancel(state)

        if (state.provisional) {
            // The UUID changes now, so the connection cannot continue: the player must reconnect and
            // land on their real identity. Nothing they did in Limbo matters, so the data is dropped.
            plugin.kick(player, "verification.reconnect")
            return
        }

        limbo.release(player)
        plugin.tell(player, "verification.success", "name" to record.name.value)
        plugin.postLoginConnect(player)
    }

    private fun fail(state: Pending, key: String, vararg placeholders: Pair<String, String>) {
        val player = plugin.server.getPlayer(state.playerUuid)
        cancel(state)
        if (player != null) {
            plugin.server.scheduler.runTask(plugin, Runnable { plugin.kick(player, key, *placeholders) })
        }
    }

    private fun cancel(state: Pending) {
        state.finished = true
        pending.remove(state.playerUuid, state)
        state.task?.cancel()
    }

    /** Drops any verification attached to this connection. Called when the player leaves. */
    fun forget(uuid: UUID) {
        pending.remove(uuid)?.let { state ->
            state.finished = true
            state.task?.cancel()
        }
    }

    companion object {
        /**
         * One minute in ticks: the slowest Microsoft's back-off may push the poll interval to.
         *
         * A ceiling is needed because a sign-in that keeps being told to slow down must still
         * finish, and the code expires on a timer that does not move. A minute between polls against
         * a five-minute code leaves room for five attempts.
         */
        internal const val MAX_INTERVAL_TICKS = 60_000L / 50

        /** The interval to use after Microsoft answered `SlowDown` once. */
        internal fun backoffTicks(current: Long): Long = (current * 2).coerceAtMost(MAX_INTERVAL_TICKS)
    }
}
