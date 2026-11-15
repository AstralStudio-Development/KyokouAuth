package moe.ryuu.kyokouauth.bukkit.listener

import moe.ryuu.kyokouauth.bukkit.KyokouAuthPlugin
import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.LoginRegistry
import moe.ryuu.kyokouauth.core.identity.GameName
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

/**
 * The login gate.
 *
 * Runs on the async pre-login event so that the database and Mojang are never touched from the main
 * thread. The decision itself lives in [AuthCoordinator]; this listener only translates it into a
 * profile, a reservation and a kick message.
 *
 * The join handler is **fail-closed**: a player whose connection has no reservation is removed from
 * the server. That can only happen if a reservation expired or was lost, and the alternative -
 * treating "no record" as "already trusted" - would turn any bookkeeping mistake into an
 * authentication bypass.
 */
class LoginListener(private val plugin: KyokouAuthPlugin) : Listener {

    private val registry = LoginRegistry()

    /** Drops any reservation belonging to a connection that died before it could join. */
    fun release(uuid: UUID) = registry.release(uuid)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        val address = event.address?.hostAddress ?: "0.0.0.0"

        // Floodgate is consulted *before* the name is parsed, and that order is the whole point.
        //
        // A Bedrock name carries Floodgate's prefix (`.` by default), and `GameName.parse` rejects
        // exactly that - deliberately, since it is what keeps a Bedrock name out of the name table.
        // But parsing first meant the rejection happened before anything had asked Floodgate who
        // this connection was: an unlinked Bedrock player was kicked as an invalid name and never
        // reached the branch below. Linking is performed from inside the server, so that ordering
        // made the Bedrock path unreachable.
        val floodgate = plugin.floodgate
        val bedrock = floodgate != null && floodgate.isBedrockPlayer(event.uniqueId)

        val name = GameName.parse(event.name)
            ?: if (bedrock) GameName.forBedrock(event.name) else null

        if (name == null) {
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                plugin.kickText("login.invalid-name"),
            )
            return
        }

        // A player Floodgate authenticated has already signed in to Microsoft - through Geyser,
        // before they ever reached this server. Asking again would be asking the same person to
        // prove the same fact twice, and a Bedrock client has no browser to click a link with.
        //
        // This is checked before the database and before Mojang: a Bedrock player consumes no name
        // slot and makes no network call.
        if (bedrock) {
            admitBedrock(event, name, address)
            return
        }

        // A completed Mojang handshake is the strongest proof available: Mojang itself confirmed that
        // this client owns the account. It short-circuits every other path.
        val handshakeProject = plugin.injector.consume(name.value, address)
        if (handshakeProject != null) {
            admitPremium(handshakeProject, name, address, event)
            return
        }

        val decision = try {
            plugin.core.auth.decide(name, address)
        } catch (failure: Throwable) {
            plugin.logger.severe("Login decision failed for ${name.value}: ${failure.message}")
            failure.printStackTrace()
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                plugin.kickText("login.internal-error"),
            )
            return
        }

        when (decision) {
            is AuthCoordinator.Decision.Admit -> {
                if (!reserve(decision.identity.uuid, name, address, null, event)) return
                val approved = fireLoginEvent(
                    moe.ryuu.kyokouauth.bukkit.api.KyokouAuthLoginEvent(
                        uuid = decision.identity.uuid,
                        name = name,
                        firstBind = false,
                        requiresVerification = false,
                        trusted = true,
                        record = decision.bound,
                    ),
                )
                if (!approved) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.kickText("login.refused-by-plugin"))
                    registry.release(decision.identity.uuid)
                    return
                }
                plugin.profiles.apply(event, decision.identity.uuid, name, decision.bound)
            }

            is AuthCoordinator.Decision.Verify -> {
                // The connection is admitted under the UUID it will use for now. Whether that is the
                // real one or a placeholder decides what happens when verification succeeds.
                if (!reserve(decision.uuid, name, address, decision, event)) return
                val approved = fireLoginEvent(
                    moe.ryuu.kyokouauth.bukkit.api.KyokouAuthLoginEvent(
                        uuid = decision.uuid,
                        name = name,
                        firstBind = decision.existing == null,
                        requiresVerification = true,
                        trusted = false,
                        record = decision.existing,
                    ),
                )
                if (!approved) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.kickText("login.refused-by-plugin"))
                    registry.release(decision.uuid)
                    return
                }
                plugin.profiles.apply(event, decision.uuid, name, decision.existing)
            }

            is AuthCoordinator.Decision.Reject -> {
                // A player whose own name was claimed by a Mojang account cannot fix this themselves -
                // they have no Java Edition to handshake with. Nothing else in the plugin reports it,
                // so the operator would otherwise never learn it happened.
                if (decision.reason == AuthCoordinator.RejectReason.PREMIUM_NAME_CLAIMED) {
                    plugin.consoleWarn(
                        "console.name-claimed",
                        "name" to name.value,
                        "detail" to decision.detail,
                    )
                }

                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    rejectMessage(decision),
                )
            }
        }
    }

    /**
     * Admits a Bedrock player Floodgate has already authenticated.
     *
     * Floodgate resolves the connection to the right identity before this event exists: an unlinked
     * player arrives as `UUID(0, xuid)` with a prefixed name, and a linked one arrives as the Java
     * account's UUID and name. Both are taken as given - this method does not second-guess them, and it
     * does not touch the name table either way, because the name table exists to arbitrate Java claims
     * and a Bedrock connection never makes one.
     */
    private fun admitBedrock(
        event: AsyncPlayerPreLoginEvent,
        name: GameName,
        address: String,
    ) {
        if (!reserve(event.uniqueId, name, address, null, event)) return

        val approved = fireLoginEvent(
            moe.ryuu.kyokouauth.bukkit.api.KyokouAuthLoginEvent(
                uuid = event.uniqueId,
                name = name,
                firstBind = false,
                requiresVerification = false,
                trusted = true,
                record = null,
            ),
        )
        if (!approved) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.kickText("login.refused-by-plugin"))
            registry.release(event.uniqueId)
            return
        }

        // Floodgate has already set this connection's profile - that is how it arrived carrying its
        // UUID and name - so this call only supplies what Floodgate cannot: the skin. It must never be
        // able to fail the login, since Floodgate's profile is already usable on its own.
    try {
        plugin.profiles.apply(event, event.uniqueId, name, null)
    } catch (failure: Throwable) {
        plugin.logger.fine("kept Floodgate's profile for ${event.name}: ${failure.message}")
    }
}



    /**
     * Claims the single slot for this UUID, refusing a duplicate connection.
     *
     * The duplicate is a real attack rather than a curiosity: vanilla disconnects the older session
     * when a newer one presents the same UUID, so without this two connections using one bound name
     * would share a slot, and one of them would reach the world with nothing waiting for it.
     */
    private fun reserve(
        uuid: UUID,
        name: GameName,
        address: String,
        verification: AuthCoordinator.Decision.Verify?,
        event: AsyncPlayerPreLoginEvent,
    ): Boolean {
        val entry = LoginRegistry.Pending(
            uuid = uuid,
            name = name,
            address = address,
            verification = verification,
            reservedAt = System.currentTimeMillis(),
        )
        return when (registry.reserve(entry)) {
            LoginRegistry.Reservation.Granted -> true
            LoginRegistry.Reservation.AlreadyInFlight -> {
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.kickText("login.concurrent"),
                )
                false
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val entry = registry.consume(player.uniqueId)

        if (entry == null) {
            // No reservation means this connection never passed the gate. Letting it play would be
            // an authentication bypass, so it leaves instead.
            plugin.logger.warning("${player.name} joined without a login reservation; disconnecting.")
            plugin.server.scheduler.runTask(plugin, Runnable {
                player.kickPlayer(plugin.kickText("login.internal-error"))
            })
            return
        }

        val verify = entry.verification ?: return

        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.limbo.hold(player)
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                plugin.verification.start(player, entry.name, entry.address, verify.provisional)
            })
        })
    }


    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        registry.release(player.uniqueId)

        val pending = plugin.verification.pendingFor(player.uniqueId)
        plugin.verification.forget(player.uniqueId)
        plugin.limbo.release(player)

        // A player who leaves while unverified under a provisional UUID leaves nothing worth
        // keeping: that UUID is a placeholder no future connection will use again, so the data would
        // sit on disk forever as an orphan.
        if (pending != null && pending.provisional) {
            discardProvisionalData(player.uniqueId, pending.name)
        }
    }

    /**
     * Removes the on-disk profile of `uuid`.
     *
     * Runs off the main thread and after the player has fully disconnected, because the server
     * writes `playerdata` during the quit sequence - deleting first would just be overwritten.
     */
    private fun discardProvisionalData(uuid: UUID, name: GameName) {
        plugin.io.execute {
            // If the bind succeeded after all, the provisional row never existed, but guard anyway:
            // deleting the data of a real identity would destroy a player's inventory.
            if (plugin.core.identities.byUuid(uuid) != null) return@execute

            val playerData = listOf("$uuid.dat", "$uuid.dat_old")
            var removed = 0

            plugin.server.worlds.forEach { world ->
                playerData.forEach { file ->
                    val target = java.io.File(world.worldFolder, "playerdata/$file")
                    if (target.isFile && target.delete()) removed++
                }
            }

            // Stats and advancements live under the main world, keyed by UUID, not per world.
            val main = plugin.server.worlds.first().worldFolder
            listOf("stats/$uuid.json", "advancements/$uuid.json").forEach { relative ->
                val target = java.io.File(main, relative)
                if (target.isFile && target.delete()) removed++
            }

            if (removed > 0) {
                plugin.logger.fine("discarded $removed provisional data files for ${name.value}")
            }
        }
    }

    /**
     * Admits a player whose identity Mojang has just confirmed.
     *
     * No Limbo and no device code: the handshake already proved the claim, so the only remaining
     * question is whether the name is free to bind - which is what `bindPremium` answers.
     */
    private fun admitPremium(
        profile: moe.ryuu.kyokouauth.core.auth.MojangSession.Profile,
        name: GameName,
        address: String,
        event: AsyncPlayerPreLoginEvent,
    ) {
        val profileName = GameName.parse(profile.name)
        if (profileName == null) {
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                plugin.kickText("login.invalid-name"),
            )
            return
        }

        when (val outcome = plugin.core.auth.bindPremium(profile.uuid, profileName)) {
            is AuthCoordinator.ClaimOutcome.Bound -> {
                if (!reserve(profile.uuid, profileName, address, null, event)) return

                // Fired here too: a plugin listening for logins must see *every* admitted player,
                // not only the ones that came through the device-code path.
                val approved = fireLoginEvent(
                    moe.ryuu.kyokouauth.bukkit.api.KyokouAuthLoginEvent(
                        uuid = profile.uuid,
                        name = profileName,
                        // `createdAt` is only ever set on a fresh row, and `lastSeenAt` is always
                        // this login's timestamp - so them matching means this call created the
                        // binding. An existing row cannot match unless two logins land in the same
                        // millisecond, which would only mislabel a `firstBind` flag for one event.
                        firstBind = outcome.record.createdAt == outcome.record.lastSeenAt,
                        requiresVerification = false,
                        trusted = true,
                        record = outcome.record,
                    ),
                )
                if (!approved) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.kickText("login.refused-by-plugin"))
                    registry.release(profile.uuid)
                    return
                }

                plugin.core.auth.grantSession(profile.uuid, address)
                plugin.profiles.apply(event, profile.uuid, profileName, outcome.record)
            }

            is AuthCoordinator.ClaimOutcome.Refused -> {
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.kickText(
                        "login.name-taken",
                    ),
                )
            }
        }
    }

    /**
     * Fires the API event without letting a listener break the login.
     *
     * A third-party listener that throws would otherwise abort this handler before the profile is
     * applied, which silently leaves the player on the offline UUID - a correctness bug blamed on
     * the plugin that did nothing wrong. The event is only consulted for its cancelled flag.
     */
    private fun fireLoginEvent(event: moe.ryuu.kyokouauth.bukkit.api.KyokouAuthLoginEvent): Boolean = try {
        plugin.server.pluginManager.callEvent(event)
        !event.isCancelled
    } catch (failure: Throwable) {
        plugin.logger.warning("A listener of KyokouAuthLoginEvent failed and was ignored: ${failure.message}")
        true
    }

    private fun rejectMessage(decision: AuthCoordinator.Decision.Reject): String =
        when (decision.reason) {
            AuthCoordinator.RejectReason.INVALID_NAME -> plugin.kickText("login.invalid-name")
            AuthCoordinator.RejectReason.NAME_TAKEN -> plugin.kickText("login.name-taken")
            AuthCoordinator.RejectReason.PREMIUM_NAME -> plugin.kickText("login.premium-name")
            AuthCoordinator.RejectReason.PREMIUM_NAME_CLAIMED -> plugin.kickText("login.premium-name-claimed")
            AuthCoordinator.RejectReason.IDENTITY_RENAMED ->
                plugin.kickText("login.identity-renamed")
            AuthCoordinator.RejectReason.DIRECTORY_UNAVAILABLE ->
                plugin.kickText("login.directory-unavailable")
            AuthCoordinator.RejectReason.PROXY_FORWARDING -> plugin.kickText("login.proxy")
            AuthCoordinator.RejectReason.INTERNAL_ERROR -> plugin.kickText("login.internal-error")
        }
}
