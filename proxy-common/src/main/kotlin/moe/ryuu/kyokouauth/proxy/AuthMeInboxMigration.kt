package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.authme.AuthMeConfigReader
import moe.ryuu.kyokouauth.core.authme.AuthMeLegacyVerifier
import moe.ryuu.kyokouauth.core.authme.AuthMeSourceConfig
import moe.ryuu.kyokouauth.core.authme.AuthMeVerification
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.storage.Dialect
import moe.ryuu.kyokouauth.core.storage.LegacyBatchStatus
import moe.ryuu.kyokouauth.core.storage.ProfileClaimRepository
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** A single bounded background importer. Authentication never reads the inbox or contacts its database. */
class AuthMeInboxMigration(
    private val folder: Path,
    private val claims: ProfileClaimRepository,
    private val driverLoader: (Dialect) -> ClassLoader?,
    private val onEnabled: (Boolean) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val lock = Any()
    // Long claim-store IO must not hold the control/status lock. Activation still waits for
    // in-flight grant callbacks before switching generations; never acquire these in reverse order.
    private val proofLifecycle = ReentrantReadWriteLock(true)
    private val closed = AtomicBoolean()
    private val cleanup = ConcurrentLinkedQueue<Path>()
    // job admits only one import/control operation; pending owns at most one expiry timer.
    // Remove cancelled timers immediately so repeated scans cannot retain old snapshots for five minutes.
    private val executor = ScheduledThreadPoolExecutor(1, { task ->
        Thread(task, "kyokouauth-migration").apply { isDaemon = true }
    }).apply {
        removeOnCancelPolicy = true
        // close cancels the sole expiry timer explicitly; admitted immediate jobs must still run
        // their completion/cleanup wrappers instead of being silently dropped during shutdown.
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }
    private var job: Job? = null
    private var pending: Staged? = null
    private var pendingExpiry: ScheduledFuture<*>? = null
    @Volatile private var active: LocalSnapshot? = null
    @Volatile private var selectedSnapshotId: String? = null
    @Volatile private var enabled = false
    @Volatile private var localState = false
    @Volatile private var phase = AuthMeImportPhase.IDLE
    @Volatile private var processed = 0
    @Volatile private var lastFailure: AuthMeImportFailure? = null

    init {
        try {
            secureDirectory(folder)
            secureDirectory(folder.resolve("inbox"))
            secureDirectory(folder.resolve("inbox/AuthMe"))
            secureDirectory(folder.resolve("staging"))
            secureDirectory(folder.resolve("snapshots"))
            val pointer = folder.resolve("active.properties")
            if (Files.exists(pointer, NOFOLLOW_LINKS)) {
                val properties = readProperties(pointer, 4096)
                val id = snapshotId(properties.getProperty("snapshot"))
                val isEnabled = when (properties.getProperty("enabled")) {
                    "true" -> true
                    "false" -> false
                    else -> error("Invalid snapshot state")
                }
                active = if (isEnabled) loadSnapshot(folder.resolve("snapshots").resolve(id)) else null
                selectedSnapshotId = id
                enabled = isEnabled
                localState = true
                phase = if (isEnabled) AuthMeImportPhase.ACTIVE else AuthMeImportPhase.DISABLED
                onEnabled(isEnabled)
            }
        } catch (_: Exception) {
            active?.verifier?.close()
            executor.shutdownNow()
            throw IllegalStateException("Local AuthMe snapshot is missing, corrupt or inaccessible")
        }
    }

    fun hasLocalState(): Boolean = localState
    fun available(): Boolean = !closed.get() && enabled && active != null
    fun status(): AuthMeImportStatus = synchronized(lock) {
        expirePendingLocked()
        AuthMeImportStatus(phase, processed, available(), lastFailure)
    }
    fun report(): AuthMeMigrationPreview? = synchronized(lock) {
        expirePendingLocked()
        pending?.preview?.takeIf { !closed.get() }
    }

    fun scan(): CompletableFuture<AuthMeMigrationPreview> = launch(AuthMeImportPhase.SCANNING_SOURCE) { running ->
        discardPending()
        drainCleanup()
        val copiedConfig = folder.resolve("inbox/AuthMe/config.yml")
        running.failure = AuthMeImportFailure.MISSING_INBOX_CONFIG
        check(Files.isRegularFile(copiedConfig, NOFOLLOW_LINKS))
        running.failure = AuthMeImportFailure.INVALID_SOURCE_CONFIG
        val source = AuthMeConfigReader.read(copiedConfig)
        running.failure = AuthMeImportFailure.SOURCE_UNAVAILABLE
        val directory = folder.resolve("staging").resolve(UUID.randomUUID().toString())
        secureDirectory(directory)
        var localVerifier: AuthMeLegacyVerifier? = null
        try {
            checkCurrent(running)
            val sourceLoader = driverLoader(source.dialect)
            val sqliteLoader = driverLoader(Dialect.SQLITE)
            checkCurrent(running)
            val databaseFile = directory.resolve("authme.db")
            AuthMeLegacyVerifier(source, sourceLoader).use { old ->
                old.exportSnapshot(databaseFile, sqliteLoader, current = { current(running) }, progress = { processed = it })
            }
            // From this point on the old database is closed and no credentials are retained by the job.
            checkCurrent(running)
            phase = AuthMeImportPhase.MATCHING_PROFILES
            running.failure = AuthMeImportFailure.PREFLIGHT_FAILED
            val canonical = AuthMeSourceConfig(Dialect.SQLITE, databaseFile.toAbsolutePath().toString(), charsetName = source.charsetName)
            val verifier = AuthMeLegacyVerifier(canonical, sqliteLoader)
            localVerifier = verifier
            val settings = AuthMeClaimSettings(canonical, BATCH, databaseFile, folder.resolve("inbox"))
            val planner = AuthMeMigrationService(settings, verifier, claims, nowMillis)
            val preview = planner.preview { current(running) }
            checkCurrent(running)
            val profiles = Properties()
            preview.report.entries.filter { it.status == LegacyBatchStatus.READY || it.status == LegacyBatchStatus.ALREADY_REGISTERED }
                .forEach { profiles.setProperty(GameName.parseOrThrow(it.candidate.name).key, it.candidate.profileUuid.toString()) }
            writeProperties(directory.resolve("profiles.properties"), profiles)
            val manifest = Properties().apply {
                setProperty("format", "1")
                setProperty("charset", source.charsetName)
                setProperty("database-sha256", digest(databaseFile))
                setProperty("profiles-sha256", digest(directory.resolve("profiles.properties")))
            }
            writeProperties(directory.resolve("manifest.properties"), manifest)
            synchronized(lock) {
                checkCurrent(running)
                val staged = Staged(directory, verifier, planner, preview)
                scheduleExpiryLocked(staged)
                pending = staged
                phase = AuthMeImportPhase.READY
            }
            localVerifier = null // pending now owns it
            preview
        } catch (failure: Throwable) {
            localVerifier?.close()
            cleanup.add(directory)
            throw failure
        }
    }

    fun confirm(token: String): CompletableFuture<AuthMeAutoMigrationReport> = launch(AuthMeImportPhase.ACTIVATING) { running ->
        running.failure = AuthMeImportFailure.INVALID_CONFIRMATION
        val staged = synchronized(lock) {
            expirePendingLocked()
            val value = checkNotNull(pending) { "Run a migration scan first" }
            check(value.preview.token == token && nowMillis() < value.preview.expiresAt) { "Invalid or expired confirmation" }
            takePendingLocked()
            value
        }
        var loaded: LocalSnapshot? = null
        var promoted: Path? = null
        var published = false
        try {
            running.failure = AuthMeImportFailure.SNAPSHOT_INVALID
            validateManifest(staged.directory)
            checkCurrent(running)
            // Confirmation rechecks only the frozen local snapshot and historical metadata, never the old DB.
            // Short target transactions release its shared pool/SQLite lock between chunks.
            // A failed activation may leave inert candidates; the fixed batch makes a rescan idempotent,
            // and only the active snapshot's own name/UUID allow-list can ever issue a claim grant.
            running.failure = AuthMeImportFailure.TARGET_IMPORT_FAILED
            val report = staged.planner.confirm(token, transactionSize = 256,
                stillCurrent = { current(running) }, progress = { processed = it })
            checkCurrent(running)
            staged.verifier.close()
            running.failure = AuthMeImportFailure.ACTIVATION_FAILED
            val finalDirectory = folder.resolve("snapshots").resolve(staged.directory.fileName)
            Files.move(staged.directory, finalDirectory, ATOMIC_MOVE)
            promoted = finalDirectory
            loaded = loadSnapshot(finalDirectory)
            proofLifecycle.write {
                synchronized(lock) {
                    checkCurrent(running)
                    writePointer(finalDirectory.fileName.toString(), true)
                    val previous = active
                    active = loaded
                    selectedSnapshotId = finalDirectory.fileName.toString()
                    enabled = true
                    localState = true
                    onEnabled(true)
                    published = true
                    phase = AuthMeImportPhase.ACTIVE
                    previous?.let { it.verifier.close(); cleanup.add(it.directory) }
                }
            }
            report
        } finally {
            staged.verifier.close()
            if (!published) {
                loaded?.verifier?.close()
                promoted?.let(cleanup::add)
            }
            cleanup.add(staged.directory)
        }
    }

    /** Cancels a scan or unconfirmed preview; activation is deliberately not interruptible by this command. */
    fun cancel(): Boolean = synchronized(lock) {
        expirePendingLocked()
        if (closed.get()) return@synchronized false
        val running = job
        if (running != null && phase !in setOf(AuthMeImportPhase.SCANNING_SOURCE, AuthMeImportPhase.MATCHING_PROFILES, AuthMeImportPhase.READY))
            return@synchronized false
        if (running == null && pending == null) return@synchronized false
        running?.cancelled?.set(true)
        // A scan may already have published READY but not completed its future. Revoke that
        // preview as well as the running job, so a successful cancel never leaves a usable token.
        takePendingLocked()?.let(::retire)
        phase = AuthMeImportPhase.CANCELLED
        lastFailure = null
        requestCleanupLocked()
        true
    }

    fun disable(): CompletableFuture<Unit> = launch(AuthMeImportPhase.DISABLING) { running ->
        running.failure = AuthMeImportFailure.NO_ACTIVE_SNAPSHOT
        val id = checkNotNull(selectedSnapshotId) { "No local snapshot has been activated" }
        running.failure = AuthMeImportFailure.ACTIVATION_FAILED
        proofLifecycle.write {
            synchronized(lock) {
                writePointer(id, false)
                enabled = false
                onEnabled(false)
                phase = AuthMeImportPhase.DISABLED
            }
        }
    }

    fun verify(name: GameName, profileUuid: UUID, password: CharArray, current: () -> Boolean): AuthMeVerification {
        try {
            val snapshot = active ?: return AuthMeVerification.UNAVAILABLE
            if (!available() || !current()) return AuthMeVerification.UNAVAILABLE
            if (snapshot.profiles[name.key] != profileUuid) return AuthMeVerification.REJECTED
            val result = snapshot.verifier.verify(name, password)
            return if (available() && active === snapshot && current()) result else AuthMeVerification.UNAVAILABLE
        } finally { password.fill('\u0000') }
    }

    /** The grant callback and generation check are atomic with snapshot activation/disable. */
    fun <T> verifyAndUse(name: GameName, profileUuid: UUID, password: CharArray,
                         current: () -> Boolean, verified: () -> T): T? {
        try {
            val snapshot = active ?: return null
            if (!available() || !current() || snapshot.profiles[name.key] != profileUuid) return null
            if (snapshot.verifier.verify(name, password) != AuthMeVerification.VERIFIED) return null
            return proofLifecycle.read {
                if (available() && active === snapshot && current()) verified() else null
            }
        } finally { password.fill('\u0000') }
    }

    private fun <T> launch(start: AuthMeImportPhase, action: (Job) -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val running = Job()
        synchronized(lock) {
            if (closed.get() || job != null) {
                future.completeExceptionally(RejectedExecutionException("Migration importer is busy or closed"))
                return future
            }
            job = running
            phase = start
            processed = 0
            lastFailure = null
            try {
                executor.execute {
                    var outcome = runCatching { checkCurrent(running); action(running) }
                    drainCleanup()
                    synchronized(lock) {
                        if (start == AuthMeImportPhase.SCANNING_SOURCE && !current(running))
                            outcome = Result.failure(CancellationException("Migration cancelled"))
                        if (job === running) job = null
                        if (outcome.isFailure) {
                            val cancelled = running.cancelled.get() || closed.get()
                            lastFailure = if (cancelled) null else running.failure
                            phase = if (cancelled) AuthMeImportPhase.CANCELLED else AuthMeImportPhase.FAILED
                        }
                    }
                    outcome.fold(future::complete) {
                        // Only a closed set of stage codes crosses this boundary, never raw YAML,
                        // JDBC, filesystem messages or their causes, which can contain credentials.
                        future.completeExceptionally(if (running.cancelled.get() || closed.get()) CancellationException("Migration cancelled")
                        else AuthMeImportException(running.failure))
                    }
                }
            } catch (_: RejectedExecutionException) {
                job = null
                phase = AuthMeImportPhase.FAILED
                future.completeExceptionally(RejectedExecutionException("Migration importer is busy or closed"))
            }
        }
        return future
    }

    private fun takePendingLocked(): Staged? {
        pendingExpiry?.cancel(false)
        pendingExpiry = null
        return pending.also { pending = null }
    }
    private fun retire(staged: Staged) {
        staged.verifier.close()
        cleanup.add(staged.directory)
    }
    private fun requestCleanupLocked() {
        if (cleanup.isEmpty()) return
        try { executor.execute(::drainCleanup) } catch (_: RejectedExecutionException) { }
    }
    private fun scheduleExpiryLocked(staged: Staged) {
        pendingExpiry?.cancel(false)
        val remaining = (staged.preview.expiresAt - nowMillis()).coerceAtLeast(1)
        pendingExpiry = executor.schedule({
            synchronized(lock) {
                if (!closed.get() && pending === staged) {
                    if (!expirePendingLocked()) scheduleExpiryLocked(staged) // wall clock moved back
                }
            }
        }, remaining, TimeUnit.MILLISECONDS)
    }
    private fun expirePendingLocked(): Boolean {
        val staged = pending ?: return false
        if (closed.get() || nowMillis() < staged.preview.expiresAt) return false
        takePendingLocked()?.let(::retire)
        if (job == null) {
            phase = AuthMeImportPhase.EXPIRED
            lastFailure = null
        }
        requestCleanupLocked()
        return true
    }
    private fun discardPending() {
        val old = synchronized(lock) { takePendingLocked() }
        old?.let(::retire)
    }
    private fun current(job: Job) = !closed.get() && !job.cancelled.get() && !Thread.currentThread().isInterrupted
    private fun checkCurrent(job: Job) = check(current(job)) { "Migration cancelled" }

    private fun loadSnapshot(directory: Path): LocalSnapshot {
        val manifest = validateManifest(directory)
        val records = readProperties(directory.resolve("profiles.properties"), MAX_PROFILE_BYTES)
        require(records.size <= 100_000)
        val profiles = records.entries.associate { (key, value) ->
            val name = GameName.parseOrThrow(key.toString())
            val uuid = UUID.fromString(value.toString())
            require(name.key == key && uuid.version() == 3 && uuid.variant() == 2)
            name.key to uuid
        }
        val source = AuthMeSourceConfig(Dialect.SQLITE, directory.resolve("authme.db").toAbsolutePath().toString(),
            charsetName = manifest.getProperty("charset"))
        val verifier = AuthMeLegacyVerifier(source, driverLoader(Dialect.SQLITE))
        try { verifier.inspectAccounts() } catch (failure: Throwable) { verifier.close(); throw failure }
        return LocalSnapshot(directory, profiles, verifier)
    }
    private fun validateManifest(directory: Path): Properties {
        require(Files.isDirectory(directory, NOFOLLOW_LINKS))
        val manifest = readProperties(directory.resolve("manifest.properties"), 4096)
        require(manifest.getProperty("format") == "1")
        require(manifest.getProperty("database-sha256") == digest(directory.resolve("authme.db")))
        require(manifest.getProperty("profiles-sha256") == digest(directory.resolve("profiles.properties")))
        return manifest
    }
    private fun writePointer(id: String, isEnabled: Boolean) {
        snapshotId(id)
        val temporary = folder.resolve("active-${UUID.randomUUID()}.tmp")
        try {
            writeProperties(temporary, Properties().apply { setProperty("snapshot", id); setProperty("enabled", isEnabled.toString()) })
            Files.move(temporary, folder.resolve("active.properties"), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun snapshotId(raw: String?): String {
        require(raw != null && UUID.fromString(raw).toString() == raw)
        return raw
    }
    private fun secureDirectory(directory: Path) {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) {
            Files.createDirectories(directory)
            if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        }
        require(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
    }
    private fun writeProperties(path: Path, properties: Properties) {
        val attributes = if (Files.getFileStore(path.parent).supportsFileAttributeView("posix"))
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
        Files.createFile(path, *attributes)
        Files.newOutputStream(path).use { properties.store(it, "KyokouAuth local migration metadata") }
    }
    private fun readProperties(path: Path, maximum: Int): Properties {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= maximum)
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(maximum + 1) }
        require(bytes.size <= maximum)
        return Properties().apply { bytes.inputStream().use(::load) }
    }
    private fun digest(path: Path): String {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= 64L * 1024 * 1024)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(8192)
            var count = input.read(buffer)
            while (count >= 0) { digest.update(buffer, 0, count); count = input.read(buffer) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun drainCleanup() {
        repeat(cleanup.size) {
            val directory = cleanup.poll() ?: return
            val owned = directory.parent == folder.resolve("staging") || directory.parent == folder.resolve("snapshots")
            if (!owned || directory.fileName.toString() == selectedSnapshotId || !Files.isDirectory(directory, NOFOLLOW_LINKS)) return@repeat
            try {
                for (name in listOf("authme.db", "authme.db-journal", "authme.db-wal", "authme.db-shm", "profiles.properties", "manifest.properties"))
                    Files.deleteIfExists(directory.resolve(name))
                Files.deleteIfExists(directory)
            } catch (_: Exception) {
                // A Windows reader may still be closing an old generation. Retry on the next job,
                // never recurse, widen the path scope, or busy-loop over a locked file.
                cleanup.add(directory)
            }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val abandoned = synchronized(lock) {
            job?.cancelled?.set(true)
            takePendingLocked()
        }
        abandoned?.let(::retire)
        executor.shutdown()
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow()
        discardPending()
        drainCleanup()
        active?.verifier?.close()
    }

    private class Job(val cancelled: AtomicBoolean = AtomicBoolean(),
                      var failure: AuthMeImportFailure = AuthMeImportFailure.OPERATION_FAILED)
    private class Staged(val directory: Path, val verifier: AuthMeLegacyVerifier,
                         val planner: AuthMeMigrationService, val preview: AuthMeMigrationPreview)
    private class LocalSnapshot(val directory: Path, val profiles: Map<String, UUID>, val verifier: AuthMeLegacyVerifier)
    private companion object {
        const val BATCH = "authme-inbox"
        const val MAX_PROFILE_BYTES = 16 * 1024 * 1024
    }
}

enum class AuthMeImportPhase { IDLE, SCANNING_SOURCE, MATCHING_PROFILES, READY, ACTIVATING, ACTIVE, DISABLING, DISABLED, CANCELLED, EXPIRED, FAILED }
data class AuthMeImportStatus(val phase: AuthMeImportPhase, val processed: Int, val active: Boolean,
                              val failure: AuthMeImportFailure? = null)

/** Stable, non-sensitive administrator diagnostics; source values and causes are never retained. */
enum class AuthMeImportFailure {
    MISSING_INBOX_CONFIG, INVALID_SOURCE_CONFIG, SOURCE_UNAVAILABLE, PREFLIGHT_FAILED,
    INVALID_CONFIRMATION, SNAPSHOT_INVALID, TARGET_IMPORT_FAILED, ACTIVATION_FAILED,
    NO_ACTIVE_SNAPSHOT, OPERATION_FAILED,
}
class AuthMeImportException(val reason: AuthMeImportFailure) :
    IllegalStateException("AuthMe migration failed: ${reason.name}")
