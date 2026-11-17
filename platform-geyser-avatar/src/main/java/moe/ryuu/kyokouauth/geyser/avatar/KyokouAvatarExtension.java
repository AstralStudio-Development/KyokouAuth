package moe.ryuu.kyokouauth.geyser.avatar;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Optional inventory presentation only. Never changes Xbox validation, Floodgate or role authority. */
public final class KyokouAvatarExtension implements Extension {
    private volatile AvatarRuntime runtime;
    private AvatarStatusServer status;

    @Subscribe
    public synchronized void onInitialize(GeyserPreInitializeEvent event) {
        closeResources();
        try {
            Path directory = dataFolder();
            Files.createDirectories(directory);
            Path config = directory.resolve("avatar.properties");
            if (!Files.exists(config)) try (InputStream defaults = getClass().getResourceAsStream("/avatar.properties")) {
                if (defaults == null) throw new IllegalStateException("Avatar defaults absent");
                Files.copy(defaults, config);
            }
            AvatarConfig settings;
            try (InputStream input = Files.newInputStream(config)) { settings = AvatarConfig.load(input); }
            if (!settings.enabled()) return;
            try (InputStream input = geyserApi().getClass().getResourceAsStream("/git.properties")) {
                if (input == null) throw new IllegalStateException("Geyser metadata absent");
                Properties build = new Properties();
                build.load(input);
                verifyBuild(build);
            }
            AvatarRuntime next = new AvatarRuntime(settings, directory.resolve("avatar-packs"));
            AvatarStatusServer server = null;
            try {
                server = new AvatarStatusServer(settings.statusPort(), next.offers(), next.executor());
                server.start();
                runtime = next;
                status = server;
            } catch (Exception failure) {
                if (server != null) server.close();
                next.close();
                throw failure;
            }
            logger().info("KYOKOU_AVATAR_READY status=offered-only");
        } catch (Exception | LinkageError failure) {
            closeResources();
            logger().error("KyokouAuth avatar presentation disabled: requires exact Geyser 2.12.1-b1303, explicit loopback ports and bounded owned cache");
        }
    }

    static void verifyBuild(Properties build) {
        if (!"1303".equals(build.getProperty("git.build.number")) ||
                !"2.12.1-b1303 (git-master-440be82)".equals(build.getProperty("git.build.version")) ||
                !"440be82b0b2def7211504226d6ea5c37911a5643".equals(build.getProperty("git.commit.id")))
            throw new IllegalStateException("Unsupported avatar SDK build");
    }

    @Subscribe
    public void onDefineItems(GeyserDefineCustomItemsEvent event) {
        AvatarRuntime current = runtime;
        if (current == null || current.isClosed() || !isEnabled()) return;
        try {
            RoleAvatarDefinitions.register(event::register);
            logger().info("KYOKOU_AVATAR_DEFINITIONS_READY count=2");
        } catch (Exception | LinkageError failure) { closeResources(); logger().error("KyokouAuth avatar definitions unavailable"); }
    }
    @Subscribe
    public void onDefinePacks(GeyserDefineResourcePacksEvent event) {
        AvatarRuntime current = runtime;
        if (current == null || current.isClosed() || !isEnabled()) return;
        try {
            AvatarRuntime.BasePackEvidence pack = current.verifyBasePack();
            logger().info("KYOKOU_AVATAR_BASE_PACK_READY sha256=" + pack.sha256() + " bytes=" + pack.bytes());
        } catch (Exception | LinkageError failure) { closeResources(); logger().error("KyokouAuth avatar base pack unavailable"); }
    }
    @Subscribe
    public void onLoad(SessionLoadResourcePacksEvent event) {
        AvatarRuntime current = runtime;
        if (current != null && !current.isClosed() && isEnabled()) current.onLoad(event);
    }
    @Subscribe
    public void onDisconnect(SessionDisconnectEvent event) {
        AvatarRuntime current = runtime;
        if (current != null) current.disconnect(event.connection());
    }
    @Subscribe
    public synchronized void onShutdown(GeyserShutdownEvent event) { closeResources(); }

    private synchronized void closeResources() {
        if (status != null) { status.close(); status = null; }
        if (runtime != null) try { runtime.close(); } catch (Exception ignored) { }
        // Keep the closed runtime reachable for exact SessionDisconnect lease cleanup. Do not
        // overwrite/delete files that a still-active native resource-pack codec may be reading.
    }
}
