package moe.ryuu.kyokouauth.geyser;

import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.java.ServerTransferEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransferRoutesTest {
    private static final String ROUTE = "enabled=true\nroute.local.java-host=proxy.example\n"
            + "route.local.java-port=25565\nroute.local.bedrock-host=bedrock.example\nroute.local.bedrock-port=19132\n";

    private static TransferRoutes parse(String text) throws Exception {
        return TransferRoutes.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static ServerTransferEvent event(String host, int port) {
        GeyserConnection connection = (GeyserConnection) Proxy.newProxyInstance(
                GeyserConnection.class.getClassLoader(), new Class<?>[]{GeyserConnection.class},
                (proxy, method, args) -> { throw new AssertionError("Routing must not inspect player identity: " + method.getName()); });
        return new ServerTransferEvent(connection, host, port, Map.of("unrelated:cookie", new byte[]{1, 2}));
    }

    @Test void onlyConfiguredExactJavaDestinationMapsToReachableBedrockGateway() throws Exception {
        try (TransferRoutes routes = parse(ROUTE)) {
            ServerTransferEvent accepted = event("PROXY.EXAMPLE", 25565);
            byte[] cookie = accepted.cookies().get("unrelated:cookie");
            routes.accept(accepted);
            assertEquals("bedrock.example", accepted.bedrockHost());
            assertEquals(19132, accepted.bedrockPort());
            assertEquals("PROXY.EXAMPLE", accepted.host());
            assertEquals(25565, accepted.port());
            assertSame(cookie, accepted.cookies().get("unrelated:cookie"));
            assertArrayEquals(new byte[]{1, 2}, cookie);
            for (ServerTransferEvent rejected : new ServerTransferEvent[]{event("proxy.example.attacker", 25565),
                    event("proxy.example", 25566), event("127.0.0.1", 25565), event("proxy.example.", 25565)}) {
                String host = rejected.bedrockHost();
                int port = rejected.bedrockPort();
                routes.accept(rejected);
                assertEquals(host, rejected.bedrockHost());
                assertEquals(port, rejected.bedrockPort());
            }
        }
    }

    @Test void disabledAndClosedRoutesNeverMutateEventsAndCloseIsIdempotent() throws Exception {
        TransferRoutes routes = parse(ROUTE);
        routes.close();
        routes.close();
        ServerTransferEvent afterClose = event("proxy.example", 25565);
        String originalHost = afterClose.bedrockHost();
        int originalPort = afterClose.bedrockPort();
        routes.accept(afterClose);
        assertEquals(originalHost, afterClose.bedrockHost());
        assertEquals(originalPort, afterClose.bedrockPort());
        try (TransferRoutes disabled = parse(ROUTE.replace("enabled=true", "enabled=false"))) {
            disabled.accept(afterClose);
            assertEquals(originalHost, afterClose.bedrockHost());
            assertEquals(originalPort, afterClose.bedrockPort());
        }
    }

    @Test void missingFieldsUnknownOptionsWildcardConflictingAndOversizedConfigurationsFailClosed() {
        for (String invalid : new String[]{
                "enabled=maybe\n", "enabled=true\n", ROUTE + "unknown=value\n", ROUTE + "route.local.extra=value\n",
                ROUTE.replace("bedrock-port=19132", "bedrock-port=0"), ROUTE.replace("java-port=25565", "java-port=65536"),
                ROUTE.replace("java-host=proxy.example", "java-host=*.example"),
                ROUTE.replace("bedrock-host=bedrock.example", "bedrock-host=0.0.0.0"),
                ROUTE.replace("bedrock-host=bedrock.example", "bedrock-host=::"),
                ROUTE.replace("route.local.bedrock-port=19132\n", ""),
                ROUTE + ROUTE.substring(ROUTE.indexOf("route.local.")).replace("route.local.", "route.other."),
                "#".repeat(16385)}) {
            assertThrows(Exception.class, () -> parse(invalid));
        }
    }

    @Test void configurationIsBoundedAndDuplicatePropertiesAreRejected() {
        assertThrows(Exception.class, () -> parse(ROUTE + "route.local.java-host=elsewhere.example\n"));
        StringBuilder tooMany = new StringBuilder("enabled=true\n");
        for (int i = 0; i < 65; i++) {
            tooMany.append("route.r").append(i).append(".java-host=p").append(i).append(".example\n")
                    .append("route.r").append(i).append(".java-port=25565\n")
                    .append("route.r").append(i).append(".bedrock-host=bedrock.example\n")
                    .append("route.r").append(i).append(".bedrock-port=19132\n");
        }
        assertThrows(Exception.class, () -> parse(tooMany.toString()));
    }
}
