package moe.ryuu.kyokouauth.geyser.avatar;

import org.geysermc.geyser.api.pack.ResourcePack;

import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** Offered means added during the synchronous pack event, never a client render/download ACK. */
public final class SessionOffers implements AutoCloseable {
    public record Offered(UUID nonce, String fingerprint, boolean fallback) {
        public Offered {
            if (nonce == null || fingerprint != null && !fingerprint.matches("[0-9a-f]{64}") || fallback != (fingerprint == null))
                throw new IllegalArgumentException("Invalid offered avatar state");
        }
        public String json() {
            return "{\"schema\":1,\"state\":\"offered\",\"nonce\":\"" + nonce + "\",\"fingerprint\":"
                    + (fingerprint == null ? "null" : "\"" + fingerprint + "\"") + ",\"fallback\":" + fallback + "}";
        }
    }
    static final class Slot {
        final Object connection;
        final String xuid;
        final UUID nonce = UUID.randomUUID();
        final AvatarPackCache.Lease base;
        ResourcePack overlayPack;
        AvatarPackCache.Lease overlay;
        Offered offered;
        Slot(Object connection, String xuid, AvatarPackCache.Lease base) {
            this.connection = connection; this.xuid = xuid; this.base = base;
        }
    }

    private final AvatarPackCache cache;
    private final int capacity;
    private final IdentityHashMap<Object, Slot> connections = new IdentityHashMap<>();
    private final Map<String, Slot> current = new HashMap<>();
    private boolean closed;

    SessionOffers(AvatarPackCache cache, int capacity) {
        if (capacity < 1 || capacity > 4096) throw new IllegalArgumentException("Invalid avatar session capacity");
        this.cache = cache; this.capacity = capacity;
    }

    synchronized Slot begin(Object connection, String xuid) {
        if (closed || connection == null || !AvatarConfig.validXuid(xuid)) return null;
        Slot previous = connections.get(connection);
        if (previous != null) {
            if (!previous.xuid.equals(xuid)) throw new IllegalStateException("Connection presentation XUID changed");
            return previous;
        }
        if (connections.size() >= capacity) {
            current.remove(xuid); // Never report an older connection's nonce as this untracked session.
            cache.retainBaseUntilProcessExit();
            return null;
        }
        Slot slot = new Slot(connection, xuid, cache.base());
        connections.put(connection, slot);
        current.put(xuid, slot);
        return slot;
    }

    synchronized boolean base(Slot slot, Runnable register) {
        if (!live(slot)) return false;
        register.run();
        if (slot.offered == null) slot.offered = new Offered(slot.nonce, null, true);
        return true;
    }

    synchronized boolean overlay(Slot slot, AvatarPackCache.Lease lease, ResourcePack pack, Runnable register) throws IOException {
        if (!live(slot) || slot.overlay != null) { lease.close(); return false; }
        // Retain before native registration: even a failing SDK call may have observed the codec.
        slot.overlay = lease;
        slot.overlayPack = pack;
        register.run();
        slot.offered = new Offered(slot.nonce, lease.pack().fingerprint(), false);
        return true;
    }

    synchronized boolean replay(Slot slot, Runnable registerBase, java.util.function.Consumer<ResourcePack> registerOverlay) {
        if (!live(slot) || slot.offered == null) return false;
        registerBase.run();
        if (slot.overlayPack != null && !slot.offered.fallback()) registerOverlay.accept(slot.overlayPack);
        return true;
    }

    private boolean live(Slot slot) { return !closed && slot != null && connections.get(slot.connection) == slot; }
    public synchronized Offered status(String xuid) {
        Slot slot = closed ? null : current.get(xuid);
        return slot == null ? null : slot.offered;
    }
    public synchronized int sessionCount() { return connections.size(); }

    public void disconnect(Object connection) throws IOException {
        Slot slot;
        synchronized (this) { slot = connections.get(connection); }
        if (slot != null) disconnect(connection, slot.nonce);
    }
    public void disconnect(Object connection, UUID nonce) throws IOException {
        Slot slot;
        synchronized (this) {
            slot = connections.get(connection);
            if (slot == null || !slot.nonce.equals(nonce)) return;
            connections.remove(connection);
            if (current.get(slot.xuid) == slot) current.remove(slot.xuid);
        }
        try { if (slot.overlay != null) slot.overlay.close(); }
        finally { slot.base.close(); }
    }

    /** Do not release active codecs merely because an extension is stopping. Actual disconnect owns it. */
    @Override public synchronized void close() { closed = true; current.clear(); }
}
