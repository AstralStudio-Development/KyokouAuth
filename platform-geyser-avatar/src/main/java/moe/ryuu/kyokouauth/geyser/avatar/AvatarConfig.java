package moe.ryuu.kyokouauth.geyser.avatar;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;

/** Presentation-only endpoints. No host lookup, external URL, redirect, or automatic port choice. */
public record AvatarConfig(boolean enabled, URI proxyEndpoint, int statusPort, int timeoutMillis,
                           int maxPacks, int maxSessions) {
    private static final Set<String> KEYS = Set.of("enabled", "proxy-endpoint", "status-host", "status-port",
            "snapshot-timeout-ms", "max-packs", "max-sessions");

    public AvatarConfig {
        requireProxyEndpoint(proxyEndpoint);
        if (statusPort < 1 || statusPort > 65535 || timeoutMillis < 100 || timeoutMillis > 250 ||
                maxPacks < 1 || maxPacks > 64 || maxSessions < 1 || maxSessions > 4096)
            throw new IllegalArgumentException("Invalid avatar presentation resource limit");
        if (proxyEndpoint.getPort() == statusPort) throw new IllegalArgumentException("Avatar bridge ports must differ");
    }

    static void requireProxyEndpoint(URI uri) {
        if (uri == null || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) ||
                uri.getPort() < 1 || uri.getPort() > 65535 || !"/v1/avatars".equals(uri.getRawPath()) ||
                uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("Avatar snapshot endpoint must be explicit IPv4 loopback /v1/avatars");
    }

    public static AvatarConfig load(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(4097);
        if (bytes.length > 4096) throw new IllegalArgumentException("Avatar configuration exceeds 4KiB");
        Properties values = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate avatar configuration key");
                return super.put(key, value);
            }
        };
        values.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        if (!KEYS.containsAll(values.stringPropertyNames())) throw new IllegalArgumentException("Unknown avatar configuration key");
        String enabled = values.getProperty("enabled", "false");
        if (!enabled.equals("true") && !enabled.equals("false")) throw new IllegalArgumentException("enabled must be true or false");
        if (!"127.0.0.1".equals(values.getProperty("status-host", "127.0.0.1")))
            throw new IllegalArgumentException("Avatar status server must bind IPv4 loopback");
        return new AvatarConfig(enabled.equals("true"), URI.create(values.getProperty("proxy-endpoint",
                "http://127.0.0.1:24682/v1/avatars")), number(values, "status-port", 24683),
                number(values, "snapshot-timeout-ms", 200), number(values, "max-packs", 64), number(values, "max-sessions", 1024));
    }

    private static int number(Properties values, String key, int fallback) {
        String value = values.getProperty(key, Integer.toString(fallback));
        if (!value.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid avatar numeric setting");
        return Integer.parseInt(value);
    }

    static boolean validXuid(String value) {
        return value != null && value.matches("[1-9][0-9]{0,19}") &&
                (value.length() < 20 || value.compareTo("18446744073709551615") <= 0);
    }
}
