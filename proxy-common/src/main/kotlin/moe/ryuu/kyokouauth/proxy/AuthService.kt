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
class AuthService(config: ProxyConfig, private val logger: ProxyLog) : AutoCloseable {
    private val drivers = DriverLibrary(config.librariesFolder, config.driverRepository, config.autoDownloadDriver) { logger.info(it) }
    private val database: Database
    private val tasks: AuthTasks
    private val maintenance: ScheduledFuture<*>
    private val closed = AtomicBoolean(false)
    val identities: CachedIdentityRepository
    val sessions: SessionRepository
    val limiter: ConnectionLimiter
    val auth: AuthCoordinator

    init {
        var opened: Database? = null
        var started: AuthTasks? = null
        try {
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
            listOfNotNull<AutoCloseable>(started, opened, drivers).forEach { resource ->
                try { resource.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            }
            throw failure
        }
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
        listOf<AutoCloseable>(tasks, database, drivers).forEach { resource ->
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