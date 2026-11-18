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
import java.util.Set;
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
    private static final Key FORM = Key.key(PluginMessageChannels.FORM);
    private static final Key FORM_ACK = Key.key("kyokouauth:role_e2e_fg_ack");
    private static final Key FINAL_DONE = Key.key("kyokouauth:role_e2e_final_done");
    private static final Key FINAL_READY = Key.key("kyokouauth:role_e2e_final_ready");
    private static final short UNKNOWN_FORM = -12345;
    private static final String FLOODGATE_KICK = "Only Floodgate players can send floodgate messages!";
    private final long XUID;
    private final UUID U;
    private static final UUID J = UUID.fromString("12345678-1234-4234-9234-123456789abc");
    private static final String U_NAME = "URole";
    private static final String J_NAME = "JRole";
    private static final UUID CLAIMED = UUID.fromString("87654321-4321-4321-9321-cba987654321");
    private final Map<UUID, String> CANARIES;
    private final Set<String> nativeProofFingerprints = ConcurrentHashMap.newKeySet();
    private final boolean singleRole;
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
        singleRole = fixture.get("scenario").getAsString().equals("single");
        require(floodgate && floodgateMessages, "This runner covers Floodgate only; Java basic uses its existing bootstrap");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("client") || args[0].equals("backend"))) {
            throw new IllegalArgumentException("Expected client|backend isolated-run-directory loopback-port");
        }
        RoleSelectionClient probe = new RoleSelectionClient(Path.of(args[1]), Integer.parseInt(args[2]));
        if (args[0].equals("backend")) probe.backend(); else probe.client();
    }

    private void client() throws Exception {
        boolean reconnect = chooseU && !singleRole;
        Attempt first = new Attempt(false, false);
        Attempt resumed = reconnect ? new Attempt(true, false) : first;
        Attempt replay = null;
        try {
            first.connect();
            require(first.closed.await(30, TimeUnit.SECONDS), "Initial socket did not complete within deadline");
            first.checkFailure();
            require(first.loginFinished.get() && first.remoteDisconnect.get(), "Initial socket lacked native login/ordinary disconnect");
            if (singleRole) require(!first.menuValidated.get() && !first.clicked.get(), "Single role must authorize before Player/menu");
            else require(first.playerLoaded.get() && first.menuValidated.get() && first.clicked.get() && first.chooserAcknowledged.get(),
                "Native GUI click did not follow ready PLAY and official Floodgate handler");
            if (reconnect) {
                require(first.denial.get() != null && first.denial.get().contains("ROLE_E2E_ROLE_SELECTED"),
                    "Different role requires the ordinary role-selected disconnect, not Transfer or a canary");
                require(!first.finalPlay.get(), "Old chooser reached final backend PLAY");
                first.close();
                // Simulate the player's manual reconnect: brand-new ordinary LOGIN, no persisted cookies.
                resumed.connect();
                require(resumed.closed.await(30, TimeUnit.SECONDS), "Fresh ordinary final LOGIN did not finish");
                resumed.checkFailure();
                require(!resumed.menuValidated.get() && !resumed.clicked.get(), "Hint did not land before native Player construction");
            }
            require(resumed.finalPlay.get() && resumed.finalAcknowledged.get() && resumed.denial.get() != null
                && resumed.denial.get().contains("ROLE_CANARY_"), "Final native PLAY official handler/canary missing");
            resumed.close();
            if (!singleRole) {
                replay = new Attempt(false, true);
                replay.connect();
                require(replay.menuReady.await(30, TimeUnit.SECONDS), "Consumed hint must reopen a fresh held chooser");
                replay.checkFailure();
                require(replay.menuValidated.get() && !replay.clicked.get() && !replay.finalPlay.get(), "Consumed hint replay authorized");
                replay.close();
            }
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("platform", fixture.get("platform").getAsString()); result.put("mode", "floodgate");
            result.put("role", chooseU ? "microsoft" : "premium"); result.put("scenario", singleRole ? "single" : "linked");
            result.put("ordinaryReconnects", reconnect ? 1 : 0); result.put("selectionDisconnect", reconnect);
            result.put("transferPackets", 0); result.put("cookiePackets", 0);
            result.put("menuSlots", singleRole ? 0 : 27); result.put("wireSlots", singleRole ? 0 : 63);
            result.put("icons", singleRole ? List.of() : List.of("NAME_TAG", "DIAMOND"));
            result.put("iconSlots", singleRole ? List.of() : List.of(12, 14));
            result.put("chooserFormAcknowledged", first.chooserAcknowledged.get());
            result.put("finalFormAcknowledged", resumed.finalAcknowledged.get()); result.put("finalPlay", resumed.finalPlay.get());
            result.put("hintReplayRequiresChooser", replay != null);
            result.put("sockets", reconnect ? List.of(first.evidence(), resumed.evidence()) : List.of(first.evidence()));
            if (replay != null) result.put("replaySocket", replay.evidence());
            result.put("realMicrosoftVerified", false); result.put("realBedrockVerified", false); result.put("renderingVerified", false);
            System.out.println("ROLE_CLIENT_RESULT " + JSON.toJson(result));
        } finally {
            first.close(); if (resumed != first) resumed.close(); if (replay != null) replay.close();
        }
    }

    private final class Attempt {
        private final boolean finalLogin;
        private final boolean replay;
        private String proofFingerprint;
        // LoginFinished's wire session UUID is not Velocity Player.sessionId.
        // The actual TCP tuple correlates this socket with the proxy's native ID.
        private final AtomicReference<UUID> wireSession = new AtomicReference<>();
        private volatile int localPort;
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch menuReady = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean loginFinished = new AtomicBoolean();
        private final AtomicBoolean remoteDisconnect = new AtomicBoolean();
        private final AtomicBoolean menuValidated = new AtomicBoolean();
        private final AtomicBoolean clicked = new AtomicBoolean();
        private final AtomicBoolean batchAcknowledged = new AtomicBoolean();
        private final AtomicBoolean teleportAcknowledged = new AtomicBoolean();
        private final AtomicBoolean playerLoaded = new AtomicBoolean();
        private final AtomicReference<String> denial = new AtomicReference<>();
        private final AtomicBoolean chooserSent = new AtomicBoolean();
        private final AtomicBoolean finalSent = new AtomicBoolean();
        private final AtomicBoolean finalPlay = new AtomicBoolean();
        private final AtomicBoolean chooserAcknowledged = new AtomicBoolean();
        private final AtomicBoolean finalAcknowledged = new AtomicBoolean();
        private volatile int container = -1;
        private volatile int menuState = -1;
        private ClientNetworkSession session;

        Attempt(boolean finalLogin, boolean replay) {
            this.finalLogin = finalLogin; this.replay = replay;
        }

        void connect() throws Exception {
            MinecraftProtocol protocol = new MinecraftProtocol(new GameProfile(CLAIMED, floodgate ? "R" : U_NAME), null);
            require(protocol.getCodec().getProtocolVersion() == 777, "Complete SDK classpath is not protocol 777");
            protocol.setUseDefaultListeners(false);
            session = ClientNetworkSessionFactory.factory().setRemoteSocketAddress(new InetSocketAddress("127.0.0.1", port))
                .setProtocol(protocol).create();
            String host = floodgate ? encryptedHost() : "127.0.0.1";
            proofFingerprint = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(host.getBytes(StandardCharsets.UTF_8)));
            require(nativeProofFingerprints.add(proofFingerprint), "Every new socket requires fresh official AES cipher, not cached chooser proof");
            session.setFlag(MinecraftConstants.CLIENT_HOST, host);
            session.setFlag(MinecraftConstants.CLIENT_PORT, port);
            session.setFlag(MinecraftConstants.FOLLOW_TRANSFERS, false);
            session.addListener(new SessionAdapter() {
                @Override public void packetReceived(Session source, Packet packet) {
                    try { receive(source, packet); } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        source.disconnect("isolated role assertion failed");
                        closed.countDown(); menuReady.countDown();
                    }
                }
                @Override public void packetError(PacketErrorEvent event) {
                    failure.compareAndSet(null, event.getCause());
                }
                @Override public void disconnected(DisconnectedEvent event) {
                    if (event.getCause() != null) failure.compareAndSet(null, event.getCause());
                    closed.countDown(); menuReady.countDown();
                }
            });
            session.addListener(new ClientListener(HandshakeIntent.LOGIN));
            session.connect(true);
        }

        void receive(Session source, Packet packet) throws Exception {
            if (packet instanceof ClientboundTransferPacket) {
                throw new IllegalStateException("Forbidden ClientboundTransferPacket: manual ordinary LOGIN only");
            } else if (packet instanceof ClientboundCookieRequestPacket || packet instanceof ClientboundStoreCookiePacket) {
                throw new IllegalStateException("Role cookies must not be requested or stored");
            } else if (packet instanceof ClientboundLoginFinishedPacket success) {
                GameProfile profile = success.getProfile();
                if (finalLogin || singleRole || !chooseU) {
                    require(expectedId().equals(profile.getId()) && expectedName().equals(profile.getName()), "Native UUID/name differ from reachable selected role");
                } else require(J.equals(profile.getId()) && J_NAME.equals(profile.getName()), "Linked chooser must preserve the real native J identity");
                require(wireSession.compareAndSet(null, success.getSessionId()), "Duplicate native LoginFinished");
                require(source.getLocalAddress() instanceof InetSocketAddress, "Native SDK socket lacks an actual local TCP address");
                localPort = ((InetSocketAddress) source.getLocalAddress()).getPort();
                System.out.println("ROLE_CLIENT_SOCKET " + JSON.toJson(evidence()));
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
                require(!singleRole && !finalLogin, "Unexpected GUI after hint or single-role prelogin");
                require(open.getType() == ContainerType.GENERIC_9X3 && open.getContainerId() > 0, "Expected a native three-row menu");
                require(open.getTitle() != null && plain(open.getTitle()).equals("Choose a role"), "Native role menu title differs from the isolated English template");
                require(open.getTitle().decoration(TextDecoration.ITALIC) != TextDecoration.State.TRUE, "Role menu title must not be italic");
                container = open.getContainerId();
            } else if (packet instanceof ClientboundContainerSetContentPacket content && content.getContainerId() == container) {
                require(content.getStateId() >= 0 && content.getItems().length == 27 + 36 && content.getCarriedItem() == null,
                    "Invalid native menu state/content size");
                validateIcon(content.getItems()[12], true);
                validateIcon(content.getItems()[14], false);
                for (int slot = 0; slot < content.getItems().length; slot++) {
                    if (slot != 12 && slot != 14) require(content.getItems()[slot] == null, "Unexpected item in isolated role menu");
                }
                if (!replay) captureSelector(content);
                menuState = content.getStateId();
                menuValidated.set(true); menuReady.countDown();
                sendChooserProbe(source);
            } else if (packet instanceof ClientboundCustomPayloadPacket response && response.getChannel().equals(FORM_ACK)) {
                require(!replay && Arrays.equals(response.getData(), formResponse()), "Unsolicited/malformed official Floodgate barrier");
                if (finalPlay.get()) {
                    require(finalSent.get() && finalAcknowledged.compareAndSet(false, true), "Duplicate final handler ACK");
                    source.send(new ServerboundCustomPayloadPacket(FINAL_DONE, formResponse()));
                } else {
                    require(chooserSent.get() && chooserAcknowledged.compareAndSet(false, true), "Duplicate chooser handler ACK");
                    click(source);
                }
                System.out.println("ROLE_CLIENT_FG_MESSAGE_ACK phase=" + (finalPlay.get() ? "final" : "chooser"));
            } else if (packet instanceof ClientboundCustomPayloadPacket ready && ready.getChannel().equals(FINAL_READY)) {
                // Backend sends this only after its actual authenticated CONFIGURATION -> PLAY.
                // Same-socket proxy switches may translate Join into Respawn; don't assume a second Join.
                require(!replay && loginFinished.get() && (finalLogin || singleRole || clicked.get())
                    && Arrays.equals(ready.getData(), formResponse()), "Untrusted/held final-backend readiness");
                MinecraftProtocol protocol = source.getPacketProtocol();
                require(protocol.getOutboundState() == ProtocolState.GAME, "Final probe outside PLAY");
                require(finalPlay.compareAndSet(false, true) && finalSent.compareAndSet(false, true), "Duplicate final backend readiness");
                source.send(new ServerboundCustomPayloadPacket(FORM, formResponse()));
                System.out.println("ROLE_CLIENT_FG_MESSAGE_SENT phase=final channel=" + FORM + " formId=" + UNKNOWN_FORM + " payload=valid-json-0");
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
                sendChooserProbe(source);
            }
        }

        void sendChooserProbe(Session source) {
            if (!replay && menuValidated.get() && playerLoaded.get() && chooserSent.compareAndSet(false, true)) {
                source.send(new ServerboundCustomPayloadPacket(FORM, formResponse()));
                System.out.println("ROLE_CLIENT_FG_MESSAGE_SENT phase=chooser channel=" + FORM + " formId=" + UNKNOWN_FORM + " payload=valid-json-0");
            }
        }

        Map<String, Object> evidence() {
            var value = new java.util.LinkedHashMap<String, Object>();
            value.put("phase", replay ? "replay" : finalLogin ? "final" : "initial");
            value.put("wireSession", wireSession.get().toString()); value.put("localPort", localPort);
            value.put("freshOfficialCipher", true); value.put("proofFingerprint", proofFingerprint); value.put("intent", "LOGIN");
            return value;
        }

        void checkFailure() { if (failure.get() != null) throw new AssertionError("Native SDK session failed", failure.get()); }
        void close() {
            if (session == null) return;
            session.disconnect("isolated role fixture complete");
            if (session.getChannel() != null) session.getChannel().close().syncUninterruptibly();
        }
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

    private void validateIcon(ItemStack item, boolean microsoft) {
        int officialId = fixture.get(microsoft ? "nameTagId" : "diamondId").getAsInt();
        require(item != null && item.getAmount() == 1 && item.getId() == officialId, "Expected official vanilla NAME_TAG/DIAMOND registry item");
        var components = item.getDataComponentsPatch();
        require(components != null && components.get(DataComponentTypes.PROFILE) == null
            && components.get(DataComponentTypes.CUSTOM_MODEL_DATA) == null, "Vanilla icon must have no PROFILE/private CMD");
        Component name = components.get(DataComponentTypes.CUSTOM_NAME);
        require(name != null && selectorPlain(name, 'f').equals(microsoft ? "Microsoft role » " + U_NAME : "Java role » " + J_NAME), "Wrong isolated role label");
        require(name.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE && name.color() != null, "Name root must supply base color/nonitalic");
        List<Component> lore = components.get(DataComponentTypes.LORE);
        require(lore != null && lore.size() == 1 && selectorPlain(lore.getFirst(), '7').equals("Click to enter the server"), "Wrong isolated role lore");
        require(lore.getFirst().decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE && lore.getFirst().color() != null, "Lore root must supply base color/nonitalic");
    }

    private String encryptedHost() throws Exception {
        Path key = run.resolve("plugins/floodgate/key.pem").toRealPath();
        require(key.startsWith(run) && Files.size(key) == 16L, "Only an isolated official Floodgate-generated AES key is accepted");
        var cipher = new AesCipher(new Base64Topping());
        cipher.init(new AesKeyProducer().produceFrom(key));
        var linked = singleRole ? null : LinkedPlayer.of(J_NAME, J, U);
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
                    private String handshakeHost;
                    private String loginName;
                    private UUID loginId;
                    private boolean queried;
                    private GameProfile authorizedProfile;
                    private int dimension = -1;
                    @Override public void packetReceived(Session session, Packet packet) {
                        try {
                            MinecraftProtocol protocol = session.getPacketProtocol();
                            boolean bungee = fixture.has("platform") && fixture.get("platform").getAsString().equals("bungee");
                            if (packet instanceof ClientIntentionPacket intention) {
                                require(intention.getProtocolVersion() == 777 && intention.getIntent() == HandshakeIntent.LOGIN, "Unexpected backend protocol/intent");
                                handshakeHost = intention.getHostname();
                                record(events, Map.of("event", "handshake", "protocol", 777));
                                protocol.setOutboundState(ProtocolState.LOGIN);
                                session.switchInboundState(() -> protocol.setInboundState(ProtocolState.LOGIN));
                            } else if (packet instanceof ServerboundHelloPacket hello) {
                                loginName = hello.getUsername(); loginId = hello.getProfileId();
                                record(events, Map.of("event", "login", "uuid", loginId.toString(), "name", loginName));
                                require(expectedId().equals(loginId) && expectedName().equals(loginName), "A chooser or wrong role reached the real backend");
                                if (bungee) {
                                    GameProfile profile = legacyForwardedProfile(handshakeHost, loginName);
                                    require(profile.getId().equals(loginId) && profile.getName().equals(loginName), "Native Hello differs from Bungee legacy forwarded profile");
                                    require(expectedId().equals(profile.getId()) && expectedName().equals(profile.getName()), "Forwarded selected role mismatch");
                                    checkTextures(profile.getProperties(), chooseU);
                                    var marker = profile.getProperties().stream().filter(p -> p.getName().equals("role-e2e:marker")).toList();
                                    require(marker.size() == 1 && marker.getFirst().getValue().equals("preserved-non-texture-property"), "Forwarding lost non-texture properties");
                                    boolean privateTicketAbsent = profile.getProperties().stream().noneMatch(p -> p.getName().equals("kyokouauth:private-ticket"));
                                    boolean connectionNonceAbsent = profile.getProperties().stream().noneMatch(p -> p.getName().equals("kyokouauth:connection-id"));
                                    require(privateTicketAbsent && connectionNonceAbsent, "Private ticket or connection nonce leaked to real backend");
                                    var testSessions = profile.getProperties().stream().filter(p -> p.getName().equals("role-e2e:test-session")).toList();
                                    require(testSessions.size() == 1 && !UUID.fromString(testSessions.getFirst().getValue()).equals(new UUID(0L, 0L)), "Missing exact Bungee test session correlation");
                                    String canary = CANARIES.get(profile.getId());
                                    require(canary != null, "No independent UUID-keyed role canary exists");
                                    record(events, Map.of("event", "authorized", "uuid", profile.getId().toString(), "name", profile.getName(),
                                        "canary", canary, "legacyForwarded", true, "privateTicketAbsent", true, "connectionNonceAbsent", true,
                                        "testSession", testSessions.getFirst().getValue(), "textures", chooseU ? (fixture.get("microsoftTextureValue").isJsonNull() ? "default" : "explicit-u") : "synthetic-j"));
                                    authorizedProfile = profile;
                                    session.send(new ClientboundLoginFinishedPacket(profile, UUID.randomUUID()));
                                    session.switchOutboundState(() -> protocol.setOutboundState(ProtocolState.CONFIGURATION));
                                } else {
                                    ByteBuf request = Unpooled.buffer();
                                    try {
                                        MinecraftTypes.writeVarInt(request, 1);
                                        byte[] data = new byte[request.readableBytes()]; request.readBytes(data);
                                        session.send(new ClientboundCustomQueryPacket(1, Key.key("velocity:player_info"), data));
                                        queried = true;
                                    } finally { request.release(); }
                                }
                            } else if (packet instanceof ServerboundCustomQueryAnswerPacket answer) {
                                require(!bungee && queried && answer.getTransactionId() == 1 && answer.getData() != null, "Missing native modern forwarding response");
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
                                session.send(new ClientboundCustomPayloadPacket(FINAL_READY, formResponse()));
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

    private GameProfile legacyForwardedProfile(String host, String requestedName) {
        require(host != null && host.length() >= 4 && host.length() <= 32767, "Invalid Bungee legacy forwarding hostname");
        String[] fields = host.split("\0", -1);
        require(fields.length == 4 && fields[1].equals("127.0.0.1"), "Unexpected Bungee legacy forwarding fields/source");
        String rawUuid = fields[2];
        String canonical = rawUuid.length() == 32 ? rawUuid.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5") : rawUuid;
        GameProfile profile = new GameProfile(UUID.fromString(canonical), requestedName);
        var entries = com.google.gson.JsonParser.parseString(fields[3]).getAsJsonArray();
        var properties = new java.util.ArrayList<GameProfile.Property>(entries.size());
        for (var element : entries) {
            var item = element.getAsJsonObject();
            String name = item.get("name").getAsString();
            String value = item.get("value").getAsString();
            String signature = item.has("signature") && !item.get("signature").isJsonNull() ? item.get("signature").getAsString() : "";
            properties.add(new GameProfile.Property(name, value, signature));
        }
        profile.setProperties(properties);
        return profile;
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
