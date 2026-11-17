package moe.ryuu.kyokouauth.proxy.avatar

import moe.ryuu.kyokouauth.core.avatar.AvatarJson
import moe.ryuu.kyokouauth.core.avatar.AvatarLimits
import moe.ryuu.kyokouauth.core.avatar.AvatarSkinDownloader
import moe.ryuu.kyokouauth.core.avatar.AvatarUnavailableException
import moe.ryuu.kyokouauth.core.avatar.DefaultAvatarSkin
import moe.ryuu.kyokouauth.core.avatar.RoleAvatarRenderer
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

typealias RoleAvatarSnapshot = moe.ryuu.kyokouauth.core.avatar.RoleAvatarSnapshot
typealias RoleAvatarOffer = moe.ryuu.kyokouauth.core.avatar.RoleAvatarOffer

/** Presentation worker/cache only. The caller must already have verified FG roles before prepare. */
class RoleAvatarService(
    private val port: Int,
    private val downloader: (URI) -> ByteArray = AvatarSkinDownloader()::download,
    private val defaultSkin: () -> ByteArray = DefaultAvatarSkin::bytes,
    private val prepareTimeoutMillis: Long = 6_000,
) : AutoCloseable {
    init { require(port in 1..65535); require(prepareTimeoutMillis in 50..6_000) }
    private val lock = Any()
    private var running = false
    private var closed = false
    private var server: AvatarSnapshotServer? = null
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "role-avatar-prepare").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val timers = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "role-avatar-prepare-deadline").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private class Pending(val xuid: Long, val result: CompletableFuture<RoleAvatarSnapshot>, val deadline: Long) {
        var task: FutureTask<Unit>? = null
        var timer: ScheduledFuture<*>? = null
    }
    private val active = mutableMapOf<CompletableFuture<RoleAvatarSnapshot>, Pending>()
    private val latest = mutableMapOf<Long, Pending>()
    private val snapshots = LinkedHashMap<Long, RoleAvatarSnapshot>(64, 0.75f, true)
    private var snapshotBytes = 0
    private val sources = LinkedHashMap<URI, ByteArray>(16, 0.75f, true)
    private var sourceBytes = 0
    private val defaultSprite: ByteArray by lazy { RoleAvatarRenderer.sprite(defaultSkin()) }

    fun start() = synchronized(lock) {
        if (closed) throw AvatarUnavailableException()
        if (!running) {
            val endpoint = AvatarSnapshotServer(port, ::snapshot)
            try { endpoint.start(); server = endpoint; running = true }
            catch (_: Exception) { endpoint.close(); close(); throw AvatarUnavailableException() }
        }
    }

    fun prepare(xuid: Long, microsoftTextureValue: String?, premiumTextureValue: String?): CompletableFuture<RoleAvatarSnapshot> {
        if (xuid <= 0 || listOfNotNull(microsoftTextureValue, premiumTextureValue).any { it.length !in 1..16_384 }) {
            return CompletableFuture.failedFuture(AvatarUnavailableException())
        }
        val result = CompletableFuture<RoleAvatarSnapshot>()
        val pending = Pending(xuid, result, System.nanoTime() + prepareTimeoutMillis * 1_000_000)
        pending.task = FutureTask<Unit> {
            try {
                check(pending)
                val microsoft = sprite(microsoftTextureValue, pending)
                check(pending)
                val premium = sprite(premiumTextureValue, pending)
                val snapshot = RoleAvatarSnapshot.create(microsoft, premium, microsoftTextureValue == null, premiumTextureValue == null)
                synchronized(lock) {
                    check(pending)
                    snapshots.remove(xuid)?.let { snapshotBytes -= it.byteSize }
                    snapshots[xuid] = snapshot
                    snapshotBytes += snapshot.byteSize
                    while (snapshots.size > 64 || snapshotBytes > 2 * 1024 * 1024) {
                        val first = snapshots.entries.iterator().next()
                        snapshotBytes -= first.value.byteSize
                        snapshots.remove(first.key)
                    }
                }
                // User completion callbacks may read the HTTP bridge; never execute them while
                // holding the cache monitor needed by that bridge.
                result.complete(snapshot)
            } catch (_: Exception) { result.completeExceptionally(AvatarUnavailableException()) }
            Unit
        }
        result.whenComplete { _, _ -> synchronized(lock) {
            active.remove(result)?.timer?.cancel(false)
            if (latest[xuid] === pending) latest.remove(xuid)
            if (result.isCancelled) { pending.task?.cancel(true); worker.remove(pending.task) }
        } }
        synchronized(lock) {
            if (!running || closed) return CompletableFuture.failedFuture(AvatarUnavailableException())
            snapshots.remove(xuid)?.let { snapshotBytes -= it.byteSize }
            active[result] = pending
            latest[xuid] = pending
            try {
                worker.execute(pending.task!!)
                pending.timer = timers.schedule({
                    result.completeExceptionally(AvatarUnavailableException())
                    pending.task?.cancel(true)
                    worker.remove(pending.task)
                }, prepareTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                pending.task?.cancel(true)
                worker.remove(pending.task)
                result.completeExceptionally(AvatarUnavailableException())
            }
        }
        return result
    }

    private fun check(pending: Pending) = synchronized(lock) {
        if (closed || !running || pending.result.isDone || latest[pending.xuid] !== pending ||
            pending.deadline - System.nanoTime() <= 0 || Thread.currentThread().isInterrupted) throw AvatarUnavailableException()
    }

    private fun sprite(textureValue: String?, pending: Pending): ByteArray {
        if (textureValue == null) return defaultSprite.copyOf()
        val location = AvatarJson.skinLocation(textureValue)
        val cached = synchronized(lock) { sources[location]?.copyOf() }
        val source = cached ?: downloader(location)
        if (source.size !in 1..AvatarLimits.SKIN_BYTES) throw AvatarUnavailableException()
        check(pending)
        val sprite = RoleAvatarRenderer.sprite(source)
        if (cached == null) synchronized(lock) {
            check(pending)
            sources[location] = source.copyOf()
            sourceBytes += source.size
            while (sources.size > 16 || sourceBytes > 2 * 1024 * 1024) {
                val first = sources.entries.iterator().next()
                sourceBytes -= first.value.size
                sources.remove(first.key)
            }
        }
        return sprite
    }

    fun snapshot(xuid: Long): RoleAvatarSnapshot? = synchronized(lock) {
        if (!running || closed || xuid <= 0) null else snapshots[xuid]
    }

    override fun close() {
        val pending: List<Pending>
        val endpoint: AvatarSnapshotServer?
        synchronized(lock) {
            if (closed) return
            closed = true
            running = false
            endpoint = server
            server = null
            pending = active.values.toList()
            snapshots.clear(); snapshotBytes = 0
            sources.clear(); sourceBytes = 0
        }
        endpoint?.close()
        pending.forEach { it.result.completeExceptionally(AvatarUnavailableException()); it.task?.cancel(true) }
        worker.shutdownNow()
        timers.shutdownNow()
        synchronized(lock) { active.clear(); latest.clear() }
    }
}
