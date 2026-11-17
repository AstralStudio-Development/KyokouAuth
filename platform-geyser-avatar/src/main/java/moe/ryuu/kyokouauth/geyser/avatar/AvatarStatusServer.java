package moe.ryuu.kyokouauth.geyser.avatar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

/** Exact read-only loopback endpoint. Offered is not a client rendering or authentication ACK. */
public final class AvatarStatusServer implements AutoCloseable {
    private final HttpServer server;
    private final SessionOffers offers;
    private volatile boolean closed;

    public AvatarStatusServer(int port, SessionOffers offers, Executor boundedExecutor) throws IOException {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Status port must be explicit and nonzero");
        this.offers = offers;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 16);
        server.setExecutor(boundedExecutor);
        server.createContext("/v1/packs/status", this::handle);
    }
    public void start() { if (closed) throw new IllegalStateException("Avatar status server closed"); server.start(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (closed) { respond(exchange, 503, "{\"schema\":1,\"state\":\"unavailable\"}"); return; }
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                respond(exchange, 403, "{\"schema\":1,\"state\":\"forbidden\"}"); return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                respond(exchange, 405, "{\"schema\":1,\"state\":\"method-not-allowed\"}"); return;
            }
            String query = exchange.getRequestURI().getRawQuery();
            if (!exchange.getRequestURI().getRawPath().equals("/v1/packs/status") || query == null ||
                    !query.startsWith("xuid=") || !AvatarConfig.validXuid(query.substring(5))) {
                respond(exchange, 400, "{\"schema\":1,\"state\":\"bad-request\"}"); return;
            }
            SessionOffers.Offered offered = offers.status(query.substring(5));
            if (offered == null) respond(exchange, 404, "{\"schema\":1,\"state\":\"not-found\"}");
            else respond(exchange, 200, offered.json());
        } finally { exchange.close(); }
    }
    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    @Override public synchronized void close() { if (!closed) { closed = true; server.stop(0); } }
}
