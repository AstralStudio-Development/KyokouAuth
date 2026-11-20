package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.ConfirmationMenu
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connection-bound two-step legacy profile claim flow (`AuthMe -> Java U`).
 *
 * Source authorization comes from independent operator verification or the read-only AuthMe
 * verifier reached only by private packet ingress. Ordinary chat/public command dispatch and prior
 * IP/AuthMe sessions never grant ownership; no player-visible bearer grant is issued.
 *
 * Prepared source authorization and the fresh Microsoft/Xbox proof are bound to the exact session
 * within a bounded TTL. Rejected passwords remain isolated for gate-limited retry; cancellation,
 * expiry or storage conflicts never fall back to ordinary binding or grant backend admission.
 */
class ProfileClaimFlow(
    private val messages: MessageCatalogue,
    private val services: Services,
    private val callbacks: Callbacks,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    init {
        require(ttlMillis in 1L..MAX_TTL_MILLIS) {
            "ttlMillis must be in 1..$MAX_TTL_MILLIS, got $ttlMillis"
        }
        require(maxEntries in 1..MAX_CAPACITY) {
            "maxEntries must be in 1..$MAX_CAPACITY, got $maxEntries"
        }
    }

    /** External executors and storage operations; never invoked while holding flow or entry locks. */
    interface Services {
        fun submit(task: () -> Unit)
        fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*>
        fun approve(recordId: UUID, session: UUID, name: GameName): PreparedClaim
        fun authMeProofAvailable(): Boolean = false
        fun proveAuthMe(legacyName: GameName, password: CharArray, session: UUID, name: GameName,
                        current: () -> Boolean): PreparedClaim? { password.fill('\u0000'); return null }
        fun commit(token: ClaimToken, session: UUID, name: GameName, xuid: Xuid, gamertag: String): BoundIdentity
    }

    /** Gate integration hooks; always invoked outside internal locks so synchronous re-entry is safe. */
    interface Callbacks {
        fun startVerification(player: Player, limbo: LimboPlayer)
        fun claimed(player: Player, limbo: LimboPlayer, record: BoundIdentity)
        fun failed(player: Player, limbo: LimboPlayer, key: String)
    }

    /** Opaque server-only claim capability; string rendering never exposes its secret value. */
    class ClaimToken(val value: String) {
        init {
            require(value.isNotBlank()) { "ClaimToken value must not be blank" }
        }

        override fun equals(other: Any?): Boolean = other is ClaimToken && other.value == value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): String = "ClaimToken[REDACTED]"
    }

    /** Prepared legacy claim bound to a single waiting connection session; [token] is redacted in logs. */
    data class PreparedClaim(
        val token: ClaimToken,
        val recordId: UUID,
        val legacyName: GameName,
        val profileUuid: UUID,
        val expiresAt: Long,
    ) {
        constructor(token: String, recordId: UUID, legacyName: GameName, profileUuid: UUID, expiresAt: Long) :
            this(ClaimToken(token), recordId, legacyName, profileUuid, expiresAt)

        override fun toString(): String =
            "PreparedClaim(token=$token, recordId=$recordId, legacyName=$legacyName, profileUuid=$profileUuid, expiresAt=$expiresAt)"
    }

    enum class Stage {
        OFFER,
        WAITING_OLD,
        CONFIRM_OLD,
        MICROSOFT,
        CONFIRM_BIND,
        COMMITTING,
        CLOSED,
    }

    /** Safe operator view of an active claim session; never carries a secret token. */
    data class PendingClaim(
        val sessionId: UUID,
        val loginName: GameName,
        val stage: Stage,
        val legacyName: GameName? = null,
        val expiresAt: Long,
    )

    private class Entry(
        val sessionId: UUID,
        val player: Player,
        val limbo: LimboPlayer,
        val loginName: GameName,
        val locale: String?,
        var deadlineMillis: Long,
    ) {
        var stage: Stage = Stage.OFFER
        var generation: UUID? = null
        val seenGenerations = HashSet<UUID>()
        var authorizing: Boolean = false
        var pendingPassword: CharArray? = null
        var prepared: PreparedClaim? = null
        var xuid: Xuid? = null
        var gamertag: String? = null
        var timeout: ScheduledFuture<*>? = null
    }

    private val registryLock = Any()
    private val entries = ConcurrentHashMap<UUID, Entry>()
    private val stopped = AtomicBoolean(false)

    /**
     * Opens the initial claim offer for a Java first-time connection in the limbo.
     *
     * Retains the exact [player] and [limbo] object references; any prior entry under the same
     * session ID is invalidated without cross-contaminating handles.
     */
    fun open(player: Player, limbo: LimboPlayer, loginName: GameName) {
        val sessionId = player.sessionId
        if (stopped.get() || !handlesMatch(player, limbo, sessionId, loginName)) {
            safeFailed(player, limbo, "login.internal-error")
            return
        }

        val now = clock()
        val deadline = saturatingAdd(now, ttlMillis)
        val entry = Entry(
            sessionId = sessionId,
            player = player,
            limbo = limbo,
            loginName = loginName,
            locale = player.locale,
            deadlineMillis = deadline,
        )
        val menuGeneration = UUID.randomUUID()
        entry.generation = menuGeneration
        entry.seenGenerations.add(menuGeneration)

        var displaced: Entry? = null
        val expiredDuringOpen = ArrayList<Entry>()
        val admitted = synchronized(registryLock) {
            purgeExpiredLocked(now, expiredDuringOpen)
            if (stopped.get()) {
                false
            } else {
                val previous = entries.remove(sessionId)
                if (previous == null && entries.size >= maxEntries) {
                    false
                } else {
                    displaced = previous
                    entries[sessionId] = entry
                    true
                }
            }
        }

        for (expiredEntry in expiredDuringOpen) {
            if (expiredEntry !== displaced) {
                closeEntryAndFail(expiredEntry, "migration.expired")
            }
        }
        displaced?.let { old -> closeEntryAndFail(old, null) }

        if (!admitted) {
            safeFailed(player, limbo, if (stopped.get()) "login.internal-error" else "migration.busy")
            return
        }

        val timer = try {
            services.schedule(ttlMillis) { onTimeout(entry) }
        } catch (_: Exception) {
            abortEntry(entry, "migration.failed")
            return
        }

        var active = false
        synchronized(entry) {
            if (isCurrentLocked(entry, now)) {
                entry.timeout = timer
                active = true
            }
        }
        if (!active) {
            timer.cancel(false)
            return
        }

        val menu = ConfirmationMenu(
            generation = menuGeneration,
            title = text("migration.offer-title", entry.locale),
            confirmLabel = text("migration.offer-confirm", entry.locale, "name" to loginName.value),
            cancelLabel = text("migration.offer-cancel", entry.locale, "name" to loginName.value),
            confirmLore = listOf(text("migration.offer-confirm-lore", entry.locale)),
            cancelLore = listOf(text("migration.offer-cancel-lore", entry.locale)),
        )
        try {
            limbo.openConfirmation(menu)
        } catch (_: Exception) {
            abortEntry(entry, "migration.failed")
        }
    }

    /** Pure read-only check whether an unexpired, live claim flow is owned by [session]. */
    fun isActive(session: UUID): Boolean {
        if (stopped.get()) return false
        val entry = entries[session] ?: return false
        val now = clock()
        return synchronized(entry) {
            !stopped.get() && isOwnedLocked(entry) && now < entry.deadlineMillis
        }
    }

    /** Pure read-only check whether an unexpired, live claim flow owns the exact [player] and [limbo] handles. */
    fun hasActive(player: Player, limbo: LimboPlayer): Boolean {
        if (stopped.get()) return false
        val entry = entries[player.sessionId] ?: return false
        val now = clock()
        return synchronized(entry) {
            !stopped.get() &&
                isOwnedLocked(entry) &&
                entry.player === player &&
                entry.limbo === limbo &&
                now < entry.deadlineMillis
        }
    }

    /** Safe read-only snapshot of active flows; never exposes [ClaimToken] or internal handles. */
    fun pending(): List<PendingClaim> {
        if (stopped.get()) return emptyList()
        val now = clock()
        val result = ArrayList<PendingClaim>()
        for (entry in entries.values) {
            synchronized(entry) {
                if (stopped.get() || !isOwnedLocked(entry) || now >= entry.deadlineMillis) return@synchronized
                result.add(
                    PendingClaim(
                        sessionId = entry.sessionId,
                        loginName = entry.loginName,
                        stage = entry.stage,
                        legacyName = entry.prepared?.legacyName,
                        expiresAt = entry.deadlineMillis,
                    ),
                )
            }
        }
        return result
    }

    /**
     * Administrator console authorization of a concrete legacy source record [recordId] for the
     * exact waiting [session]. Accepted only in [Stage.WAITING_OLD] with no in-flight approval.
     */
    fun authorize(session: UUID, recordId: UUID): CompletableFuture<Unit> {
        if (stopped.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("Claim flow is closed"))
        }
        val entry = entries[session]
            ?: return CompletableFuture.failedFuture(IllegalStateException("No pending claim session: $session"))

        val now = clock()
        var timedOut = false
        var snapshotName: GameName? = null
        val problem: Throwable? = synchronized(entry) {
            when {
                !isOwnedLocked(entry) -> IllegalStateException("Session is no longer active")
                now >= entry.deadlineMillis -> {
                    timedOut = true
                    IllegalStateException("Claim session has expired")
                }
                entry.stage != Stage.WAITING_OLD || entry.authorizing ->
                    IllegalStateException("Session is not awaiting administrator authorization (stage=${entry.stage})")
                else -> {
                    entry.authorizing = true
                    snapshotName = entry.loginName
                    null
                }
            }
        }
        if (timedOut) onTimeout(entry)
        if (problem != null) return CompletableFuture.failedFuture(problem)

        val loginName = checkNotNull(snapshotName)
        val completion = CompletableFuture<Unit>()
        try {
            services.submit {
                runAuthorize(entry, recordId, loginName, completion)
            }
        } catch (rejected: Exception) {
            abortEntry(entry, "migration.failed")
            completion.completeExceptionally(rejected)
        }
        return completion
    }

    /** Takes password ownership, including when the request is stale, disconnected or overloaded. */
    fun proveAuthMe(player: Player, limbo: LimboPlayer, legacyName: GameName,
                    password: CharArray): CompletableFuture<Boolean> {
        val entry = entries[player.sessionId]
        val completion = CompletableFuture<Boolean>()
        if (entry == null) {
            password.fill('\u0000')
            return CompletableFuture.completedFuture(false)
        }
        val accepted = synchronized(entry) {
            if (!isCurrentLocked(entry, clock()) || entry.player !== player || entry.limbo !== limbo ||
                entry.stage !in setOf(Stage.OFFER, Stage.WAITING_OLD) || entry.authorizing || password.size !in 1..1024) false
            else {
                entry.stage = Stage.WAITING_OLD
                entry.generation = null
                entry.authorizing = true
                entry.pendingPassword = password
                true
            }
        }
        if (!accepted) {
            password.fill('\u0000')
            return CompletableFuture.completedFuture(false)
        }
        val current = { synchronized(entry) { isCurrentLocked(entry, clock()) &&
            entry.stage == Stage.WAITING_OLD && entry.authorizing && entry.pendingPassword === password } }
        try {
            limbo.closeRoleSelector()
            services.submit {
                try {
                    if (!current()) { completion.complete(false); return@submit }
                    val prepared = services.proveAuthMe(legacyName, password, entry.sessionId, entry.loginName, current)
                    if (!current() || prepared == null) {
                        synchronized(entry) { if (entries[entry.sessionId] === entry) entry.authorizing = false }
                        completion.complete(false)
                        return@submit
                    }
                    check(prepared.legacyName.key == legacyName.key) { "Legacy proof source changed" }
                    val finish = CompletableFuture<Unit>()
                    finish.whenComplete { _, failure ->
                        if (failure == null) completion.complete(true)
                        else completion.completeExceptionally(IllegalStateException("Legacy proof unavailable"))
                    }
                    finishAuthorization(entry, prepared.recordId, entry.loginName, finish, prepared)
                } catch (_: Throwable) {
                    synchronized(entry) { if (entries[entry.sessionId] === entry) entry.authorizing = false }
                    completion.completeExceptionally(IllegalStateException("Legacy proof unavailable"))
                } finally {
                    password.fill('\u0000')
                    synchronized(entry) { if (entry.pendingPassword === password) entry.pendingPassword = null }
                }
            }
        } catch (_: Exception) {
            password.fill('\u0000')
            synchronized(entry) {
                if (entry.pendingPassword === password) { entry.pendingPassword = null; entry.authorizing = false }
            }
            completion.completeExceptionally(IllegalStateException("Legacy proof unavailable"))
        }
        return completion
    }

    /** Cancellation never races an already committing ownership handoff. */
    fun cancel(player: Player, limbo: LimboPlayer): Boolean {
        val entry = entries[player.sessionId] ?: return false
        synchronized(entry) {
            if (!isOwnedLocked(entry) || entry.player !== player || entry.limbo !== limbo || entry.stage == Stage.COMMITTING) return false
            detachLocked(entry)
        }
        runCatching { limbo.closeRoleSelector() }
        safeFailed(player, limbo, "migration.cancelled")
        return true
    }

    private fun runAuthorize(
        entry: Entry,
        recordId: UUID,
        loginName: GameName,
        completion: CompletableFuture<Unit>,
    ) {
        val prepared = try {
            services.approve(recordId, entry.sessionId, loginName)
        } catch (failure: Throwable) {
            abortEntry(entry, "migration.failed")
            completion.completeExceptionally(failure)
            return
        }
        finishAuthorization(entry, recordId, loginName, completion, prepared)
    }

    private fun finishAuthorization(entry: Entry, recordId: UUID, loginName: GameName,
                                    completion: CompletableFuture<Unit>, prepared: PreparedClaim) {
        val now = clock()
        if (prepared.recordId != recordId || prepared.expiresAt <= now) {
            abortEntry(entry, "migration.failed")
            completion.completeExceptionally(IllegalStateException("Prepared claim is invalid or already expired"))
            return
        }

        var nextMenu: ConfirmationMenu? = null
        var timedOut = false
        var invalid = false
        var staleError: Throwable? = null
        val boundedDeadline = minOf(
            saturatingAdd(now, MAX_TTL_MILLIS),
            prepared.expiresAt,
        )

        synchronized(entry) {
            when {
                stopped.get() || !isOwnedLocked(entry) ->
                    staleError = IllegalStateException("Session closed before authorization completed")
                now >= entry.deadlineMillis -> {
                    timedOut = true
                    staleError = IllegalStateException("Claim session expired during authorization")
                }
                entry.stage != Stage.WAITING_OLD || !entry.authorizing ->
                    staleError = IllegalStateException("Claim session left WAITING_OLD during authorization")
                boundedDeadline <= now -> {
                    invalid = true
                    staleError = IllegalStateException("Prepared claim expired")
                }
                else -> {
                    entry.authorizing = false
                    entry.prepared = prepared
                    if (boundedDeadline < entry.deadlineMillis) {
                        entry.deadlineMillis = boundedDeadline
                    }
                    entry.stage = Stage.CONFIRM_OLD
                    val gen = UUID.randomUUID()
                    entry.generation = gen
                    entry.seenGenerations.add(gen)
                    nextMenu = ConfirmationMenu(
                        generation = gen,
                        title = text("migration.confirm-old-title", entry.locale),
                        confirmLabel = text("migration.confirm-old-confirm", entry.locale, "from" to prepared.legacyName.value),
                        cancelLabel = text("migration.confirm-old-cancel", entry.locale),
                        confirmLore = listOf(
                            text(
                                "migration.confirm-old-lore",
                                entry.locale,
                                "from" to prepared.legacyName.value,
                                "to" to entry.loginName.value,
                            ),
                        ),
                        cancelLore = listOf(text("migration.confirm-old-cancel-lore", entry.locale)),
                    )
                }
            }
        }

        if (timedOut) {
            onTimeout(entry)
            completion.completeExceptionally(checkNotNull(staleError))
            return
        }
        if (invalid) {
            abortEntry(entry, "migration.failed")
            completion.completeExceptionally(checkNotNull(staleError))
            return
        }
        if (staleError != null) {
            completion.completeExceptionally(staleError)
            return
        }

        val delayMillis = synchronized(entry) { (entry.deadlineMillis - now).coerceAtLeast(1L) }
        if (!rescheduleTimeout(entry, delayMillis)) {
            completion.completeExceptionally(IllegalStateException("Could not arm claim timeout"))
            return
        }

        val stillConfirmOld = synchronized(entry) { isOwnedLocked(entry) && entry.stage == Stage.CONFIRM_OLD }
        if (!stillConfirmOld) {
            completion.completeExceptionally(IllegalStateException("Session closed before confirmation could be shown"))
            return
        }

        try {
            entry.limbo.openConfirmation(checkNotNull(nextMenu))
            completion.complete(Unit)
        } catch (failure: Exception) {
            abortEntry(entry, "migration.failed")
            completion.completeExceptionally(failure)
        }
    }

    /**
     * Delivers a fresh Microsoft/Xbox proof for the exact [player]/[limbo] handles.
     *
     * Returns `true` only when an active claim flow owns these exact [player] and [limbo] handles
     * (advancing or failing that claim flow closed). Returns `false` for ordinary connections or
     * superseded handles so the caller's normal login/verification path is never swallowed.
     */
    fun verified(player: Player, limbo: LimboPlayer, xuid: Xuid, gamertag: String): Boolean {
        val sessionId = player.sessionId
        val entry = entries[sessionId] ?: return false

        val now = clock()
        var timedOut = false
        var failKey: String? = null
        var confirmMenu: ConfirmationMenu? = null

        synchronized(entry) {
            if (entries[sessionId] !== entry || entry.stage == Stage.CLOSED ||
                entry.player !== player || entry.limbo !== limbo
            ) {
                return false
            }
            if (stopped.get() || !handlesLive(entry)) {
                failKey = "login.internal-error"
                return@synchronized
            }
            if (now >= entry.deadlineMillis) {
                timedOut = true
                return@synchronized
            }
            val prepared = entry.prepared
            if (entry.stage != Stage.MICROSOFT || prepared == null || now >= prepared.expiresAt ||
                xuid.value <= 0L || gamertag.isBlank()
            ) {
                failKey = "migration.failed"
                return@synchronized
            }

            entry.xuid = xuid
            entry.gamertag = gamertag
            entry.stage = Stage.CONFIRM_BIND
            val gen = UUID.randomUUID()
            entry.generation = gen
            entry.seenGenerations.add(gen)
            confirmMenu = ConfirmationMenu(
                generation = gen,
                title = text("migration.confirm-bind-title", entry.locale),
                confirmLabel = text("migration.confirm-bind-confirm", entry.locale, "to" to entry.loginName.value),
                cancelLabel = text("migration.confirm-bind-cancel", entry.locale),
                confirmLore = listOf(
                    text(
                        "migration.confirm-bind-lore",
                        entry.locale,
                        "from" to prepared.legacyName.value,
                        "to" to entry.loginName.value,
                        "gamertag" to gamertag,
                    ),
                ),
                cancelLore = listOf(text("migration.confirm-bind-cancel-lore", entry.locale)),
            )
        }

        if (timedOut) {
            onTimeout(entry)
            return true
        }

        if (failKey != null) {
            abortEntry(entry, failKey!!)
            return true
        }

        try {
            limbo.clearTitle()
            limbo.openConfirmation(checkNotNull(confirmMenu))
        } catch (_: Exception) {
            abortEntry(entry, "migration.failed")
        }
        return true
    }

    /**
     * Routes a selector click from [limbo].
     *
     * Returns `false` when [limbo]'s `connectionId` is not owned by this flow (allowing other menus
     * such as Bedrock role selection to handle it). Stale generations belonging to an active flow
     * are swallowed and return `true`.
     */
    fun select(limbo: LimboPlayer, generation: UUID, optionId: String): Boolean {
        val sessionId = limbo.connectionId
        val entry = entries[sessionId] ?: return false

        val now = clock()
        var timedOut = false
        var startNormalVerification = false
        var startClaimVerification = false
        var waitingOldPrompt = false
        var commitSnapshot: CommitSnapshot? = null
        var failKey: String? = null
        var swallow = false

        synchronized(entry) {
            if (entries[sessionId] !== entry || entry.stage == Stage.CLOSED || entry.limbo !== limbo) {
                return false
            }
            if (stopped.get() || !handlesLive(entry)) {
                failKey = "login.internal-error"
                return@synchronized
            }
            if (now >= entry.deadlineMillis) {
                timedOut = true
                return@synchronized
            }
            if (entry.generation != generation) {
                // Swallow stale or unknown generation while this session belongs to ProfileClaimFlow.
                swallow = true
                return@synchronized
            }
            if (optionId != ConfirmationMenu.CONFIRM_ID && optionId != ConfirmationMenu.CANCEL_ID) {
                swallow = true
                return@synchronized
            }

            // Consume current menu generation so duplicate clicks cannot replay.
            entry.generation = null
            val confirmed = optionId == ConfirmationMenu.CONFIRM_ID

            when (entry.stage) {
                Stage.OFFER -> {
                    if (confirmed) {
                        entry.stage = Stage.WAITING_OLD
                        waitingOldPrompt = true
                    } else {
                        // Explicit decline at OFFER removes the claim flow and enters ordinary verification.
                        detachLocked(entry)
                        startNormalVerification = true
                    }
                }
                Stage.CONFIRM_OLD -> {
                    val prepared = entry.prepared
                    if (!confirmed) {
                        failKey = "migration.cancelled"
                    } else if (prepared == null || now >= prepared.expiresAt) {
                        failKey = "migration.expired"
                    } else {
                        entry.stage = Stage.MICROSOFT
                        startClaimVerification = true
                    }
                }
                Stage.CONFIRM_BIND -> {
                    val prepared = entry.prepared
                    val xuid = entry.xuid
                    val gamertag = entry.gamertag
                    if (!confirmed) {
                        failKey = "migration.cancelled"
                    } else if (prepared == null || now >= prepared.expiresAt) {
                        failKey = "migration.expired"
                    } else if (xuid == null || xuid.value <= 0L || gamertag.isNullOrBlank()) {
                        failKey = "migration.failed"
                    } else {
                        entry.stage = Stage.COMMITTING
                        commitSnapshot = CommitSnapshot(
                            token = prepared.token,
                            expectedProfileUuid = prepared.profileUuid,
                            sessionId = entry.sessionId,
                            loginName = entry.loginName,
                            xuid = xuid,
                            gamertag = gamertag,
                        )
                    }
                }
                else -> swallow = true
            }
        }

        if (swallow) return true
        if (timedOut) {
            onTimeout(entry)
            return true
        }
        if (failKey != null) {
            abortEntry(entry, failKey!!)
            return true
        }
        if (startNormalVerification) {
            runCatching { limbo.closeRoleSelector() }
            if (stopped.get() || !entry.player.isActive) {
                safeFailed(entry.player, entry.limbo, "login.internal-error")
                return true
            }
            callbacks.startVerification(entry.player, entry.limbo)
            return true
        }
        if (waitingOldPrompt) {
            try {
                limbo.closeRoleSelector()
                limbo.sendMessage(text(if (services.authMeProofAvailable()) "migration.claim-usage" else "migration.waiting-old",
                    entry.locale, "name" to entry.loginName.value))
            } catch (_: Exception) {
                abortEntry(entry, "migration.failed")
            }
            return true
        }
        if (startClaimVerification) {
            if (stopped.get()) {
                abortEntry(entry, "login.internal-error")
                return true
            }
            try {
                limbo.closeRoleSelector()
                callbacks.startVerification(entry.player, entry.limbo)
            } catch (_: Exception) {
                abortEntry(entry, "migration.failed")
            }
            return true
        }
        val snapshot = commitSnapshot
        if (snapshot != null) {
            try {
                limbo.closeRoleSelector()
                limbo.sendMessage(text("migration.committing", entry.locale))
                services.submit { runCommit(entry, snapshot) }
            } catch (_: Exception) {
                abortEntry(entry, "migration.failed")
            }
            return true
        }
        return true
    }

    private data class CommitSnapshot(
        val token: ClaimToken,
        val expectedProfileUuid: UUID,
        val sessionId: UUID,
        val loginName: GameName,
        val xuid: Xuid,
        val gamertag: String,
    )

    private fun runCommit(entry: Entry, snapshot: CommitSnapshot) {
        val preCheckNow = clock()
        var timedOut = false
        val stillCommitting = synchronized(entry) {
            when {
                stopped.get() || !isOwnedLocked(entry) || entry.stage != Stage.COMMITTING -> false
                preCheckNow >= entry.deadlineMillis -> {
                    timedOut = true
                    false
                }
                else -> true
            }
        }
        if (timedOut) {
            onTimeout(entry)
            return
        }
        if (!stillCommitting) return

        val bound = try {
            services.commit(
                token = snapshot.token,
                session = snapshot.sessionId,
                name = snapshot.loginName,
                xuid = snapshot.xuid,
                gamertag = snapshot.gamertag,
            )
        } catch (_: Throwable) {
            abortEntry(entry, "migration.failed")
            return
        }

        var deliverClaimed = false
        var postTimeout = false
        var postInvalid = false
        val finishNow = clock()
        synchronized(entry) {
            val expectedUuid = entry.prepared?.profileUuid
            when {
                stopped.get() || !isOwnedLocked(entry) || entry.stage != Stage.COMMITTING -> Unit
                finishNow >= entry.deadlineMillis -> postTimeout = true
                expectedUuid == null ||
                    expectedUuid != snapshot.expectedProfileUuid ||
                    bound.uuid != expectedUuid ||
                    bound.kind != BoundIdentity.Kind.XUID ||
                    bound.subject != snapshot.xuid.value.toString() ||
                    bound.name.key != snapshot.loginName.key -> postInvalid = true
                else -> {
                    detachLocked(entry)
                    deliverClaimed = true
                }
            }
        }

        when {
            postTimeout -> onTimeout(entry)
            postInvalid -> abortEntry(entry, "migration.failed")
            deliverClaimed -> {
                if (stopped.get() || !handlesLive(entry)) {
                    safeFailed(entry.player, entry.limbo, "login.internal-error")
                    return
                }
                try {
                    callbacks.claimed(entry.player, entry.limbo, bound)
                } catch (_: Exception) {
                    safeFailed(entry.player, entry.limbo, "login.internal-error")
                }
            }
        }
    }

    /** Closes and removes any claim state associated with [session] without firing failure callbacks. */
    fun close(session: UUID) {
        val removed = synchronized(registryLock) { entries.remove(session) } ?: return
        closeEntryAndFail(removed, null)
    }

    /** Stops the claim manager and fails all currently active claim connections closed. */
    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        val active = synchronized(registryLock) {
            val snapshot = entries.values.toList()
            entries.clear()
            snapshot
        }
        for (entry in active) {
            closeEntryAndFail(entry, "login.internal-error")
        }
    }

    private fun onTimeout(entry: Entry) {
        var shouldFail = false
        synchronized(entry) {
            if (entries[entry.sessionId] === entry && entry.stage != Stage.CLOSED) {
                detachLocked(entry)
                shouldFail = true
            }
        }
        if (shouldFail) {
            runCatching { entry.limbo.closeRoleSelector() }
            safeFailed(entry.player, entry.limbo, "migration.expired")
        }
    }

    private fun rescheduleTimeout(entry: Entry, delayMillis: Long): Boolean {
        val nextFuture = try {
            services.schedule(delayMillis) { onTimeout(entry) }
        } catch (_: Exception) {
            abortEntry(entry, "migration.failed")
            return false
        }
        var orphan: ScheduledFuture<*>? = null
        var armed = false
        synchronized(entry) {
            if (entries[entry.sessionId] === entry && entry.stage != Stage.CLOSED) {
                orphan = entry.timeout
                entry.timeout = nextFuture
                armed = true
            } else {
                orphan = nextFuture
            }
        }
        orphan?.cancel(false)
        return armed
    }

    private fun abortEntry(entry: Entry, key: String) {
        closeEntryAndFail(entry, key)
    }

    private fun closeEntryAndFail(entry: Entry, failKey: String?) {
        var transitioned = false
        synchronized(entry) {
            if (entry.stage != Stage.CLOSED) {
                detachLocked(entry)
                transitioned = true
            } else {
                entries.remove(entry.sessionId, entry)
            }
        }
        if (transitioned) {
            runCatching { entry.limbo.closeRoleSelector() }
            if (failKey != null) {
                safeFailed(entry.player, entry.limbo, failKey)
            }
        }
    }

    /** Must hold `synchronized(entry)`. */
    private fun detachLocked(entry: Entry) {
        entry.stage = Stage.CLOSED
        entry.generation = null
        entry.authorizing = false
        entry.pendingPassword?.fill('\u0000')
        entry.pendingPassword = null
        entry.prepared = null
        entry.xuid = null
        entry.gamertag = null
        entry.timeout?.cancel(false)
        entry.timeout = null
        entries.remove(entry.sessionId, entry)
    }

    /** Must hold `synchronized(entry)`. */
    private fun isOwnedLocked(entry: Entry): Boolean =
        entries[entry.sessionId] === entry && entry.stage != Stage.CLOSED && handlesLive(entry)

    /** Must hold `synchronized(entry)`. */
    private fun isCurrentLocked(entry: Entry, now: Long): Boolean =
        !stopped.get() && isOwnedLocked(entry) && now < entry.deadlineMillis

    private fun handlesLive(entry: Entry): Boolean =
        entry.player.isActive &&
            entry.player.sessionId == entry.sessionId &&
            entry.limbo.connectionId == entry.sessionId

    private fun handlesMatch(player: Player, limbo: LimboPlayer, sessionId: UUID, loginName: GameName): Boolean =
        player.isActive &&
            limbo.connectionId == sessionId &&
            player.uniqueId == limbo.uuid &&
            player.username == limbo.name &&
            (GameName.parse(player.username)?.key == loginName.key ||
                player.uniqueId == moe.ryuu.kyokouauth.core.auth.AuthCoordinator.provisionalUuid(loginName) &&
                moe.ryuu.kyokouauth.limbo.VerificationProfileName.matches(player.uniqueId, player.username))

    /** Must hold `synchronized(registryLock)`. Collects entries already past their deadline or closed. */
    private fun purgeExpiredLocked(now: Long, collectedExpired: MutableList<Entry>) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next().value
            when {
                candidate.stage == Stage.CLOSED || !candidate.player.isActive -> {
                    iterator.remove()
                    candidate.timeout?.cancel(false)
                }
                now >= candidate.deadlineMillis -> {
                    iterator.remove()
                    collectedExpired.add(candidate)
                }
            }
        }
    }

    private fun safeFailed(player: Player, limbo: LimboPlayer, key: String) {
        runCatching { callbacks.failed(player, limbo, key) }
    }

    private fun text(key: String, locale: String?, vararg placeholders: Pair<String, String>): TextComponent =
        messages.parsed(key, locale, *placeholders).toLimbo()

    private fun saturatingAdd(base: Long, delta: Long): Long {
        val sum = base + delta
        return if (((base xor sum) and (delta xor sum)) < 0L) Long.MAX_VALUE else sum
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 10 * 60_000L
        const val MAX_TTL_MILLIS: Long = 15 * 60_000L
        const val DEFAULT_CAPACITY: Int = 4096
        const val MAX_CAPACITY: Int = 65536
    }
}
