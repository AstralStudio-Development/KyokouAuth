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
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
        var task: org.bukkit.scheduler.BukkitTask? = null
    }

    fun pendingFor(uuid: UUID): Pending? = pending[uuid]

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

        val state = Pending(player.uniqueId, name, address, provisional, challenge)
        pending[player.uniqueId] = state

        // The link is the whole point of Limbo: chat is the only surface in Minecraft that can carry
        // a clickable URL, and the disconnected screen cannot.
        plugin.tell(
            player,
            "verification.prompt",
            "url" to challenge.verificationUriComplete,
            "code" to challenge.userCode,
            "minutes" to (timeoutSeconds / 60).toString(),
        )

        state.task = plugin.server.scheduler.runTaskTimerAsynchronously(
            plugin,
            Runnable { poll(state) },
            pollIntervalSeconds * 20L,
            pollIntervalSeconds * 20L,
        )

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (pending.remove(player.uniqueId, state)) {
                state.task?.cancel()
                plugin.server.getPlayer(player.uniqueId)?.let { stale ->
                    plugin.kick(stale, "verification.expired")
                }
            }
        }, timeoutSeconds * 20L)
    }

    private fun poll(state: Pending) {
        if (state.finished) return

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

            is DeviceCodeFlow.PollResult.SlowDown -> Unit

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

    /**
     * Tries to turn the signed-in Microsoft account into its Mojang profile.
     *
     * A null result is not an error - the account may simply not own Java Edition - but it is also
     * what an XSTS failure or a 403 from the Minecraft API looks like, and those two are very
     * different for the operator. Every step therefore logs its own outcome instead of collapsing
     * into one silent null.
     */

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
    }

    private fun fail(state: Pending, key: String, vararg placeholders: Pair<String, String>) {
        val player = plugin.server.getPlayer(state.playerUuid)
        cancel(state)
        if (player != null) {
            plugin.server.scheduler.runTask(plugin, Runnable { plugin.kick(player, key, *placeholders) })
        }
    }

    private fun cancel(state: Pending) {
        pending.remove(state.playerUuid, state)
        state.task?.cancel()
    }

    /** Drops any verification attached to this connection. Called when the player leaves. */
    fun forget(uuid: UUID) {
        pending.remove(uuid)?.task?.cancel()
    }
}
