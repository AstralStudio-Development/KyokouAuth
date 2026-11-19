import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.*;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.*;
import org.geysermc.mcprotocollib.protocol.data.handshake.HandshakeIntent;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;

/** Unsigned Java U transport fixture using complete SDK codecs, not a Microsoft credential. */
public final class ProfileMappingClient {
    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4) throw new IllegalArgumentException("port name expectedUuid [claim|authme|cancel|disconnect|expired]");
        var claim = args.length == 4 ? new ClaimProbe(args[3]) : null;
        var closed = new CountDownLatch(1);
        var protocol = new MinecraftProtocol(new GameProfile(UUID.randomUUID(), args[1]), null);
        if (protocol.getCodec().getProtocolVersion() != 777) throw new IllegalStateException("Pinned protocol 777 required");
        protocol.setUseDefaultListeners(false);
        var session = ClientNetworkSessionFactory.factory().setRemoteSocketAddress(
            new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0]))).setProtocol(protocol).create();
        session.setFlag(MinecraftConstants.CLIENT_HOST, "127.0.0.1");
        session.setFlag(MinecraftConstants.CLIENT_PORT, Integer.parseInt(args[0]));
        var ready = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var stopping = new AtomicBoolean();
        var teleported = new AtomicBoolean();
        var chunks = new AtomicBoolean();
        var loaded = new AtomicBoolean();
        var announced = new AtomicBoolean();
        session.addListener(new SessionAdapter() {
            private final java.util.Set<String> observed = new java.util.HashSet<>();
            private void announce(Session source) {
                if (teleported.get() && loaded.get() && announced.compareAndSet(false, true)) {
                    // Console assertions establish actual backend identity/data, not client rendering.
                    System.out.println("PROFILE_CLIENT_CONNECTED name=" + args[1]);
                    ready.countDown();
                }
            }
            @Override public void packetReceived(Session source, Packet packet) {
                try {
                    if (observed.size() < 40 && observed.add(packet.getClass().getSimpleName())) System.out.println(packet.getClass().getSimpleName());
                    if (packet instanceof ClientboundLoginFinishedPacket login) {
                        if (!UUID.fromString(args[2]).equals(login.getProfile().getId())) throw new IllegalStateException("Login UUID mismatch");
                    } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket) {
                        source.send(new org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket(
                            "en_us", 2, org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility.FULL,
                            true, java.util.List.of(), org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference.RIGHT_HAND,
                            false, true, org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus.ALL));
                    } else if (packet instanceof ClientboundPlayerPositionPacket position) {
                        source.send(new ServerboundAcceptTeleportationPacket(position.getId(), position.getPosition().getX(),
                            position.getPosition().getY(), position.getPosition().getZ(), position.getYRot(), position.getXRot()));
                        teleported.set(true);
                        if (loaded.compareAndSet(false, true)) source.send(ServerboundPlayerLoadedPacket.INSTANCE);
                        announce(source);
                    } else if (packet instanceof ClientboundChunkBatchFinishedPacket batch) {
                        source.send(new ServerboundChunkBatchReceivedPacket(8.0f)); chunks.set(true); announce(source);
                    }
                    if (claim != null) claim.receive(source, packet);
                } catch (Throwable error) { failure.set(error); ready.countDown(); closed.countDown(); }
            }
            @Override public void packetError(PacketErrorEvent event) { failure.set(event.getCause()); ready.countDown(); }
            @Override public void disconnected(DisconnectedEvent event) {
                if (!stopping.get() && claim == null) failure.compareAndSet(null, new IllegalStateException("Disconnected: " + event.getReason(), event.getCause()));
                if (claim != null && event.getCause() != null) failure.compareAndSet(null, event.getCause());
                ready.countDown(); closed.countDown();
            }
        });
        session.addListener(new ClientListener(HandshakeIntent.LOGIN));
        session.connect(true);
        if (!ready.await(45, TimeUnit.SECONDS) || !loaded.get() || failure.get() != null) throw new IllegalStateException("No ready", failure.get());
        if (claim == null) System.in.read();
        else {
            if (!closed.await(40, TimeUnit.SECONDS) || failure.get() != null) throw new IllegalStateException("Claim did not close safely", failure.get());
            claim.verify();
            System.out.println("CLAIM_CLIENT_COMPLETED case=" + args[3] + " menus=" + claim.menus);
        }
        stopping.set(true);
        session.disconnect("Isolated profile proof complete");
    }

    private static final class ClaimProbe {
        private final String scenario;
        private final java.util.Set<Integer> acted = new java.util.HashSet<>();
        private int container = -1;
        private String title;
        private String denial;
        private int menus;
        private boolean localClose;
        private int privateSubmissions;

        ClaimProbe(String scenario) {
            if (!java.util.Set.of("claim", "authme", "cancel", "disconnect", "expired").contains(scenario)) throw new IllegalArgumentException("Unknown case");
            this.scenario = scenario;
        }

        void receive(Session source, Packet packet) {
            if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket open) {
                require(open.getType() == org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType.GENERIC_9X3, "Wrong confirmation container");
                container = open.getContainerId(); title = plain(open.getTitle());
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket content
                    && content.getContainerId() == container && acted.add(container)) {
                require(content.getItems().length == 63 && content.getCarriedItem() == null, "Wrong menu content");
                menus++;
                require(menus <= 3 && title.equals(java.util.List.of("CLAIM_OFFER", "CLAIM_SOURCE", "CLAIM_BIND").get(menus - 1)), "Confirmation ordering changed: " + title);
                var yes = content.getItems()[12]; var no = content.getItems()[14];
                // Official pinned protocol-777 registry fixture: DIAMOND=1012, NAME_TAG=1413.
                require(yes != null && yes.getId() == 1012 && no != null && no.getId() == 1413, "Confirmation icons differ");
                var components = yes.getDataComponentsPatch();
                require(components != null, "Confirmation label absent");
                var label = components.get(org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes.CUSTOM_NAME);
                var lore = components.get(org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes.LORE);
                String text = plain(label) + (lore == null ? "" : lore.stream().map(ProfileMappingClient::plain).collect(java.util.stream.Collectors.joining(" ")));
                if (menus >= 2) require(text.contains("OldSteve"), "Source was not shown");
                if (menus == 3) require(text.contains("NewSteve") && text.contains("SyntheticMappedXbox"), "Target proof was not shown");
                System.out.println("CLAIM_CLIENT_MENU index=" + menus + " title=" + title);
                if (menus == 1 && scenario.equals("authme")) { sendOldProof(source, false); return; }
                if (menus == 2 && scenario.equals("expired")) return;
                if (menus == 3 && scenario.equals("disconnect")) {
                    localClose = true; source.disconnect("Synthetic claimant disconnected before final confirmation"); return;
                }
                int slot = menus == 2 && scenario.equals("cancel") ? 14 : 12;
                var click = new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket(
                    container, content.getStateId(), slot,
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType.CLICK_ITEM,
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction.LEFT_CLICK, null, java.util.Map.of());
                source.send(click);
                if (menus == 3 && (scenario.equals("claim") || scenario.equals("authme"))) source.send(click); // One commit only.
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandSuggestionsPacket suggestions) {
                require(!scenario.equals("authme") || suggestions.getTransactionId() != 17, "Private completion reached ordinary suggestion handling");
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket chat) {
                String message = plain(chat.getContent());
                require(!message.contains("PassWord1") && !message.contains("PRIVATE_BAD_E2E_PASSWORD"), "Private password was echoed");
                if (scenario.equals("authme") && message.contains("CLAIM_BAD_PROOF")) {
                    require(privateSubmissions == 1 && menus == 1, "Unexpected private-proof retry");
                    sendOldProof(source, true);
                }
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket disconnect) {
                denial = plain(disconnect.getReason());
                require(!denial.contains("PassWord1") && !denial.contains("PRIVATE_BAD_E2E_PASSWORD"), "Password appeared in disconnect reason");
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundTransferPacket ||
                    packet instanceof org.geysermc.mcprotocollib.protocol.packet.cookie.clientbound.ClientboundCookieRequestPacket ||
                    packet instanceof org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundStoreCookiePacket) {
                throw new AssertionError("Claim must use ordinary reconnect, not Transfer/cookies");
            }
        }

        private void sendOldProof(Session source, boolean correct) {
            // Both values are public synthetic fixture inputs, never real user credentials.
            String command = "ka claim authme OldSteve " + (correct ? "PassWord1" : "PRIVATE_BAD_E2E_PASSWORD");
            source.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundCommandSuggestionPacket(17, "/" + command));
            source.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket(command));
            privateSubmissions++;
            System.out.println("CLAIM_CLIENT_PRIVATE_SENT attempt=" + privateSubmissions + " unsigned=true");
        }

        void verify() {
            require(menus == (scenario.equals("claim") || scenario.equals("authme") || scenario.equals("disconnect") ? 3 : 2), "Claim did not traverse expected menus");
            if (scenario.equals("authme")) require(privateSubmissions == 2, "Wrong-then-correct private proof was not exercised");
            if (scenario.equals("disconnect")) require(localClose, "Expected local disconnect after proof");
            else require(denial != null && denial.contains(switch (scenario) {
                case "claim", "authme" -> "CLAIM_RECONNECT";
                case "cancel" -> "CLAIM_CANCELLED";
                default -> "CLAIM_EXPIRED";
            }), "Unexpected claim disconnect: " + denial);
        }
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        if (component == null) return "";
        var value = new StringBuilder(component instanceof net.kyori.adventure.text.TextComponent text ? text.content() : "");
        for (var child : component.children()) value.append(plain(child));
        return value.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
