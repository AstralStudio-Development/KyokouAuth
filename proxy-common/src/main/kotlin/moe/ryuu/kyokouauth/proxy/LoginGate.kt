package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.PollBackoff
import moe.ryuu.kyokouauth.core.auth.XboxAuthException
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.core.text.RichText
import moe.ryuu.kyokouauth.limbo.LimboListener
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The login gate: who may enter, who waits, and who is turned away.
 *
 * Three events make this possible, and the order matters:
 *
 *  - [PreLoginEvent] is the decision, before the proxy has told the client anything. It waits for an
 *    answer, so the database and Mojang can be consulted without blocking a proxy thread.
 *  - [PlayerChooseInitialServerEvent] is where a player who has to verify is pointed at the limbo
 *    instead of a real backend.
 *  - [ServerPreConnectEvent] is every move after that - `/server`, a plugin, the proxy's own failover -
 *    which is the one thing a backend server could never do: refuse to let a held player leave.
 *
 * A player who passes the decision with a trusted session never sees the limbo at all.
 */
class LoginGate constructor(
    private val server: ProxyServer,
    private val config: ProxyConfig,
    private val messages: MessageCatalogue,
    private val services: Services,
    private val logger: ProxyLog,
    private val clock: () -> Long = System::currentTimeMillis,
) : LimboListener {

    constructor(server: ProxyServer, config: ProxyConfig, messages: MessageCatalogue, auth: AuthService, logger: ProxyLog) :
        this(server, config, messages, liveServices(auth), logger)

    /** External work is replaceable in tests; the gate and its event ordering remain the real ones. */
    interface Services {
        fun allow(key: String): Boolean
        fun decide(name: GameName, address: String): AuthCoordinator.Decision
        fun bindPremium(uuid: UUID, name: GameName): AuthCoordinator.ClaimOutcome
        fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String): AuthCoordinator.ClaimOutcome
        fun grantSession(uuid: UUID, address: String)
        fun submit(task: () -> Unit)
        fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*>
        fun scheduleAtFixedRate(periodMillis: Long, task: () -> Unit): ScheduledFuture<*>
        fun begin(): DeviceCodeFlow.Challenge
        fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult
        fun exchange(accessToken: String): XboxLive.XstsToken
    }

    /** Decisions cross login events by Velocity's connection session id, never by claimed name. */
    private class Pending(
        val connection: InboundConnection,
        val name: GameName,
        val address: String,
        val decision: AuthCoordinator.Decision,
        val createdAt: Long,
    ) {
        val provisional = (decision as? AuthCoordinator.Decision.Verify)?.provisional == true
    }

    private enum class Access { HELD, AUTHENTICATED, CLOSED }

    /** All callbacks retain this exact Player, even if a new connection claims the same UUID. */
    private class Connection(val player: Player, val pending: Pending) {
        @Volatile var access = Access.HELD
        var verification: Verifying? = null
        var arrivalTimeout: ScheduledFuture<*>? = null
    }

    private class Verifying(
        val player: LimboPlayer,
        val connection: Connection,
        val locale: String?,
    ) {
        val pending get() = connection.pending
        @Volatile
        var finished = false

        @Volatile
        var intervalMillis: Long = MIN_POLL_MILLIS

        @Volatile
        var poll: ScheduledFuture<*>? = null

        var timeout: ScheduledFuture<*>? = null
        var titleShown = false

        /** Restart creates a new Verifying object; stale work can never become current again. */
        @Volatile
        var deadlineMillis: Long = Long.MAX_VALUE

        /** One poll at a time: a slow request must not let the next tick start a second. */
        val busy = AtomicBoolean(false)
    }

    private val premiumHandshakes = PremiumHandshakeGuard(
        baseBackoffMillis = config.sessionFailureBackoffSeconds * 1000L,
        maxBackoffMillis = config.sessionFailureMaxBackoffSeconds * 1000L,
        handshakeTimeoutMillis = config.sessionHandshakeTimeoutSeconds * 1000L,
        minRetryMillis = config.sessionRetrySeconds * 1000L,
        globalLimit = config.sessionHandshakeLimit,
        clock = clock,
    )
    private val premiumWatches = mutableMapOf<UUID, ScheduledFuture<*>>()
    private val pending = ConcurrentHashMap<UUID, Pending>()
    private val connections = ConcurrentHashMap<UUID, Connection>()

    /** Armed only after the listener and the registered limbo are both ready. */
    @Volatile private var ready = false

    fun activate() { ready = true }

    fun deactivate() {
        ready = false
        synchronized(premiumWatches) {
            premiumWatches.values.forEach { it.cancel(false) }
            premiumWatches.clear()
            premiumHandshakes.clear()
        }
        pending.clear()
        connections.values.forEach { connection ->
            synchronized(connection) {
                close(connection)
                connection.player.disconnect(component("login.internal-error", localeOf(connection.player)))
            }
        }
        connections.clear()
    }

    /** When the budget-exhaustion warning was last written, so it cannot become the flood. */
    @Volatile
    private var lastThrottleWarning: Long = 0L

    // --- who may enter ---------------------------------------------------------------------

    fun onPreLogin(event: PreLoginEvent) {
        pending.remove(event.connection.sessionId)
        if (!ready || !event.connection.isActive) {
            event.result = denied("login.internal-error")
            return
        }
        if (!event.result.isAllowed) return
        val name = GameName.parse(event.username)
        if (name == null) {
            event.result = denied("login.invalid-name")
            return
        }

        val remote = event.connection.remoteAddress
        val address = remote.address?.hostAddress ?: remote.hostString

        // The limiter is keyed more coarsely than the session: see [limitKey].
        if (!services.allow(limitKey(remote.address))) {
            logger.info("Refused {}: too many connection attempts from {}", name, address)
            event.result = denied("login.too-many-connections")
            return
        }

        sweepPending()

        // Deliberately inline. Velocity reads this event's result as soon as the handler returns -
        // `PreLoginEvent` is not an awaiting event - so handing the work to another thread would let
        // the proxy admit the player with a default result that arrives after the decision. The cost
        // is a blocked worker for the duration of one stored-state read plus one Mojang lookup, both
        // of which are cached and rate-limited, and it is paid once per connection.
        decide(event, name, address)
    }

    private fun decide(event: PreLoginEvent, name: GameName, address: String) {
        val decision = try {
            services.decide(name, address)
        } catch (failure: Exception) {
            // A decision that throws must still produce a result: returning with none set would leave
            // the proxy to admit the player on its default, which is the opposite of what we want.
            logger.error("Could not decide for $name", failure)
            event.result = denied("login.internal-error")
            return
        }

        if (!ready || !event.connection.isActive) {
            event.result = denied("login.internal-error")
            return
        }
        if (decision is AuthCoordinator.Decision.MojangSession) {
            // A UUID mismatch is useful only for refusal. Matching this public UUID proves nothing:
            // that connection MUST still pass Velocity's real Mojang session handshake.
            val claimed = event.uniqueId
            if (claimed != null && claimed != decision.expectedUuid) {
                event.result = denied("login.premium-name")
                return
            }
            val source = event.connection.remoteAddress.address
            if (source == null) {
                event.result = denied("login.internal-error")
                return
            }
            val reservation = premiumHandshakes.reserve(source, name, event.connection.sessionId) { event.connection.isActive }
            if (reservation != PremiumHandshakeGuard.Decision.Allowed) {
                val key = when (reservation) {
                    PremiumHandshakeGuard.Decision.InFlight -> "login.premium-in-flight"
                    PremiumHandshakeGuard.Decision.Cooldown -> "login.premium-backoff"
                    PremiumHandshakeGuard.Decision.GlobalBudget, PremiumHandshakeGuard.Decision.Capacity -> "login.premium-busy"
                    else -> "login.internal-error"
                }
                event.result = denied(key)
                return
            }
        }
        if (decision !is AuthCoordinator.Decision.Reject) {
            pending[event.connection.sessionId] = Pending(event.connection, name, address, decision, clock())
        }
        when (decision) {
            is AuthCoordinator.Decision.Admit -> {
                logger.info("{} logged in with a trusted session", name)
                event.result = PreLoginEvent.PreLoginComponentResult.forceOfflineMode()
            }

            // The name is Mojang's, so the proxy runs the session check on this connection. A premium
            // client answers it on its own - the player does nothing - and a client that only typed
            // the name fails here, before it can be given any part of the server.
            is AuthCoordinator.Decision.MojangSession -> {
                try {
                    watchPremium(event.connection.sessionId)
                } catch (_: Exception) {
                    pending.remove(event.connection.sessionId)
                    finishPremium(event.connection.sessionId, verified = false)
                    event.result = denied("login.internal-error")
                    return
                }
                logger.info("{} is a Mojang-owned name; asking the proxy for a session check", name)
                event.result = PreLoginEvent.PreLoginComponentResult.forceOnlineMode()
            }

            is AuthCoordinator.Decision.Verify -> {
                logger.info("{} must verify ({})", name, decision.reason)
                event.result = PreLoginEvent.PreLoginComponentResult.forceOfflineMode()
            }

            is AuthCoordinator.Decision.Reject -> {
                if (decision.reason == AuthCoordinator.RejectReason.DIRECTORY_THROTTLED) {
                    warnThrottled(name, decision.detail)
                } else {
                    logger.info("Refused {}: {} {}", name, decision.reason, decision.detail)
                }
                event.result = if (decision.reason == AuthCoordinator.RejectReason.DIRECTORY_THROTTLED ||
                    decision.reason == AuthCoordinator.RejectReason.DIRECTORY_UNAVAILABLE) {
                    denied(keyFor(decision.reason), "reason" to decision.detail)
                } else denied(keyFor(decision.reason))
            }
        }
    }

    /** Initial-login disconnects have no Player/DisconnectEvent, so observe these leases separately. */
    private fun watchPremium(sessionId: UUID) = synchronized(premiumWatches) {
        if (ready && premiumHandshakes.observe(sessionId)) {
            premiumWatches[sessionId] = services.schedule(1_000L) { monitorPremium(sessionId) }
        }
    }

    private fun monitorPremium(sessionId: UUID) {
        val abandoned = synchronized(premiumWatches) {
            if (!ready) {
                premiumWatches.remove(sessionId)
                null
            } else if (premiumHandshakes.observe(sessionId)) {
                premiumWatches[sessionId] = services.schedule(1_000L) { monitorPremium(sessionId) }
                null
            } else {
                premiumWatches.remove(sessionId)
                pending.remove(sessionId)
            }
        }
        if (abandoned?.decision is AuthCoordinator.Decision.MojangSession && abandoned.connection.isActive) {
            val connection = abandoned.connection
            val reason = component("login.premium-backoff")
            runCatching { connection.disconnect(reason) }
                .onFailure { logger.warn("Could not close expired initial login: type={}; admission remains denied", it.javaClass.simpleName) }
        }
    }

    private fun finishPremium(sessionId: UUID, verified: Boolean): Boolean = synchronized(premiumWatches) {
        val accepted = premiumHandshakes.complete(sessionId, verified)
        premiumWatches.remove(sessionId)?.cancel(false)
        accepted
    }

    /** The marker is inside Velocity's signed forwarding payload, not client-supplied identity. */
    fun onGameProfile(event: GameProfileRequestEvent) {
        val pending = pending[event.connection.sessionId] ?: return
        if (!ready || !pending.connection.isActive || expired(pending)) return
        val uuid = when (val decision = pending.decision) {
            is AuthCoordinator.Decision.Admit -> decision.identity.uuid
            is AuthCoordinator.Decision.Verify -> decision.uuid
            else -> event.gameProfile.id
        }
        val properties = event.gameProfile.properties.filterNot { it.name == CONNECTION_PROPERTY } +
            GameProfile.Property(CONNECTION_PROPERTY, event.connection.sessionId.toString(), "")
        event.gameProfile = event.gameProfile.withId(uuid).withProperties(properties)
    }

    /** Premium storage acceptance is part of admission, not a best-effort write after login. */
    fun onLogin(event: LoginEvent) {
        val player = event.player
        val pending = pending.remove(player.sessionId)
        if (!event.result.isAllowed) {
            finishPremium(player.sessionId, verified = false)
            return
        }
        if (!ready || !player.isActive || pending == null || expired(pending) ||
            !pending.connection.isActive || GameName.parse(player.username)?.key != pending.name.key ||
            addressOf(player) != pending.address
        ) {
            finishPremium(player.sessionId, verified = false)
            event.result = ResultedEvent.ComponentResult.denied(component("login.internal-error"))
            return
        }

        val connection = Connection(player, pending)
        try {
            when (val decision = pending.decision) {
                is AuthCoordinator.Decision.MojangSession -> {
                    check(player.isOnlineMode && player.uniqueId == decision.expectedUuid) { "Mojang profile was not verified for this name" }
                    check(finishPremium(player.sessionId, verified = true)) { "Mojang handshake lease is no longer active" }
                    logger.info("Mojang session handshake for {} completed in {} ms", pending.name, clock() - pending.createdAt)
                    when (val outcome = services.bindPremium(player.uniqueId, pending.name)) {
                        is AuthCoordinator.ClaimOutcome.Bound -> {
                            check(outcome.record.uuid == player.uniqueId) { "Premium UUID changed" }
                            connection.access = Access.AUTHENTICATED
                        }
                        is AuthCoordinator.ClaimOutcome.Refused -> {
                            event.result = ResultedEvent.ComponentResult.denied(component(keyFor(outcome.reason)))
                            return
                        }
                    }
                }
                is AuthCoordinator.Decision.Admit -> {
                    check(player.uniqueId == decision.identity.uuid) { "Trusted identity UUID changed" }
                    connection.access = Access.AUTHENTICATED
                }
                is AuthCoordinator.Decision.Verify -> {
                    check(player.uniqueId == decision.uuid) { "Verification UUID changed" }
                }
                is AuthCoordinator.Decision.Reject -> error("Rejected decision reached login")
            }
            check(ready && player.isActive) { "Connection closed during login" }
            check(connections.putIfAbsent(player.sessionId, connection) == null) { "Duplicate connection session id" }
            if (connection.access == Access.HELD) {
                synchronized(connection) {
                    connection.arrivalTimeout = services.schedule(config.limbo.joinTimeoutSeconds * 1000L) {
                        synchronized(connection) {
                            if (current(connection) && connection.access == Access.HELD && connection.verification == null) {
                                close(connection)
                                player.disconnect(component("login.internal-error", localeOf(player)))
                            }
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            finishPremium(player.sessionId, verified = false)
            close(connection)
            connections.remove(player.sessionId, connection)
            logger.error("Could not authorize {}", player.username, failure)
            event.result = ResultedEvent.ComponentResult.denied(component("login.internal-error"))
        }
    }

    fun onChooseInitialServer(event: PlayerChooseInitialServerEvent) {
        val connection = connectionOf(event.player)
        if (connection != null && authenticated(connection)) return
        val limbo = limboFor(event.player)
        if (ready && connection != null && connection.access == Access.HELD && limbo != null) {
            event.setInitialServer(limbo)
        } else {
            event.setInitialServer(null)
            connection?.let { synchronized(it) { close(it) } }
            val reason = if (event.player.protocolVersion !in 767..777) "limbo.unsupported-version" else "login.internal-error"
            event.player.disconnect(component(reason, localeOf(event.player)))
        }
    }

    /** Every actual target is guarded, including fallback before there is a currentServer. */
    fun onPreConnect(event: ServerPreConnectEvent) {
        val target = event.result.server.orElse(null) ?: return
        val connection = connectionOf(event.player)
        if (connection != null) synchronized(connection) {
            if (authenticated(connection)) return
            if (ready && current(connection) && connection.access == Access.HELD &&
                target === limboFor(event.player)
            ) return
        }
        event.result = ServerPreConnectEvent.ServerResult.denied()
        event.player.sendMessage(component("limbo.command-blocked", localeOf(event.player)))
    }

    fun onKicked(event: KickedFromServerEvent) {
        val connection = connectionOf(event.player)
        if (connection != null && authenticated(connection)) return
        connection?.let { synchronized(it) { close(it) } }
        event.result = KickedFromServerEvent.DisconnectPlayer.create(component("login.internal-error", localeOf(event.player)))
    }

    fun onDisconnect(event: DisconnectEvent) {
        val player = event.player
        pending.remove(player.sessionId)
        finishPremium(player.sessionId, verified = false)
        val connection = connectionOf(player) ?: return
        synchronized(connection) {
            close(connection)
            connections.remove(player.sessionId, connection)
        }
    }

    // --- the limbo -------------------------------------------------------------------------

    override fun onJoin(player: LimboPlayer) {
        val connection = connections[player.connectionId]
        if (connection == null) {
            player.disconnect(limboText("login.internal-error"))
            return
        }
        synchronized(connection) {
            if (!ready || !current(connection) || connection.access != Access.HELD ||
                connection.player.uniqueId != player.uuid || connection.pending.name.value != player.name ||
                connection.pending.address != player.address || connection.verification != null
            ) {
                player.disconnect(limboText("login.internal-error"))
                return
            }
            val state = Verifying(player, connection, localeOf(connection.player))
            connection.arrivalTimeout?.cancel(false)
            connection.verification = state
            logger.info("{} ({}) is verifying in the limbo from {}", player.name, player.uuid, player.address)
            try {
                armVerification(state)
                services.submit { startSignIn(state) }
            } catch (failure: Exception) {
                fail(state, "verification.start-failed")
            }
        }
    }

    override fun onLeave(player: LimboPlayer) {
        val connection = connections[player.connectionId] ?: return
        synchronized(connection) {
            val state = connection.verification ?: return
            if (state.player !== player) return
            finish(state)
            connection.verification = null
            if (connection.access == Access.HELD) {
                close(connection)
                connection.player.disconnect(component("login.internal-error", state.locale))
            }
        }
    }

    // --- one sign-in ----------------------------------------------------------------------

    /** The deadline is armed before queueing IO, so an exhausted worker pool cannot hold forever. */
    private fun armVerification(state: Verifying) {
        val timeoutMillis = config.verificationTimeoutSeconds.coerceAtLeast(1) * 1000L
        state.deadlineMillis = clock() + timeoutMillis
        state.timeout = services.schedule(timeoutMillis) { expire(state) }
    }

    /** Blocking work never holds the connection lock or the scheduler while calling Microsoft. */
    private fun startSignIn(state: Verifying) {
        synchronized(state.connection) {
            if (!active(state)) return
            state.player.sendMessage(limboText("verification.starting", state.locale))
        }
        val startedAt = clock()
        val challenge = try {
            services.begin()
        } catch (failure: DeviceCodeFlow.DeviceCodeException) {
            logger.warn("Microsoft sign-in initialization for {} failed: code={}, httpStatus={}, elapsedMs={}",
                state.pending.name, failure.code.name, failure.httpStatus, clock() - startedAt)
            val message = when (failure.code) {
                DeviceCodeFlow.FailureCode.TIMEOUT, DeviceCodeFlow.FailureCode.TRANSPORT,
                DeviceCodeFlow.FailureCode.UPSTREAM_UNAVAILABLE -> "verification.start-unavailable"
                DeviceCodeFlow.FailureCode.RATE_LIMITED -> "verification.start-busy"
                else -> "verification.start-failed"
            }
            fail(state, message)
            return
        } catch (failure: Exception) {
            logger.warn("Microsoft sign-in initialization for {} failed: type={}", state.pending.name, failure.javaClass.simpleName)
            fail(state, "verification.start-failed")
            return
        }
        synchronized(state.connection) {
            if (!active(state)) return
            if (clock() >= state.deadlineMillis) {
                expire(state)
                return
            }
            state.intervalMillis = challenge.intervalSeconds.coerceAtLeast(1) * 1000L
            try {
                showPrompt(state, challenge)
                schedulePoll(state, challenge)
            } catch (failure: Exception) {
                fail(state, "verification.start-failed")
            }
        }
    }

    /** How many sign-ins are in flight, for `/kyokouauth status`. */
    fun verifyingCount(): Int = connections.values.count { connection ->
        synchronized(connection) { connection.verification?.let(::active) == true }
    }

    /**
     * Issues a fresh code to a player who is already waiting in the limbo.
     *
     * The remedy for a code that never arrived - a closed browser tab, a phone that never loaded the
     * page - would otherwise be a reconnect. Blocking: asks Microsoft for a code, so it belongs on
     * the IO pool like every other network call here.
     */
    fun restartVerification(uuid: UUID): Boolean =
        server.getPlayer(uuid).orElse(null)?.let(::restartVerification) ?: false

    /** Commands capture this exact handle before queueing IO; a reconnect cannot inherit the work. */
    fun restartVerification(player: Player): Boolean {
        val connection = connectionOf(player) ?: return false
        val replacement = synchronized(connection) {
            val state = connection.verification ?: return false
            if (!active(state)) return false
            finish(state)
            Verifying(state.player, connection, state.locale).also {
                connection.verification = it
                try {
                    armVerification(it)
                } catch (failure: Exception) {
                    fail(it, "verification.start-failed")
                    return false
                }
            }
        }
        startSignIn(replacement)
        return true
    }

    private fun showPrompt(state: Verifying, challenge: DeviceCodeFlow.Challenge) {
        if (!active(state)) return
        val minutes = (config.verificationTimeoutSeconds / 60).coerceAtLeast(1)
        val remainingMillis = (state.deadlineMillis - clock()).coerceAtLeast(0L)
        val stayTicks = (remainingMillis / 50L + if (remainingMillis % 50L == 0L) 0L else 1L)
            .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

        // Show the ready challenge once; re-sending would restart its fade animation.
        state.titleShown = true
        state.player.showTitle(
            title = limboText("limbo.title", state.locale),
            subtitle = limboText("limbo.subtitle", state.locale),
            stay = stayTicks,
        )

        state.player.sendMessage(
            limboText(
                "verification.prompt",
                state.locale,
                "url" to challenge.verificationUriComplete,
                "code" to challenge.userCode,
                "minutes" to minutes.toString(),
            ),
        )
    }

    private fun schedulePoll(state: Verifying, challenge: DeviceCodeFlow.Challenge) = synchronized(state.connection) {
        if (!active(state)) return@synchronized
        try {
            state.poll = services.schedule(state.intervalMillis) {
                try {
                    services.submit { poll(state, challenge) }
                } catch (failure: Exception) {
                    fail(state, "verification.retry")
                }
            }
        } catch (failure: Exception) {
            fail(state, "verification.retry")
        }
    }

    private fun poll(state: Verifying, challenge: DeviceCodeFlow.Challenge) {
        synchronized(state.connection) { if (!active(state)) return }

        // A slow request must not let the next tick start a second poll: two answers would bind the
        // same account twice.
        if (!state.busy.compareAndSet(false, true)) {
            schedulePoll(state, challenge)
            return
        }

        try {
            val result = try {
                services.poll(challenge)
            } catch (failure: DeviceCodeFlow.DeviceCodeException) {
                if (failure.code == DeviceCodeFlow.FailureCode.RATE_LIMITED) {
                    state.intervalMillis = maxOf(state.intervalMillis, failure.retryAfterMillis ?: 60_000L)
                }
                logger.warn("Microsoft sign-in poll for {} failed: code={}, httpStatus={}", state.pending.name, failure.code.name, failure.httpStatus)
                schedulePoll(state, challenge)
                return
            } catch (failure: Exception) {
                logger.warn("Microsoft sign-in poll for {} failed: type={}", state.pending.name, failure.javaClass.simpleName)
                schedulePoll(state, challenge)
                return
            }

            synchronized(state.connection) {
                if (!active(state)) return
                if (clock() >= state.deadlineMillis) {
                    expire(state)
                    return
                }
            }
            when (result) {
                is DeviceCodeFlow.PollResult.Pending -> schedulePoll(state, challenge)

                is DeviceCodeFlow.PollResult.SlowDown -> {
                    // Microsoft asked for a slower cadence; ignoring it escalates the throttle and can
                    // end with the client id blocked, which would break sign-in for everyone.
                    state.intervalMillis = PollBackoff.next(state.intervalMillis)
                    schedulePoll(state, challenge)
                }

                is DeviceCodeFlow.PollResult.Expired -> expire(state)
                is DeviceCodeFlow.PollResult.Declined -> decline(state)
                is DeviceCodeFlow.PollResult.Authorized -> complete(state, result.accessToken)
            }
        } finally {
            state.busy.set(false)
        }
    }

    private fun complete(state: Verifying, accessToken: String) {
        synchronized(state.connection) { if (!active(state)) return }
        val xsts = try {
            services.exchange(accessToken)
        } catch (failure: XboxAuthException) {
            fail(state, "verification.xbox-failed", "reason" to failure.message)
            return
        } catch (failure: Exception) {
            logger.warn("Xbox token exchange failed for {}", state.pending.name)
            fail(state, "verification.retry")
            return
        }

        synchronized(state.connection) {
            if (!active(state)) return
            if (clock() >= state.deadlineMillis) {
                expire(state)
                return
            }
        }
        // Binding may re-check the name directory; leave and timeout must remain able to invalidate
        // the connection while that request is in flight. Only the final session grant is serialized.
        val outcome = try {
            services.bindMicrosoft(state.pending.name, Xuid(xsts.xuid), xsts.gamertag)
        } catch (failure: Exception) {
            logger.error("Could not bind the identity of {}", state.pending.name, failure)
            fail(state, "login.internal-error")
            return
        }
        synchronized(state.connection) {
            if (!active(state)) return
            try {
                when (outcome) {
                    is AuthCoordinator.ClaimOutcome.Bound -> {
                        val previousName = outcome.previousName
                        if (!active(state)) return
                        if (clock() >= state.deadlineMillis) {
                            expire(state)
                            return
                        }
                        if (!state.pending.provisional && outcome.record.uuid != state.connection.player.uniqueId) {
                            fail(state, "login.internal-error")
                            return
                        }
                        services.grantSession(outcome.record.uuid, state.pending.address)
                        if (previousName != null) {
                            // A prior online session must not keep presenting the superseded name.
                            server.getPlayer(outcome.record.uuid).ifPresent { previous ->
                                if (previous !== state.connection.player) {
                                    connectionOf(previous)?.let { old -> synchronized(old) { close(old) } }
                                    previous.disconnect(component("login.name-changed", localeOf(previous), "name" to outcome.record.name.value))
                                }
                            }
                            logger.info("Verified Microsoft identity renamed {} to {} ({})", previousName.value,
                                outcome.record.name.value, outcome.record.uuid)
                        }
                        if (!active(state)) return
                        if (clock() >= state.deadlineMillis) {
                            expire(state)
                            return
                        }
                        if (state.pending.provisional) {
                            // Persist the real identity, but never authorize a placeholder connection.
                            if (previousName != null) {
                                fail(state, "verification.renamed-reconnect", "from" to previousName.value,
                                    "name" to outcome.record.name.value)
                            } else {
                                fail(state, "verification.reconnect")
                            }
                            return
                        }
                        finish(state)
                        state.connection.access = Access.AUTHENTICATED
                        state.player.sendMessage(
                            limboText("verification.success", state.locale, "name" to outcome.record.name.value),
                        )
                        release(state.connection)
                    }
                    is AuthCoordinator.ClaimOutcome.Refused ->
                        fail(state, keyFor(outcome.reason), "reason" to outcome.detail,
                            "name" to outcome.existingName?.value.orEmpty())
                }
            } catch (failure: Exception) {
                logger.error("Could not complete verification for {}", state.pending.name, failure)
                close(state.connection)
                state.connection.player.disconnect(component("login.internal-error", state.locale))
            }
        }
    }

    private fun expire(state: Verifying) = fail(state, "verification.expired")

    private fun decline(state: Verifying) = fail(state, "verification.declined")

    private fun fail(state: Verifying, key: String, vararg placeholders: Pair<String, String>) {
        synchronized(state.connection) {
            if (!active(state)) return
            close(state.connection)
            // Disconnect the frontend itself: closing only the backend invites Velocity failover.
            state.connection.player.disconnect(component(key, state.locale, *placeholders))
        }
    }

    private fun finish(state: Verifying) {
        if (state.finished) return
        state.finished = true
        state.poll?.cancel(false)
        state.timeout?.cancel(false)
        if (state.titleShown && state.connection.verification === state) {
            try {
                state.player.clearTitle()
            } catch (failure: Exception) {
                logger.warn("Could not clear verification title for {}: type={}", state.pending.name, failure.javaClass.simpleName)
            }
        }
    }

    private fun close(connection: Connection) = synchronized(connection) {
        connection.access = Access.CLOSED
        connection.arrivalTimeout?.cancel(false)
        connection.verification?.let(::finish)
    }

    // --- moving a verified player on ------------------------------------------------------

    private fun release(connection: Connection) {
        if (!authenticated(connection)) return
        val player = connection.player
        val target = backend()
        if (target == null) {
            logger.warn("No backend to send {} to; check `post-login.server` or velocity.toml", player.username)
            close(connection)
            player.disconnect(component("login.internal-error", localeOf(player)))
            return
        }
        try {
            player.createConnectionRequest(target).connect().whenComplete { result, failure ->
                synchronized(connection) {
                    if (!authenticated(connection)) return@whenComplete
                    if (failure != null || result == null ||
                        result.status !in setOf(ConnectionRequestBuilder.Status.SUCCESS, ConnectionRequestBuilder.Status.ALREADY_CONNECTED)
                    ) {
                        close(connection)
                        player.disconnect(component("login.internal-error", localeOf(player)))
                    }
                }
            }
        } catch (failure: Exception) {
            close(connection)
            player.disconnect(component("login.internal-error", localeOf(player)))
        }
    }

    /**
     * The server a verified player is sent to.
     *
     * The configured one first, so an operator can pin a lobby; otherwise the proxy's own attempt
     * order, minus the limbo itself, because sending a player from the limbo to the limbo is a loop.
     */
    private fun backend(): RegisteredServer? {
        if (config.postLoginServer.isNotEmpty() && !isLimboName(config.postLoginServer)) {
            server.getServer(config.postLoginServer).orElse(null)?.let { return it }
            logger.warn("`post-login.server` names '{}', which is not a registered server", config.postLoginServer)
        }

        return server.configuration.attemptConnectionOrder
            .asSequence()
            .filterNot(::isLimboName)
            .mapNotNull { server.getServer(it).orElse(null) }
            .firstOrNull()
    }

    private fun limboFor(player: Player): RegisteredServer? {
        if (player.protocolVersion !in 767..777) return null
        return server.getServer("$LIMBO_SERVER_NAME-${player.protocolVersion}").orElse(null)
            ?: if (player.protocolVersion == 777) server.getServer(LIMBO_SERVER_NAME).orElse(null) else null
    }

    private fun isLimboName(name: String) = name == LIMBO_SERVER_NAME || name.startsWith("$LIMBO_SERVER_NAME-")

    // --- helpers ---------------------------------------------------------------------------

    /**
     * The key an address is rate-limited under.
     *
     * IPv4 is keyed by the address itself. IPv6 is keyed by its `/64`, because that is the smallest
     * block a residential connection is given: keying by the full address would let one client walk
     * through its own allocation and spend the Mojang lookup budget with what looks like millions of
     * distinct addresses, and the limiter would never notice.
     *
     * The session binding still records the exact address; this coarser key exists only to bound
     * bursts.
     */
    private fun limitKey(address: java.net.InetAddress?): String {
        if (address == null) return "unknown"

        val bytes = address.address
        if (bytes.size == 16) {
            return bytes.copyOfRange(0, 8).joinToString("") { "%02x".format(it) } + "::/64"
        }
        return address.hostAddress
    }

    private fun connectionOf(player: Player): Connection? =
        connections[player.sessionId]?.takeIf { it.player === player }

    private fun current(connection: Connection): Boolean =
        connection.player.isActive && connections[connection.player.sessionId] === connection

    private fun authenticated(connection: Connection): Boolean =
        ready && current(connection) && connection.access == Access.AUTHENTICATED

    /** Call under the connection lock; every async boundary must check before using a result. */
    private fun active(state: Verifying): Boolean =
        ready && current(state.connection) && state.connection.access == Access.HELD &&
            state.connection.verification === state && !state.finished

    private fun addressOf(connection: InboundConnection): String =
        connection.remoteAddress.address?.hostAddress ?: connection.remoteAddress.hostString

    private fun expired(pending: Pending): Boolean =
        clock() - pending.createdAt >= PENDING_LIFETIME_MILLIS

    /** Old and disconnected decisions cannot be transferred to a later login. */
    private fun sweepPending() {
        pending.entries.removeIf { !it.value.connection.isActive || expired(it.value) }
    }

    private fun keyFor(reason: AuthCoordinator.RejectReason): String = when (reason) {
        AuthCoordinator.RejectReason.INVALID_NAME -> "login.invalid-name"
        AuthCoordinator.RejectReason.NAME_TAKEN -> "login.name-taken"
        AuthCoordinator.RejectReason.IDENTITY_RENAMED -> "login.identity-already-bound"
        AuthCoordinator.RejectReason.PREMIUM_NAME -> "login.premium-name"
        AuthCoordinator.RejectReason.PREMIUM_NAME_CLAIMED -> "login.premium-name-claimed"
        AuthCoordinator.RejectReason.DIRECTORY_UNAVAILABLE -> "login.directory-unavailable"
        AuthCoordinator.RejectReason.DIRECTORY_THROTTLED -> "login.directory-throttled"
        AuthCoordinator.RejectReason.PROXY_FORWARDING -> "login.proxy"
        AuthCoordinator.RejectReason.INTERNAL_ERROR -> "login.internal-error"
    }

    /**
     * Reports a spent lookup budget without letting the report become the flood.
     *
     * An attack on the budget produces one of these per connection; logging each of them would turn
     * a rate-limit problem into a disk-space problem, and bury the line that explains what happened.
     */
    private fun warnThrottled(name: GameName, detail: String) {
        val now = clock()
        if (now - lastThrottleWarning < THROTTLE_WARNING_MILLIS) return

        lastThrottleWarning = now
        logger.warn(
            "Mojang lookups are being withheld, so {} was turned away and unknown names will be until the window resets: {}",
            name,
            detail,
        )
    }

    private fun denied(key: String, vararg placeholders: Pair<String, String>): PreLoginEvent.PreLoginComponentResult =
        PreLoginEvent.PreLoginComponentResult.denied(component(key, null, *placeholders))

    private fun component(key: String, locale: String? = null, vararg placeholders: Pair<String, String>): RichText.Parsed =
        messages.parsed(key, locale, *placeholders)

    private fun limboText(
        key: String,
        locale: String? = null,
        vararg placeholders: Pair<String, String>,
    ) = messages.parsed(key, locale, *placeholders).toLimbo()

    private fun localeOf(player: Player): String? = player.effectiveLocale.toString()

    companion object {
        fun liveServices(auth: AuthService) = object : Services {
            override fun allow(key: String) = auth.limiter.allow(key)
            override fun decide(name: GameName, address: String) = auth.auth.decide(name, address)
            override fun bindPremium(uuid: UUID, name: GameName) = auth.auth.bindPremium(uuid, name)
            override fun bindMicrosoft(name: GameName, xuid: Xuid, gamertag: String) =
                auth.auth.bindMicrosoft(name, xuid, gamertag)
            override fun grantSession(uuid: UUID, address: String) { auth.auth.grantSession(uuid, address) }
            override fun submit(task: () -> Unit) { auth.submit(task) }
            override fun schedule(delayMillis: Long, task: () -> Unit) = auth.schedule(delayMillis, task)
            override fun scheduleAtFixedRate(periodMillis: Long, task: () -> Unit) =
                auth.scheduleAtFixedRate(periodMillis, task)
            override fun begin() = DeviceCodeFlow.begin()
            override fun poll(challenge: DeviceCodeFlow.Challenge) = DeviceCodeFlow.poll(challenge)
            override fun exchange(accessToken: String) = XboxLive.exchange(accessToken, XboxLive.RELYING_PARTY_PROFILE)
        }

        /** The name the limbo is registered under. Visible in `/server` and in the proxy's lists. */
        const val LIMBO_SERVER_NAME = "kyokouauth-limbo"

        const val CONNECTION_PROPERTY = "kyokouauth:connection-id"

        /** How long a decision waits for the player it was made for. */
        private const val PENDING_LIFETIME_MILLIS = 5 * 60 * 1000L

        /** A player with no session waits this long before their first poll, before Microsoft has
         *  said anything about the cadence. */
        private const val MIN_POLL_MILLIS = 5_000L

        /** At most one "lookups are being withheld" warning a minute. */
        private const val THROTTLE_WARNING_MILLIS = 60_000L

    }
}