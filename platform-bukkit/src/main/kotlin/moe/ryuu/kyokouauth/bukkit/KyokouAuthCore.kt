package moe.ryuu.kyokouauth.bukkit

import moe.ryuu.kyokouauth.core.auth.AuthCoordinator
import moe.ryuu.kyokouauth.core.auth.MojangDirectory
import moe.ryuu.kyokouauth.core.storage.Database
import moe.ryuu.kyokouauth.core.storage.DriverLibrary
import moe.ryuu.kyokouauth.core.storage.IdentityRepository
import moe.ryuu.kyokouauth.core.storage.SessionRepository

/**
 * Everything the platform layer holds on to, built once at startup and torn down at shutdown.
 */
class KyokouAuthCore(
    private val database: Database,
    val identities: CachedIdentityRepository,
    val sessions: SessionRepository,
    val auth: AuthCoordinator,
) : AutoCloseable {

    override fun close() = database.close()

    companion object {
        fun create(
            configuration: Configuration,
            onMigrated: (Int) -> Unit = {},
            onNotice: (String) -> Unit = {},
        ): KyokouAuthCore {
            val drivers = DriverLibrary(
                librariesFolder = configuration.librariesFolder,
                repository = configuration.driverRepository,
                allowDownload = configuration.autoDownloadDriver,
                notice = onNotice,
            )

            onNotice("数据库驱动目录：${configuration.librariesFolder.absolutePath}")

            // A missing driver for a backend that is not in use must never block startup, so this
            // runs before the Database constructor and reports rather than throws.
            drivers.prefetch()

            val database = Database(
                descriptor = configuration.database,
                poolSize = configuration.poolSize,
                onMigrated = onMigrated,
                driverLoader = drivers::classLoader,
            )
            val identities = CachedIdentityRepository(IdentityRepository(database))
            val sessions = SessionRepository(database)
            val auth = AuthCoordinator(
                identities = identities,
                sessions = sessions,
                sessionMillis = configuration.sessionHours * 60L * 60L * 1000L,
            )
            return KyokouAuthCore(database, identities, sessions, auth)
        }
    }
}
