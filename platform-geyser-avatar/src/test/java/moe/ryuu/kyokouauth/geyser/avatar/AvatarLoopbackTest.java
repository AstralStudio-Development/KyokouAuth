package moe.ryuu.kyokouauth.geyser.avatar;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AvatarLoopbackTest {
    @TempDir Path directory;
    private static final byte[] MISS = "{\"schema\":1,\"state\":\"not-ready\"}".getBytes(StandardCharsets.UTF_8);

    @Test void snapshotBridgeUsesOnlyGetWithBoundedValidatedReadyMissAndNeverFollowsAnyRedirect() throws Exception {
        AtomicInteger requests = new AtomicInteger(), redirects = new AtomicInteger();
        AtomicInteger code = new AtomicInteger(200);
        AvatarSnapshot image = AvatarTestData.snapshot(0xFF123456);
        AtomicReference<byte[]> response = new AtomicReference<>(AvatarTestData.json(image));
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        target.createContext("/redirected", exchange -> { redirects.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        target.start();
        HttpServer bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        bridge.createContext("/v1/avatars", exchange -> {
            requests.incrementAndGet();
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("xuid=1", exchange.getRequestURI().getRawQuery());
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.get();
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/redirected");
            exchange.sendResponseHeaders(code.get(), bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
            exchange.close();
        });
        bridge.start();
        try (LoopbackSnapshotClient client = new LoopbackSnapshotClient(URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/v1/avatars"))) {
            assertEquals(image.fingerprint(), client.fetch("1", deadline()).fingerprint());
            code.set(202); response.set(MISS);
            assertNull(client.fetch("1", deadline()));
            code.set(302);
            assertThrows(IOException.class, () -> client.fetch("1", deadline()));
            assertEquals(0, redirects.get());
            code.set(200); response.set(new byte[24577]);
            assertThrows(IOException.class, () -> client.fetch("1", deadline()));
            response.set(new String(AvatarTestData.json(image), StandardCharsets.UTF_8).replace(image.fingerprint(), "0".repeat(64)).getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> client.fetch("1", deadline()));
            int before = requests.get();
            assertThrows(IOException.class, () -> client.fetch("1&xuid=2", deadline()));
            assertThrows(IOException.class, () -> client.fetch("1", System.nanoTime() - 1));
            client.close();
            assertThrows(IOException.class, () -> client.fetch("1", deadline()));
            assertEquals(before, requests.get());
        } finally { bridge.stop(0); target.stop(0); }
    }

    @Test void stalledSnapshotBodyIsBoundedAndCloseDoesNotLeaveAResponseWait() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        bridge.createContext("/v1/avatars", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            try { release.await(1, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        bridge.start();
        try (LoopbackSnapshotClient client = new LoopbackSnapshotClient(URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/v1/avatars"))) {
            long started = System.nanoTime();
            assertThrows(IOException.class, () -> client.fetch("1", deadline()));
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
        } finally { release.countDown(); bridge.stop(0); }
    }

    @Test void readOnlyStatusReportsOnlyCurrentOfferedNonceAndExplicitBindConflictNeverChangesPort() throws Exception {
        int port;
        // Test-only ephemeral reservation: production AvatarConfig/AvatarStatusServer reject zero.
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = reservation.getLocalPort(); }
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));
        Object first = new Object(), replacement = new Object();
        try (AvatarPackCache cache = new AvatarPackCache(directory, 4)) {
            SessionOffers offers = new SessionOffers(cache, 4);
            try (AvatarStatusServer server = new AvatarStatusServer(port, offers, worker)) {
                server.start();
                assertEquals(404, request(port, "GET", "xuid=1").code);
                SessionOffers.Slot old = offers.begin(first, "1");
                offers.base(old, () -> {});
                Reply reply = request(port, "GET", "xuid=1");
                assertEquals(200, reply.code);
                var json = AvatarJson.object(reply.bytes);
                assertEquals("offered", json.get("state"));
                assertEquals(old.nonce.toString(), json.get("nonce"));
                assertNull(json.get("fingerprint"));
                assertEquals(true, json.get("fallback"));
                SessionOffers.Slot next = offers.begin(replacement, "1");
                offers.base(next, () -> {});
                offers.disconnect(first, old.nonce);
                assertEquals(next.nonce.toString(), AvatarJson.object(request(port, "GET", "xuid=1").bytes).get("nonce"));
                assertEquals(400, request(port, "GET", "xuid=1&xuid=2").code);
                assertEquals(405, request(port, "POST", "xuid=1").code);
                assertThrows(IOException.class, () -> new AvatarStatusServer(port, offers, worker));
                assertEquals(200, request(port, "GET", "xuid=1").code);
                offers.disconnect(replacement, next.nonce);
                assertEquals(404, request(port, "GET", "xuid=1").code);
            } finally {
                offers.disconnect(first); offers.disconnect(replacement); offers.close();
            }
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(1, TimeUnit.SECONDS)); }
    }

    private record Reply(int code, byte[] bytes) {}
    private static Reply request(int port, String method, String query) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/v1/packs/status?" + query).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(250); connection.setReadTimeout(250);
        connection.setInstanceFollowRedirects(false);
        try {
            int status = connection.getResponseCode();
            try (var input = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                return new Reply(status, input == null ? new byte[0] : input.readNBytes(1024));
            }
        } finally { connection.disconnect(); }
    }
    private static long deadline() { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250); }
}
