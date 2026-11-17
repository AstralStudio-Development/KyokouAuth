package moe.ryuu.kyokouauth.geyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.java.ServerTransferEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Optional public-API extension for the verified Geyser build; no authentication integration. */
public final class KyokouRoleTransferExtension implements Extension {
    private volatile TransferRoutes routes;

    @Subscribe
    public synchronized void onInitialize(GeyserPreInitializeEvent event) {
        closeRoutes();
        try {
            // Public provider metadata, not internal session fields, keys or server configuration.
            try (InputStream metadata = geyserApi().getClass().getResourceAsStream("/git.properties")) {
                Properties build = new Properties();
                if (metadata == null) throw new IllegalStateException("Geyser build metadata absent");
                build.load(metadata);
                verifyBuild(build);
            }
            Path directory = dataFolder();
            Files.createDirectories(directory);
            Path config = directory.resolve("transfer-routes.properties");
            if (!Files.exists(config)) {
                try (InputStream defaults = getClass().getResourceAsStream("/transfer-routes.properties")) {
                    if (defaults == null) throw new IllegalStateException("Default transfer routes absent");
                    Files.copy(defaults, config);
                }
            }
            try (InputStream input = Files.newInputStream(config)) { routes = TransferRoutes.load(input); }
            logger().info("KYOKOU_ROLE_TRANSFER_READY routes=" + routes.routeCount());
        } catch (Exception | LinkageError failure) {
            closeRoutes();
            logger().error("KyokouAuth transfer extension disabled: requires exact Geyser 2.12.1-b1303 and valid explicit routes");
            disable();
        }
    }

    static void verifyBuild(Properties build) {
        if (!"1303".equals(build.getProperty("git.build.number")) ||
                !"2.12.1-b1303 (git-master-440be82)".equals(build.getProperty("git.build.version")) ||
                !"440be82b0b2def7211504226d6ea5c37911a5643".equals(build.getProperty("git.commit.id"))) {
            throw new IllegalStateException("Unsupported Geyser build");
        }
    }

    @Subscribe
    public void onServerTransfer(ServerTransferEvent event) {
        TransferRoutes current = routes;
        if (current != null && isEnabled()) current.accept(event);
    }

    @Subscribe
    public synchronized void onShutdown(GeyserShutdownEvent event) { closeRoutes(); }

    private void closeRoutes() {
        TransferRoutes current = routes;
        routes = null;
        if (current != null) current.close();
    }
}
