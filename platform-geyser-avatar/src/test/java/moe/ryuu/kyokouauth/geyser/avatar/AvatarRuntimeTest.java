package moe.ryuu.kyokouauth.geyser.avatar;

import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.option.PriorityOption;
import org.geysermc.geyser.api.pack.option.ResourcePackOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AvatarRuntimeTest {
    @TempDir Path directory;
    @BeforeAll static void nativeValues() { NativeGeyserFixture.install(); }

    static GeyserConnection connection(String xuid) {
        return (GeyserConnection) Proxy.newProxyInstance(GeyserConnection.class.getClassLoader(), new Class<?>[]{GeyserConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "xuid" -> xuid;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "presentation-session";
                    default -> throw new AssertionError("Presentation must not inspect identity/permission APIs: " + method.getName());
                });
    }
    static final class Event extends SessionLoadResourcePacksEvent {
        final List<ResourcePack> packs = new ArrayList<>();
        final Map<UUID, List<ResourcePackOption<?>>> options = new HashMap<>();
        boolean returned;
        Event(GeyserConnection connection) { super(connection); }
        @Override public List<ResourcePack> resourcePacks() { return packs; }
        @Override public boolean register(ResourcePack pack) { register(pack, PriorityOption.NORMAL); return true; }
        @Override public void register(ResourcePack pack, ResourcePackOption<?>... values) {
            assertFalse(returned, "An async callback must never register after SessionLoadResourcePacksEvent returned");
            packs.add(pack); options.put(pack.uuid(), List.copyOf(Arrays.asList(values)));
        }
        @Override public void registerOptions(UUID uuid, ResourcePackOption<?>... values) { options.put(uuid, List.copyOf(Arrays.asList(values))); }
        @Override public Collection<ResourcePackOption<?>> options(UUID uuid) { return options.getOrDefault(uuid, List.of()); }
        @Override public ResourcePackOption option(UUID uuid, ResourcePackOption.Type type) { return null; }
        @Override public boolean unregister(UUID uuid) { return packs.removeIf(pack -> pack.uuid().equals(uuid)); }
        @Override public void allowVibrantVisuals(boolean allow) {}
    }
    private AvatarConfig config(int timeout) {
        return new AvatarConfig(true, URI.create("http://127.0.0.1:24682/v1/avatars"), 24683, timeout, 4, 16);
    }

    @Test void readySessionOffersBaseAndHighestOverlayAndExactConnectionNonceStatusWithoutAuthCalls() throws Exception {
        AvatarSnapshot image = AvatarTestData.snapshot(0xFF123456);
        AtomicInteger reads = new AtomicInteger();
        Map<UUID, AvatarPackCache.Pack> packs = new HashMap<>();
        GeyserConnection first = connection("1"), replacement = connection("1"), other = connection("2");
        try (AvatarRuntime runtime = new AvatarRuntime(config(250), directory, (xuid, deadline) -> { reads.incrementAndGet(); return image; },
                pack -> { synchronized (packs) { packs.put(pack.uuid(), pack); } return AvatarTestData.pack(pack); })) {
            Event event = new Event(first);
            runtime.onLoad(event); event.returned = true;
            assertEquals(2, event.packs.size());
            assertEquals(List.of(PriorityOption.NORMAL), event.options(event.packs.get(0).uuid()));
            assertEquals(List.of(PriorityOption.HIGHEST), event.options(event.packs.get(1).uuid()));
            SessionOffers.Offered initial = runtime.offers().status("1");
            assertNotNull(initial); assertFalse(initial.fallback()); assertEquals(image.fingerprint(), initial.fingerprint());
            assertTrue(initial.json().contains("\"state\":\"offered\""));
            assertFalse(initial.json().contains("applied"));
            Event duplicate = new Event(first);
            runtime.onLoad(duplicate); duplicate.returned = true;
            assertEquals(1, reads.get(), "A physical session must not refresh its packages after offer");
            assertEquals(initial.nonce(), runtime.offers().status("1").nonce());
            Event next = new Event(replacement);
            runtime.onLoad(next); next.returned = true;
            SessionOffers.Offered current = runtime.offers().status("1");
            assertNotEquals(initial.nonce(), current.nonce());
            assertEquals(event.packs.get(1).uuid(), next.packs.get(1).uuid(), "Same public image content may share an immutable package");
            runtime.disconnect(first);
            assertEquals(current, runtime.offers().status("1"), "Old disconnect must not clear a new nonce");
            Event another = new Event(other);
            runtime.onLoad(another); another.returned = true;
            assertNotEquals(current.nonce(), runtime.offers().status("2").nonce());
            runtime.disconnect(replacement);
            assertNull(runtime.offers().status("1"));
            assertNotNull(runtime.offers().status("2"));
            runtime.disconnect(other);
        }
    }

    @Test void missBridgeFailureAndFullCacheOfferTwoExplicitGenericFallbackIcons() throws Exception {
        for (boolean failed : List.of(false, true)) {
            Path root = directory.resolve("case-" + failed);
            GeyserConnection connection = connection("1");
            try (AvatarRuntime runtime = new AvatarRuntime(config(100), root, (xuid, deadline) -> {
                if (failed) throw new IOException("controlled bridge outage");
                return null;
            }, AvatarTestData::pack)) {
                Event event = new Event(connection);
                runtime.onLoad(event); event.returned = true;
                assertEquals(1, event.packs.size());
                SessionOffers.Offered offered = runtime.offers().status("1");
                assertNotNull(offered); assertTrue(offered.fallback()); assertNull(offered.fingerprint());
                runtime.disconnect(connection);
            }
        }
        GeyserConnection connection = connection("2");
        AvatarConfig noOverlaySlots = new AvatarConfig(true, config(100).proxyEndpoint(), 24683, 100, 1, 16);
        try (AvatarRuntime runtime = new AvatarRuntime(noOverlaySlots, directory.resolve("base-only"),
                (xuid, deadline) -> AvatarTestData.snapshot(0xFF123456), AvatarTestData::pack)) {
            Event event = new Event(connection);
            runtime.onLoad(event); event.returned = true;
            assertEquals(1, event.packs.size());
            assertTrue(runtime.offers().status("2").fallback());
            runtime.disconnect(connection);
        }
    }

    @Test void snapshotDelayNeverRegistersLateAndWorkerQueueSaturationFallsBackWithoutRunningIoOnEventThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AvatarSnapshot image = AvatarTestData.snapshot(0xFF123456);
        GeyserConnection connection = connection("1");
        try (AvatarRuntime runtime = new AvatarRuntime(config(100), directory.resolve("late"), (xuid, deadline) -> {
            while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { }
            return image;
        }, AvatarTestData::pack)) {
            Event event = new Event(connection);
            try {
                long start = System.nanoTime();
                runtime.onLoad(event); event.returned = true;
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
                assertEquals(1, event.packs.size());
                assertTrue(runtime.offers().status("1").fallback());
            } finally { release.countDown(); }
            runtime.executor().submit(() -> {}).get(1, TimeUnit.SECONDS);
            assertEquals(1, event.packs.size());
            runtime.disconnect(connection);
        } finally { release.countDown(); }

        CountDownLatch blocked = new CountDownLatch(1), entered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        GeyserConnection overloaded = connection("2");
        try (AvatarRuntime runtime = new AvatarRuntime(config(100), directory.resolve("full"), (xuid, deadline) -> {
            calls.incrementAndGet(); return image;
        }, AvatarTestData::pack)) {
            runtime.executor().execute(() -> { entered.countDown(); try { blocked.await(); } catch (InterruptedException ignored) { } });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 16; i++) runtime.executor().execute(() -> {});
            Event event = new Event(overloaded);
            runtime.onLoad(event); event.returned = true;
            assertEquals(0, calls.get());
            assertEquals(1, event.packs.size());
            assertTrue(runtime.offers().status("2").fallback());
            assertTrue(runtime.queuedWork() <= 16);
            runtime.disconnect(overloaded);
        } finally { blocked.countDown(); }
    }

    @Test void changedSkinGetsNewSessionPackUuidAndCloseCannotDeleteAReferencedActivePack() throws Exception {
        AtomicReferenceSnapshot source = new AtomicReferenceSnapshot(AvatarTestData.snapshot(0xFF123456));
        Map<UUID, AvatarPackCache.Pack> packs = new HashMap<>();
        GeyserConnection first = connection("1"), next = connection("1");
        AvatarRuntime runtime = new AvatarRuntime(config(250), directory, source,
                pack -> { synchronized (packs) { packs.put(pack.uuid(), pack); } return AvatarTestData.pack(pack); });
        Event old = new Event(first); runtime.onLoad(old); old.returned = true;
        source.snapshot = AvatarTestData.snapshot(0xFF654321);
        Event fresh = new Event(next); runtime.onLoad(fresh); fresh.returned = true;
        assertNotEquals(old.packs.get(1).uuid(), fresh.packs.get(1).uuid());
        Path active = packs.get(fresh.packs.get(1).uuid()).path();
        runtime.disconnect(first);
        runtime.close(); runtime.close();
        assertTrue(Files.exists(active));
        assertNull(runtime.offers().status("1"));
        Event afterClose = new Event(connection("3")); runtime.onLoad(afterClose); afterClose.returned = true;
        assertTrue(afterClose.packs.isEmpty());
        runtime.disconnect(next);
        assertFalse(Files.exists(active));
        assertTrue(runtime.executor().awaitTermination(1, TimeUnit.SECONDS));
    }

    private static final class AtomicReferenceSnapshot implements AvatarSnapshotSource {
        volatile AvatarSnapshot snapshot;
        AtomicReferenceSnapshot(AvatarSnapshot snapshot) { this.snapshot = snapshot; }
        @Override public AvatarSnapshot fetch(String xuid, long deadline) { return snapshot; }
    }
}
