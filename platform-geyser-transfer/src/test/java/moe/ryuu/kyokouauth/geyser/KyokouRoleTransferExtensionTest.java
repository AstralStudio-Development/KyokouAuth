package moe.ryuu.kyokouauth.geyser;

import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.java.ServerTransferEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class KyokouRoleTransferExtensionTest {
    @Test void fixedOfficialVersionIsRequiredAndMissingOrChangedMetadataCannotEnableRouting() {
        Properties build = new Properties();
        assertThrows(IllegalStateException.class, () -> KyokouRoleTransferExtension.verifyBuild(build));
        build.setProperty("git.build.number", "1303");
        build.setProperty("git.build.version", "2.12.1-b1303 (git-master-440be82)");
        build.setProperty("git.commit.id", "440be82b0b2def7211504226d6ea5c37911a5643");
        assertDoesNotThrow(() -> KyokouRoleTransferExtension.verifyBuild(build));
        for (String property : build.stringPropertyNames()) {
            Properties changed = new Properties();
            changed.putAll(build);
            changed.setProperty(property, "not-the-verified-build");
            assertThrows(IllegalStateException.class, () -> KyokouRoleTransferExtension.verifyBuild(changed));
        }
    }

    @Test void uninitializedAndShutdownExtensionNeverInspectsPlayerOrMutatesTransfer() {
        GeyserConnection connection = (GeyserConnection) Proxy.newProxyInstance(
                GeyserConnection.class.getClassLoader(), new Class<?>[]{GeyserConnection.class},
                (proxy, method, args) -> { throw new AssertionError("Unexpected identity access"); });
        ServerTransferEvent event = new ServerTransferEvent(connection, "proxy.example", 25565, Map.of());
        String host = event.bedrockHost();
        int port = event.bedrockPort();
        KyokouRoleTransferExtension extension = new KyokouRoleTransferExtension();
        extension.onServerTransfer(event);
        extension.onShutdown(null);
        extension.onShutdown(null);
        extension.onServerTransfer(event);
        assertEquals(host, event.bedrockHost());
        assertEquals(port, event.bedrockPort());
    }
}
