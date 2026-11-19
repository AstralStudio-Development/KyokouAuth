package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.OfficialJavaProfileUnavailableException
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.core.text.RichText
import moe.ryuu.kyokouauth.limbo.LimboListener
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import moe.ryuu.kyokouauth.limbo.RoleSelectorItem
import moe.ryuu.kyokouauth.limbo.RoleSelectorMenu
import moe.ryuu.kyokouauth.limbo.RoleSelectorIcon
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

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
        fun begin(): DeviceCodeFlow.Challenge
        fun poll(challenge: DeviceCodeFlow.Challenge): DeviceCodeFlow.PollResult
        fun exchange(accessToken: String): XboxLive.XstsToken
        fun permitsMappedProfile(record: BoundIdentity): Boolean = record.kind != BoundIdentity.Kind.XUID ||
            record.uuid == Identity.ofXuid(Xuid(record.subject.toLong()))
        fun approveLegacyProfile(recordId: UUID, session: UUID, name: GameName): ProfileClaimFlow.PreparedClaim =
            error("Legacy profile approval is unavailable")
        fun authMeClaimsAvailable(): Boolean = false
        fun proveAuthMeProfile(legacyName: GameName, password: CharArray, session: UUID, name: GameName,
                              current: () -> Boolean): ProfileClaimFlow.PreparedClaim? { password.fill('\u0000'); return null }
        fun commitLegacyProfile(token: ProfileClaimFlow.ClaimToken, session: UUID, name: GameName, xuid: Xuid, gamertag: String,
                                current: () -> Boolean): BoundIdentity =
            error("Legacy profile claims are unavailable")
        /** Missing integration is an error, never an empty or implicitly authorized role list. */
        fun roleOptions(xuid: Xuid, gamertag: String, fallbackBedrockName: String?): RoleOptions =
            error("Role resolution is unavailable")
    }

    /** Decisions cross login events by Velocity's connection session id, never by claimed name. */
    private class Pending(
        val connection: InboundConnection,
        val name: GameName,
        val address: String,
        val decision: AuthCoordinator.Decision,
        val createdAt: Long,
        val bedrock: BedrockProof? = null,
        val roles: Boolean = false,
        val selected: SelectedRoleAuthorization? = null,
        val role: RoleOption? = null,
    ) {
        val provisional = (decision as? AuthCoordinator.Decision.Verify)?.provisional == true
        @Volatile var profileValidated = false
        // Keep chooser names isolated and valid for the private Limbo protocol. Bedrock UUIDs,
        // unlike these display aliases, must remain registered with Floodgate's message lookup.
        private val chooserName = if (roles && role == null)
            "KA" + UUID.randomUUID().toString().replace("-", "").take(14) else null
        val profileUuid: UUID get() = role?.identity?.uuid ?: when (decision) {
            is AuthCoordinator.Decision.Verify -> decision.uuid
            is AuthCoordinator.Decision.Admit -> decision.identity.uuid
            is AuthCoordinator.Decision.VerifiedBedrock -> decision.identity.uuid
            is AuthCoordinator.Decision.MojangSession -> decision.expectedUuid
            is AuthCoordinator.Decision.Reject -> error("Rejected pending profile")
        }
        val profileName: String get() = role?.identity?.name?.value ?: chooserName ?: name.value
    }

    /** Authorization is local to one exact session, never inferred from a hint's existence. */
    private enum class RoleAuthorizationSource { PRELOGIN_SINGLE, CURRENT_SELECTION, RESUMED_HINT }
    private data class SelectedRoleAuthorization(val choice: BedrockRoleChoice, val role: RoleOption,
        val sessionId: UUID, val source: RoleAuthorizationSource)

    private class RoleSelection(val player: LimboPlayer, val xuid: Xuid, val gamertag: String, val deadline: Long) {
        var generation = UUID.randomUUID()
        var options: RoleOptions? = null
        var choosing = false
        var finished = false
        var hint: BedrockRoleChoice? = null
        var hintDelivered = false
        var timeout: ScheduledFuture<*>? = null
        var menuVisible = false
    }

    private enum class Access { HELD, AUTHENTICATED, CLOSED }

    /** All callbacks retain this exact Player, even if a new connection claims the same UUID. */
    private class Connection(val player: Player, val pending: Pending) {
        @Volatile var access = Access.HELD
        var verification: VerificationSession? = null
        var arrivalTimeout: ScheduledFuture<*>? = null
        var arrivalDeadlineMillis = Long.MAX_VALUE
        var limboPlayer: LimboPlayer? = null
        var selection: RoleSelection? = null
        @Volatile var privateSelectedAuthorization: SelectedRoleAuthorization? = null
        // Presentation only while an async native property update is pending; never grants access.
        @Volatile var applyingRoleProfile: RoleOption? = null
        @Volatile var claimProfileUuid: UUID? = null
        @Volatile var claimTargetUuid: UUID? = null
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
    private val pendingLock = Any()
    private val connections = ConcurrentHashMap<UUID, Connection>()
    private val roleHints = BedrockRoleHints(config.pendingLoginLimit, config.roleHintTtlSeconds * 1000L, clock)
    /** Covers both UUID and case-insensitive name before native Player registration can displace anyone. */
    private val roleAdmissionLock = Any()
    private val roleReservations = HashMap<UUID, RoleReference>()
    private val oldProofAccounts = moe.ryuu.kyokouauth.core.auth.ConnectionLimiter(5, 600_000L, clock)
    private val oldProofAddresses = moe.ryuu.kyokouauth.core.auth.ConnectionLimiter(20, 600_000L, clock)
    private val protectedClaimSessions = ConcurrentHashMap.newKeySet<UUID>()
    private val claims: ProfileClaimFlow = ProfileClaimFlow(messages,
        object : ProfileClaimFlow.Services {
            override fun submit(task: () -> Unit) = services.submit(task)
            override fun schedule(delayMillis: Long, task: () -> Unit) = services.schedule(delayMillis, task)
            override fun authMeProofAvailable() = services.authMeClaimsAvailable()
            override fun approve(recordId: UUID, session: UUID, name: GameName): ProfileClaimFlow.PreparedClaim {
                check(ready) { "Gate is unavailable" }
                val prepared = services.approveLegacyProfile(recordId, session, name)
                val connection = checkNotNull(connections[session]) { "Claim connection closed" }
                synchronized(connection) {
                    check(ready && current(connection) && connection.access == Access.HELD) { "Claim connection closed" }
                    synchronized(roleAdmissionLock) {
                        check(!roleOccupiedLocked(prepared.profileUuid, name.value, session)) { "Legacy profile is online or reserved" }
                        connection.claimProfileUuid = prepared.profileUuid
                    }
                }
                return prepared
            }
            override fun proveAuthMe(legacyName: GameName, password: CharArray, session: UUID, name: GameName,
                                     current: () -> Boolean): ProfileClaimFlow.PreparedClaim? {
                val prepared = services.proveAuthMeProfile(legacyName, password, session, name, current) ?: return null
                val connection = checkNotNull(connections[session]) { "Claim connection closed" }
                synchronized(connection) {
                    check(ready && current() && this@LoginGate.current(connection) && connection.access == Access.HELD) { "Claim connection closed" }
                    synchronized(roleAdmissionLock) {
                        check(!roleOccupiedLocked(prepared.profileUuid, name.value, session)) { "Legacy profile is online or reserved" }
                        connection.claimProfileUuid = prepared.profileUuid
                    }
                }
                return prepared
            }
            override fun commit(token: ProfileClaimFlow.ClaimToken, session: UUID, name: GameName, xuid: Xuid, gamertag: String): BoundIdentity {
                val connection = checkNotNull(connections[session]) { "Claim connection closed" }
                synchronized(connection) {
                    check(ready && current(connection) && connection.access == Access.HELD && connection.pending.provisional &&
                        connection.pending.bedrock == null && connection.pending.name == name) { "Claim is no longer current" }
                    val profile = checkNotNull(connection.claimProfileUuid) { "Claim source is missing" }
                    val target = Identity.ofXuid(xuid)
                    synchronized(roleAdmissionLock) {
                        check(!roleOccupiedLocked(profile, name.value, session) &&
                            !roleOccupiedLocked(target, name.value, session)) { "Claim source or target is online or reserved" }
                        connection.claimTargetUuid = target
                    }
                }
                return services.commitLegacyProfile(token, session, name, xuid, gamertag) {
                    ready && current(connection) && connection.access == Access.HELD &&
                        connections[session] === connection && claims.isActive(session) == true
                }
            }
        }, object : ProfileClaimFlow.Callbacks {
            override fun startVerification(player: Player, limbo: LimboPlayer) {
                val connection = connectionOf(player) ?: return
                synchronized(connection) {
                    if (!ready || !current(connection) || connection.access != Access.HELD || connection.limboPlayer !== limbo) return
                    val state = verification(connection, limbo, localeOf(player))
                    connection.verification = state
                    state.enqueueStart()
                }
            }
            override fun claimed(player: Player, limbo: LimboPlayer, record: BoundIdentity) {
                val connection = connectionOf(player) ?: return
                synchronized(connection) {
                    if (!ready || !current(connection) || connection.access != Access.HELD || connection.limboPlayer !== limbo) return
                    check(record.kind == BoundIdentity.Kind.XUID && record.name == connection.pending.name)
                    close(connection)
                    player.disconnect(component("verification.reconnect", localeOf(player)))
                }
            }
            override fun failed(player: Player, limbo: LimboPlayer, key: String) {
                val connection = connectionOf(player) ?: return
                synchronized(connection) {
                    if (current(connection) && connection.access == Access.HELD && connection.limboPlayer === limbo) roleFailure(connection, key)
                }
            }
        }, maxEntries = config.pendingLoginLimit, clock = clock)

    /** Native adapters call this only after the pre-dispatch guard is installed for this connection. */
    fun onPrivateClaimIngressReady(session: UUID) { if (ready) protectedClaimSessions.add(session) }

    fun pendingProfileClaims(): List<ProfileClaimFlow.PendingClaim> = claims.pending()
    fun approveProfileClaim(session: UUID, record: UUID): java.util.concurrent.CompletableFuture<Unit> {
        check(config.profileClaimsEnabled) { "Profile claims are disabled" }
        return claims.authorize(session, record)
    }

    /** Called only by a native, pre-dispatch sensitive-packet interceptor; never logs raw input. */
    fun onPrivateClaim(player: Player, command: String) {
        val input = PrivateClaimCommands.parse(command) ?: return
        fun reply(key: String, vararg values: Pair<String, String>) {
            runCatching { player.sendMessage(component(key, localeOf(player), *values)) }
        }
        try {
            if (input.action == PrivateClaimCommands.Action.USAGE) { reply("migration.claim-usage"); return }
            val connection = connectionOf(player)
            val limbo = connection?.let { captured -> synchronized(captured) {
                captured.limboPlayer?.takeIf { ready && current(captured) && captured.access == Access.HELD &&
                    captured.pending.provisional && captured.pending.bedrock == null }
            } }
            if (connection == null || limbo == null || claims.hasActive(player, limbo) != true) {
                reply("migration.claim-not-waiting"); return
            }
            when (input.action) {
                PrivateClaimCommands.Action.STATUS -> {
                    val pending = claims.pending().singleOrNull { it.sessionId == player.sessionId }
                    reply("migration.claim-status", "stage" to (pending?.stage?.name ?: "CLOSED"))
                }
                PrivateClaimCommands.Action.CANCEL -> {
                    if (!claims.cancel(player, limbo)) reply("migration.claim-busy")
                }
                PrivateClaimCommands.Action.AUTHME -> {
                    if (!services.authMeClaimsAvailable()) { reply("migration.claim-unavailable"); return }
                    val legacy = checkNotNull(input.legacyName)
                    if (!oldProofAddresses.allow(limitKey(player.remoteAddress.address)) || !oldProofAccounts.allow(legacy.key)) {
                        reply("migration.claim-limited"); return
                    }
                    val password = checkNotNull(input.takePassword())
                    claims.proveAuthMe(player, limbo, legacy, password).whenComplete { proven, failure ->
                        if (ready && current(connection) && connection.access == Access.HELD && connection.limboPlayer === limbo &&
                            claims.hasActive(player, limbo)) {
                            reply(when { failure != null -> "migration.claim-unavailable"
                                proven == true -> "migration.claim-proven"
                                else -> "migration.claim-rejected" })
                        }
                    }
                }
                PrivateClaimCommands.Action.USAGE -> Unit
            }
        } catch (_: Exception) {
            reply("migration.claim-unavailable")
        } finally { input.close() }
    }

    /** Armed only after the listener and the registered limbo are both ready. */
    @Volatile private var ready = false

    fun activate() { ready = true }

    fun deactivate() {
        ready = false
        claims.close()
        protectedClaimSessions.clear()
        roleHints.close()
        synchronized(roleAdmissionLock) { roleReservations.clear() }
        synchronized(premiumWatches) {
            premiumWatches.values.forEach { it.cancel(false) }
            premiumWatches.clear()
            premiumHandshakes.clear()
        }
        synchronized(pendingLock) { pending.clear() }
        connections.values.forEach { connection ->
            synchronized(connection) {
                close(connection)
                connection.player.disconnect(component("login.internal-error", localeOf(connection.player)))
            }
        }
        connections.clear()
    }

    /** When the budget-exhaustion warning was last written, so it cannot become the flood. */
    private val lastThrottleWarning = java.util.concurrent.atomic.AtomicLong(0L)

    // --- who may enter ---------------------------------------------------------------------

    fun onPreLogin(event: PreLoginEvent) {
        if (pending.remove(event.connection.sessionId) != null) {
            releaseRoleReservation(event.connection.sessionId)
            finishPremium(event.connection.sessionId, verified = false)
        }
        if (!ready || !event.connection.isActive) {
            event.result = denied("login.internal-error")
            return
        }
        if (!event.result.isAllowed) return
        when (val input = event.bedrock) {
            is BedrockLogin.Rejected -> { event.result = denied(bedrockKey(input.reason)); return }
            is BedrockLogin.Verified -> { admitBedrock(event, input.proof); return }
            BedrockLogin.Java -> Unit
        }
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
        if (synchronized(pendingLock) { pending.size >= config.pendingLoginLimit }) {
            event.result = denied("login.internal-error")
            return
        }

        // Deliberately inline. Velocity reads this event's result as soon as the handler returns -
        // `PreLoginEvent` is not an awaiting event - so handing the work to another thread would let
        // the proxy admit the player with a default result that arrives after the decision. The cost
        // is a blocked worker for the duration of one stored-state read plus one Mojang lookup, both
        // of which are cached and rate-limited, and it is paid once per connection.
        decide(event, name, address)
    }

    private fun admitBedrock(event: PreLoginEvent, proof: BedrockProof) {
        if (!config.bedrockEnabled) { event.result = denied("bedrock.disabled"); return }
        if (!proof.isCurrent()) { event.result = denied("bedrock.stale"); return }
        val remote = event.connection.remoteAddress
        if (!services.allow(limitKey(remote.address))) { event.result = denied("login.too-many-connections"); return }
        sweepPending()
        if (synchronized(pendingLock) { pending.size >= config.pendingLoginLimit }) {
            event.result = denied("login.internal-error")
            return
        }
        try {
            val hadHint = config.rolesEnabled && roleHints.hasHint(proof.xuid)
            val hint = if (hadHint) roleHints.consume(proof, event.connection.sessionId) else null
            check(!hadHint || hint != null) { "Role hint is invalid" }
            val options = if (config.rolesEnabled) resolveRoles(proof.xuid, proof.gamertag, proof.username) else null
            check(options == null || options.linkedJavaUuid == proof.linkedJavaUuid) { "Native relationship changed" }
            val selected = when {
                hint != null -> SelectedRoleAuthorization(hint, revalidateRole(hint, proof), event.connection.sessionId,
                    RoleAuthorizationSource.RESUMED_HINT)
                options?.roles?.size == 1 -> {
                    val role = options.roles.single()
                    SelectedRoleAuthorization(BedrockRoleChoice(proof.xuid, proof.gamertag, role.reference,
                        options.linkedJavaUuid, event.connection.sessionId), role, event.connection.sessionId,
                        RoleAuthorizationSource.PRELOGIN_SINGLE)
                }
                else -> null
            }
            // A legal native candidate can remain as-is, but is HELD until a fresh explicit click.
            val profileRole = selected?.role ?: options?.roles?.singleOrNull {
                it.identity.uuid == proof.uniqueId && it.identity.name.value == proof.username
            }
            val decision = if (config.rolesEnabled) {
                AuthCoordinator.Decision.Verify(profileRole?.identity?.uuid ?: proof.uniqueId,
                    AuthCoordinator.Reason.SESSION_EXPIRED, selected == null, profileRole?.existing)
            } else {
                val identity = proof.identity as? Identity.Microsoft ?: error("Linked Bedrock requires role selection")
                check(services.permitsMappedProfile(BoundIdentity.of(identity, clock()))) {
                    "Native Bedrock cannot use a mapped Microsoft profile"
                }
                AuthCoordinator.Decision.VerifiedBedrock(identity)
            }
            val state = Pending(event.connection, proof.identity.name, addressOf(event.connection), decision,
                clock(), proof, config.rolesEnabled, selected, profileRole)
            val stored = synchronized(roleAdmissionLock) {
                check(!roleOccupiedLocked(state.profileUuid, state.profileName, event.connection.sessionId)) { "Role is already online or reserved" }
                synchronized(pendingLock) {
                    if (ready && event.connection.isActive && proof.isCurrent() && pending.size < config.pendingLoginLimit) {
                        pending[event.connection.sessionId] = state
                        roleReservations[event.connection.sessionId] = RoleReference(
                            if (profileRole?.identity is Identity.Premium) BoundIdentity.Kind.MOJANG else BoundIdentity.Kind.XUID,
                            state.profileUuid, state.profileName)
                        true
                    } else false
                }
            }
            event.result = if (stored) PreLoginEvent.PreLoginComponentResult.forceOfflineMode() else denied("bedrock.stale")
        } catch (_: Exception) {
            event.result = denied("role-selection.retry")
        }
    }

    private fun bedrockKey(reason: BedrockFailure) = "bedrock." + when (reason) {
        BedrockFailure.DISABLED -> "disabled"
        BedrockFailure.UNAVAILABLE -> "unavailable"
        BedrockFailure.PROVIDER_DENIED -> "provider-denied"
        BedrockFailure.LINKED -> "linked"
        BedrockFailure.INVALID_IDENTITY -> "invalid-identity"
        BedrockFailure.UNSAFE_NAME -> "unsafe-name"
        BedrockFailure.STALE -> "stale"
    }

    private fun decide(event: PreLoginEvent, name: GameName, address: String) {
        val decision = try {
            // Java keeps its basic device-code/session path; the role flag applies only to Floodgate.
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
        if (decision is AuthCoordinator.Decision.VerifiedBedrock) {
            event.result = denied("bedrock.invalid-identity")
            return // A stored-state decision can never stand in for the native channel proof.
        }
        val bound = when (decision) {
            is AuthCoordinator.Decision.Admit -> decision.bound
            is AuthCoordinator.Decision.Verify -> decision.existing
            else -> null
        }
        if (bound != null) {
            val permitted = try { services.permitsMappedProfile(bound) } catch (failure: Exception) {
                logger.error("Could not verify profile ownership for {}", name, failure)
                event.result = denied("login.internal-error")
                return
            }
            if (!permitted) {
                event.result = denied("migration.disabled")
                return
            }
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
            val stored = synchronized(pendingLock) {
                if (ready && event.connection.isActive && pending.size < config.pendingLoginLimit) {
                    pending[event.connection.sessionId] = Pending(event.connection, name, address, decision, clock())
                    true
                } else false
            }
            if (!stored) {
                finishPremium(event.connection.sessionId, verified = false)
                event.result = denied("login.internal-error")
                return
            }
        }
        when (decision) {
            is AuthCoordinator.Decision.VerifiedBedrock -> { event.result = denied("bedrock.invalid-identity") }
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

    /** Adapter hint only (e.g. key isolation); never an authentication or role authorization grant. */
    fun requiresRoleProfile(connection: InboundConnection): Boolean =
        pending[connection.sessionId]?.roles == true || connections[connection.sessionId]?.pending?.roles == true

    fun requiresMappedProfile(connection: InboundConnection): Boolean {
        val state = pending[connection.sessionId] ?: connections[connection.sessionId]?.pending ?: return false
        val record = when (val decision = state.decision) {
            is AuthCoordinator.Decision.Admit -> decision.bound
            is AuthCoordinator.Decision.Verify -> decision.existing
            else -> null
        } ?: return false
        return record.kind == BoundIdentity.Kind.XUID && record.uuid != Identity.ofXuid(Xuid(record.subject.toLong()))
    }

    /** Pure snapshot for native late-profile guards: never performs IO or changes an identity. */
    fun currentRoleProfile(connection: InboundConnection): GameProfile? {
        val beforeLogin = pending[connection.sessionId]
        if (beforeLogin != null) {
            if (!beforeLogin.roles || !ready || !connection.isActive || !beforeLogin.connection.isActive ||
                expired(beforeLogin) || beforeLogin.bedrock?.isCurrent() != true) return null
            return roleProfile(GameProfile(beforeLogin.profileUuid, beforeLogin.profileName, emptyList()), beforeLogin)
        }
        val live = connections[connection.sessionId] ?: return null
        synchronized(live) {
            val state = live.pending
            if (!state.roles || !ready || !connection.isActive || !current(live) || live.access == Access.CLOSED ||
                state.bedrock?.isCurrent() != true || live.player.uniqueId != state.profileUuid ||
                live.player.username != state.profileName ||
                connection is Player && connection !== live.player) return null
            val selected = live.privateSelectedAuthorization
            if (live.access == Access.AUTHENTICATED && !validSelectedAuthorization(live, selected)) return null
            return roleProfile(GameProfile(live.player.uniqueId, live.player.username, emptyList()), state,
                selected?.role ?: live.applyingRoleProfile ?: state.role)
        }
    }

    /** NORMAL-before hook: assign only a server-selected role or isolated chooser profile. */
    fun onPrepareGameProfile(event: GameProfileRequestEvent) {
        val state = pending[event.connection.sessionId] ?: return
        if (!state.roles) return
        if (!validRoleProfileContext(state, event)) {
            rejectRoleProfile(event, state)
            return
        }
        state.profileValidated = false
        event.gameProfile = roleProfile(event.gameProfile, state)
    }

    private fun validRoleProfileContext(state: Pending, event: GameProfileRequestEvent): Boolean =
        ready && state.connection.isActive && event.connection.isActive && !expired(state) && bedrockMatches(state, event.bedrock)

    private fun rejectRoleProfile(event: GameProfileRequestEvent, state: Pending) {
        state.profileValidated = false
        pending.remove(event.connection.sessionId, state)
        releaseRoleReservation(event.connection.sessionId)
        event.connection.disconnect(component("role-selection.retry"))
    }

    private fun roleProfile(profile: GameProfile, state: Pending, role: RoleOption? = state.role): GameProfile {
        val properties = profile.properties.filterNot { it.name == "textures" || it.name == CONNECTION_PROPERTY } +
            listOfNotNull(role?.skin) + GameProfile.Property(CONNECTION_PROPERTY, state.connection.sessionId.toString(), "")
        return profile.copy(id = state.profileUuid, name = state.profileName, properties = properties)
    }

    /** LAST hook: other plugins may alter textures, never the chosen UUID/name or native proof. */
    fun onGameProfile(event: GameProfileRequestEvent) {
        val pending = pending[event.connection.sessionId] ?: return
        if (pending.roles) {
            if (!validRoleProfileContext(pending, event) || event.gameProfile.id != pending.profileUuid ||
                event.gameProfile.name != pending.profileName) {
                rejectRoleProfile(event, pending)
                return
            }
            event.gameProfile = roleProfile(event.gameProfile, pending)
            pending.profileValidated = true
            return
        }
        if (!ready || !pending.connection.isActive || expired(pending)) return
        val proof = pending.bedrock
        if (!bedrockMatches(pending, event.bedrock) || proof != null &&
            (event.gameProfile.id != proof.uniqueId || event.gameProfile.name != proof.username)) {
            this.pending.remove(event.connection.sessionId, pending)
            finishPremium(event.connection.sessionId, verified = false)
            event.connection.disconnect(component("bedrock.stale"))
            return
        }
        if (proof != null) return // Floodgate already supplied the final profile and textures.
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
            releaseRoleReservation(player.sessionId)
            finishPremium(player.sessionId, verified = false)
            return
        }
        if (!ready || !player.isActive || pending == null || expired(pending) ||
            !pending.connection.isActive || !bedrockMatches(pending, event.bedrock) ||
            (if (pending.roles) !pending.profileValidated || player.username != pending.profileName || player.uniqueId != pending.profileUuid
                else if (pending.bedrock != null) player.username != pending.name.value else GameName.parse(player.username)?.key != pending.name.key) ||
            addressOf(player) != pending.address
        ) {
            releaseRoleReservation(player.sessionId)
            finishPremium(player.sessionId, verified = false)
            event.result = ResultedEvent.ComponentResult.denied(component("login.internal-error"))
            return
        }

        val connection = Connection(player, pending)
        try {
            if (pending.roles) {
                check(!player.isOnlineMode) { "Role resumption cannot masquerade as a Mojang session" }
            } else when (val decision = pending.decision) {
                is AuthCoordinator.Decision.VerifiedBedrock -> {
                    check(pending.bedrock != null && !player.isOnlineMode && player.uniqueId == decision.identity.uuid &&
                        pending.bedrock.isCurrent()) { "Native Bedrock profile or proof changed" }
                    check(services.permitsMappedProfile(BoundIdentity.of(decision.identity, clock()))) {
                        "Native Bedrock profile ownership changed"
                    }
                    connection.access = Access.AUTHENTICATED
                }
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
            if (pending.roles && pending.selected != null) admitSelectedRole(connection, pending.selected)
            synchronized(roleAdmissionLock) {
                check(connections.putIfAbsent(player.sessionId, connection) == null) { "Duplicate connection session id" }
            }
            if (connection.access == Access.HELD) {
                synchronized(connection) {
                    val arrivalMillis = config.limbo.joinTimeoutSeconds * 1000L
                    connection.arrivalDeadlineMillis = clock() + arrivalMillis
                    connection.arrivalTimeout = services.schedule(arrivalMillis) {
                        synchronized(connection) {
                            if (current(connection) && connection.access == Access.HELD && connection.limboPlayer == null) {
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
            val profileUnavailable = failure is OfficialJavaProfileUnavailableException
            if (profileUnavailable) logger.warn("Public role profile unavailable: {}", failure.message)
            else logger.error("Could not authorize {}", player.username, failure)
            event.result = ResultedEvent.ComponentResult.denied(
                component(if (profileUnavailable) "role-selection.retry" else "login.internal-error", localeOf(player)))
        }
    }

    private fun admitSelectedRole(connection: Connection, authorization: SelectedRoleAuthorization) {
        val state = connection.pending
        val selected = authorization.role
        check(authorization.sessionId == connection.player.sessionId)
        check(connection.player.uniqueId == selected.identity.uuid && connection.player.username == selected.identity.name.value)
        val latest = revalidateRole(authorization.choice, checkNotNull(state.bedrock))
        check(latest.existing == selected.existing && latest.skin == selected.skin) { "Role record or properties changed" }
        if (selected.identity is Identity.Premium) {
            // A fresh current XUID -> J relationship is its own proof, not a forged MojangSession.
            val outcome = services.bindPremium(selected.identity.uuid, selected.identity.name)
            check(outcome is AuthCoordinator.ClaimOutcome.Bound && outcome.record.uuid == selected.identity.uuid &&
                outcome.record.kind == BoundIdentity.Kind.MOJANG && outcome.record.name == selected.identity.name) {
                "Premium role binding refused"
            }
        }
        val finalRole = revalidateRole(authorization.choice, checkNotNull(state.bedrock))
        check(finalRole.skin == selected.skin) { "Role properties changed" }
        synchronized(connection) {
            check(ready && connection.player.isActive && state.connection.isActive && state.bedrock.isCurrent() &&
                (authorization.source != RoleAuthorizationSource.CURRENT_SELECTION ||
                    connection.selection?.let { clock() < it.deadline && !it.finished } == true)) { "Role login is stale" }
            synchronized(roleAdmissionLock) {
                val expected = RoleReference(finalRole.reference.kind, finalRole.identity.uuid, finalRole.identity.name.value)
                check(roleReservations[connection.player.sessionId] == expected) { "Role reservation changed" }
                check(!roleOccupiedLocked(finalRole.identity.uuid, finalRole.identity.name.value, connection.player.sessionId)) {
                    "Role is already online"
                }
                connection.privateSelectedAuthorization = authorization.copy(role = finalRole)
                connection.access = Access.AUTHENTICATED
            }
        }
    }

    private fun roleOccupied(uuid: UUID, name: String, session: UUID): Boolean = synchronized(roleAdmissionLock) {
        roleOccupiedLocked(uuid, name, session)
    }

    private fun roleOccupiedLocked(uuid: UUID, name: String, session: UUID): Boolean {
        fun matches(otherUuid: UUID, otherName: String) = otherUuid == uuid || otherName.equals(name, ignoreCase = true)
        return server.allPlayers.any { it.isActive && it.sessionId != session && matches(it.uniqueId, it.username) } ||
            connections.values.any { it.player.sessionId != session && current(it) && it.access != Access.CLOSED &&
                (matches(it.player.uniqueId, it.player.username) || it.claimProfileUuid == uuid || it.claimTargetUuid == uuid) } ||
            roleReservations.any { (owner, role) -> owner != session && matches(role.uuid, role.name) }
    }


        private fun reserveRole(connection: Connection, role: RoleOption): Boolean = synchronized(roleAdmissionLock) {
            val session = connection.player.sessionId
            if (roleOccupiedLocked(role.identity.uuid, role.identity.name.value, session)) return@synchronized false
            roleReservations[session] = RoleReference(role.reference.kind, role.identity.uuid, role.identity.name.value)
            true
        }

        private fun restoreRoleReservation(connection: Connection) = synchronized(roleAdmissionLock) {
            val role = connection.pending.role
            if (role == null) {
                roleReservations.remove(connection.player.sessionId)
            } else {
                roleReservations[connection.player.sessionId] = RoleReference(
                    role.reference.kind, role.identity.uuid, role.identity.name.value,
                )
            }
        }

    private fun releaseRoleReservation(session: UUID) = synchronized(roleAdmissionLock) { roleReservations.remove(session); Unit }

    /** Shared by native execution, command-tree and completion adapters; never performs IO. */
    fun isCommandBlocked(player: Player?, command: String): Boolean {
        val root = CommandNames.root(command)
        if (root.isEmpty() || (root !in config.blockedCommands && "*" !in config.blockedCommands)) return false
        return !isAuthenticated(player)
    }

    fun isAuthenticated(player: Player?): Boolean {
        val connection = player?.let(::connectionOf) ?: return false
        return synchronized(connection) { authenticated(connection) }
    }

    /** The Boolean is command admission, not an authentication grant. Console is handled by adapters. */
    fun allowCommand(player: Player, command: String): Boolean {
        if (!isCommandBlocked(player, command)) return true
        try {
            player.sendMessage(component("verification.command-blocked", localeOf(player)))
        } catch (_: Exception) {
            logger.warn("Could not deliver blocked-command feedback; command remains denied")
        }
        return false
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
        protectedClaimSessions.remove(player.sessionId)
        val connection = connectionOf(player) ?: return
        pending.remove(player.sessionId, connection.pending)
        finishPremium(player.sessionId, verified = false)
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
                connection.player.uniqueId != player.uuid || connection.player.username != player.name ||
                connection.pending.address != player.address || connection.limboPlayer != null
            ) {
                player.disconnect(limboText("login.internal-error"))
                return
            }
            if (clock() >= connection.arrivalDeadlineMillis) {
                close(connection)
                connection.player.disconnect(component("login.internal-error", localeOf(connection.player)))
                return
            }
            connection.arrivalTimeout?.cancel(false)
            connection.limboPlayer = player
            if (connection.pending.roles && connection.pending.bedrock != null) {
                val proof = connection.pending.bedrock
                startRoleSelection(connection, player, proof.xuid, proof.gamertag)
                return
            }
            if (config.profileClaimsEnabled && connection.player.sessionId in protectedClaimSessions &&
                connection.pending.provisional && connection.pending.bedrock == null) {
                claims.open(connection.player, player, connection.pending.name)
                return
            }
            val state = verification(connection, player, localeOf(connection.player))
            connection.verification = state
            logger.info("{} ({}) is verifying in the limbo from {}", player.name, player.uuid, player.address)
            state.enqueueStart()
        }
    }

    override fun onLeave(player: LimboPlayer) {
        val connection = connections[player.connectionId] ?: return
        synchronized(connection) {
            if (connection.limboPlayer !== player) return
            connection.verification?.cancel()
            connection.verification = null
            connection.limboPlayer = null
            if (connection.access == Access.HELD) {
                close(connection)
                connection.player.disconnect(component("login.internal-error", localeOf(connection.player)))
            }
        }
    }

    // --- one sign-in ----------------------------------------------------------------------

    private fun verification(connection: Connection, player: LimboPlayer, locale: String?) = VerificationSession(
        player, locale, connection, config, messages, services, logger, clock,
        current = { ready && current(connection) && connection.access == Access.HELD && connection.verification === it },
        ownsTitle = { connection.verification === it },
        failed = { state, key, placeholders -> fail(connection, state, key, *placeholders) },
        verified = { state, xsts -> complete(connection, state, xsts) },
    )

    /** How many sign-ins are in flight, for `/kyokouauth status`. */
    fun verifyingCount(): Int = connections.values.count { connection ->
        synchronized(connection) { connection.verification?.active() == true }
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
            if (!state.beforeDeadline()) return false
            state.cancel()
            verification(connection, state.player, state.locale).also {
                connection.verification = it
                try {
                    it.arm()
                } catch (failure: Exception) {
                    fail(connection, it, "verification.start-failed")
                    return false
                }
            }
        }
        replacement.start()
        return true
    }

    /** Account binding and final authorization belong to the gate, never to the device-code module. */
    private fun complete(connection: Connection, state: VerificationSession, xsts: XboxLive.XstsToken) {
        synchronized(connection) {
            if (!state.beforeDeadline()) return
            if (connection.claimProfileUuid != null) {
                state.cancel()
                connection.verification = null
                if (!claims.verified(connection.player, state.player, Xuid(xsts.xuid), xsts.gamertag)) {
                    roleFailure(connection, "migration.failed")
                }
                return
            }
        }
        // Binding may re-check the name directory; leave and timeout must remain able to invalidate
        // the connection while that request is in flight. Only the final session grant is serialized.
        val outcome = try {
            services.bindMicrosoft(connection.pending.name, Xuid(xsts.xuid), xsts.gamertag)
        } catch (failure: Exception) {
            logger.error("Could not bind the identity of {}", connection.pending.name, failure)
            fail(connection, state, "login.internal-error")
            return
        }
        synchronized(connection) {
            if (!state.beforeDeadline()) return
            try {
                when (outcome) {
                    is AuthCoordinator.ClaimOutcome.Bound -> {
                        val previousName = outcome.previousName
                        if (!state.active()) return
                        if (!state.beforeDeadline()) return
                        if (!connection.pending.provisional && outcome.record.uuid != connection.player.uniqueId) {
                            fail(connection, state, "login.internal-error")
                            return
                        }
                        services.grantSession(outcome.record.uuid, connection.pending.address)
                        if (previousName != null) {
                            // A prior online session must not keep presenting the superseded name.
                            server.getPlayer(outcome.record.uuid).ifPresent { previous ->
                                if (previous !== connection.player) {
                                    connectionOf(previous)?.let { old -> synchronized(old) { close(old) } }
                                    previous.disconnect(component("login.name-changed", localeOf(previous), "name" to outcome.record.name.value))
                                }
                            }
                            logger.info("Verified Microsoft identity renamed {} to {} ({})", previousName.value,
                                outcome.record.name.value, outcome.record.uuid)
                        }
                        if (!state.active()) return
                        if (!state.beforeDeadline()) return
                        if (connection.pending.provisional) {
                            // Persist the real identity, but never authorize a placeholder connection.
                            if (previousName != null) {
                                fail(connection, state, "verification.renamed-reconnect", "from" to previousName.value,
                                    "name" to outcome.record.name.value)
                            } else {
                                fail(connection, state, "verification.reconnect")
                            }
                            return
                        }
                        state.cancel()
                        connection.access = Access.AUTHENTICATED
                        state.player.sendMessage(
                            limboText("verification.success", state.locale, "name" to outcome.record.name.value),
                        )
                        release(connection)
                    }
                    is AuthCoordinator.ClaimOutcome.Refused ->
                        fail(connection, state, keyFor(outcome.reason), "reason" to outcome.detail,
                            "name" to outcome.existingName?.value.orEmpty())
                }
            } catch (failure: Exception) {
                logger.error("Could not complete verification for {}", connection.pending.name, failure)
                close(connection)
                connection.player.disconnect(component("login.internal-error", state.locale))
            }
        }
    }

    private fun startRoleSelection(connection: Connection, player: LimboPlayer, xuid: Xuid, gamertag: String,
                                   options: RoleOptions? = null) {
        val selection = RoleSelection(player, xuid, gamertag,
            clock() + config.roleSelectionTimeoutSeconds * 1000L)
        connection.selection = selection
        try {
            selection.timeout = services.schedule(config.roleSelectionTimeoutSeconds * 1000L) {
                synchronized(connection) {
                    if (current(connection) && connection.access == Access.HELD && connection.selection === selection) {
                        roleFailure(connection, "role-selection.expired")
                    }
                }
            }
            if (options != null) presentRoles(connection, selection, options) else services.submit {
                try {
                    synchronized(connection) { if (!selectionActive(connection, selection)) return@submit }
                    val resolved = resolveRoles(xuid, gamertag, connection.pending.bedrock?.username)
                    synchronized(connection) {
                        if (selectionActive(connection, selection)) presentRoles(connection, selection, resolved)
                    }
                } catch (_: Exception) {
                    synchronized(connection) {
                        if (selectionCurrent(connection, selection)) roleFailure(connection, "role-selection.retry")
                    }
                }
            }
        } catch (_: Exception) {
            roleFailure(connection, "role-selection.retry")
        }
    }

    private fun selectionCurrent(connection: Connection, selection: RoleSelection): Boolean =
        ready && current(connection) && connection.access == Access.HELD && connection.selection === selection &&
            connection.limboPlayer === selection.player && !selection.finished

    private fun selectionActive(connection: Connection, selection: RoleSelection): Boolean {
        if (!selectionCurrent(connection, selection)) return false
        if (clock() < selection.deadline && connection.pending.bedrock?.isCurrent() != false) return true
        roleFailure(connection, "role-selection.expired")
        return false
    }

    private fun presentRoles(connection: Connection, selection: RoleSelection, options: RoleOptions) {
        if (!selectionActive(connection, selection)) return
        connection.pending.bedrock?.let { check(it.linkedJavaUuid == options.linkedJavaUuid) { "Native relationship changed" } }
        selection.options = options
        selection.generation = UUID.randomUUID()
        selection.choosing = false
        selection.menuVisible = false
        // A dual-role chooser losing a role cannot silently become a single-role authorization.
        if (options.roles.size != 2) { roleFailure(connection, "role-selection.retry"); return }
        val microsoft = options.roles.single { it.identity is Identity.Microsoft }
        val premium = options.roles.single { it.identity is Identity.Premium }
        fun itemText(key: String, baseCode: Char, vararg placeholders: Pair<String, String>) =
            limboText(key, localeOf(connection.player), *placeholders).let { text ->
                // Geyser's raw item translator ignores an empty root's italic=false; Java stays pure components.
                TextComponent("\u00a7r\u00a7$baseCode", children = listOf(text))
            }
        fun item(option: RoleOption, id: String, label: String, icon: RoleSelectorIcon) = RoleSelectorItem(id, option.identity.uuid,
            option.identity.name.value, option.skin?.value, option.skin?.signature,
            itemText(label, 'f', "name" to option.identity.name.value),
            listOf(itemText("role-selection.enter", '7')), icon = icon)
        selection.menuVisible = true
        selection.player.openRoleSelector(RoleSelectorMenu(selection.generation,
            limboText("role-selection.title", localeOf(connection.player)),
            item(microsoft, "microsoft", "role-selection.microsoft", RoleSelectorIcon.NAME_TAG),
            item(premium, "premium", "role-selection.premium", RoleSelectorIcon.DIAMOND)))
    }

    override fun onRoleSelect(player: LimboPlayer, generation: UUID, optionId: String) {
        if (claims.select(player, generation, optionId) == true) return
        val connection = connections[player.connectionId] ?: return
        synchronized(connection) {
            val selection = connection.selection ?: return
            if (selection.player !== player || selection.generation != generation || !selection.menuVisible ||
                !selectionActive(connection, selection) || selection.choosing) return
            val option = selection.options?.roles?.singleOrNull {
                (optionId == "microsoft" && it.identity is Identity.Microsoft) ||
                    (optionId == "premium" && it.identity is Identity.Premium)
            } ?: return
            selectRole(connection, selection, option)
        }
    }

    /** A click names a server-side option only; authorization always re-reads the relationship. */
    private fun selectRole(connection: Connection, selection: RoleSelection, chosen: RoleOption) {
        if (!selectionActive(connection, selection) || selection.choosing) return
        selection.choosing = true
        selection.menuVisible = false
        val snapshot = checkNotNull(selection.options)
        try {
            services.submit {
                try {
                    synchronized(connection) { if (!selectionActive(connection, selection)) return@submit }
                    val latest = resolveRoles(selection.xuid, selection.gamertag, connection.pending.bedrock?.username)
                    synchronized(connection) {
                        if (!selectionActive(connection, selection)) return@submit
                        val currentRole = latest.roles.singleOrNull { it.reference == chosen.reference && it.existing == chosen.existing }
                        if (snapshot.linkedJavaUuid != latest.linkedJavaUuid || currentRole == null) {
                            // A stale menu must not silently reinterpret a renamed option or revoked J.
                            restoreRoleReservation(connection)
                            if (latest.roles.size == 1) roleFailure(connection, "role-selection.retry")
                            else presentRoles(connection, selection, latest)
                            return@submit
                        }
                        connection.pending.bedrock?.let { check(it.linkedJavaUuid == latest.linkedJavaUuid) }
                        check(reserveRole(connection, currentRole)) { "Role is already online or reserved" }
                        val choice = BedrockRoleChoice(selection.xuid, selection.gamertag, currentRole.reference,
                            latest.linkedJavaUuid, connection.player.sessionId)
                        if (connection.player.uniqueId == currentRole.identity.uuid &&
                            connection.player.username == currentRole.identity.name.value) {
                            authorizeInPlace(connection, selection, choice, currentRole)
                        } else {
                            // An intent is not a credential. Only a fresh proof on a new Channel may resume it.
                            roleHints.issue(choice)
                            selection.hint = choice
                            selection.player.closeRoleSelector()
                            selection.hintDelivered = true
                            connection.player.disconnect(component("role-selection.reconnect", localeOf(connection.player)))
                            close(connection)
                        }
                    }
                } catch (_: Exception) {
                    synchronized(connection) {
                        selection.hint?.let(roleHints::revoke)
                        if (current(connection) && connection.access == Access.HELD && connection.selection === selection) {
                            roleFailure(connection, "role-selection.retry")
                        }
                    }
                }
            }
        } catch (_: Exception) {
            selection.hint?.let(roleHints::revoke)
            roleFailure(connection, "role-selection.retry")
        }
    }

    private fun authorizeInPlace(connection: Connection, selection: RoleSelection, choice: BedrockRoleChoice, role: RoleOption) {
        val authorization = SelectedRoleAuthorization(choice, role, connection.player.sessionId, RoleAuthorizationSource.CURRENT_SELECTION)
        val profile = roleProfile(GameProfile(connection.player.uniqueId, connection.player.username, emptyList()), connection.pending, role)
        connection.applyingRoleProfile = role
        connection.player.applyRoleProfile(profile).whenComplete { _, failure ->
            if (failure != null) {
                synchronized(connection) { if (selectionActive(connection, selection)) roleFailure(connection, "role-selection.retry") }
                return@whenComplete
            }
            try {
                services.submit {
                    try {
                        admitSelectedRole(connection, authorization)
                        synchronized(connection) {
                            if (!current(connection) || connection.selection !== selection || connection.access != Access.AUTHENTICATED) return@synchronized
                            selection.finished = true
                            selection.menuVisible = false
                            selection.timeout?.cancel(false)
                            selection.player.closeRoleSelector()
                            release(connection)
                        }
                    } catch (_: Exception) {
                        synchronized(connection) {
                            if (current(connection) && connection.access == Access.HELD && connection.selection === selection) {
                                roleFailure(connection, "role-selection.retry")
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                synchronized(connection) { if (selectionActive(connection, selection)) roleFailure(connection, "role-selection.retry") }
            }
        }
    }

    private fun resolveRoles(xuid: Xuid, gamertag: String, fallback: String?): RoleOptions {
        check(xuid.value > 0 && gamertag.isNotBlank())
        val options = services.roleOptions(xuid, gamertag, fallback)
        for (option in options.roles) {
            when (val identity = option.identity) {
                is Identity.Microsoft -> {
                    check(identity.xuid == xuid && identity.uuid == Identity.ofXuid(xuid))
                    check(if (GameName.parse(identity.name.value) != null) option.existing != null else
                        fallback != null && identity.name.value == fallback && option.existing == null)
                }
                is Identity.Premium -> check(options.linkedJavaUuid != null && identity.uuid == options.linkedJavaUuid &&
                    GameName.parse(identity.name.value) != null)
            }
            option.existing?.let { record ->
                check(record.uuid == option.identity.uuid && record.kind == option.reference.kind)
                // Mojang may rename J before its stored row is atomically updated at admission.
                // Keep that actual row as evidence; only U must already have the candidate's exact name.
                check(record.kind == BoundIdentity.Kind.MOJANG || record.name == option.identity.name)
                check(record.subject == if (record.kind == BoundIdentity.Kind.XUID) xuid.value.toString() else record.uuid.toString())
            }
        }
        return options
    }

    private fun revalidateRole(choice: BedrockRoleChoice, proof: BedrockProof): RoleOption {
        check(proof.isCurrent() && proof.xuid == choice.xuid && proof.gamertag == choice.gamertag &&
            proof.linkedJavaUuid == choice.linkedJavaUuid) { "Native role proof changed" }
        val options = resolveRoles(choice.xuid, choice.gamertag, proof.username)
        check(proof.isCurrent() && options.linkedJavaUuid == choice.linkedJavaUuid) { "Role relationship changed" }
        return options.roles.singleOrNull { it.reference == choice.role } ?: error("Selected role changed")
    }

    private fun roleFailure(connection: Connection, key: String) {
        close(connection)
        connection.player.disconnect(component(key, localeOf(connection.player)))
    }

    private fun fail(connection: Connection, state: VerificationSession, key: String, vararg placeholders: Pair<String, String>) {
        synchronized(connection) {
            if (!state.active()) return
            close(connection)
            // Disconnect the frontend itself: closing only the backend invites proxy failover.
            connection.player.disconnect(component(key, state.locale, *placeholders))
        }
    }

    private fun close(connection: Connection) = synchronized(connection) {
        claims.close(connection.player.sessionId)
        connection.access = Access.CLOSED
        connection.privateSelectedAuthorization = null
        connection.applyingRoleProfile = null
        releaseRoleReservation(connection.player.sessionId)
        connection.arrivalTimeout?.cancel(false)
        connection.verification?.cancel()
        connection.selection?.timeout?.cancel(false)
        connection.selection?.let { selection ->
            if (!selection.hintDelivered) selection.hint?.let(roleHints::revoke)
            selection.menuVisible = false
            runCatching { selection.player.closeRoleSelector() }
            selection.finished = true
        }
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
    @OptIn(ExperimentalStdlibApi::class)
    private fun limitKey(address: java.net.InetAddress?): String {
        if (address == null) return "unknown"

        val bytes = address.address
        if (bytes.size == 16) {
            return bytes.toHexString(0, 8) + "::/64"
        }
        return address.hostAddress
    }

    private fun connectionOf(player: Player): Connection? =
        connections[player.sessionId]?.takeIf { it.player === player }

    private fun current(connection: Connection): Boolean =
        connection.player.isActive && connections[connection.player.sessionId] === connection

    private fun bedrockMatches(pending: Pending, input: BedrockLogin): Boolean {
        val proof = pending.bedrock ?: return input === BedrockLogin.Java
        return config.bedrockEnabled && input is BedrockLogin.Verified && proof.matches(input.proof)
    }

    private fun authenticated(connection: Connection): Boolean =
        ready && current(connection) && connection.access == Access.AUTHENTICATED &&
            (if (connection.pending.roles) {
                validSelectedAuthorization(connection, connection.privateSelectedAuthorization)
            } else connection.pending.bedrock?.let { proof ->
                proof.isCurrent() && !connection.player.isOnlineMode && connection.player.uniqueId == proof.uniqueId &&
                    connection.player.username == proof.username
            } ?: true)

    private fun validSelectedAuthorization(connection: Connection, selected: SelectedRoleAuthorization?): Boolean {
        val proof = connection.pending.bedrock ?: return false
        return selected != null && selected.sessionId == connection.player.sessionId && !connection.player.isOnlineMode &&
            selected.choice.xuid == proof.xuid && selected.choice.gamertag == proof.gamertag &&
            selected.choice.linkedJavaUuid == proof.linkedJavaUuid && selected.role.reference == selected.choice.role &&
            connection.player.uniqueId == selected.role.identity.uuid && connection.player.username == selected.role.identity.name.value &&
            proof.isCurrent()
    }

    private fun addressOf(connection: InboundConnection): String =
        connection.remoteAddress.address?.hostAddress ?: connection.remoteAddress.hostString

    private fun expired(pending: Pending): Boolean =
        clock() - pending.createdAt >= PENDING_LIFETIME_MILLIS

    /** Old and disconnected decisions cannot be transferred to a later login. */
    private fun sweepPending() {
        val abandoned = synchronized(pendingLock) {
            pending.entries.filter { !it.value.connection.isActive || expired(it.value) }
                .filter { pending.remove(it.key, it.value) }.map { it.key }
        }
        abandoned.forEach { releaseRoleReservation(it); finishPremium(it, verified = false) }
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
        val previous = lastThrottleWarning.get()
        if (now - previous < THROTTLE_WARNING_MILLIS || !lastThrottleWarning.compareAndSet(previous, now)) return

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
            override fun submit(task: () -> Unit) { auth.execute(task) }
            override fun schedule(delayMillis: Long, task: () -> Unit) = auth.schedule(delayMillis, task)
            override fun begin() = DeviceCodeFlow.begin()
            override fun poll(challenge: DeviceCodeFlow.Challenge) = DeviceCodeFlow.poll(challenge)
            override fun exchange(accessToken: String) = XboxLive.exchange(accessToken, XboxLive.RELYING_PARTY_PROFILE)
            override fun permitsMappedProfile(record: BoundIdentity) = auth.permitsMappedProfile(record)
            override fun approveLegacyProfile(recordId: UUID, session: UUID, name: GameName) =
                auth.approveLegacyProfile(recordId, session, name)
            override fun authMeClaimsAvailable() = auth.authMeClaimsAvailable()
            override fun proveAuthMeProfile(legacyName: GameName, password: CharArray, session: UUID, name: GameName,
                                            current: () -> Boolean) = auth.proveAuthMeProfile(legacyName, password, session, name, current)
            override fun commitLegacyProfile(token: ProfileClaimFlow.ClaimToken, session: UUID, name: GameName, xuid: Xuid, gamertag: String,
                                             current: () -> Boolean) =
                auth.commitLegacyProfile(token, session, name, xuid, gamertag, current)
            override fun roleOptions(xuid: Xuid, gamertag: String, fallbackBedrockName: String?) =
                auth.roleOptions(xuid, gamertag, fallbackBedrockName)
        }

        /** Internal server name prefix for private Limbo endpoints; never registered in public `/server` lists. */
        const val LIMBO_SERVER_NAME = "kyokouauth-limbo"

        const val CONNECTION_PROPERTY = "kyokouauth:connection-id"

        /** How long a decision waits for the player it was made for. */
        private const val PENDING_LIFETIME_MILLIS = 5 * 60 * 1000L

        /** At most one "lookups are being withheld" warning a minute. */
        private const val THROTTLE_WARNING_MILLIS = 60_000L

    }
}