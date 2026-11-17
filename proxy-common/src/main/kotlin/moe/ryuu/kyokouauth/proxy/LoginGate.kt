package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
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
import moe.ryuu.kyokouauth.limbo.RoleAvatarIcon
import moe.ryuu.kyokouauth.proxy.avatar.RoleAvatarOffer
import java.util.concurrent.CompletableFuture
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
        /** Missing integration is an error, never an empty or implicitly authorized role list. */
        fun roleOptions(xuid: Xuid, gamertag: String, fallbackBedrockName: String?): RoleOptions =
            error("Role resolution is unavailable")
        /** Presentation only. Unavailable integration must not claim that an avatar was prepared. */
        fun prepareRoleAvatars(xuid: Xuid, options: RoleOptions): CompletableFuture<String> =
            CompletableFuture.failedFuture(IllegalStateException("Role avatars are unavailable"))
        fun roleAvatarOffer(xuid: Xuid): RoleAvatarOffer? = null
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
        val handoff: RoleHandoff? = null,
        val role: RoleOption? = null,
    ) {
        val provisional = (decision as? AuthCoordinator.Decision.Verify)?.provisional == true
        @Volatile var profileValidated = false
        // Keep chooser names isolated and valid for the private Limbo protocol. Bedrock UUIDs,
        // unlike these display aliases, must remain registered with Floodgate's message lookup.
        private val chooserName = if (roles && handoff == null)
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

    /** Authentication is not admission: only a final handoff login can leave HELD. */
    private class RoleSelection(val player: LimboPlayer, val xuid: Xuid, val gamertag: String,
                                val origin: RoleOrigin, val deadline: Long) {
        var generation = UUID.randomUUID()
        var options: RoleOptions? = null
        var choosing = false
        var transferred = false
        var handoff: RoleHandoff? = null
        var timeout: ScheduledFuture<*>? = null
        var avatar: AvatarPreparation? = null
        var avatarResolved = false
        var avatarUnavailable = false
        var menuVisible = false
    }

    private class AvatarPreparation(val options: RoleOptions) {
        var future: CompletableFuture<String>? = null
        var completed = false
    }

    /** Bounded visual retry budget, not a role hint: no chosen role, proof or authorization is stored. */
    private class AvatarRefreshBudget(private val capacity: Int, private val ttlMillis: Long, private val clock: () -> Long) {
        private data class Key(val xuid: Long, val fingerprint: String)
        private val entries = HashMap<Key, Long>()
        @Synchronized fun claim(xuid: Xuid, fingerprint: String): Boolean {
            val now = clock()
            entries.entries.removeIf { now >= it.value }
            val key = Key(xuid.value, fingerprint)
            if (entries.containsKey(key) || entries.size >= capacity) return false
            entries[key] = if (now > Long.MAX_VALUE - ttlMillis) Long.MAX_VALUE else now + ttlMillis
            return true
        }
        @Synchronized fun clear() { entries.clear() }
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
    private val roleHandoffs = RoleHandoffStore(config.pendingLoginLimit, config.roleTransferTtlSeconds * 1000L, clock)
    private val avatarRefreshes = AvatarRefreshBudget(config.pendingLoginLimit,
        (config.roleSelectionTimeoutSeconds + config.roleTransferTtlSeconds) * 1000L, clock)
    /** Serializes this gate's duplicate-role admission without disconnecting an existing player. */
    private val roleAdmissionLock = Any()

    /** Armed only after the listener and the registered limbo are both ready. */
    @Volatile private var ready = false

    fun activate() { ready = true }

    fun deactivate() {
        ready = false
        roleHandoffs.close()
        avatarRefreshes.clear()
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
    @Volatile
    private var lastThrottleWarning: Long = 0L

    // --- who may enter ---------------------------------------------------------------------

    fun onPreLogin(event: PreLoginEvent) {
        if (pending.remove(event.connection.sessionId) != null) finishPremium(event.connection.sessionId, verified = false)
        if (!ready || !event.connection.isActive) {
            event.result = denied("login.internal-error")
            return
        }
        if (!event.result.isAllowed) return
        if (event.roleCookie != null && (!config.rolesEnabled || event.bedrock !is BedrockLogin.Java)) {
            event.result = denied("login.internal-error")
            return
        }
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
        if (event.roleCookie != null) resumeJavaRole(event, name, address) else decide(event, name, address)
    }

    private fun resumeJavaRole(event: PreLoginEvent, name: GameName, address: String) {
        try {
            val grant = roleHandoffs.consumeJava(checkNotNull(event.roleCookie), event.connection.sessionId)
                ?: error("Role cookie is invalid")
            check(grant.origin == RoleOrigin.MICROSOFT)
            val option = revalidateRole(grant, null)
            check(!roleOccupied(option.identity.uuid, event.connection.sessionId)) { "Role is already online" }
            val stored = synchronized(pendingLock) {
                if (ready && event.connection.isActive && pending.size < config.pendingLoginLimit) {
                    pending[event.connection.sessionId] = Pending(event.connection, name, address,
                        AuthCoordinator.Decision.Verify(option.identity.uuid, AuthCoordinator.Reason.SESSION_EXPIRED, false, option.existing),
                        clock(), roles = true, handoff = grant, role = option)
                    true
                } else false
            }
            check(stored)
            event.result = PreLoginEvent.PreLoginComponentResult.forceOfflineMode()
        } catch (_: Exception) {
            // Presence matters: an expired, replayed or otherwise invalid cookie has no normal-login fallback.
            event.result = denied("role-selection.retry")
        }
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
            val hadHint = config.rolesEnabled && roleHandoffs.hasBedrockHint(proof.xuid)
            val grant = if (hadHint) roleHandoffs.consumeBedrock(proof, event.connection.sessionId) else null
            check(!hadHint || grant != null) { "Role hint is invalid" }
            if (config.rolesEnabled && grant == null) {
                check(!roleOccupied(proof.uniqueId, event.connection.sessionId)) { "Native Floodgate identity is already online" }
            }
            val option = grant?.let { revalidateRole(it, proof) }
            if (option != null) check(!roleOccupied(option.identity.uuid, event.connection.sessionId)) { "Role is already online" }
            val decision = if (config.rolesEnabled) {
                AuthCoordinator.Decision.Verify(option?.identity?.uuid ?: proof.uniqueId,
                    AuthCoordinator.Reason.SESSION_EXPIRED, option == null, option?.existing)
            } else {
                // Disabled role selection preserves the unlinked native Floodgate branch only.
                AuthCoordinator.Decision.VerifiedBedrock(proof.identity as? Identity.Microsoft
                    ?: error("Linked Bedrock requires role selection"))
            }
            val stored = synchronized(pendingLock) {
                if (ready && event.connection.isActive && proof.isCurrent() && pending.size < config.pendingLoginLimit) {
                    pending[event.connection.sessionId] = Pending(event.connection, proof.identity.name,
                        addressOf(event.connection), decision, clock(), proof, config.rolesEnabled, grant, option)
                    true
                } else false
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
            val original = services.decide(name, address)
            if (config.rolesEnabled && (original is AuthCoordinator.Decision.Verify ||
                    original is AuthCoordinator.Decision.Admit && original.identity is Identity.Microsoft)) {
                // IP sessions can identify the U role, but cannot prove control of an XUID for J.
                // Always collect a fresh Microsoft proof under an isolated chooser UUID.
                AuthCoordinator.Decision.Verify(UUID.randomUUID(), AuthCoordinator.Reason.SESSION_EXPIRED,
                    true, (original as? AuthCoordinator.Decision.Verify)?.existing ?: (original as? AuthCoordinator.Decision.Admit)?.bound)
            } else original
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
                    pending[event.connection.sessionId] = Pending(event.connection, name, address, decision, clock(),
                        roles = config.rolesEnabled && decision is AuthCoordinator.Decision.Verify)
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
    fun requiresRoleProfile(connection: InboundConnection): Boolean = pending[connection.sessionId]?.let {
        it.roles && ready && connection.isActive && it.connection.isActive && !expired(it)
    } == true

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
        event.connection.disconnect(component("role-selection.retry"))
    }

    private fun roleProfile(profile: GameProfile, state: Pending): GameProfile {
        val properties = profile.properties.filterNot { it.name == "textures" || it.name == CONNECTION_PROPERTY } +
            listOfNotNull(state.role?.skin) + GameProfile.Property(CONNECTION_PROPERTY, state.connection.sessionId.toString(), "")
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
            finishPremium(player.sessionId, verified = false)
            return
        }
        if (!ready || !player.isActive || pending == null || expired(pending) ||
            !pending.connection.isActive || !bedrockMatches(pending, event.bedrock) ||
            (if (pending.roles) !pending.profileValidated || player.username != pending.profileName || player.uniqueId != pending.profileUuid
                else if (pending.bedrock != null) player.username != pending.name.value else GameName.parse(player.username)?.key != pending.name.key) ||
            addressOf(player) != pending.address
        ) {
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
            synchronized(roleAdmissionLock) {
                if (pending.roles && pending.handoff != null) admitSelectedRole(connection)
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
            logger.error("Could not authorize {}", player.username, failure)
            event.result = ResultedEvent.ComponentResult.denied(component("login.internal-error"))
        }
    }

    private fun admitSelectedRole(connection: Connection) {
        val state = connection.pending
        val grant = checkNotNull(state.handoff)
        val selected = checkNotNull(state.role)
        check(revalidateRole(grant, state.bedrock).reference == selected.reference)
        check(!roleOccupied(selected.identity.uuid, connection.player.sessionId)) { "Role is already online" }
        if (selected.identity is Identity.Premium) {
            // A valid current XUID -> J relationship is its own proof path, not a forged MojangSession.
            val outcome = services.bindPremium(selected.identity.uuid, selected.identity.name)
            check(outcome is AuthCoordinator.ClaimOutcome.Bound && outcome.record.uuid == selected.identity.uuid &&
                outcome.record.kind == BoundIdentity.Kind.MOJANG && outcome.record.name == selected.identity.name) {
                "Premium role binding refused"
            }
        }
        check(revalidateRole(grant, state.bedrock).reference == selected.reference)
        check(ready && connection.player.isActive && state.connection.isActive && !expired(state) &&
            (state.bedrock?.isCurrent() != false)) { "Role login is stale" }
        check(!roleOccupied(selected.identity.uuid, connection.player.sessionId)) { "Role is already online" }
        if (grant.origin == RoleOrigin.MICROSOFT && selected.identity is Identity.Microsoft) {
            services.grantSession(selected.identity.uuid, state.address)
        }
        check(ready && connection.player.isActive && state.bedrock?.isCurrent() != false)
        connection.access = Access.AUTHENTICATED
    }

    private fun roleOccupied(uuid: UUID, session: UUID): Boolean =
        server.allPlayers.any { it.isActive && it.sessionId != session && it.uniqueId == uuid } ||
            connections.values.any { it.player.sessionId != session && current(it) &&
                it.access != Access.CLOSED && it.player.uniqueId == uuid }

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
                startRoleSelection(connection, player, proof.xuid, proof.gamertag, RoleOrigin.FLOODGATE)
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
        synchronized(connection) { if (!state.beforeDeadline()) return }
        if (connection.pending.roles) {
            completeRoles(connection, state, xsts)
            return
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

    private fun completeRoles(connection: Connection, state: VerificationSession, xsts: XboxLive.XstsToken) {
        try {
            val xuid = Xuid(xsts.xuid)
            val outcome = services.bindMicrosoft(connection.pending.name, xuid, xsts.gamertag)
            synchronized(connection) { if (!state.beforeDeadline()) return }
            if (outcome is AuthCoordinator.ClaimOutcome.Refused) {
                fail(connection, state, keyFor(outcome.reason), "reason" to outcome.detail,
                    "name" to outcome.existingName?.value.orEmpty())
                return
            }
            check(outcome is AuthCoordinator.ClaimOutcome.Bound && outcome.record.kind == BoundIdentity.Kind.XUID &&
                outcome.record.uuid == Identity.ofXuid(xuid) && outcome.record.subject == xuid.value.toString() &&
                outcome.record.name.key == connection.pending.name.key) { "Microsoft role binding changed" }
            val options = resolveRoles(xuid, xsts.gamertag, null)
            synchronized(connection) {
                if (!state.beforeDeadline()) return
                // End the attempt before opening a menu; old poll/timeout callbacks no longer own anything.
                state.cancel()
                connection.verification = null
                startRoleSelection(connection, state.player, xuid, xsts.gamertag, RoleOrigin.MICROSOFT, options)
            }
        } catch (_: Exception) {
            synchronized(connection) {
                if (state.active()) fail(connection, state, "role-selection.retry")
            }
        }
    }

    private fun startRoleSelection(connection: Connection, player: LimboPlayer, xuid: Xuid, gamertag: String,
                                   origin: RoleOrigin, options: RoleOptions? = null) {
        val selection = RoleSelection(player, xuid, gamertag, origin,
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
            connection.limboPlayer === selection.player && !selection.transferred

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
        if (options.roles.size == 1) {
            selectRole(connection, selection, options.roles.single())
            return
        }
        val avatars = config.roleAvatarsEnabled && selection.origin == RoleOrigin.FLOODGATE && connection.pending.bedrock != null
        if (avatars && !selection.avatarResolved) {
            if (selection.avatar == null) prepareAvatars(connection, selection, options)
            return
        }
        val microsoft = options.roles.single { it.identity is Identity.Microsoft }
        val premium = options.roles.single { it.identity is Identity.Premium }
        fun item(option: RoleOption, id: String, label: String, icon: RoleAvatarIcon) = RoleSelectorItem(id, option.identity.uuid,
            option.identity.name.value, option.skin?.value, option.skin?.signature,
            limboText(label, localeOf(connection.player), "name" to option.identity.name.value),
            listOf(limboText("role-selection.enter", localeOf(connection.player))), avatarIcon = icon.takeIf { avatars })
        if (selection.avatarUnavailable) selection.player.sendMessage(limboText("role-selection.avatar-unavailable", localeOf(connection.player)))
        selection.menuVisible = true
        selection.player.openRoleSelector(RoleSelectorMenu(selection.generation,
            limboText("role-selection.title", localeOf(connection.player)),
            item(microsoft, "microsoft", "role-selection.microsoft", RoleAvatarIcon.MICROSOFT),
            item(premium, "premium", "role-selection.premium", RoleAvatarIcon.PREMIUM)))
    }

    private fun avatarCurrent(connection: Connection, selection: RoleSelection, avatar: AvatarPreparation): Boolean =
        selectionActive(connection, selection) && selection.avatar === avatar && !avatar.completed

    /** Only queues work here: even creating the preparation future happens outside the connection lock. */
    private fun prepareAvatars(connection: Connection, selection: RoleSelection, options: RoleOptions) {
        val avatar = AvatarPreparation(options)
        selection.avatar = avatar
        selection.player.sendMessage(limboText("role-selection.avatar-loading", localeOf(connection.player)))
        try {
            services.submit {
                synchronized(connection) { if (!avatarCurrent(connection, selection, avatar)) return@submit }
                val future = try {
                    // Cancel only this connection's dependent future, not a shared image/cache computation.
                    services.prepareRoleAvatars(selection.xuid, options).thenApply { it }
                } catch (_: Exception) {
                    synchronized(connection) { finishAvatars(connection, selection, avatar, unavailable = true) }
                    return@submit
                }
                synchronized(connection) {
                    if (!avatarCurrent(connection, selection, avatar)) { future.cancel(false); return@submit }
                    avatar.future = future
                }
                future.whenComplete { fingerprint, failure ->
                    synchronized(connection) {
                        if (!avatarCurrent(connection, selection, avatar)) return@whenComplete
                        if (failure != null || fingerprint?.matches(Regex("[a-f0-9]{64}")) != true) {
                            finishAvatars(connection, selection, avatar, unavailable = true)
                            return@whenComplete
                        }
                    }
                    // Completion means images are prepared, not that Geyser offered this session's pack.
                    queueAvatarOffer(connection, selection, avatar, fingerprint)
                }
            }
        } catch (_: Exception) {
            finishAvatars(connection, selection, avatar, unavailable = true)
        }
    }

    private fun queueAvatarOffer(connection: Connection, selection: RoleSelection, avatar: AvatarPreparation, fingerprint: String) {
        try {
            services.submit {
                synchronized(connection) { if (!avatarCurrent(connection, selection, avatar)) return@submit }
                val offer = runCatching { services.roleAvatarOffer(selection.xuid) }.getOrNull()
                synchronized(connection) {
                    if (!avatarCurrent(connection, selection, avatar)) return@submit
                    if (offer != null && !offer.fallback && offer.fingerprint == fingerprint) {
                        finishAvatars(connection, selection, avatar, unavailable = false)
                    } else if (avatarRefreshes.claim(selection.xuid, fingerprint)) {
                        refreshAvatars(connection, selection, avatar)
                    } else {
                        finishAvatars(connection, selection, avatar, unavailable = true)
                    }
                }
            }
        } catch (_: Exception) {
            synchronized(connection) { finishAvatars(connection, selection, avatar, unavailable = true) }
        }
    }

    /** A visual retry never mints/consumes a RoleHandoff and cannot select or authorize any role. */
    private fun refreshAvatars(connection: Connection, selection: RoleSelection, avatar: AvatarPreparation) {
        if (!avatarCurrent(connection, selection, avatar)) return
        selection.transferred = true
        selection.menuVisible = false
        try {
            connection.player.transferRole(config.roleTransferHost, config.roleTransferPort, null).whenComplete { _, failure ->
                if (failure != null) synchronized(connection) {
                    if (ready && current(connection) && connection.access == Access.HELD && connection.selection === selection &&
                        selection.avatar === avatar && !avatar.completed) {
                        selection.transferred = false
                        finishAvatars(connection, selection, avatar, unavailable = true)
                    }
                }
            }
        } catch (_: Exception) {
            selection.transferred = false
            finishAvatars(connection, selection, avatar, unavailable = true)
        }
    }

    private fun finishAvatars(connection: Connection, selection: RoleSelection, avatar: AvatarPreparation, unavailable: Boolean) {
        if (!avatarCurrent(connection, selection, avatar)) return
        avatar.completed = true
        selection.avatar = null
        selection.avatarResolved = true
        selection.avatarUnavailable = unavailable
        // An open session keeps its immutable pack; later name/menu refreshes do not hot-swap images.
        try { presentRoles(connection, selection, avatar.options) }
        catch (_: Exception) { roleFailure(connection, "role-selection.retry") }
    }

    override fun onRoleSelect(player: LimboPlayer, generation: UUID, optionId: String) {
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
                        if (snapshot.linkedJavaUuid != latest.linkedJavaUuid ||
                            latest.roles.none { it.reference == chosen.reference }) {
                            // A stale menu must not silently reinterpret a renamed option or revoked J.
                            if (latest.roles.size == 1) roleFailure(connection, "role-selection.retry")
                            else presentRoles(connection, selection, latest)
                            return@submit
                        }
                        connection.pending.bedrock?.let { check(it.linkedJavaUuid == latest.linkedJavaUuid) }
                        check(!roleOccupied(chosen.identity.uuid, connection.player.sessionId)) { "Role is already online" }
                        val grant = RoleHandoff(selection.xuid, selection.gamertag, chosen.reference,
                            latest.linkedJavaUuid, selection.origin, connection.player.sessionId)
                        val cookie = if (selection.origin == RoleOrigin.MICROSOFT) roleHandoffs.issueJava(grant)
                            else { roleHandoffs.issueBedrock(grant); null }
                        selection.handoff = grant
                        selection.transferred = true
                        selection.player.closeRoleSelector()
                        // The chooser remains HELD. Only the new exact login may redeem this role.
                        connection.player.transferRole(config.roleTransferHost, config.roleTransferPort, cookie)
                            .whenComplete { _, failure ->
                                if (failure != null) synchronized(connection) {
                                    roleHandoffs.revoke(grant)
                                    if (current(connection) && connection.access == Access.HELD) {
                                        roleFailure(connection, "role-selection.reconnect")
                                    }
                                }
                            }
                    }
                } catch (_: Exception) {
                    synchronized(connection) {
                        selection.handoff?.let(roleHandoffs::revoke)
                        if (current(connection) && connection.access == Access.HELD && connection.selection === selection) {
                            roleFailure(connection, "role-selection.retry")
                        }
                    }
                }
            }
        } catch (_: Exception) {
            selection.handoff?.let(roleHandoffs::revoke)
            roleFailure(connection, "role-selection.retry")
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

    private fun revalidateRole(grant: RoleHandoff, proof: BedrockProof?): RoleOption {
        if (grant.origin == RoleOrigin.FLOODGATE) {
            check(proof != null && proof.isCurrent() && proof.xuid == grant.xuid && proof.gamertag == grant.gamertag &&
                proof.linkedJavaUuid == grant.linkedJavaUuid) { "Native role proof changed" }
        } else check(proof == null)
        val options = resolveRoles(grant.xuid, grant.gamertag, proof?.username)
        check(options.linkedJavaUuid == grant.linkedJavaUuid) { "Role relationship changed" }
        return options.roles.singleOrNull { it.reference == grant.role } ?: error("Selected role changed")
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
        connection.access = Access.CLOSED
        connection.arrivalTimeout?.cancel(false)
        connection.verification?.cancel()
        connection.selection?.timeout?.cancel(false)
        connection.selection?.let { selection ->
            selection.avatar?.future?.cancel(false)
            selection.avatar = null
            selection.menuVisible = false
            runCatching { selection.player.closeRoleSelector() }
            selection.transferred = true
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

    private fun bedrockMatches(pending: Pending, input: BedrockLogin): Boolean {
        val proof = pending.bedrock ?: return input === BedrockLogin.Java
        return config.bedrockEnabled && input is BedrockLogin.Verified && proof.matches(input.proof)
    }

    private fun authenticated(connection: Connection): Boolean =
        ready && current(connection) && connection.access == Access.AUTHENTICATED &&
            (if (connection.pending.roles) {
                connection.pending.handoff != null && connection.pending.role != null && !connection.player.isOnlineMode &&
                    connection.player.uniqueId == connection.pending.profileUuid && connection.player.username == connection.pending.profileName &&
                    connection.pending.bedrock?.isCurrent() != false
            } else connection.pending.bedrock?.let { proof ->
                proof.isCurrent() && !connection.player.isOnlineMode && connection.player.uniqueId == proof.uniqueId &&
                    connection.player.username == proof.username
            } ?: true)

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
        abandoned.forEach { finishPremium(it, verified = false) }
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
            override fun submit(task: () -> Unit) { auth.execute(task) }
            override fun schedule(delayMillis: Long, task: () -> Unit) = auth.schedule(delayMillis, task)
            override fun begin() = DeviceCodeFlow.begin()
            override fun poll(challenge: DeviceCodeFlow.Challenge) = DeviceCodeFlow.poll(challenge)
            override fun exchange(accessToken: String) = XboxLive.exchange(accessToken, XboxLive.RELYING_PARTY_PROFILE)
            override fun roleOptions(xuid: Xuid, gamertag: String, fallbackBedrockName: String?) =
                auth.roleOptions(xuid, gamertag, fallbackBedrockName)
            override fun prepareRoleAvatars(xuid: Xuid, options: RoleOptions) = auth.prepareRoleAvatars(xuid, options)
            override fun roleAvatarOffer(xuid: Xuid) = auth.roleAvatarOffer(xuid)
        }

        /** The name the limbo is registered under. Visible in `/server` and in the proxy's lists. */
        const val LIMBO_SERVER_NAME = "kyokouauth-limbo"

        const val CONNECTION_PROPERTY = "kyokouauth:connection-id"

        /** How long a decision waits for the player it was made for. */
        private const val PENDING_LIFETIME_MILLIS = 5 * 60 * 1000L

        /** At most one "lookups are being withheld" warning a minute. */
        private const val THROTTLE_WARNING_MILLIS = 60_000L

    }
}