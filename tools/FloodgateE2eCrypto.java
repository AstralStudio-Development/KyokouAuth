package moe.ryuu.kyokouauth.tools.floodgate;

import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundLoginAcknowledgedPacket;
import org.geysermc.floodgate.crypto.AesCipher;
import org.geysermc.floodgate.crypto.AesKeyProducer;
import org.geysermc.floodgate.crypto.Base64Topping;
import org.geysermc.floodgate.crypto.FloodgateCipher;
import org.geysermc.floodgate.util.BedrockData;
import org.geysermc.floodgate.util.LinkedPlayer;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.ClientListener;
import org.geysermc.mcprotocollib.protocol.data.handshake.HandshakeIntent;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundCustomQueryPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundCustomQueryAnswerPacket;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only trusted Geyser-side Java transport fixture. No handwritten Minecraft codec, attribute
 * injection, Microsoft session, or production key is used. The real installed Floodgate listener
 * must decrypt the hostname and create its own native channel player attribute.
 *
 * Compiled only by floodgate-e2e.py against the pinned official Floodgate artifact and the existing
 * complete, unrelocated MCProtocolLib tool classpath. It never enters a production plugin JAR.
 */
public final class FloodgateE2eCrypto {
    private static final long XUID = 2535412345678901L;
    private static final UUID BEDROCK_UUID = new UUID(0L, XUID);
    private static final String GAMERTAG = "CryptoBedrock";
    private static final Set<String> CASES = Set.of("valid", "wrongkey", "forgedUUIDOnly", "linked",
        "nativeProfileMismatch", "nativeNameMismatch", "providerDisabled", "providerStale");

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--encode-backend")) {
            encodeBackendFixture(Path.of(args[1]));
            return;
        }
        if (args.length != 3 || !CASES.contains(args[2])) {
            throw new IllegalArgumentException("Expected isolated-run-directory loopback-port fixture-case");
        }
        Path root = Path.of(System.getProperty("kyokou.floodgate.isolatedRoot")).toRealPath();
        Path run = Path.of(args[0]).toRealPath();
        if (!run.startsWith(root) || run.equals(root) ||
            !Files.isRegularFile(run.resolve("isolated-fixture.json"))) {
            throw new IllegalArgumentException("Only a newly created isolated Floodgate fixture may supply a key");
        }
        Path keyFile = run.resolve("plugins/floodgate/key.pem").toRealPath();
        if (!keyFile.startsWith(run) || Files.size(keyFile) != 16L) {
            throw new IllegalStateException("Expected the isolated Floodgate-generated AES key");
        }
        int port = Integer.parseInt(args[1]);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid loopback port");
        String testCase = args[2];
        boolean forgedOnly = testCase.equals("forgedUUIDOnly");
        String host = "localhost";
        if (!forgedOnly) {
            AesKeyProducer producer = new AesKeyProducer();
            Key key = testCase.equals("wrongkey") ? producer.produce() : producer.produceFrom(keyFile);
            FloodgateCipher cipher = new AesCipher(new Base64Topping());
            cipher.init(key);
            LinkedPlayer linked = testCase.equals("linked")
                ? LinkedPlayer.of("Lnk", UUID.fromString("22222222-2222-2222-2222-222222222222"), BEDROCK_UUID)
                : null;
            BedrockData data = BedrockData.of("1.21.0", GAMERTAG, Long.toString(XUID), 7,
                "en_US", 0, 1, "127.0.0.1", linked, linked != null, -1,
                linked != null ? "x" : "isolated-no-skin-upload");
            host += '\0' + new String(cipher.encryptFromString(data.toString()), StandardCharsets.UTF_8);
        }
        // Native hostname decoding precedes Floodgate; keep every fixture within the vanilla bound.
        if (host.length() > 255) {
            throw new IllegalStateException("Fixture hostname exceeds the safe native bound: chars=" + host.length());
        }
        // A forged UUID and isolated display prefix without encrypted proof must not be sufficient.
        String claimedName = forgedOnly ? "." + GAMERTAG : GAMERTAG;
        UUID claimedId = forgedOnly ? BEDROCK_UUID : UUID.fromString("11111111-1111-1111-1111-111111111111");
        MinecraftProtocol protocol = new MinecraftProtocol(new GameProfile(claimedId, claimedName), null);
        if (protocol.getCodec().getProtocolVersion() != 777) {
            throw new IllegalStateException("This bounded native fixture requires the pinned protocol 777 tool classpath");
        }
        // Observe terminal packets before the upstream listener closes the transport.
        protocol.setUseDefaultListeners(false);
        var client = ClientNetworkSessionFactory.factory()
            .setRemoteSocketAddress(new InetSocketAddress("127.0.0.1", port)).setProtocol(protocol).create();
        client.setFlag(MinecraftConstants.CLIENT_HOST, host);
        client.setFlag(MinecraftConstants.CLIENT_PORT, port);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean rejected = new AtomicBoolean();
        AtomicReference<String> finalId = new AtomicReference<>();
        AtomicReference<String> finalName = new AtomicReference<>();
        AtomicReference<String> rejectionReason = new AtomicReference<>();
        AtomicReference<String> transportFailure = new AtomicReference<>();
        client.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session session, Packet packet) {
                if (packet instanceof ClientboundLoginFinishedPacket success) {
                    finished.set(true);
                    finalId.set(success.getProfile().getId().toString());
                    finalName.set(success.getProfile().getName());
                } else if (packet instanceof ClientboundLoginDisconnectPacket denial) {
                    rejectionReason.set(denial.getReason().toString());
                    rejected.set(true);
                } else if (packet instanceof ClientboundDisconnectPacket denial) {
                    rejectionReason.set(denial.getReason().toString());
                    rejected.set(true);
                } else if (packet instanceof ClientboundCustomQueryPacket query) {
                    session.send(new ServerboundCustomQueryAnswerPacket(query.getMessageId(), null));
                }
            }

            @Override
            public void packetError(PacketErrorEvent event) {
                transportFailure.compareAndSet(null, event.getCause().getClass().getName());
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                if (event.getCause() != null) {
                    transportFailure.compareAndSet(null, event.getCause().getClass().getName());
                }
                closed.countDown();
            }
        });
        client.addListener(new ClientListener(HandshakeIntent.LOGIN));
        try {
            client.connect(true);
            if (!closed.await(25, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Native fixture did not reach bounded denial or recording-backend stop");
            }
            if (transportFailure.get() != null) {
                throw new IllegalStateException("Transport failure is not an authentication denial: " + transportFailure.get());
            }
            if (!rejected.get()) throw new IllegalStateException("Expected a real proxy disconnect packet, not silent transport loss");
            if (testCase.equals("valid")) {
                boolean loginFinished = finished.get();
                String actualId = finalId.get();
                String actualName = finalName.get();
                // Only controlled native profile fields; JSON-escape the name and never print crypto data.
                System.out.println("FLOODGATE_CRYPTO_NATIVE_PROFILE finished=" + loginFinished +
                    " uuid=" + actualId + " name=" + new Gson().toJson(actualName));
                if (!loginFinished) {
                    throw new IllegalStateException("Native login finished packet was not observed");
                }
                if (!BEDROCK_UUID.toString().equals(actualId)) {
                    throw new IllegalStateException("Native final UUID differs from the encrypted XUID");
                }
                if (!("." + GAMERTAG).equals(actualName)) {
                    throw new IllegalStateException("Native final name differs from the encrypted username");
                }
            } else if (finished.get()) {
                throw new IllegalStateException("Rejected fixture received a native successful login");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("case", testCase);
            result.put("officialCipher", !forgedOnly);
            result.put("loginFinished", finished.get());
            result.put("remoteDisconnect", rejected.get());
            result.put("disconnectReason", rejectionReason.get());
            result.put("uuid", finalId.get());
            result.put("name", finalName.get());
            result.put("realBedrockClientVerified", false);
            System.out.println("FLOODGATE_CRYPTO_RESULT " + new Gson().toJson(result));
        } finally {
            client.disconnect("isolated fixture complete");
            if (client.getChannel() != null) client.getChannel().close().syncUninterruptibly();
        }
    }

    /** Bungee 2111 waits for backend LoginSuccess before it sends the first frontend LoginSuccess. */
    private static void encodeBackendFixture(Path directory) throws Exception {
        Path root = Path.of(System.getProperty("kyokou.floodgate.isolatedRoot")).toRealPath();
        Path output = directory.toRealPath();
        if (!output.startsWith(root) || output.equals(root)) {
            throw new IllegalArgumentException("Backend codec output must remain inside the isolated fixture");
        }
        MinecraftProtocol protocol = new MinecraftProtocol(GAMERTAG);
        if (protocol.getCodec().getProtocolVersion() != 777) {
            throw new IllegalStateException("Backend fixture requires the fixed protocol-777 native codec");
        }
        protocol.setOutboundState(ProtocolState.LOGIN);
        int acknowledgement = protocol.getOutboundPacketRegistry().getServerboundId(ServerboundLoginAcknowledgedPacket.class);
        Files.write(output.resolve("login-success.bin"), frame(protocol,
            new ClientboundLoginFinishedPacket(new GameProfile(BEDROCK_UUID, "." + GAMERTAG), UUID.randomUUID())));
        protocol.setOutboundState(ProtocolState.CONFIGURATION);
        Files.write(output.resolve("configuration-disconnect.bin"), frame(protocol,
            new ClientboundDisconnectPacket(Component.text("ISOLATED_BACKEND_STOP"))));
        Files.writeString(output.resolve("codec.json"), new Gson().toJson(Map.of(
            "protocol", 777, "sdk", "MCProtocolLib-26.3-4", "loginAcknowledgedId", acknowledgement,
            "uuid", BEDROCK_UUID.toString(), "name", "." + GAMERTAG)), StandardCharsets.UTF_8);
    }

    /** Packet IDs, all fields and VarInt framing use the upstream SDK; no local packet layout. */
    private static byte[] frame(MinecraftProtocol protocol, MinecraftPacket packet) {
        ByteBuf payload = Unpooled.buffer();
        ByteBuf framed = Unpooled.buffer();
        try {
            MinecraftTypes.writeVarInt(payload, protocol.getOutboundPacketRegistry().getClientboundId(packet));
            packet.serialize(payload);
            MinecraftTypes.writeVarInt(framed, payload.readableBytes());
            framed.writeBytes(payload);
            byte[] bytes = new byte[framed.readableBytes()];
            framed.readBytes(bytes);
            return bytes;
        } finally {
            framed.release();
            payload.release();
        }
    }
}
