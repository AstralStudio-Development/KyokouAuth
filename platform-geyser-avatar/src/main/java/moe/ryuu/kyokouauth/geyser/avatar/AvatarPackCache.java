package moe.ryuu.kyokouauth.geyser.avatar;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Immutable content files, bounded including base, with explicit leases held until session close. */
public final class AvatarPackCache implements AutoCloseable {
    public record Pack(Path path, UUID uuid, String fingerprint, boolean base, int bytes) {}
    private static final class Entry {
        final Pack pack;
        int references;
        Entry(Pack pack) { this.pack = pack; }
    }
    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private final AtomicBoolean released = new AtomicBoolean();
        private Lease(Entry entry) { this.entry = entry; }
        public Pack pack() {
            if (released.get()) throw new IllegalStateException("Avatar lease already released");
            return entry.pack;
        }
        @Override public void close() throws IOException {
            if (released.compareAndSet(false, true)) release(entry);
        }
    }

    private final Path root;
    private final int capacity;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private boolean closed;
    private boolean permanentBasePin;

    public AvatarPackCache(Path directory, int capacity) throws IOException {
        if (capacity < 1 || capacity > 64) throw new IllegalArgumentException("Avatar cache must contain 1..64 packs including base");
        this.capacity = capacity;
        root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) throw new IOException("Avatar cache directory is unsafe");
        Path lockPath = root.resolve(".avatar.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Avatar cache lock is unsafe");
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (OverlappingFileLockException busy) { lockChannel.close(); throw new IOException("Avatar cache still has active leases"); }
        if (acquired == null) { lockChannel.close(); throw new IOException("Avatar cache is already owned"); }
        directoryLock = acquired;
        try {
            // A live generation retains the exclusive lock while any lease exists. Startup can
            // therefore remove only our orphaned files, never an active previous generation.
            try (var files = Files.newDirectoryStream(root)) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (name.equals(".avatar.lock")) continue;
                    if (!name.matches("avatar-(?:base-)?[0-9a-f]{64}\\.mcpack") ||
                            !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))
                        throw new IOException("Unexpected file in owned avatar cache");
                    Files.delete(file);
                }
            }
            byte[] u = AvatarPng.fallback(true), j = AvatarPng.fallback(false);
            AvatarSnapshot generic = new AvatarSnapshot(AvatarSnapshot.fingerprint(u, j), u, j, false, false);
            entries.put("base", write(generic, true));
        } catch (IOException | RuntimeException failure) {
            directoryLock.close();
            lockChannel.close();
            throw failure;
        }
    }

    public synchronized Lease base() {
        ensureOpen();
        return lease(entries.get("base"));
    }

    /** Null means all bounded slots are pinned: use the already-offered generic base, not stale art. */
    public synchronized Lease acquire(AvatarSnapshot snapshot) throws IOException {
        ensureOpen();
        Entry entry = entries.get(snapshot.fingerprint());
        if (entry != null) return lease(entry);
        while (entries.size() >= capacity) {
            boolean evicted = false;
            Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry idle = iterator.next().getValue();
                if (!idle.pack.base && idle.references == 0) {
                    Files.delete(idle.pack.path);
                    iterator.remove();
                    evicted = true;
                    break;
                }
            }
            if (!evicted) return null;
        }
        entry = write(snapshot, false);
        entries.put(snapshot.fingerprint(), entry);
        return lease(entry);
    }

    /** Session tracking overflow must not delete a base still being read by an untracked client. */
    synchronized void retainBaseUntilProcessExit() { permanentBasePin = true; }
    public synchronized int packCount() { return entries.size(); }
    public synchronized long totalBytes() { return entries.values().stream().mapToLong(entry -> entry.pack.bytes).sum(); }

    private Entry write(AvatarSnapshot snapshot, boolean base) throws IOException {
        byte[] bytes = AvatarPackWriter.build(snapshot, base);
        Path file = root.resolve("avatar-" + (base ? "base-" : "") + snapshot.fingerprint() + ".mcpack");
        // CREATE_NEW is intentional: no caller can overwrite a previously registered codec path.
        boolean created = false;
        try {
            try (var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                output.write(bytes);
            }
        } catch (IOException failure) {
            if (created) Files.deleteIfExists(file);
            throw failure;
        }
        return new Entry(new Pack(file, AvatarPackWriter.uuid(snapshot.fingerprint(), base), snapshot.fingerprint(), base, bytes.length));
    }

    private Lease lease(Entry entry) { entry.references++; return new Lease(entry); }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Avatar cache closed"); }

    private synchronized void release(Entry entry) throws IOException {
        if (entry.references < 1) throw new IllegalStateException("Avatar reference underflow");
        entry.references--;
        if (closed) cleanupClosed();
    }
    @Override public synchronized void close() throws IOException { closed = true; cleanupClosed(); }
    private void cleanupClosed() throws IOException {
        for (Entry entry : new ArrayList<>(entries.values())) {
            if (entry.references != 0 || entry.pack.base && permanentBasePin) continue;
            Files.deleteIfExists(entry.pack.path);
            entries.values().remove(entry);
        }
        if (entries.isEmpty() && directoryLock.isValid()) {
            directoryLock.close();
            lockChannel.close();
        }
    }
}
