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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The storage and network side of a login, on threads that are allowed to block.
 *
 * A login decision walks the database and asks Mojang, and the Microsoft sign-in polls an HTTP
 * endpoint for minutes. None of that may happen on a proxy thread: the limbo's writes are dispatched
 * onto the event loop by the limbo itself, so the only rule here is that nothing blocking ever runs
 * inline in an event handler.
 *
 * Two pools, not one: an HTTP poll that takes seconds must not delay the database query behind a
 * player waiting to join, and the scheduled pool exists so a poll and its timeout are ordered.
 */
class AuthService(config: ProxyConfig, private val logger: ProxyLog) : AutoCloseable {

    private val database: Database
    val identities: CachedIdentityRepository
    val sessions: SessionRepository
    val limiter: ConnectionLimiter
    val auth: AuthCoordinator

    private val io: ExecutorService = Executors.newFixedThreadPool(4, threads("KyokouAuth-IO"))
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(threads("KyokouAuth-Scheduler"))

    init {
        val drivers = DriverLibrary(
            librariesFolder = config.librariesFolder,
            repository = config.driverRepository,
            allowDownload = config.autoDownloadDriver,
            notice = { logger.info(it) },
        )
        drivers.prefetch()

        MojangDirectory.configureNetwork(config.directoryNetwork)
        MojangDirectory.configureBudget(config.directoryBudget, config.directoryBudgetWindowMinutes)

        database = Database(
            descriptor = config.database,
            poolSize = config.poolSize,
            onMigrated = { version -> logger.info("Database schema at version $version") },
            driverLoader = drivers::classLoader,
        )
        identities = CachedIdentityRepository(IdentityRepository(database))
        sessions = SessionRepository(database)
        limiter = ConnectionLimiter(config.connectionsPerMinute)
        auth = AuthCoordinator(
            identities = identities,
            sessions = sessions,
            sessionMillis = config.sessionHours * 60L * 60L * 1000L,
            sessionBindsAddress = config.sessionBindsAddress,
            autoRenameMicrosoft = config.autoRenameMicrosoft,
        )
    }

    /** Runs a blocking task off the proxy threads. Failures are logged, never rethrown into Netty. */
    fun <T> submit(task: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({
            try {
                task()
            } catch (failure: Exception) {
                logger.error("Login task failed", failure)
                null
            }
        }, io)

    fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*> =
        scheduler.schedule({ runCatching(task).onFailure { logger.error("Scheduled login task failed", it) } },
            delayMillis, TimeUnit.MILLISECONDS)

    fun scheduleAtFixedRate(periodMillis: Long, task: () -> Unit): ScheduledFuture<*> =
        scheduler.scheduleAtFixedRate(
            { runCatching(task).onFailure { logger.error("Repeating login task failed", it) } },
            periodMillis, periodMillis, TimeUnit.MILLISECONDS,
        )

    override fun close() {
        io.shutdownNow()
        scheduler.shutdownNow()
        database.close()
    }

    private fun threads(prefix: String): ThreadFactory {
        val counter = AtomicInteger()
        return ThreadFactory { task ->
            Thread(task, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}