package moe.ryuu.kyokouauth.geyser.avatar;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AvatarConfigTest {
    private static AvatarConfig parse(String value) throws Exception {
        return AvatarConfig.load(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void bundledDefaultsAreDisabledAndOnlyExplicitPairedLoopbackPortsAreUsed() throws Exception {
        try (var input = getClass().getResourceAsStream("/avatar.properties")) {
            AvatarConfig config = AvatarConfig.load(input);
            assertFalse(config.enabled());
            assertEquals(URI.create("http://127.0.0.1:24682/v1/avatars"), config.proxyEndpoint());
            assertEquals(24683, config.statusPort());
            assertEquals(200, config.timeoutMillis());
            assertEquals(64, config.maxPacks());
        }
    }
    @Test void externalHostsRedirectLikeEndpointsCredentialsPortZeroAndResourceExpansionAreRejected() {
        for (String endpoint : new String[]{"http://localhost:24682/v1/avatars", "http://0.0.0.0:24682/v1/avatars",
                "http://127.0.0.2:24682/v1/avatars", "http://example.com:24682/v1/avatars", "https://127.0.0.1:24682/v1/avatars",
                "http://127.0.0.1:0/v1/avatars", "http://user@127.0.0.1:24682/v1/avatars", "http://127.0.0.1:24682/other",
                "http://127.0.0.1:24682/v1/avatars?xuid=1", "http://127.0.0.1:24682/v1/avatars#fragment"})
            assertThrows(Exception.class, () -> parse("enabled=true\nproxy-endpoint=" + endpoint));
        for (String invalid : new String[]{"status-host=0.0.0.0", "status-port=0", "status-port=24682", "snapshot-timeout-ms=99",
                "snapshot-timeout-ms=251", "max-packs=65", "max-packs=0", "max-sessions=4097", "enabled=maybe",
                "enabled=true\nenabled=false", "validate-bedrock-login=false", "#".repeat(4097)})
            assertThrows(Exception.class, () -> parse(invalid));
    }
    @Test void xuidIsOnlyABoundedPresentationLookupKeyNotAnAnonymousOrEncodedInput() {
        assertTrue(AvatarConfig.validXuid("2535412345678901"));
        assertTrue(AvatarConfig.validXuid("18446744073709551615"));
        for (String bad : new String[]{"0", "01", "-1", "18446744073709551616", "1&xuid=2", "%31", "1\r\nHost:external", "x".repeat(1000)})
            assertFalse(AvatarConfig.validXuid(bad));
    }
}
