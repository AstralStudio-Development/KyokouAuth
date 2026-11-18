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
class AuthService(config: ProxyConfig, private val logger: ProxyLog,
                  private val roleSkins: RoleSkinService = RoleSkinService()) : AutoCloseable {
    private val drivers = DriverLibrary(config.librariesFolder, config.driverRepository, config.autoDownloadDriver) { logger.info(it) }
    private val database: Database
    private val tasks: AuthTasks
    private val maintenance: ScheduledFuture<*>
    private val closed = AtomicBoolean(false)
    val identities: CachedIdentityRepository
    val sessions: SessionRepository
    val limiter: ConnectionLimiter
    val auth: AuthCoordinator
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
            sessions = SessionRepository(storage)
            limiter = ConnectionLimiter(config.connectionsPerMinute)
            auth = AuthCoordinator(identities, sessions, sessionMillis = config.sessionHours * MILLIS_PER_HOUR,
                sessionBindsAddress = config.sessionBindsAddress, autoRenameMicrosoft = config.autoRenameMicrosoft)
            val runner = AuthTasks(logger, config.ioWorkers, config.ioQueueCapacity)
            started = runner
            tasks = runner
            maintenance = runner.scheduleAtFixedRate(SESSION_SWEEP_MILLIS) {
                try { runner.execute { sessions.purgeExpired(System.currentTimeMillis()) } }
                catch (_: RejectedExecutionException) { logger.warn("Session maintenance skipped: authentication workers are busy or closed") }
            }
        } catch (failure: Throwable) {
            listOfNotNull<AutoCloseable>(started, roleSkins, opened, drivers).forEach { resource ->
                try { resource.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            }
            throw failure
        }
    }

    /** Call only after this connection has proved its XUID, and only on the bounded IO executor. */
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
        listOfNotNull<AutoCloseable>(tasks, roleSkins, database, drivers).forEach { resource ->
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