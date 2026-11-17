package moe.ryuu.kyokouauth.geyser;

import org.geysermc.geyser.api.event.java.ServerTransferEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Address-only UX adapter. It cannot prove identity, access cookies or authorize a role. */
public final class TransferRoutes implements AutoCloseable {
    private static final int MAX_CONFIG_BYTES = 16_384;
    private static final int MAX_ROUTES = 64;
    private static final Pattern KEY = Pattern.compile("route\\.([A-Za-z0-9_-]{1,32})\\.(java-host|java-port|bedrock-host|bedrock-port)");
    private final Map<Endpoint, Endpoint> routes;
    private boolean closed;

    private TransferRoutes(Map<Endpoint, Endpoint> routes) { this.routes = Map.copyOf(routes); }

    /** UTF-8 properties, bounded before parsing. Unknown/duplicate keys and targets fail closed. */
    public static TransferRoutes load(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_CONFIG_BYTES + 1);
        if (bytes.length > MAX_CONFIG_BYTES) throw new IllegalArgumentException("Transfer configuration exceeds 16KiB");
        Properties config = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate transfer configuration key");
                return super.put(key, value);
            }
        };
        config.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        String enabled = config.getProperty("enabled", "false");
        if (!enabled.equals("true") && !enabled.equals("false")) throw new IllegalArgumentException("enabled must be true or false");
        Set<String> names = new HashSet<>();
        for (String key : config.stringPropertyNames()) {
            if (key.equals("enabled")) continue;
            Matcher matcher = KEY.matcher(key);
            if (!matcher.matches()) throw new IllegalArgumentException("Unknown transfer configuration key");
            names.add(matcher.group(1));
        }
        if (names.size() > MAX_ROUTES) throw new IllegalArgumentException("At most 64 transfer routes are supported");
        Map<Endpoint, Endpoint> routes = new HashMap<>();
        for (String name : names) {
            String prefix = "route." + name + ".";
            Endpoint java = endpoint(config, prefix, "java");
            Endpoint bedrock = endpoint(config, prefix, "bedrock");
            if (bedrock.host.equals("0.0.0.0")) throw new IllegalArgumentException("Bedrock target must be client reachable, not a bind address");
            if (routes.putIfAbsent(java, bedrock) != null) throw new IllegalArgumentException("Duplicate Java transfer destination");
        }
        if (enabled.equals("true") && routes.isEmpty()) throw new IllegalArgumentException("Enabled transfer requires an explicit route");
        return new TransferRoutes(enabled.equals("true") ? routes : Map.of());
    }

    private static Endpoint endpoint(Properties config, String prefix, String kind) {
        String host = config.getProperty(prefix + kind + "-host");
        String portText = config.getProperty(prefix + kind + "-port");
        if (host == null || portText == null || host.length() > 253 || host.isEmpty())
            throw new IllegalArgumentException("Transfer route requires a host and port");
        // DNS/IPv4 addresses only; no wildcard, URI, whitespace, SRV discovery or client hostname.
        for (String label : host.split("\\.", -1)) {
            if (label.length() > 63 || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
                throw new IllegalArgumentException("Transfer host must be an explicit DNS or IPv4 address");
        }
        if (!portText.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid transfer port");
        int port = Integer.parseInt(portText);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid transfer port");
        return new Endpoint(host.toLowerCase(Locale.ROOT), port);
    }

    /** Uses the native event destination; never virtual hostname, XUID, or cookie payload. */
    public synchronized void accept(ServerTransferEvent event) {
        if (closed || event.host() == null) return;
        Endpoint target = routes.get(new Endpoint(event.host().toLowerCase(Locale.ROOT), event.port()));
        if (target == null) return;
        event.bedrockHost(target.host);
        event.bedrockPort(target.port);
    }

    /** Safe startup evidence: no target, cookie, identity or credential is exposed. */
    public synchronized int routeCount() { return closed ? 0 : routes.size(); }

    @Override public synchronized void close() { closed = true; }

    private record Endpoint(String host, int port) {}
}
