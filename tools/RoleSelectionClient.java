package moe.ryuu.kyokouauth.tools.roles;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import org.geysermc.floodgate.crypto.AesCipher;
import org.geysermc.floodgate.crypto.AesKeyProducer;
import org.geysermc.floodgate.crypto.Base64Topping;
import org.geysermc.floodgate.util.BedrockData;
import org.geysermc.floodgate.util.LinkedPlayer;
import org.geysermc.floodgate.pluginmessage.PluginMessageChannels;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.server.ServerAdapter;
import org.geysermc.mcprotocollib.network.event.server.SessionAddedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.server.NetworkServer;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.ClientListener;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.ResolvableProfile;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.handshake.HandshakeIntent;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundStoreCookiePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundTransferPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.clientbound.ClientboundCookieRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.serverbound.ServerboundCookieResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.handshake.serverbound.ClientIntentionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundUpdateEnabledFeaturesPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundUpdateTagsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundCustomQueryPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundCustomQueryAnswerPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundHelloPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundLoginAcknowledgedPacket;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Proxy;
import java.net.HttpURLConnection;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Set;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Complete official SDK only: no handwritten Minecraft packet IDs, layout or framing. */
public final class RoleSelectionClient {
    private static final Gson JSON = new Gson();
    private static final Key COOKIE = Key.key("kyokouauth:role_transfer");
    private static final Key FORM = Key.key(PluginMessageChannels.FORM);
    private static final Key FORM_ACK = Key.key("kyokouauth:role_e2e_fg_ack");
    private static final Key FINAL_DONE = Key.key("kyokouauth:role_e2e_final_done");
    private static final short UNKNOWN_FORM = -12345;
    private static final String FLOODGATE_KICK = "Only Floodgate players can send floodgate messages!";
    private final long XUID;
    private final UUID U;
    private static final UUID J = UUID.fromString("12345678-1234-4234-9234-123456789abc");
    private static final String U_NAME = "URole";
    private static final String J_NAME = "JRole";
    private static final UUID CLAIMED = UUID.fromString("87654321-4321-4321-9321-cba987654321");
    private static final Map<Key, byte[]> COOKIES = new ConcurrentHashMap<>();
    private final Map<UUID, String> CANARIES;
    private final Set<String> nativeProofFingerprints = ConcurrentHashMap.newKeySet();
    private final boolean avatars;
    private final String avatarCase;
    private JsonObject avatarSnapshot;
    private boolean selectorCaptured;

    private final Path run;
    private final JsonObject fixture;
    private final int port;
    private final boolean floodgate;
    private final boolean chooseU;
    private final boolean floodgateMessages;

    private RoleSelectionClient(Path run, int port) throws Exception {
        Path root = Path.of(System.getProperty("kyokou.roleE2e.root")).toRealPath();
        this.run = run.toRealPath();
        require(this.run.startsWith(root) && !this.run.equals(root), "Fixture must remain under isolated root");
        fixture = JSON.fromJson(Files.readString(this.run.resolve("isolated-role-fixture.json")), JsonObject.class);
        require(fixture.get("protocol").getAsInt() == 777, "Only the pinned protocol-777 fixture is supported");
        require(port > 0 && port <= 65535, "Invalid loopback port");
        this.port = port;
        floodgate = fixture.get("mode").getAsString().equals("floodgate");
        chooseU = fixture.get("role").getAsString().equals("microsoft");
        floodgateMessages = fixture.has("floodgateMessages") && fixture.get("floodgateMessages").getAsBoolean();
        XUID = fixture.has("xuid") ? fixture.get("xuid").getAsLong() : 2535412345678901L;
        U = new UUID(0L, XUID);
        require(U.toString().equals(fixture.get("roleU").getAsString()), "Fixture XUID/U do not match");
        CANARIES = Map.of(U, "independent-u-data", J, "independent-j-data");
        avatars = fixture.has("avatars") && fixture.get("avatars").getAsBoolean();
        avatarCase = avatars ? fixture.get("avatarCase").getAsString() : "none";
        require(!avatars || floodgate && floodgateMessages, "Avatar tests require strong official Floodgate messages");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("client") || args[0].equals("backend"))) {
            throw new IllegalArgumentException("Expected client|backend isolated-run-directory loopback-port");
        }
        RoleSelectionClient probe = new RoleSelectionClient(Path.of(args[1]), Integer.parseInt(args[2]));
        if (args[0].equals("backend")) probe.backend(); else probe.client();
    }

    private void client() throws Exception {
        require(COOKIES.isEmpty(), "No cross-run cookie state is permitted");
        if (floodgateMessages && !floodgate) { forgedJava(); return; }
        Attempt visual = null;
        if (avatars && !avatarCase.equals("hit")) {
            visual = new Attempt(false, false, true);
            try {
                visual.connect(HandshakeIntent.LOGIN);
                require(visual.transferred.await(30, TimeUnit.SECONDS), "Avatar MISS never received bounded native visual Transfer");
                visual.checkFailure();
                require(visual.visualTransferObserved.get() && visual.loginFinished.get() && visual.playerLoaded.get(), "Visual refresh must occur in the original native HELD PLAY socket");
                require(!visual.menuValidated.get() && !visual.clicked.get() && visual.cookieRequests.get() == 0 && COOKIES.isEmpty(),
                    "Visual Transfer must have no menu, role click or Java role cookie");
                avatarSnapshot = readAvatarSnapshot(XUID);
                verifyAvatarSnapshot(avatarSnapshot);
                offerPreparedSnapshot(avatarSnapshot.get("fingerprint").getAsString(), avatarCase.equals("fallback"));
            } finally { visual.close(); }
        } else if (avatars) {
            avatarSnapshot = readAvatarSnapshot(XUID);
            verifyAvatarSnapshot(avatarSnapshot);
        }
        Attempt first = new Attempt(false, false);
        try {
            first.connect(HandshakeIntent.LOGIN);
            boolean completed = first.transferred.await(30, TimeUnit.SECONDS);
            first.checkFailure();
            require(completed && first.transferObserved.get(), "Chooser never received native Transfer; denial=" + first.denial.get());
            require(first.playerLoaded.get() && first.batchAcknowledged.get() && first.teleportAcknowledged.get(), "Native PLAY readiness acknowledgements were not sent");
            require(first.menuValidated.get() && first.clicked.get(), "Native GUI was not decoded and clicked");
            if (floodgateMessages) require(first.formAcknowledged.get(), "GUI click must follow the official Floodgate handler barrier");
            if (avatars) {
                require(first.avatarUnavailable.get() == avatarCase.equals("fallback"), "Fallback must be explicitly unavailable, never called rendered/applied");
                require(visual == null || !visual.wireSession.get().equals(first.wireSession.get()), "Visual retry reused the old native socket");
            }
            require(first.loginFinished.get(), "Chooser lacked a real native Player login");
            require(first.cookieRequests.get() == (floodgate ? 0 : 1), "Initial LOGIN cookie request count differs");
            if (floodgate) require(COOKIES.isEmpty(), "Floodgate handoff must not mint a Java cookie");
            else require(COOKIES.size() == 1 && COOKIES.get(COOKIE).length == 32, "Exactly one scoped 32-byte role cookie required");
        } finally { first.close(); }
        Attempt resumed = new Attempt(true, false);
        try {
            // Real Java transfers use TRANSFER; a new Geyser-side Java channel starts a fresh LOGIN.
            resumed.connect(floodgate ? HandshakeIntent.LOGIN : HandshakeIntent.TRANSFER);
            require(resumed.closed.await(30, TimeUnit.SECONDS), "Final native login did not reach the bounded backend stop");
            resumed.checkFailure();
            require(resumed.loginFinished.get() && resumed.remoteDisconnect.get(), "Final login lacked native success or backend termination");
            if (floodgateMessages) {
                require(resumed.finalPlay.get() && resumed.formAcknowledged.get(), "The final new socket did not pass the official handler in real PLAY");
                require(resumed.denial.get() != null && resumed.denial.get().contains("ROLE_CANARY_"), "Final PLAY ended before the bounded authorized backend canary");
            }
            require(!resumed.menuValidated.get() && !resumed.clicked.get(), "Final handoff must not reopen a chooser");
            require(resumed.cookieRequests.get() == (floodgate ? 0 : 1), "Final LOGIN cookie request count differs");
        } finally { resumed.close(); }
        Attempt replay = new Attempt(false, true);
        try {
            replay.connect(floodgate ? HandshakeIntent.LOGIN : HandshakeIntent.TRANSFER);
            if (floodgate) {
                require(replay.menuReady.await(30, TimeUnit.SECONDS), "Consumed hint must require a fresh chooser");
                replay.checkFailure();
                require(!replay.clicked.get() && replay.menuValidated.get(), "Hint replay must not auto-authorize");
            } else {
                require(replay.closed.await(15, TimeUnit.SECONDS), "Replayed cookie was not rejected");
                replay.checkFailure();
                require(replay.remoteDisconnect.get() && !replay.loginFinished.get(), "Replay must be denied before native login success");
                require(!replay.menuValidated.get() && replay.cookieRequests.get() == 1, "Invalid cookie must not downgrade to a fresh chooser");
            }
        } finally { replay.close(); COOKIES.clear(); }
        System.out.println("ROLE_CLIENT_RESULT " + JSON.toJson(Map.ofEntries(
            Map.entry("mode", floodgate ? "floodgate" : "java"), Map.entry("role", chooseU ? "microsoft" : "premium"),
            Map.entry("newSocket", true), Map.entry("playReady", first.playerLoaded.get()), Map.entry("menuSlots", 63), Map.entry("heads", List.of(12, 14)), Map.entry("nativeFinalProfile", true),
            Map.entry("cookieReplayRejected", !floodgate), Map.entry("hintReplayRequiresChooser", floodgate),
            Map.entry("realMicrosoftVerified", false), Map.entry("realBedrockVerified", false), Map.entry("renderingVerified", false),
            Map.entry("skinPolicy", fixture.get("skinPolicy").getAsString()),
            Map.entry("floodgateMessages", floodgateMessages), Map.entry("chooserFormAcknowledged", first.formAcknowledged.get()),
            Map.entry("finalFormAcknowledged", resumed.formAcknowledged.get()), Map.entry("finalPlay", resumed.finalPlay.get()),
            Map.entry("avatars", avatars ? avatarEvidence(visual, first, resumed) : Map.of("enabled", false)))));
    }

    private void forgedJava() throws Exception {
        Attempt forged = new Attempt(false, false);
        try {
            forged.connect(HandshakeIntent.LOGIN);
            require(forged.closed.await(30, TimeUnit.SECONDS), "Java Floodgate impersonation was not disconnected");
            forged.checkFailure();
            require(forged.loginFinished.get() && forged.playerLoaded.get() && forged.menuValidated.get() && forged.formSent.get(),
                "Java negative control did not send the same valid Floodgate message from real HELD chooser PLAY");
            require(forged.remoteDisconnect.get() && forged.denial.get() != null && forged.denial.get().contains(FLOODGATE_KICK),
                "Java negative control must receive the exact official source rejection, not a timeout or parse error: " + forged.denial.get());
            require(!forged.formAcknowledged.get() && !forged.clicked.get() && !forged.transferObserved.get(),
                "A forged Java message received a Floodgate success barrier or role transfer");
        } finally { forged.close(); COOKIES.clear(); }
        System.out.println("ROLE_CLIENT_RESULT " + JSON.toJson(Map.ofEntries(
            Map.entry("mode", "java"), Map.entry("role", chooseU ? "microsoft" : "premium"),
            Map.entry("floodgateMessages", true), Map.entry("forgedFloodgateRejected", true),
            Map.entry("chooserFormSent", true), Map.entry("officialKick", FLOODGATE_KICK),
            Map.entry("newSocket", false), Map.entry("playReady", true),
            Map.entry("realMicrosoftVerified", false), Map.entry("realBedrockVerified", false))));
    }

    private final class Attempt {
        private final boolean finalLogin;
        private final boolean replay;
        private final boolean visualStage;
        private final AtomicBoolean visualTransferObserved = new AtomicBoolean();
        private final AtomicBoolean avatarUnavailable = new AtomicBoolean();
        // LoginFinished's wire session UUID is not Velocity Player.sessionId.
        // The actual TCP tuple correlates this socket with the proxy's native ID.
        private final AtomicReference<UUID> wireSession = new AtomicReference<>();
        private volatile int localPort;
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch transferred = new CountDownLatch(1);
        private final CountDownLatch menuReady = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean loginFinished = new AtomicBoolean();
        private final AtomicBoolean remoteDisconnect = new AtomicBoolean();
        private final AtomicBoolean menuValidated = new AtomicBoolean();
        private final AtomicBoolean clicked = new AtomicBoolean();
        private final AtomicBoolean transferObserved = new AtomicBoolean();
        private final AtomicBoolean batchAcknowledged = new AtomicBoolean();
        private final AtomicBoolean teleportAcknowledged = new AtomicBoolean();
        private final AtomicBoolean playerLoaded = new AtomicBoolean();
        private final AtomicReference<String> denial = new AtomicReference<>();
        private final AtomicInteger cookieRequests = new AtomicInteger();
        private final AtomicBoolean formSent = new AtomicBoolean();
        private final AtomicBoolean finalPlay = new AtomicBoolean();
        private final AtomicBoolean formAcknowledged = new AtomicBoolean();
        private volatile int container = -1;
        private volatile int menuState = -1;
        private ClientNetworkSession session;

        Attempt(boolean finalLogin, boolean replay) { this(finalLogin, replay, false); }
        Attempt(boolean finalLogin, boolean replay, boolean visualStage) {
            this.finalLogin = finalLogin; this.replay = replay; this.visualStage = visualStage;
        }

        void connect(HandshakeIntent intent) throws Exception {
            MinecraftProtocol protocol = new MinecraftProtocol(new GameProfile(CLAIMED, floodgate ? "R" : U_NAME), null);
            require(protocol.getCodec().getProtocolVersion() == 777, "Complete SDK classpath is not protocol 777");
            protocol.setUseDefaultListeners(false);
            session = ClientNetworkSessionFactory.factory().setRemoteSocketAddress(new InetSocketAddress("127.0.0.1", port))
                .setProtocol(protocol).create();
            String host = floodgate ? encryptedHost() : "127.0.0.1";
            if (floodgate) require(nativeProofFingerprints.add(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(host.getBytes(StandardCharsets.UTF_8)))),
                "Every new Floodgate socket must present fresh official AES proof, not a cached chooser proof");
            session.setFlag(MinecraftConstants.CLIENT_HOST, host);
            session.setFlag(MinecraftConstants.CLIENT_PORT, port);
            session.setFlag(MinecraftConstants.FOLLOW_TRANSFERS, false);
            session.addListener(new SessionAdapter() {
                @Override public void packetReceived(Session source, Packet packet) {
                    try { receive(source, packet); } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        source.disconnect("isolated role assertion failed");
                        closed.countDown(); transferred.countDown(); menuReady.countDown();
                    }
                }
                @Override public void packetError(PacketErrorEvent event) {
                    failure.compareAndSet(null, event.getCause());
                }
                @Override public void disconnected(DisconnectedEvent event) {
                    if (event.getCause() != null) failure.compareAndSet(null, event.getCause());
                    closed.countDown(); transferred.countDown(); menuReady.countDown();
                }
            });
            session.addListener(new ClientListener(intent));
            session.connect(true);
        }

        void receive(Session source, Packet packet) throws Exception {
            if (packet instanceof ClientboundCookieRequestPacket request) {
                require(request.getKey().equals(COOKIE), "Unexpected cookie scope");
                require(!floodgate, "Native Floodgate proof must not request a Java resumption cookie");
                cookieRequests.incrementAndGet();
                byte[] value = COOKIES.get(request.getKey());
                // Missing cookies are null, never empty arrays or synthesized credentials.
                source.send(new ServerboundCookieResponsePacket(request.getKey(), value == null ? null : value.clone()));
            } else if (packet instanceof ClientboundStoreCookiePacket store) {
                require(!floodgate && !finalLogin && !replay && store.getKey().equals(COOKIE), "Unexpected cookie store");
                require(store.getPayload().length == 32, "Role cookie must be exactly 32 bytes");
                COOKIES.put(store.getKey(), store.getPayload().clone());
            } else if (packet instanceof ClientboundLoginFinishedPacket success) {
                GameProfile profile = success.getProfile();
                if (finalLogin) {
                    require(expectedId().equals(profile.getId()) && expectedName().equals(profile.getName()), "Final native UUID/name differ from selected role");
                } else {
                    require(floodgate || !replay, "Replayed cookie reached native login success");
                    // Java must use a provisional identity. A verified Floodgate chooser must
                    // remain SDK-addressable; its native identity is not backend authorization.
                    if (!floodgate) {
                        require(!U.equals(profile.getId()) && !J.equals(profile.getId()), "Java chooser occupied a real role UUID");
                        require(!U_NAME.equals(profile.getName()) && !J_NAME.equals(profile.getName()) && profile.getName().length() == 16,
                            "Java chooser occupied a real role name");
                    }
                }
                require(wireSession.compareAndSet(null, success.getSessionId()), "Duplicate native LoginFinished");
                require(source.getLocalAddress() instanceof InetSocketAddress, "Native SDK socket lacks an actual local TCP address");
                localPort = ((InetSocketAddress) source.getLocalAddress()).getPort();
                if (avatars) System.out.println("ROLE_CLIENT_AVATAR_SOCKET phase=" + (visualStage ? "visual" : finalLogin ? "final" : replay ? "replay" : "chooser")
                    + " wireSession=" + success.getSessionId() + " localPort=" + localPort + " freshOfficialCipher=" + floodgate);
                loginFinished.set(true);
            } else if (packet instanceof ClientboundChunkBatchFinishedPacket batch) {
                require(batch.getBatchSize() > 0, "Expected an actual initial world chunk batch");
                source.send(new ServerboundChunkBatchReceivedPacket(8.0f));
                batchAcknowledged.set(true);
                announceLoaded(source);
            } else if (packet instanceof ClientboundPlayerPositionPacket position) {
                // Protocol 777's official confirmation includes the received position and rotations.
                source.send(new ServerboundAcceptTeleportationPacket(position.getId(), position.getPosition().getX(),
                    position.getPosition().getY(), position.getPosition().getZ(), position.getYRot(), position.getXRot()));
                teleportAcknowledged.set(true);
                announceLoaded(source);
            } else if (packet instanceof ClientboundOpenScreenPacket open) {
                require(!visualStage && !finalLogin && (!replay || floodgate), "Unexpected role GUI before visual reload or after final handoff");
                require(open.getType() == ContainerType.GENERIC_9X3 && open.getContainerId() > 0, "Expected a native three-row menu");
                require(open.getTitle() != null && plain(open.getTitle()).equals("Choose a role"), "Native role menu title differs from the isolated English template");
                require(open.getTitle().decoration(TextDecoration.ITALIC) != TextDecoration.State.TRUE, "Role menu title must not be italic");
                container = open.getContainerId();
            } else if (packet instanceof ClientboundContainerSetContentPacket content && content.getContainerId() == container) {
                require(content.getStateId() >= 0 && content.getItems().length == 27 + 36 && content.getCarriedItem() == null,
                    "Invalid native menu state/content size");
                validateHead(content.getItems()[12], true);
                validateHead(content.getItems()[14], false);
                for (int slot = 0; slot < content.getItems().length; slot++) {
                    if (slot != 12 && slot != 14) require(content.getItems()[slot] == null, "Unexpected item in isolated role menu");
                }
                if (!replay) captureSelector(content);
                menuState = content.getStateId();
                menuValidated.set(true); menuReady.countDown();
                if (!replay && floodgateMessages && formSent.compareAndSet(false, true)) {
                    require(playerLoaded.get(), "Floodgate message must be sent in ready chooser PLAY, not LOGIN");
                    source.send(new ServerboundCustomPayloadPacket(FORM, formResponse()));
                    System.out.println("ROLE_CLIENT_FG_MESSAGE_SENT phase=chooser channel=" + FORM + " formId=" + UNKNOWN_FORM + " payload=valid-json-0");
                } else if (!replay && !floodgateMessages) click(source);
            } else if (packet instanceof ClientboundCustomPayloadPacket response && response.getChannel().equals(FORM_ACK)) {
                require(floodgate && floodgateMessages && formSent.get() && !replay && (finalLogin ? finalPlay.get() : menuValidated.get()), "Unsolicited Floodgate handler barrier");
                require(Arrays.equals(response.getData(), formResponse()), "Floodgate barrier payload differs from the valid response");
                require(formAcknowledged.compareAndSet(false, true), "Duplicate Floodgate handler barrier");
                System.out.println("ROLE_CLIENT_FG_MESSAGE_ACK phase=" + (finalLogin ? "final" : "chooser"));
                if (finalLogin) source.send(new ServerboundCustomPayloadPacket(FINAL_DONE, formResponse())); else click(source);
            } else if (packet instanceof ClientboundLoginPacket && floodgateMessages && finalLogin) {
                require(floodgate && !replay && loginFinished.get(), "Unexpected real recording-backend PLAY login");
                MinecraftProtocol protocol = source.getPacketProtocol();
                require(protocol.getOutboundState() == ProtocolState.GAME, "Final Floodgate message cannot be sent during LOGIN or CONFIGURATION");
                finalPlay.set(true);
                require(formSent.compareAndSet(false, true), "Duplicate final Floodgate probe");
                source.send(new ServerboundCustomPayloadPacket(FORM, formResponse()));
                System.out.println("ROLE_CLIENT_FG_MESSAGE_SENT phase=final channel=" + FORM + " formId=" + UNKNOWN_FORM + " payload=valid-json-0");
            } else if (packet instanceof ClientboundSystemChatPacket chat && avatars) {
                if (plain(chat.getContent()).contains("Avatar unavailable")) avatarUnavailable.set(true);
            } else if (packet instanceof ClientboundTransferPacket transfer) {
                require(!finalLogin && !replay && (clicked.get() || avatars && visualStage && !menuValidated.get()),
                    "Transfer before a trusted native click, or more than the one allowed visual reload");
                if (visualStage) require(visualTransferObserved.compareAndSet(false, true), "A visual stage transferred more than once");
                require(transfer.getHost().equals("127.0.0.1") && transfer.getPort() == port, "Transfer target is outside the configured loopback proxy");
                transferObserved.set(true);
                transferred.countDown();
            } else if (packet instanceof ClientboundCustomQueryPacket query) {
                source.send(new ServerboundCustomQueryAnswerPacket(query.getMessageId(), null));
            } else if (packet instanceof ClientboundLoginDisconnectPacket disconnect) {
                remoteDisconnect.set(true); denial.set(disconnect.getReason().toString());
            } else if (packet instanceof ClientboundDisconnectPacket disconnect) {
                remoteDisconnect.set(true); denial.set(disconnect.getReason().toString());
            }
        }

        void click(Session source) {
            require(menuValidated.get() && container > 0 && menuState >= 0, "No validated GUI exists");
            if (clicked.compareAndSet(false, true)) source.send(new ServerboundContainerClickPacket(container, menuState, chooseU ? 12 : 14,
                ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK, null, Map.of()));
        }

        void announceLoaded(Session source) {
            if (batchAcknowledged.get() && teleportAcknowledged.get() && playerLoaded.compareAndSet(false, true)) {
                source.send(ServerboundPlayerLoadedPacket.INSTANCE);
                System.out.println("ROLE_CLIENT_READY protocol=777 chunkBatch=acknowledged teleport=acknowledged playerLoaded=sent");
            }
        }

        void checkFailure() { if (failure.get() != null) throw new AssertionError("Native SDK session failed", failure.get()); }
        void close() {
            if (session == null) return;
            session.disconnect("isolated role fixture complete");
            if (session.getChannel() != null) session.getChannel().close().syncUninterruptibly();
        }
    }

    private JsonObject readJson(URI endpoint) throws Exception {
        require(endpoint.getScheme().equals("http") && endpoint.getHost().equals("127.0.0.1") && endpoint.getPort() > 0,
            "Presentation probes must remain explicit loopback HTTP");
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(800); connection.setReadTimeout(1500); connection.setInstanceFollowRedirects(false);
        try {
            require(connection.getResponseCode() == 200, "Presentation endpoint was not ready: " + endpoint.getPath());
            byte[] bytes;
            try (var stream = connection.getInputStream()) { bytes = stream.readNBytes(24577); }
            require(bytes.length > 0 && bytes.length <= 24576, "Presentation HTTP response exceeds its budget");
            return JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
        } finally { connection.disconnect(); }
    }

    private JsonObject readAvatarSnapshot(long xuid) throws Exception {
        JsonObject snapshot = readJson(URI.create("http://127.0.0.1:" + fixture.get("avatarBridgePort").getAsInt() + "/v1/avatars?xuid=" + xuid));
        require(snapshot.get("schema").getAsInt() == 1 && snapshot.get("state").getAsString().equals("ready"), "Real avatar bridge did not return a schema-1 prepared snapshot");
        if (xuid == XUID) Files.writeString(run.resolve("avatar-snapshot-observed.json"), JSON.toJson(snapshot));
        return snapshot;
    }

    private void verifyAvatarSnapshot(JsonObject snapshot) throws Exception {
        byte[] u = Base64.getDecoder().decode(snapshot.get("microsoft_png").getAsString());
        byte[] j = Base64.getDecoder().decode(snapshot.get("premium_png").getAsString());
        require(snapshot.get("microsoft_default").getAsBoolean() && !snapshot.get("premium_default").getAsBoolean(),
            "Fixture must distinguish explicitly default U from prepared synthetic-profile J");
        require(!Arrays.equals(u, j), "Two prepared avatar PNGs unexpectedly share the same pixels");
        checkSprite(u, true); checkSprite(j, false);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("kyokouauth-role-avatar-v1".getBytes(StandardCharsets.US_ASCII)); digest.update((byte) 0);
        for (byte[] png : List.of(u, j)) { digest.update(ByteBuffer.allocate(4).putInt(png.length).array()); digest.update(png); }
        require(java.util.HexFormat.of().formatHex(digest.digest()).equals(snapshot.get("fingerprint").getAsString()), "HTTP fingerprint is not the exact independently hashed U/J PNG content");
        if (avatarCase.equals("changed") || avatarCase.equals("new-user") || avatarCase.equals("hit")) {
            JsonObject baseline = JSON.fromJson(Files.readString(run.resolve("avatar-baseline.json")), JsonObject.class);
            boolean same = baseline.get("fingerprint").getAsString().equals(snapshot.get("fingerprint").getAsString());
            require(same == avatarCase.equals("hit"), "Changed/new-user presentation reused a stale bootstrap fingerprint");
            if (avatarCase.equals("new-user")) {
                long previous = baseline.get("xuid").getAsLong();
                require(previous != XUID && previous == 2535412345678901L, "New-user case did not use a distinct strong fixture XUID");
                require(readAvatarSnapshot(previous).get("fingerprint").equals(baseline.get("fingerprint")), "Preparing a new XUID overwrote another subject's presentation snapshot");
            }
        }
        System.out.println("ROLE_CLIENT_AVATAR_SNAPSHOT xuid=" + XUID + " fingerprint=" + snapshot.get("fingerprint").getAsString() + " http=real-service png=32x32-distinct defaultU=true defaultJ=false renderAck=false");
    }

    private static void checkSprite(byte[] png, boolean red) throws Exception {
        require(png.length > 8 && png.length <= 8192, "Prepared avatar exceeds the PNG budget");
        var image = ImageIO.read(new ByteArrayInputStream(png));
        require(image != null && image.getWidth() == 32 && image.getHeight() == 32, "Production avatar renderer did not produce a 32x32 sprite");
        int colored = 0, transparent = 0;
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            int pixel = image.getRGB(x, y);
            if ((pixel >>> 24) == 0) { transparent++; continue; }
            int r = pixel >>> 16 & 255, b = pixel & 255;
            if (red ? r > b + 20 : b > r + 20) colored++;
        }
        require(colored > 64 && transparent > 0, "Actual rendered sprite lost the distinct red/blue fixture or transparency");
    }

    private void offerPreparedSnapshot(String fingerprint, boolean fallback) throws Exception {
        JsonObject offered = new JsonObject();
        offered.addProperty("schema", 1); offered.addProperty("state", "offered"); offered.addProperty("xuid", XUID);
        offered.addProperty("nonce", UUID.randomUUID().toString()); offered.addProperty("fingerprint", fingerprint); offered.addProperty("fallback", fallback);
        Path temporary = run.resolve("avatar-presentation.next.json");
        Files.writeString(temporary, JSON.toJson(offered));
        Files.move(temporary, run.resolve("avatar-presentation.json"), StandardCopyOption.REPLACE_EXISTING);
        verifyOffered(fingerprint, fallback);
    }

    private void verifyOffered(String fingerprint, boolean fallback) throws Exception {
        JsonObject offered = readJson(URI.create("http://127.0.0.1:" + fixture.get("avatarStatusPort").getAsInt() + "/v1/packs/status?xuid=" + XUID));
        require(offered.get("schema").getAsInt() == 1 && offered.get("state").getAsString().equals("offered") &&
            offered.get("fingerprint").getAsString().equals(fingerprint) && offered.get("fallback").getAsBoolean() == fallback,
            "Controlled offered/fallback HTTP fixture differs from the requested presentation state");
        UUID.fromString(offered.get("nonce").getAsString());
    }

    private Map<String, Object> avatarEvidence(Attempt visual, Attempt chooser, Attempt finalSocket) throws Exception {
        String fingerprint = avatarSnapshot.get("fingerprint").getAsString();
        verifyOffered(fingerprint, avatarCase.equals("fallback"));
        require(!chooser.wireSession.get().equals(finalSocket.wireSession.get()), "Final role reused its chooser native socket");
        return Map.ofEntries(Map.entry("enabled", true), Map.entry("scenario", avatarCase), Map.entry("xuid", XUID),
            Map.entry("fingerprint", fingerprint), Map.entry("snapshotHttpVerified", true), Map.entry("distinctSprites", true),
            Map.entry("visualTransfers", visual == null ? 0 : 1), Map.entry("visualWireSession", visual == null ? "" : visual.wireSession.get().toString()),
            Map.entry("chooserWireSession", chooser.wireSession.get().toString()), Map.entry("finalWireSession", finalSocket.wireSession.get().toString()),
            Map.entry("visualPort", visual == null ? 0 : visual.localPort), Map.entry("chooserPort", chooser.localPort), Map.entry("finalPort", finalSocket.localPort),
            Map.entry("freshProof", true), Map.entry("visualRoleHandoffUsed", false), Map.entry("roleCookieMinted", false),
            Map.entry("offeredMatch", !avatarCase.equals("fallback")), Map.entry("fallback", avatarCase.equals("fallback")),
            Map.entry("unavailableNotice", chooser.avatarUnavailable.get()), Map.entry("itemCmd", List.of(0x4B5901, 0x4B5902)),
            Map.entry("nonItalic", true), Map.entry("geyserStatusProof", "controlled-loopback-fixture-not-geyser"),
            Map.entry("rendered", false), Map.entry("applied", false));
    }

    private static byte[] formResponse() {
        // Fixed Geyser 2.12.1 b1303 JavaCustomPayloadTranslator.lambda$translate$0:
        // two big-endian form-ID bytes + UTF-8 response JSON. Negative IDs select
        // the proxy-owned form namespace. No stored form is created or mutated.
        // Minecraft's channel, packet ID, length and frame are official SDK codec only.
        byte[] json = JSON.toJson(0).getBytes(StandardCharsets.UTF_8);
        ByteBuf body = Unpooled.buffer(2 + json.length);
        try {
            body.writeShort(UNKNOWN_FORM).writeBytes(json);
            byte[] data = new byte[body.readableBytes()]; body.readBytes(data);
            require(data.length > 2, "A nonempty valid form response is required");
            return data;
        } finally { body.release(); }
    }

    private static String plain(Component component) {
        StringBuilder value = new StringBuilder(component instanceof TextComponent text ? text.content() : "");
        for (Component child : component.children()) value.append(plain(child));
        return value.toString();
    }

    private synchronized void captureSelector(ClientboundContainerSetContentPacket content) throws Exception {
        // Resyncs after rejected clicks are legitimate; retain the first validated menu only.
        if (selectorCaptured) return;
        // Only this just-validated, synthetic isolated GUI is exported; never keys, cookies or login packets.
        Path target = run.resolve("role-selector.bin");
        require(!Files.exists(target), "Synthetic selector fixture was already captured");
        ByteBuf buffer = Unpooled.buffer();
        try {
            content.serialize(buffer);
            require(buffer.readableBytes() > 0 && buffer.readableBytes() <= 32768, "Selector fixture exceeds its packet budget");
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
            selectorCaptured = true;
            System.out.println("ROLE_CLIENT_SELECTOR_CAPTURE packet=role-selector.bin bytes=" + bytes.length + " source=validated-native-gui");
        } finally { buffer.release(); }
    }

    private String selectorPlain(Component component, char baseCode) {
        String value = plain(component);
        if (!floodgate) {
            require(value.indexOf('\u00a7') < 0, "Java selector must remain pure components without literal legacy codes");
            return value;
        }
        String prefix = "\u00a7r\u00a7" + baseCode;
        require(value.startsWith(prefix) && value.indexOf('\u00a7', prefix.length()) < 0,
                "Only the known Geyser-bound reset/base prefix is permitted in Floodgate selector text");
        return value.substring(prefix.length());
    }

    private void validateHead(ItemStack item, boolean microsoft) {
        require(item != null && item.getAmount() == 1 && item.getId() == fixture.get("playerHeadId").getAsInt(), "Expected official player_head registry item");
        var components = item.getDataComponentsPatch();
        require(components != null, "Head metadata missing");
        ResolvableProfile resolved = components.get(DataComponentTypes.PROFILE);
        require(resolved != null && resolved.getProfile() != null, "Native head profile missing");
        GameProfile profile = resolved.getProfile();
        require((microsoft ? U : J).equals(profile.getId()) && (microsoft ? U_NAME : J_NAME).equals(profile.getName()), "Head profile does not match its server-selected role");
        Component name = components.get(DataComponentTypes.CUSTOM_NAME);
        require(name != null && selectorPlain(name, 'f').equals((microsoft ? "Microsoft role » " + U_NAME : "Java role » " + J_NAME)), "Role display name differs from the isolated English template");
        require(name.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE && name.color() != null,
            "Role display-name root must explicitly disable italic and supply a base color");
        List<Component> lore = components.get(DataComponentTypes.LORE);
        require(lore != null && lore.size() == 1 && selectorPlain(lore.getFirst(), '7').equals("Click to enter the server"), "Expected exactly one concise enter-server lore line");
        require(lore.getFirst().decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE && lore.getFirst().color() != null,
            "Role lore root must explicitly disable italic and supply a base color");
        var marker = components.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (avatars) {
            float expected = (float) (microsoft ? 0x4B5901 : 0x4B5902);
            require(marker != null && marker.floats().equals(List.of(expected)) && marker.flags().isEmpty() && marker.strings().isEmpty() && marker.colors().isEmpty(),
                "Avatar GUI must contain only the exact 1/0/0/0 CMD sentinel, without replacing the real PROFILE");
        } else require(marker == null, "Ordinary Java or disabled-avatar GUI must not carry private avatar CMD");
        checkTextures(profile.getProperties(), microsoft);
    }

    private String encryptedHost() throws Exception {
        Path key = run.resolve("plugins/floodgate/key.pem").toRealPath();
        require(key.startsWith(run) && Files.size(key) == 16L, "Only an isolated official Floodgate-generated AES key is accepted");
        var cipher = new AesCipher(new Base64Topping());
        cipher.init(new AesKeyProducer().produceFrom(key));
        var linked = LinkedPlayer.of(J_NAME, J, U);
        var data = BedrockData.of("1.21.0", "R", Long.toString(XUID), 7, "en_US", 0, 1, "127.0.0.1", linked, true, -1, "x");
        String host = "localhost\0" + new String(cipher.encryptFromString(data.toString()), StandardCharsets.UTF_8);
        require(host.length() <= 255, "Linked official cipher fixture exceeds native hostname bound");
        return host;
    }

    private void backend() throws Exception {
        Path events = run.resolve("backend-events.jsonl");
        var server = new NetworkServer(new InetSocketAddress("127.0.0.1", port), () -> {
            var protocol = new MinecraftProtocol();
            protocol.setUseDefaultListeners(false);
            return protocol;
        });
        server.addListener(new ServerAdapter() {
            @Override public void sessionAdded(SessionAddedEvent event) {
                event.getSession().addListener(new SessionAdapter() {
                    private String loginName;
                    private UUID loginId;
                    private boolean queried;
                    private GameProfile authorizedProfile;
                    private int dimension = -1;
                    @Override public void packetReceived(Session session, Packet packet) {
                        try {
                            MinecraftProtocol protocol = session.getPacketProtocol();
                            if (packet instanceof ClientIntentionPacket intention) {
                                require(intention.getProtocolVersion() == 777 && intention.getIntent() == HandshakeIntent.LOGIN, "Unexpected backend protocol/intent");
                                record(events, Map.of("event", "handshake", "protocol", 777));
                                protocol.setOutboundState(ProtocolState.LOGIN);
                                session.switchInboundState(() -> protocol.setInboundState(ProtocolState.LOGIN));
                            } else if (packet instanceof ServerboundHelloPacket hello) {
                                loginName = hello.getUsername(); loginId = hello.getProfileId();
                                record(events, Map.of("event", "login", "uuid", loginId.toString(), "name", loginName));
                                require(expectedId().equals(loginId) && expectedName().equals(loginName), "A chooser or wrong role reached the real backend");
                                ByteBuf request = Unpooled.buffer();
                                try {
                                    MinecraftTypes.writeVarInt(request, 1);
                                    byte[] data = new byte[request.readableBytes()]; request.readBytes(data);
                                    session.send(new ClientboundCustomQueryPacket(1, Key.key("velocity:player_info"), data));
                                    queried = true;
                                } finally { request.release(); }
                            } else if (packet instanceof ServerboundCustomQueryAnswerPacket answer) {
                                require(queried && answer.getTransactionId() == 1 && answer.getData() != null, "Missing native modern forwarding response");
                                GameProfile profile = forwardedProfile(answer.getData());
                                require(profile.getId().equals(loginId) && profile.getName().equals(loginName), "Native Hello differs from HMAC forwarded profile");
                                require(expectedId().equals(profile.getId()) && expectedName().equals(profile.getName()), "Forwarded selected role mismatch");
                                checkTextures(profile.getProperties(), chooseU);
                                var marker = profile.getProperties().stream().filter(p -> p.getName().equals("role-e2e:marker")).toList();
                                require(marker.size() == 1 && marker.getFirst().getValue().equals("preserved-non-texture-property"), "Forwarding lost non-texture properties");
                                var nonces = profile.getProperties().stream().filter(p -> p.getName().equals("kyokouauth:connection-id")).toList();
                                require(nonces.size() == 1 && !UUID.fromString(nonces.getFirst().getValue()).equals(new UUID(0L, 0L)), "Missing exact-connection nonce");
                                String canary = CANARIES.get(profile.getId());
                                require(canary != null, "No independent UUID-keyed role canary exists");
                                record(events, Map.of("event", "authorized", "uuid", profile.getId().toString(), "name", profile.getName(),
                                    "canary", canary, "hmac", true, "nonce", nonces.getFirst().getValue(), "textures", chooseU ? (fixture.get("microsoftTextureValue").isJsonNull() ? "default" : "explicit-u") : "synthetic-j"));
                                if (floodgateMessages && floodgate) {
                                    authorizedProfile = profile;
                                    session.send(new ClientboundLoginFinishedPacket(profile, UUID.randomUUID()));
                                    session.switchOutboundState(() -> protocol.setOutboundState(ProtocolState.CONFIGURATION));
                                } else session.send(new ClientboundLoginDisconnectPacket(Component.text("ROLE_CANARY_" + canary)), () -> session.disconnect("bounded backend complete"));
                            } else if (packet instanceof ServerboundLoginAcknowledgedPacket) {
                                require(authorizedProfile != null && floodgateMessages && floodgate, "Unexpected backend login acknowledgement");
                                session.switchInboundState(() -> protocol.setInboundState(ProtocolState.CONFIGURATION));
                                dimension = configureBackend(session, protocol);
                                session.send(new ClientboundFinishConfigurationPacket());
                            } else if (packet instanceof ServerboundFinishConfigurationPacket) {
                                require(authorizedProfile != null && dimension >= 0, "Final backend configuration was not authenticated");
                                session.switchInboundState(() -> protocol.setInboundState(ProtocolState.GAME));
                                session.switchOutboundState(() -> protocol.setOutboundState(ProtocolState.GAME));
                                Key world = Key.key("minecraft:overworld");
                                sendBackendJoin263(session, new ClientboundLoginPacket(90, false, new Key[]{world}, 1, 2, 2, false, true, false,
                                    new PlayerSpawnInfo(dimension, world, 0L, GameMode.SURVIVAL, null, false, true, null, 0, 63), false, false));
                                record(events, Map.of("event", "play", "uuid", loginId.toString(), "name", loginName));
                            } else if (packet instanceof ServerboundCustomPayloadPacket payload && payload.getChannel().equals(FORM)) {
                                throw new IllegalStateException("Official Floodgate form response must be consumed by the proxy, not forwarded to backend");
                            } else if (packet instanceof ServerboundCustomPayloadPacket payload && payload.getChannel().equals(FINAL_DONE)) {
                                require(authorizedProfile != null && Arrays.equals(payload.getData(), formResponse()),
                                    "Official Floodgate form message leaked to backend or an untrusted completion arrived");
                                require(protocol.getInboundState() == ProtocolState.GAME, "Final completion was outside PLAY");
                                record(events, Map.of("event", "play-message-complete", "uuid", loginId.toString(), "name", loginName));
                                session.send(new ClientboundDisconnectPacket(Component.text("ROLE_CANARY_" + CANARIES.get(loginId))),
                                    () -> session.disconnect("bounded final PLAY backend complete"));
                            }
                        } catch (Throwable failure) {
                            record(events, Map.of("event", "error", "type", failure.getClass().getName()));
                            session.disconnect("isolated backend assertion failed");
                        }
                    }
                    @Override public void packetError(PacketErrorEvent event) {
                        record(events, Map.of("event", "error", "type", event.getCause().getClass().getName()));
                    }
                });
            }
        });
        try {
            server.bind();
            require(server.isListening(), "Official SDK backend failed to bind");
            System.out.println("ROLE_BACKEND_READY"); System.out.flush();
            System.in.read();
        } finally { server.close(); }
    }

    private void sendBackendJoin263(Session session, ClientboundLoginPacket packet) throws Exception {
        // Same pinned one-field compatibility as limbo's proven Login263, ADR 0018.
        // MCPL 26.3-4 writes absent previous mode as BYTE -1; 777 requires
        // OPTIONAL_VAR_INT 0. Keep null semantics; never invent a previous mode.
        MinecraftProtocol protocol = session.getPacketProtocol();
        require(protocol.getCodec().getProtocolVersion() == 777 && protocol.getOutboundState() == ProtocolState.GAME,
            "The fixed JOIN compatibility applies only to real protocol-777 PLAY");
        require(packet.getCommonPlayerSpawnInfo().getPreviousGamemode() == null, "Fixture must preserve absent previous mode");
        ByteBuf spawn = Unpooled.buffer();
        ByteBuf encoded = Unpooled.buffer();
        boolean handedOff = false;
        try {
            MinecraftTypes.writePlayerSpawnInfo(spawn, packet.getCommonPlayerSpawnInfo());
            MinecraftTypes.readVarInt(spawn);
            MinecraftTypes.readResourceLocation(spawn);
            spawn.readLong();
            spawn.readByte();
            int previousOffset = spawn.readerIndex();
            var pipeline = session.getChannel().pipeline();
            var codec = pipeline.get(org.geysermc.mcprotocollib.network.netty.PacketCodec.class);
            require(codec != null, "Official SDK packet codec missing");
            // The actual active SDK encoder supplies its registered packet ID,
            // header and every field. No IDs, protocol registry entries or SDK
            // serializers are changed. Only the documented null representation
            // is corrected at the SDK-derived offset, just as Login263 does.
            codec.encode(pipeline.context(codec), packet, encoded);
            int fieldOffset = encoded.writerIndex() - 2 - spawn.writerIndex() + previousOffset;
            require(fieldOffset >= 0 && encoded.getByte(fieldOffset) == -1 && spawn.getByte(previousOffset) == -1,
                "Pinned previous-mode encoding changed; refuse a guessed JOIN patch");
            encoded.setByte(fieldOffset, 0);
            // PacketCodec<Packet> passes encoded ByteBuf through; all remaining
            // compression/length/encryption/framing are the unchanged SDK pipeline.
            session.getChannel().writeAndFlush(encoded).addListener(future -> {
                if (!future.isSuccess()) {
                    record(run.resolve("backend-events.jsonl"), Map.of("event", "error", "type", future.cause().getClass().getName()));
                    session.disconnect("isolated compatible JOIN send failed");
                }
            });
            handedOff = true;
            System.out.println("ROLE_BACKEND_JOIN263_COMPAT previous=absent optionalVarInt=0 codec=official framing=official");
        } finally {
            spawn.release();
            if (!handedOff) encoded.release();
        }
    }

    private int configureBackend(Session session, MinecraftProtocol protocol) throws Exception {
        byte[] bytes = Files.readAllBytes(run.resolve("configuration-777.bin"));
        require(bytes.length > 0 && bytes.length <= 4 * 1024 * 1024, "Final PLAY configuration snapshot exceeds bounds");
        require(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            .equals(fixture.get("configurationSha256").getAsString()), "Final PLAY configuration snapshot checksum mismatch");
        ByteBuf input = Unpooled.wrappedBuffer(bytes);
        int dimension = -1;
        int registries = 0;
        try {
            var codec = protocol.getCodec().getCodec(ProtocolState.CONFIGURATION);
            while (input.isReadable()) {
                int length = MinecraftTypes.readVarInt(input);
                require(length > 0 && length <= input.readableBytes(), "Truncated official configuration snapshot frame");
                ByteBuf frame = input.readSlice(length);
                Packet packet = codec.createClientboundPacket(MinecraftTypes.readVarInt(frame), frame);
                require(!frame.isReadable(), "Trailing official configuration snapshot bytes");
                if (packet instanceof ClientboundRegistryDataPacket registry) {
                    registries++;
                    if (registry.getRegistry().equals(Key.key("minecraft:dimension_type"))) {
                        for (int i = 0; i < registry.getEntries().size(); i++) {
                            if (registry.getEntries().get(i).getId().equals(Key.key("minecraft:overworld"))) dimension = i;
                        }
                    }
                    session.send(packet);
                } else if (packet instanceof ClientboundUpdateEnabledFeaturesPacket || packet instanceof ClientboundUpdateTagsPacket) {
                    session.send(packet);
                } else require(packet instanceof ClientboundCustomPayloadPacket brand && brand.getChannel().equals(Key.key("minecraft:brand")),
                    "Unexpected packet in official configuration snapshot");
            }
            require(registries == 32 && dimension >= 0, "Incomplete official configuration for final PLAY");
            return dimension;
        } finally { input.release(); }
    }

    private GameProfile forwardedProfile(byte[] bytes) throws Exception {
        require(bytes.length >= 33 && bytes.length <= 65536, "Forwarding envelope exceeds test bounds");
        byte[] body = Arrays.copyOfRange(bytes, 32, bytes.length);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Files.readAllBytes(run.resolve("forwarding.secret")), "HmacSHA256"));
        require(MessageDigest.isEqual(Arrays.copyOf(bytes, 32), mac.doFinal(body)), "Modern forwarding HMAC failed");
        ByteBuf input = Unpooled.wrappedBuffer(body);
        try {
            require(MinecraftTypes.readVarInt(input) == 1, "Expected modern forwarding v1");
            require(MinecraftTypes.readString(input).equals("127.0.0.1"), "Unexpected source address");
            GameProfile profile = MinecraftTypes.readStaticGameProfile(input);
            require(!input.isReadable(), "Trailing modern forwarding data");
            return profile;
        } finally { input.release(); }
    }

    private void checkTextures(List<GameProfile.Property> properties, boolean microsoft) {
        var textures = properties.stream().filter(p -> p.getName().equals("textures")).toList();
        String expected = microsoft ? (fixture.get("microsoftTextureValue").isJsonNull() ? null : fixture.get("microsoftTextureValue").getAsString())
            : fixture.get("premiumTextureValue").getAsString();
        if (expected == null) require(textures.isEmpty(), "Unassigned U leaked original or Java textures");
        else require(textures.size() == 1 && textures.getFirst().getValue().equals(expected) &&
            textures.getFirst().getSignature().equals("role-e2e-synthetic-signature"), "Role texture/signature differs from real explicit SR or J fixture policy");
    }

    private UUID expectedId() { return chooseU ? U : J; }
    private String expectedName() { return chooseU ? U_NAME : J_NAME; }
    private static void require(boolean value, String detail) { if (!value) throw new IllegalStateException(detail); }
    private static synchronized void record(Path path, Map<String, ?> value) {
        try { Files.writeString(path, JSON.toJson(value) + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (Exception error) { throw new IllegalStateException("Cannot record isolated backend evidence", error); }
    }
}
