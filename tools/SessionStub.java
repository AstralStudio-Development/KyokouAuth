import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for Mojang's session server, used to exercise the handshake success path.
 *
 * The one thing a local test cannot produce is a real Mojang session: that requires an actual
 * Microsoft account joining from an actual client. Everything else - the RSA exchange, the token
 * check, the session hash, installing encryption, replaying the held Login Start, binding the
 * verified profile - is ordinary code, and this stub lets all of it run.
 *
 * It answers `hasJoined` with a fixed UUID for whatever name was asked about, which is exactly the
 * shape the real server returns.
 *
 * Usage: java SessionStub <port> <uuid> [expectedName]
 */
public final class SessionStub {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String uuid = args[1];
        String expected = args.length > 2 ? args[2] : null;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

        server.createContext("/session/minecraft/hasJoined", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String name = null;
            String serverId = null;

            if (query != null) {
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq < 0) continue;
                    String key = pair.substring(0, eq);
                    String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    if (key.equals("username")) name = value;
                    if (key.equals("serverId")) serverId = value;
                }
            }

            System.out.println("HAS_JOINED username=" + name + " serverId=" + serverId);

            if (name == null || (expected != null && !expected.equalsIgnoreCase(name))) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            String body = "{\"id\":\"" + uuid.replace("-", "") + "\",\"name\":\"" + name + "\"," +
                "\"properties\":[{\"name\":\"textures\",\"value\":\"stubtexture\",\"signature\":\"stubsig\"}]}";

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        server.start();
        System.out.println("STUB listening on 127.0.0.1:" + port + " uuid=" + uuid);
    }
}
