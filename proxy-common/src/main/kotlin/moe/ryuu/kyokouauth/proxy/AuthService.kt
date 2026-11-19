package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.ConnectionLimiter
import moe.ryuu.kyokouauth.core.auth.MojangDirectory
import moe.ryuu.kyokouauth.core.storage.CachedIdentityRepository
import moe.ryuu.kyokouauth.core.storage.Database
import moe.ryuu.kyokouauth.core.storage.DriverLibrary
import moe.ryuu.kyokouauth.core.storage.IdentityRepository
import moe.ryuu.kyokouauth.core.storage.SessionRepository
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Owns blocking authentication work, persistence and only the driver selected by configuration. */
class AuthService(private val config: ProxyConfig, private val logger: ProxyLog,
                  private val roleSkins: RoleSkinService = RoleSkinService(),
                  private val supportsProfileClaims: Boolean = false) : AutoCloseable {
    private val drivers = DriverLibrary(config.librariesFolder, config.driverRepository, config.autoDownloadDriver) { logger.info(it) }
    private val database: Database
    private val tasks: AuthTasks
    private val maintenance: ScheduledFuture<*>
    private val closed = AtomicBoolean(false)
    private var authMeSource: moe.ryuu.kyokouauth.core.authme.AuthMeLegacyVerifier? = null
    private var authMeMigration: AuthMeMigrationService? = null
    private var authMeInbox: AuthMeInboxMigration? = null
    val identities: CachedIdentityRepository
    val sessions: SessionRepository
    val limiter: ConnectionLimiter
    val auth: AuthCoordinator
    val profileClaims: moe.ryuu.kyokouauth.core.storage.ProfileClaimRepository
    private val roleLinks by lazy { moe.ryuu.kyokouauth.core.storage.AccountLinkRepository(database) }
    private val roleResolver by lazy { moe.ryuu.kyokouauth.core.auth.RoleResolver(identities) }
    private val roleLinkClient = RoleLinkClient { url ->
        moe.ryuu.kyokouauth.core.http.Http.get(url, readTimeoutMs = 5_000,
            route = moe.ryuu.kyokouauth.core.http.Http.Route.DIRECT)
    }
    private val javaProfiles = moe.ryuu.kyokouauth.core.auth.OfficialJavaProfileClient()

    init {
        var opened: Database? = null
        var started: AuthTasks? = null
        try {
            if (config.hasRetiredRoleSettings) logger.warn(
                "Retired role avatar/transfer endpoint settings are ignored; remove avatars and reconnect-host/port from role-selection")
            drivers.prepare(config.database.dialect)
            MojangDirectory.configureNetwork(config.directoryNetwork)
            MojangDirectory.configureBudget(config.directoryBudget, config.directoryBudgetWindowMinutes)
            val storage = Database(config.database, config.poolSize,
                onMigrated = { logger.info("Database schema at version $it") }, driverLoader = drivers::classLoader)
            opened = storage
            database = storage
            identities = CachedIdentityRepository(IdentityRepository(storage))
            profileClaims = moe.ryuu.kyokouauth.core.storage.ProfileClaimRepository(storage,
                onProfileChanged = identities::invalidate)
            check(!config.profileClaimsEnabled || supportsProfileClaims) { "Profile claims require the supported Velocity Java adapter" }
            if (supportsProfileClaims) authMeInbox = AuthMeInboxMigration(config.migrationFolder, profileClaims,
                drivers::classLoader, config::snapshotClaimsEnabled)
            config.authMeClaims?.let { claims ->
                drivers.prepare(claims.source.dialect)
                authMeSource = moe.ryuu.kyokouauth.core.authme.AuthMeLegacyVerifier(claims.source, drivers.classLoader(claims.source.dialect))
                if (claims.automaticDiscovery) authMeMigration = AuthMeMigrationService(claims, checkNotNull(authMeSource), profileClaims)
            }
            sessions = SessionRepository(storage)
            limiter = ConnectionLimiter(config.connectionsPerMinute)
            auth = AuthCoordinator(identities, sessions, sessionMillis = config.sessionHours * MILLIS_PER_HOUR,
                sessionBindsAddress = config.sessionBindsAddress, autoRenameMicrosoft = config.autoRenameMicrosoft,
                microsoftProfile = { xuid ->
                    val original = moe.ryuu.kyokouauth.core.identity.Identity.ofXuid(xuid)
                    val mapped = profileClaims.resolveMicrosoftProfile(xuid)
                    val stored = identities.bySubject(moe.ryuu.kyokouauth.core.storage.BoundIdentity.Kind.XUID, xuid.value.toString())
                    check(stored == null || stored.uuid == (mapped ?: original)) { "Microsoft profile ownership is inconsistent" }
                    val profile = mapped ?: original
                    check(supportsProfileClaims || profile == original) {
                        "Mapped Microsoft profiles are unsupported on this proxy"
                    }
                    profile
                })
            val runner = AuthTasks(logger, config.ioWorkers, config.ioQueueCapacity)
            started = runner
            tasks = runner
            maintenance = runner.scheduleAtFixedRate(SESSION_SWEEP_MILLIS) {
                try { runner.execute { sessions.purgeExpired(System.currentTimeMillis()) } }
                catch (_: RejectedExecutionException) { logger.warn("Session maintenance skipped: authentication workers are busy or closed") }
            }
        } catch (failure: Throwable) {
            listOfNotNull<AutoCloseable>(authMeInbox, started, authMeSource, roleSkins, opened, drivers).forEach { resource ->
                try { resource.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            }
            throw failure
        }
    }

    fun permitsMappedProfile(record: moe.ryuu.kyokouauth.core.storage.BoundIdentity): Boolean {
        check(!closed.get()) { "Authentication is unavailable" }
        if (record.kind == moe.ryuu.kyokouauth.core.storage.BoundIdentity.Kind.MOJANG) {
            return record.uuid == java.util.UUID.fromString(record.subject)
        }
        val xuid = moe.ryuu.kyokouauth.core.identity.Xuid(record.subject.toLong())
        val original = moe.ryuu.kyokouauth.core.identity.Identity.ofXuid(xuid)
        val expected = profileClaims.resolveMicrosoftProfile(xuid) ?: original
        return record.uuid == expected && (supportsProfileClaims || expected == original)
    }

    fun preflightLegacyProfile(batch: String, name: String, profileUuid: java.util.UUID) {
        check(config.profileClaimsEnabled && supportsProfileClaims && !closed.get()) { "Profile claims are unavailable" }
        profileClaims.preflightLegacy(batch, name, profileUuid)
    }

    fun importLegacyProfile(batch: String, name: String, profileUuid: java.util.UUID): moe.ryuu.kyokouauth.core.storage.LegacyProfile {
        check(config.profileClaimsEnabled && supportsProfileClaims && !closed.get()) { "Profile claims are unavailable" }
        return profileClaims.importLegacy(batch, name, profileUuid)
    }

    /** Import work has its own single-worker executor and never occupies the authentication queue. */
    fun scanInboxLegacyImport(): CompletableFuture<AuthMeMigrationPreview> {
        if (!supportsProfileClaims || closed.get()) return CompletableFuture.failedFuture(IllegalStateException("Profile claims are unavailable"))
        return checkNotNull(authMeInbox).scan()
    }

    fun inboxLegacyImportStatus(): AuthMeImportStatus? = authMeInbox?.status()
    fun cancelInboxLegacyImport(): Boolean = authMeInbox?.cancel() ?: false
    fun disableInboxLegacyImport(): CompletableFuture<Unit> {
        if (!supportsProfileClaims || closed.get()) return CompletableFuture.failedFuture(IllegalStateException("Profile claims are unavailable"))
        return checkNotNull(authMeInbox).disable()
    }

    fun previewAutomaticLegacyImport(): AuthMeMigrationPreview {
        check(config.profileClaimsEnabled && supportsProfileClaims && !closed.get()) { "Profile claims are unavailable" }
        return checkNotNull(authMeMigration) { "Configure an old AuthMe server-folder first" }.preview()
    }

    fun automaticLegacyImportReport(): AuthMeMigrationPreview? {
        check(supportsProfileClaims && !closed.get()) { "Profile claims are unavailable" }
        return authMeInbox?.report() ?: authMeMigration?.report()
    }

    fun confirmInboxLegacyImport(token: String): CompletableFuture<AuthMeAutoMigrationReport> {
        if (!supportsProfileClaims || closed.get()) return CompletableFuture.failedFuture(IllegalStateException("Profile claims are unavailable"))
        return checkNotNull(authMeInbox).confirm(token)
    }

    fun importAutomaticLegacyProfiles(token: String): AuthMeAutoMigrationReport {
        check(config.profileClaimsEnabled && supportsProfileClaims && !closed.get()) { "Profile claims are unavailable" }
        return checkNotNull(authMeMigration) { "Configure an old AuthMe server-folder first" }.confirm(token)
    }

    fun approveLegacyProfile(recordId: java.util.UUID, session: java.util.UUID, name: moe.ryuu.kyokouauth.core.identity.GameName): ProfileClaimFlow.PreparedClaim {
        check(config.profileClaimsEnabled && supportsProfileClaims && !closed.get()) { "Profile claims are unavailable" }
        val record = checkNotNull(profileClaims.byId(recordId)) { "Legacy profile not found" }
        val grant = profileClaims.issue(recordId, session, name)
        return ProfileClaimFlow.PreparedClaim(grant.token, record.id,
            moe.ryuu.kyokouauth.core.identity.GameName.parseOrThrow(record.legacyName), record.profileUuid, grant.expiresAt)
    }

    fun authMeClaimsAvailable(): Boolean {
        if (closed.get() || !supportsProfileClaims || !config.profileClaimsEnabled) return false
        val inbox = authMeInbox
        return if (inbox?.hasLocalState() == true) inbox.available() else authMeSource != null
    }

    /** Must run on bounded auth IO, and only for a live private-ingress request. Owns password. */
    fun proveAuthMeProfile(legacyName: moe.ryuu.kyokouauth.core.identity.GameName, password: CharArray,
                          session: java.util.UUID, name: moe.ryuu.kyokouauth.core.identity.GameName,
                          current: () -> Boolean): ProfileClaimFlow.PreparedClaim? {
        try {
            if (!authMeClaimsAvailable() || !current()) return null
            val inbox = authMeInbox?.takeIf { it.hasLocalState() }
            val batch = if (inbox != null) "authme-inbox" else checkNotNull(config.authMeClaims).batch
            val source = profileClaims.listByLegacyName(legacyName.value).singleOrNull()?.takeIf {
                it.batch == batch && it.source == "authme" && it.status == moe.ryuu.kyokouauth.core.storage.LegacyProfile.Status.AVAILABLE
            } ?: return null
            val issue = {
                profileClaims.issue(source.id, session, name,
                    proofMethod = moe.ryuu.kyokouauth.core.storage.LegacyProofMethod.AUTHME_PASSWORD)
            }
            val grant = if (inbox != null) {
                inbox.verifyAndUse(legacyName, source.profileUuid, password,
                    current = { current() && !closed.get() }, verified = issue) ?: return null
            } else {
                when (checkNotNull(authMeSource).verify(legacyName, password)) {
                    moe.ryuu.kyokouauth.core.authme.AuthMeVerification.VERIFIED -> Unit
                    moe.ryuu.kyokouauth.core.authme.AuthMeVerification.UNAVAILABLE -> error("Legacy source unavailable")
                    else -> return null
                }
                if (!current() || closed.get()) return null
                issue()
            }
            return ProfileClaimFlow.PreparedClaim(grant.token, source.id,
                moe.ryuu.kyokouauth.core.identity.GameName.parseOrThrow(source.legacyName), source.profileUuid, grant.expiresAt)
        } catch (_: Exception) {
            throw IllegalStateException("Legacy source unavailable")
        } finally { password.fill('\u0000') }
    }

    fun commitLegacyProfile(token: ProfileClaimFlow.ClaimToken, session: java.util.UUID, name: moe.ryuu.kyokouauth.core.identity.GameName,
                            xuid: moe.ryuu.kyokouauth.core.identity.Xuid, gamertag: String,
                            current: () -> Boolean): moe.ryuu.kyokouauth.core.storage.BoundIdentity {
        check(config.profileClaimsEnabled && supportsProfileClaims && !closed.get()) { "Profile claims are unavailable" }
        check(MojangDirectory.lookup(name.key) is MojangDirectory.Lookup.Free) { "Claim target name is unavailable" }
        check(current()) { "Claim connection closed during name validation" }
        return profileClaims.commit(token.value, session, name, xuid, gamertag, current = current)
    }

    /** Call only after this connection has proved its Xuid, and only on the bounded IO executor. */
    fun roleOptions(xuid: moe.ryuu.kyokouauth.core.identity.Xuid, gamertag: String,
                    fallbackBedrockName: String?): RoleOptions {
        check(!closed.get()) { "Authentication is unavailable" }
        val observedAt = System.currentTimeMillis()
        val rawLink = roleLinkClient.lookup(xuid)
        if (rawLink is moe.ryuu.kyokouauth.core.auth.AccountLinkResult.Unavailable) {
            throw IllegalStateException("Current account association is unavailable")
        }
        val official = (rawLink as? moe.ryuu.kyokouauth.core.auth.AccountLinkResult.Linked)?.let {
            // Only public name/skin metadata may lag by 60s; this connection's proof and link stay fresh.
            javaProfiles.lookupRecent(it.profile.uuid)
        }
        val link = if (official == null) rawLink else moe.ryuu.kyokouauth.core.auth.AccountLinkResult.Linked(
            moe.ryuu.kyokouauth.core.auth.LinkedJavaProfile(official.uuid, official.name))
        val resolution = roleResolver.resolve(xuid, gamertag, link, fallbackBedrockName)
        val candidates = (resolution as? moe.ryuu.kyokouauth.core.auth.RoleResolution.Ready)?.roles
            ?: throw IllegalStateException("Current role records are unavailable or conflicting")
        val options = candidates.map { candidate ->
            val identity = candidate.identity
            if (identity is moe.ryuu.kyokouauth.core.identity.Identity.Microsoft) {
                // An old XUID reservation must not capture a name later owned by Mojang.
                if (moe.ryuu.kyokouauth.core.identity.GameName.parse(identity.name.value) != null &&
                    MojangDirectory.lookup(identity.name.key) !is MojangDirectory.Lookup.Free) {
                    throw IllegalStateException("Microsoft role name is unavailable; rename or retry is required")
                }
                RoleOption(identity, candidate.existing, roleSkins.microsoft(identity.uuid))
            } else {
                check(identity is moe.ryuu.kyokouauth.core.identity.Identity.Premium && official != null && official.uuid == identity.uuid)
                val latest = identity.copy(name = official.name)
                val texture = official.texture?.let { GameProfile.Property("textures", it.value, it.signature) }
                RoleOption(latest, candidate.existing, texture)
            }
        }
        if (official == null) roleLinks.revoke(xuid, observedAt)
        else roleLinks.recordVerified(moe.ryuu.kyokouauth.core.storage.VerifiedAccountLink(
            xuid, official.uuid, "global-api", "geysermc-v2-global", observedAt))
        return RoleOptions(options, official?.uuid)
    }

    /** Every failure remains exceptional; null may only be the caller's genuine successful result. */
    fun <T> submit(task: () -> T): CompletableFuture<T> {
        val completion = tasks.submit(task)
        completion.whenComplete { _, failure ->
            if (failure != null && failure !is RejectedExecutionException && failure !is java.util.concurrent.CancellationException) reportFailure(failure)
        }
        return completion
    }

    internal fun reportFailure(failure: Throwable) { logger.error("Background authentication operation failed", failure) }

    /** Event admission sees immediate overload/closing rejection and can complete its intent. */
    fun execute(task: () -> Unit) = tasks.execute(task)
    fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*> = tasks.schedule(delayMillis, task)
    fun scheduleAtFixedRate(periodMillis: Long, task: () -> Unit): ScheduledFuture<*> = tasks.scheduleAtFixedRate(periodMillis, task)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        maintenance.cancel(false)
        var failure: Throwable? = null
        listOfNotNull<AutoCloseable>(authMeInbox, tasks, authMeSource, roleSkins, database, drivers).forEach { resource ->
            try { resource.close() } catch (closing: Throwable) {
                if (failure == null) failure = closing else failure.addSuppressed(closing)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        private const val MILLIS_PER_HOUR = 3_600_000L
        private const val SESSION_SWEEP_MILLIS = 15 * 60_000L
    }
}