import moe.ryuu.kyokouauth.limbo.libs.org.geysermc.mcprotocollib.network.Session;
import moe.ryuu.kyokouauth.limbo.libs.org.geysermc.mcprotocollib.network.event.session.*;
import moe.ryuu.kyokouauth.limbo.libs.org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import moe.ryuu.kyokouauth.limbo.libs.org.geysermc.mcprotocollib.network.packet.Packet;
import moe.ryuu.kyokouauth.limbo.libs.org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import moe.ryuu.kyokouauth.limbo.libs.org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import moe.ryuu.kyokouauth.limbo.libs.org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import moe.ryuu.kyokouauth.limbo.libs.org.geysermc.mcprotocollib.protocol.data.handshake.HandshakeIntent;
import moe.ryuu.kyokouauth.limbo.libs.org.geysermc.mcprotocollib.protocol.packet.handshake.serverbound.ClientIntentionPacket;
import moe.ryuu.kyokouauth.limbo.libs.org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundHelloPacket;
import moe.ryuu.kyokouauth.limbo.libs.org.geysermc.mcprotocollib.protocol.packet.login.clientbound.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests native Velocity admission without answering encryption: zero client join or server hasJoined calls.
 * A cold name lookup may occur once. Uses public Notch UUID as an untrusted hint, never as proof.
 * Run after compiling against the deployable plugin jar; args: port latest-proxy-log.
 */
public final class PremiumHandshakeProbe {
    private static final AtomicInteger challenges = new AtomicInteger();
    private static final UUID PUBLIC_ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final class Attempt implements AutoCloseable {
        final CountDownLatch reply = new CountDownLatch(1);
        final ClientNetworkSession session;
        volatile boolean challenge;
        volatile String refusal;
        Attempt(int port) {
            MinecraftProtocol protocol = new MinecraftProtocol();
            protocol.setUseDefaultListeners(false);
            session = ClientNetworkSessionFactory.factory().setAddress("127.0.0.1", port).setProtocol(protocol).create();
            session.addListener(new SessionAdapter() {
                public void connected(ConnectedEvent event) {
                    session.switchInboundState(() -> protocol.setInboundState(ProtocolState.LOGIN));
                    session.send(new ClientIntentionPacket(777, "127.0.0.1", port, HandshakeIntent.LOGIN));
                    session.switchOutboundState(() -> protocol.setOutboundState(ProtocolState.LOGIN));
                    session.send(new ServerboundHelloPacket("Notch", PUBLIC_ID));
                }
                public void packetReceived(Session source, Packet packet) {
                    if (packet instanceof ClientboundHelloPacket) {
                        challenge = true;
                        challenges.incrementAndGet();
                        reply.countDown();
                    } else if (packet instanceof ClientboundLoginDisconnectPacket rejected) {
                        refusal = rejected.getReason().toString();
                        reply.countDown();
                    }
                }
            });
            session.connect(true);
        }
        void awaitReply() throws Exception {
            if (!reply.await(12, TimeUnit.SECONDS)) throw new AssertionError("No login reply before bounded timeout");
        }
        public void close() { if (session.isConnected()) session.disconnect("local probe complete"); }
    }
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Path log = Path.of(args[1]);
        try (Attempt first = new Attempt(port)) {
            first.awaitReply();
            if (!first.challenge) throw new AssertionError("Initial challenge not admitted; existing cooldown or upstream name outage");
            try (Attempt duplicate = new Attempt(port)) {
                duplicate.awaitReply();
                if (duplicate.challenge || duplicate.refusal == null || !(duplicate.refusal.contains("正在验证") || duplicate.refusal.contains("already being verified")))
                    throw new AssertionError("Parallel connection was not refused as in-flight");
            }
            int sourcePort = ((InetSocketAddress) first.session.getLocalAddress()).getPort();
            first.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            String closed = ":" + sourcePort + " has disconnected";
            boolean observed = false;
            while (System.nanoTime() < deadline) {
                if (Files.readString(log).contains(closed)) { observed = true; break; }
                Thread.sleep(25);
            }
            if (!observed) throw new AssertionError("Proxy did not observe the probe's disconnect");
            try (Attempt retry = new Attempt(port)) {
                retry.awaitReply();
                if (retry.challenge || retry.refusal == null || !(retry.refusal.contains("冷却") || retry.refusal.contains("cooling down")))
                    throw new AssertionError("Incomplete handshake did not trigger failure backoff");
            }
            if (challenges.get() != 1) throw new AssertionError("Duplicate handshake escaped the guard");
            System.out.println("PASS: one native challenge; parallel attempt refused; disconnected attempt enters backoff; no Mojang session proof requested");
        }
    }
}
