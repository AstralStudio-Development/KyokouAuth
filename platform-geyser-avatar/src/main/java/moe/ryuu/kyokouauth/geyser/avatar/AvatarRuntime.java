package moe.ryuu.kyokouauth.geyser.avatar;

import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.option.PriorityOption;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Synchronous event registration with one bounded presentation worker; no authentication APIs. */
public final class AvatarRuntime implements AutoCloseable {
    @FunctionalInterface public interface PackFactory { ResourcePack create(AvatarPackCache.Pack pack) throws IOException; }
    private record Prepared(AvatarPackCache.Lease lease, ResourcePack pack) {}
    private record Work(Acquisition acquisition, FutureTask<Prepared> future) {}
    private final AvatarConfig config;
    private final AvatarPackCache cache;
    private final SessionOffers offers;
    private final AvatarSnapshotSource source;
    private final PackFactory packFactory;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), action -> {
                Thread thread = new Thread(action, "KyokouAuth-Avatar");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Set<Work> work = new HashSet<>();
    private ResourcePack basePack;
    private volatile boolean closed;

    public AvatarRuntime(AvatarConfig config, Path directory) throws IOException {
        this(config, directory, new LoopbackSnapshotClient(config.proxyEndpoint()),
                pack -> ResourcePack.create(PackCodec.path(pack.path())));
    }
    public AvatarRuntime(AvatarConfig config, Path directory, AvatarSnapshotSource source, PackFactory packFactory) throws IOException {
        if (!config.enabled()) throw new IllegalArgumentException("Avatar runtime is explicitly disabled");
        this.config = config; this.source = source; this.packFactory = packFactory;
        cache = new AvatarPackCache(directory, config.maxPacks());
        offers = new SessionOffers(cache, config.maxSessions());
    }
    public SessionOffers offers() { return offers; }
    ThreadPoolExecutor executor() { return worker; }
    public boolean isClosed() { return closed; }
    public int queuedWork() { return worker.getQueue().size(); }

    public record BasePackEvidence(java.util.UUID uuid, long bytes, String sha256) {}
    /** Real public native codec/manifest smoke, deferred until Geyser defines resource packs. */
    public synchronized BasePackEvidence verifyBasePack() throws IOException {
        if (closed) throw new IOException("Avatar runtime closed");
        if (basePack == null) try (AvatarPackCache.Lease base = cache.base()) { basePack = checkedPack(base); }
        byte[] digest = basePack.codec().sha256();
        long bytes = basePack.codec().size();
        if (digest.length != 32 || bytes < 1 || bytes > AvatarPackWriter.MAX_PACK_BYTES) throw new IOException("Invalid native avatar base codec");
        return new BasePackEvidence(basePack.uuid(), bytes, java.util.HexFormat.of().formatHex(digest));
    }

    public void onLoad(SessionLoadResourcePacksEvent event) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMillis());
        SessionOffers.Slot slot;
        try {
            synchronized (this) {
                if (closed) return;
                if (basePack == null) {
                    try (AvatarPackCache.Lease base = cache.base()) { basePack = checkedPack(base); }
                }
                String xuid = event.connection().xuid();
                slot = offers.begin(event.connection(), xuid);
                if (slot == null) {
                    // Overflow/unknown XUID is presentation failure, never a substitute identity.
                    cache.retainBaseUntilProcessExit();
                    event.register(basePack, PriorityOption.NORMAL);
                    return;
                }
                if (offers.replay(slot, () -> event.register(basePack, PriorityOption.NORMAL),
                        pack -> event.register(pack, PriorityOption.HIGHEST))) return;
                if (!offers.base(slot, () -> event.register(basePack, PriorityOption.NORMAL))) return;
            }
        } catch (Exception | LinkageError unavailable) { return; }

        Acquisition acquisition = new Acquisition(slot.xuid, deadline);
        FutureTask<Prepared> future = new FutureTask<>(acquisition);
        Work request = new Work(acquisition, future);
        Prepared prepared = null;
        boolean handedOff = false;
        try {
            synchronized (this) {
                if (closed || work.size() >= 17) return;
                work.add(request);
                worker.execute(future); // AbortPolicy: never run bridge IO on the Geyser event thread.
            }
            prepared = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (prepared == null || !acquisition.claim(prepared)) return;
            synchronized (this) {
                if (closed || System.nanoTime() >= deadline) return;
                handedOff = true;
                Prepared offered = prepared;
                offers.overlay(slot, offered.lease, offered.pack, () -> event.register(offered.pack, PriorityOption.HIGHEST));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError unavailable) {
            // Base has already been registered and its status is explicitly fallback/unknown.
        } finally {
            if (prepared != null && !handedOff) closeQuietly(prepared.lease);
            acquisition.abandon();
            future.cancel(true);
            worker.remove(future);
            synchronized (this) { work.remove(request); }
        }
        // No future callback registers a pack after this synchronous event has returned.
    }

    private ResourcePack checkedPack(AvatarPackCache.Lease lease) throws IOException {
        AvatarPackCache.Pack expected = lease.pack();
        ResourcePack pack = packFactory.create(expected);
        if (pack == null || !expected.uuid().equals(pack.uuid())) throw new IOException("Avatar pack manifest UUID differs");
        return pack;
    }

    private final class Acquisition implements Callable<Prepared> {
        final String xuid;
        final long deadline;
        final AtomicBoolean abandoned = new AtomicBoolean();
        final AtomicReference<Prepared> held = new AtomicReference<>();
        Acquisition(String xuid, long deadline) { this.xuid = xuid; this.deadline = deadline; }
        @Override public Prepared call() throws Exception {
            if (expired()) return null;
            AvatarSnapshot snapshot = source.fetch(xuid, deadline);
            if (snapshot == null || expired()) return null;
            AvatarPackCache.Lease lease = cache.acquire(snapshot);
            if (lease == null) return null;
            try {
                Prepared result = new Prepared(lease, checkedPack(lease));
                held.set(result);
                if (expired()) { abandon(); return null; }
                return result;
            } catch (Exception | LinkageError failure) {
                closeQuietly(lease);
                throw failure;
            }
        }
        boolean expired() { return abandoned.get() || closed || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline; }
        boolean claim(Prepared result) { return held.compareAndSet(result, null); }
        void abandon() {
            abandoned.set(true);
            Prepared late = held.getAndSet(null);
            if (late != null) closeQuietly(late.lease);
        }
    }

    public void disconnect(Object actualConnection) { try { offers.disconnect(actualConnection); } catch (IOException ignored) { } }
    @Override public void close() throws IOException {
        ArrayList<Work> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            offers.close();
            pending = new ArrayList<>(work);
            work.clear();
        }
        try { source.close(); }
        finally {
            for (Work item : pending) { item.acquisition.abandon(); item.future.cancel(true); }
            worker.shutdownNow().forEach(item -> { if (item instanceof FutureTask<?> task) task.cancel(false); });
            // Active sessions keep their immutable files/lock until their exact disconnect callback.
            cache.close();
        }
    }
    private static void closeQuietly(AvatarPackCache.Lease lease) { try { lease.close(); } catch (IOException ignored) { } }
}
