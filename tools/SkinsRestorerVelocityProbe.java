package moe.ryuu.kyokouauth.tools.skinsrestorer;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.GameProfile;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.property.SkinIdentifier;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.velocity.SkinApplierVelocity;
import org.geysermc.floodgate.api.FloodgateApi;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Test-only companion plugin. Compiled against, but NEVER bundles, the pinned official SR Jar.
 * Uses the public storage API and the real native NORMAL listener, not a replacement listener.
 * Synthetic unsigned textures prove property transport/authority only, not client skin rendering.
 */
public final class SkinsRestorerVelocityProbe {
    private static final UUID STABLE = new UUID(0L, 2535412345678901L);
    private static final String JAVA_NAME = "VelOracle";
    private static final String BEDROCK_NAME = ".CryptoBedrock";
    private static final String MARKER = "kyokou-e2e:skins-marker";
    private static final String NONCE = "kyokouauth:connection-id";
    private static final String STABLE_SKIN = "kyokou-e2e-stable";
    private static final String WRONG_SKIN = "kyokou-e2e-offline-decoy";
    private static final String SIGNATURE = "e2e-synthetic-not-a-mojang-signature";
    private static final SkinProperty PROPERTY = skin('a');
    private static final SkinProperty DECOY = skin('b');
    private final ProxyServer proxy;
    private final Logger logger;
    private volatile Snapshot snapshot;
    private volatile boolean seeded;

    @Inject
    public SkinsRestorerVelocityProbe(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    private static SkinProperty skin(char texture) {
        String json = "{\"timestamp\":0,\"profileId\":\"00000000000000000000000000000001\","
            + "\"profileName\":\"SyntheticE2E\",\"textures\":{\"SKIN\":{\"url\":"
            + "\"https://textures.minecraft.net/texture/" + String.valueOf(texture).repeat(64) + "\"}}}";
        return SkinProperty.of(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)), SIGNATURE);
    }

    @Subscribe(order = PostOrder.LATE)
    public void initialize(ProxyInitializeEvent event) {
        guard(() -> {
            require(proxy.getPluginManager().getPlugin("skinsrestorer").isPresent(), "native plugin missing");
            verifyOfficialSource(SkinsRestorerProvider.class);
            verifyOfficialSource(SkinProperty.class);
            verifyOfficialSource(SkinApplierVelocity.class);
            SkinsRestorer api = SkinsRestorerProvider.get();
            require("15.12.6".equals(api.getVersion()), "unexpected native version");
            // The configured isolated FILE adapter persists these through SR's public API.
            api.getSkinStorage().setCustomSkinData(STABLE_SKIN, PROPERTY);
            api.getSkinStorage().setCustomSkinData(WRONG_SKIN, DECOY);
            boolean bedrock = System.getProperty("kyokou.e2e.scenario", "").startsWith("bedrock");
            if (!bedrock) api.getPlayerStorage().setSkinIdOfPlayer(STABLE, SkinIdentifier.ofCustom(STABLE_SKIN));
            UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + JAVA_NAME).getBytes(StandardCharsets.UTF_8));
            api.getPlayerStorage().setSkinIdOfPlayer(offline, SkinIdentifier.ofCustom(WRONG_SKIN));
            require(!offline.equals(STABLE), "decoy must use another UUID");
            require(bedrock ? api.getPlayerStorage().getSkinOfPlayer(STABLE).isEmpty()
                : api.getPlayerStorage().getSkinOfPlayer(STABLE).orElseThrow().equals(PROPERTY), "stable storage roundtrip");
            require(api.getPlayerStorage().getSkinOfPlayer(offline).orElseThrow().equals(DECOY), "offline storage roundtrip");
            nativeUpdateContract();
            seeded = true;
            logger.info("SR_E2E_SEEDED version=15.12.6 storage=FILE stable={} decoy={} synthetic=true", STABLE, offline);
        });
    }

    private void verifyOfficialSource(Class<?> type) throws Exception {
        Path expected = Path.of(System.getProperty("kyokou.e2e.skinsrestorerJar")).toRealPath();
        Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        require(actual.equals(expected), "SR class not loaded from the copied official plugin: " + type.getName());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(actual)));
        require(hash.equals(System.getProperty("kyokou.e2e.skinsrestorerSha256")), "native source checksum");
    }

    private void nativeUpdateContract() {
        GameProfile.Property nonce = new GameProfile.Property(NONCE, "11111111-2222-3333-4444-555555555555", "");
        GameProfile.Property marker = new GameProfile.Property(MARKER, "native-contract", "marker-signature");
        List<GameProfile.Property> original = List.of(nonce, texture(DECOY), marker, texture(DECOY));
        GameProfile profile = new GameProfile(STABLE, JAVA_NAME, original);
        // This native method has no dependency on the three applySkin collaborators. Do not call
        // applySkin or mock the implementation: this invokes the actual pinned profile transformer.
        GameProfile updated = new SkinApplierVelocity(null, null, null).updateProfileSkin(profile, PROPERTY);
        require(updated.getId().equals(STABLE) && updated.getName().equals(JAVA_NAME), "native transformer changed identity");
        require(withoutTextures(updated.getProperties()).equals(List.of(nonce, marker)), "native transformer lost nonce/marker");
        require(sameProperties(updated.getProperties().stream().filter(p -> p.getName().equals("textures")).toList(), List.of(texture(PROPERTY))),
            "native transformer did not replace all textures");
        require(profile.getProperties().equals(original), "native transformer mutated original profile");
        logger.info("SR_E2E_NATIVE_UPDATE nonce=preserved marker=preserved uuid=preserved name=preserved textures=replaced input=unchanged");
    }

    // Velocity 4.3.1: EARLY=16383, NORMAL=0. These observers bracket the official SR listener,
    // independent of plugin/listener registration order and including its asynchronous EventTask.
    @Subscribe(priority = 1)
    public void beforeNormal(GameProfileRequestEvent event) {
        guard(() -> {
            require(seeded && snapshot == null, "one seeded frontend is required per isolated process");
            GameProfile profile = event.getGameProfile();
            boolean bedrock = System.getProperty("kyokou.e2e.scenario", "").startsWith("bedrock");
            String expectedName = bedrock ? BEDROCK_NAME : JAVA_NAME;
            require(profile.getId().equals(STABLE), "BEFORE_NORMAL UUID is offline/provisional, not stable");
            require(profile.getName().equals(expectedName), "BEFORE_NORMAL name is not final");
            require(profile.getProperties().stream().noneMatch(p -> p.getName().equals(MARKER)), "duplicate fixture marker");
            List<GameProfile.Property> properties = new ArrayList<>(profile.getProperties());
            properties.add(new GameProfile.Property(MARKER, "native-listener-contract", "marker-signature"));
            if (!bedrock) {
                properties.removeIf(p -> p.getName().equals("textures"));
                properties.add(texture(DECOY));
            } else if (count(properties, "textures") == 0) {
                // The crypto fixture intentionally has no real skin-upload service. Supply a
                // nonempty synthetic skin at the public post-Floodgate profile seam, not in proof.
                properties.add(texture(DECOY));
            }
            FloodgateApi floodgate = FloodgateApi.getInstance();
            require(floodgate.isFloodgateId(profile.getId()), "fixture must exercise the UUID(0,xuid) shape collision");
            require(floodgate.isFloodgatePlayer(profile.getId()) == bedrock, "UUID shape was confused with a current native player");
            logger.info("SR_E2E_FLOODGATE_CLASSIFICATION uuidShape=true current={} path={}", bedrock, bedrock ? "BEDROCK" : "JAVA");
            event.setGameProfile(new GameProfile(profile.getId(), profile.getName(), properties));
            snapshot = new Snapshot(profile.getId(), profile.getName(), withoutTextures(properties),
                properties.stream().filter(p -> p.getName().equals("textures")).toList(), bedrock);
            logger.info("SR_E2E_BEFORE_NORMAL uuid={} name={} marker=1 nonce={} path={}", profile.getId(), profile.getName(),
                count(properties, NONCE), bedrock ? "BEDROCK" : "JAVA");
        });
    }

    @Subscribe(priority = -1)
    public void afterNormal(GameProfileRequestEvent event) {
        guard(() -> {
            Snapshot current = requireSnapshot();
            checkProfile(event.getGameProfile(), current);
            require(sameProperties(withoutTextures(event.getGameProfile().getProperties()), current.properties()),
                "native NORMAL listener dropped/reordered non-texture properties");
            logger.info("SR_E2E_AFTER_NORMAL uuid={} name={} marker=1 texture={} nonce=preserved path={}",
                current.id(), current.name(), current.bedrock() ? "bedrock-preserved" : "stable-authority", current.bedrock() ? "BEDROCK" : "JAVA");
        });
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void finalProfile(GameProfileRequestEvent event) {
        guard(() -> {
            Snapshot current = requireSnapshot();
            checkProfile(event.getGameProfile(), current);
            checkNonce(event.getGameProfile().getProperties(), current);
            logger.info("SR_E2E_FINAL_PROFILE uuid={} name={} marker=1 texture={} nonce={} path={}",
                current.id(), current.name(), current.bedrock() ? "bedrock-preserved" : "stable-authority",
                current.bedrock() ? "absent" : "present", current.bedrock() ? "BEDROCK" : "JAVA");
        });
    }

    @Subscribe(order = PostOrder.FIRST)
    public void login(LoginEvent event) {
        guard(() -> {
            Snapshot current = requireSnapshot();
            require(event.getPlayer().getUniqueId().equals(current.id()) && event.getPlayer().getUsername().equals(current.name()),
                "native LoginEvent identity changed");
            checkProperties(event.getPlayer().getGameProfileProperties(), current);
            checkNonce(event.getPlayer().getGameProfileProperties(), current);
            logger.info("SR_E2E_LOGIN_PROFILE uuid={} name={} marker=1 texture={} nonce={} path={}",
                current.id(), current.name(), current.bedrock() ? "bedrock-preserved" : "stable-authority",
                current.bedrock() ? "absent" : "present", current.bedrock() ? "BEDROCK" : "JAVA");
        });
    }

    @Subscribe(order = PostOrder.LAST)
    public void postLogin(PostLoginEvent event) {
        guard(() -> {
            Snapshot current = requireSnapshot();
            require(event.getPlayer().getUniqueId().equals(current.id()) && event.getPlayer().getUsername().equals(current.name()),
                "admitted native identity changed");
            checkProperties(event.getPlayer().getGameProfileProperties(), current);
            checkNonce(event.getPlayer().getGameProfileProperties(), current);
            logger.info("SR_E2E_NATIVE_POST_LOGIN uuid={} name={} path={}", current.id(), current.name(), current.bedrock() ? "BEDROCK" : "JAVA");
        });
    }

    private static void checkProfile(GameProfile profile, Snapshot current) {
        require(profile.getId().equals(current.id()) && profile.getName().equals(current.name()), "native listener changed UUID/name");
        checkProperties(profile.getProperties(), current);
    }

    private static void checkProperties(List<GameProfile.Property> properties, Snapshot current) {
        require(count(properties, MARKER) == 1 && properties.stream().anyMatch(p -> p.getName().equals(MARKER)
            && p.getValue().equals("native-listener-contract") && "marker-signature".equals(p.getSignature())), "fixture marker lost");
        List<GameProfile.Property> expected = current.bedrock() ? current.textures() : List.of(texture(PROPERTY));
        require(sameProperties(properties.stream().filter(p -> p.getName().equals("textures")).toList(), expected),
            "SR lost authoritative Java textures or overwrote the current Bedrock profile texture");
    }

    private static void checkNonce(List<GameProfile.Property> properties, Snapshot current) {
        long expected = current.bedrock() ? 0 : 1;
        require(count(properties, NONCE) == expected, "connection nonce missing/duplicated or added to Bedrock");
        if (expected == 1) {
            GameProfile.Property nonce = properties.stream().filter(p -> p.getName().equals(NONCE)).findFirst().orElseThrow();
            UUID.fromString(nonce.getValue());
            List<GameProfile.Property> before = current.properties().stream().filter(p -> p.getName().equals(NONCE)).toList();
            require(before.isEmpty() || sameProperties(before, List.of(nonce)), "current connection nonce changed across native SR");
        }
    }

    private Snapshot requireSnapshot() {
        require(snapshot != null, "pre-NORMAL event was not observed");
        return snapshot;
    }

    private static long count(List<GameProfile.Property> properties, String name) {
        return properties.stream().filter(p -> p.getName().equals(name)).count();
    }

    private static List<GameProfile.Property> withoutTextures(List<GameProfile.Property> properties) {
        return properties.stream().filter(p -> !p.getName().equals("textures")).toList();
    }

    private static GameProfile.Property texture(SkinProperty property) {
        return new GameProfile.Property("textures", property.getValue(), property.getSignature());
    }

    // Native Property intentionally has no equals; cloned properties must be compared by all fields.
    private static boolean sameProperties(List<GameProfile.Property> actual, List<GameProfile.Property> expected) {
        return actual.stream().map(p -> new PropertyValue(p.getName(), p.getValue(), p.getSignature())).toList()
            .equals(expected.stream().map(p -> new PropertyValue(p.getName(), p.getValue(), p.getSignature())).toList());
    }

    private record PropertyValue(String name, String value, String signature) {}

    private void guard(Checked action) {
        try {
            action.run();
        } catch (Throwable failure) {
            logger.error("SR_E2E_FAILED", failure);
            throw new IllegalStateException("Isolated native SkinsRestorer contract failed", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private record Snapshot(UUID id, String name, List<GameProfile.Property> properties,
                            List<GameProfile.Property> textures, boolean bedrock) {}
}
