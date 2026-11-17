import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.*;
import net.minecraft.network.chat.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.login.*;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.tags.TagLoader;
import net.minecraft.tags.TagNetworkSerialization.NetworkPayload;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Minecraft 26.3 oracle template. prepare-limbo-oracle.py specializes old official API families.
 * This has no dependency on KyokouAuth's packet writer/parser or any MCProtocolLib runtime.
 * Input: state<TAB>decimal packet id<TAB>relative payload filename (no frame/id).
 * Real packet ids are dispatched by vanilla protocols; registry ids retain wire order.
 * Public exporter fixture: Oracle link/aqua/bold/open_url, Oracle title/gold/bold,
 * Oracle subtitle/gray, animation 10/60/10. This is NOT a client/render/E2E test.
 */
public final class ProtocolOracle {
    private static final String PREFIX = "ORACLE ";
    private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
    private static final Identifier PLAINS = Identifier.parse("minecraft:plains");
    private static final String TEST_URL = "https://example.invalid/limbo-oracle";
    private static final UUID TEST_UUID = UUID.fromString("38aef317-7922-4c5a-aaf4-e973691f0c15");
    private final Map<ResourceKey<? extends Registry<?>>, List<RegistrySynchronization.PackedRegistryEntry>> entries = new LinkedHashMap<>();
    private final Map<ResourceKey<? extends Registry<?>>, NetworkPayload> tags = new LinkedHashMap<>();
    private final Set<String> seen = new HashSet<>();
    private RegistryAccess access;
    private com.mojang.authlib.GameProfile nativeProfile;
    private ProtocolInfo<ClientGamePacketListener> play;
    private DimensionType dimension;
    private boolean configurationFinished;
    private boolean batchOpen;
    private boolean chunksStarted;
    private int batchChunks;
    private int chunks;
    private int failures;
    private int decodedCount;
    private int negativeCount;
    private int centerX;
    private int centerZ;
    private int cacheRadius;
    private final Set<Long> chunkCoordinates = new HashSet<>();
    private boolean centerSeen;
    private boolean javaSelectorSeen;
    private boolean markedSelectorSeen;
    private int lastState;

    private record Input(String state, int id, String name, byte[] body) {}
    private static final class Violation extends RuntimeException {
        Violation(String message) { super(message); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: ProtocolOracle manifest.tsv");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ProtocolOracle oracle = new ProtocolOracle();
        oracle.run(Path.of(args[0]).toAbsolutePath());
        System.exit(oracle.failures == 0 ? 0 : 1);
    }

    private void run(Path manifest) throws Exception {
        Path root = manifest.getParent();
        List<Input> inputs = new ArrayList<>();
        for (String line : Files.readAllLines(manifest)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            require(fields.length == 3, "manifest requires exactly three tab-separated fields");
            require(Set.of("login", "config", "play").contains(fields[0]), "unknown manifest state");
            int id = Integer.parseInt(fields[1]);
            require(id >= 0, "negative packet id");
            Path file = root.resolve(fields[2]).normalize();
            require(file.startsWith(root), "manifest path escapes capture directory");
            inputs.add(new Input(fields[0], id, fields[2], Files.readAllBytes(file)));
        }
        for (Input input : inputs) {
            try {
                int state = switch (input.state) { case "login" -> 0; case "config" -> 1; default -> 2; };
                require(state >= lastState, "state moved backwards");
                lastState = state;
                if (state == 2 && access == null) buildRegistryContext();
                Packet<?> decoded = decode(input);
                decodedCount++;
                seen.add(decoded.getClass().getSimpleName());
                checkPacket(decoded);
                log("PASS " + input.state + "/" + input.id + " " + decoded.getClass().getSimpleName());
            } catch (Throwable failure) {
                fail(input.state + "/" + input.id, failure);
            }
        }
        for (String type : List.of("ClientboundLoginFinishedPacket", "ClientboundRegistryDataPacket",
                "ClientboundUpdateTagsPacket", "ClientboundUpdateEnabledFeaturesPacket",
                "ClientboundFinishConfigurationPacket", "ClientboundLoginPacket", "ClientboundGameEventPacket",
                "ClientboundPlayerAbilitiesPacket", "ClientboundSetChunkCacheCenterPacket",
                "ClientboundSetChunkCacheRadiusPacket", "ClientboundChunkBatchStartPacket",
                "ClientboundChunkBatchFinishedPacket", "ClientboundLevelChunkWithLightPacket",
                "ClientboundPlayerPositionPacket", "ClientboundSystemChatPacket",
                "ClientboundSetTitleTextPacket", "ClientboundSetSubtitleTextPacket",
                "ClientboundSetTitlesAnimationPacket", "ClientboundOpenScreenPacket", "ClientboundContainerSetContentPacket",
                "ClientboundContainerClosePacket")) {
            if (!seen.contains(type)) fail("coverage", new Violation("missing " + type));
        }
        if (!javaSelectorSeen || !markedSelectorSeen) fail("avatar-coverage", new Violation("both unmarked Java and exact-marker menus must be decoded"));
        if (batchOpen) fail("chunk-batch", new Violation("batch never finished"));
        if (centerSeen && !chunkCoordinates.contains(chunkKey(centerX, centerZ)))
            fail("chunk-center", new Violation("initial spawn chunk was not successfully decoded"));
        if (!chunksStarted) fail("game-event", new Violation("LEVEL_CHUNKS_LOAD_START was not received"));
        if (access != null) {
            try { negativeControls(); }
            catch (Throwable failure) { fail("negative-controls", failure); }
        } else {
            fail("negative-controls", new Violation("registry context unavailable; controls not run"));
        }
        log("SUMMARY decoded=" + decodedCount + " packets=" + inputs.size() + " chunks=" + chunks
                + " negative-controls=" + negativeCount + " failures=" + failures + " render/E2E=NOT_TESTED");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Packet<?> decode(Input input) {
        StreamCodec codec = switch (input.state) {
            case "login" -> LoginProtocols.CLIENTBOUND.codec();
            case "config" -> ConfigurationProtocols.CLIENTBOUND.codec();
            case "play" -> { require(play != null, "no network registry context"); yield play.codec(); }
            default -> throw new Violation("unknown state");
        };
        FriendlyByteBuf framed = new FriendlyByteBuf(Unpooled.buffer());
        ByteBuf roundTrip = Unpooled.buffer();
        try {
            framed.writeVarInt(input.id);
            framed.writeBytes(input.body);
            Packet<?> packet = (Packet<?>) codec.decode(framed);
            require(!framed.isReadable(), "official codec left trailing bytes=" + framed.readableBytes());
            codec.encode(roundTrip, packet);
            Packet<?> again = (Packet<?>) codec.decode(roundTrip);
            require(!roundTrip.isReadable() && again.type().equals(packet.type()), "official codec round-trip changed type or left bytes");
            return packet;
        } finally {
            framed.release();
            roundTrip.release();
        }
    }

    private void checkPacket(Packet<?> packet) {
        if (packet instanceof ClientboundLoginFinishedPacket p) {
            require(p.gameProfile().id().equals(TEST_UUID), "login UUID differs from public fixture identity");
            require(p.gameProfile().name().equals("OraclePlayer"), "login name differs from public fixture identity");
            nativeProfile = p.gameProfile();
        } else if (packet instanceof ClientboundRegistryDataPacket p) {
            require(!configurationFinished, "registry sent after configuration finish");
            require(entries.putIfAbsent(p.registry(), p.entries()) == null, "registry sent twice");
            Set<Identifier> ids = new HashSet<>();
            for (var entry : p.entries()) {
                require(entry.data().isPresent(), "network fixture omits full registry NBT");
                require(ids.add(entry.id()), "duplicate registry entry identifier");
            }
        } else if (packet instanceof ClientboundUpdateTagsPacket p) {
            require(!configurationFinished, "tags sent after configuration finish");
            p.tags().forEach((key, value) -> require(tags.putIfAbsent(key, value) == null, "duplicate tag registry"));
        } else if (packet instanceof ClientboundUpdateEnabledFeaturesPacket p) {
            require(p.features().contains(Identifier.parse("minecraft:vanilla")), "vanilla feature flag missing");
        } else if (packet instanceof ClientboundFinishConfigurationPacket) {
            require(!configurationFinished, "configuration finished twice");
            require(!entries.isEmpty() && !tags.isEmpty(), "configuration incomplete before finish");
            configurationFinished = true;
        } else if (packet instanceof ClientboundLoginPacket p) {
            checkJoin(p);
        } else if (packet instanceof ClientboundGameEventPacket p) {
            require(p.getEvent() == ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START && p.getParam() == 0f,
                    "expected level chunks load start game event with parameter zero");
            require(chunks == 0, "chunk loading start event arrived after chunks");
            chunksStarted = true;
        } else if (packet instanceof ClientboundSetChunkCacheCenterPacket p) {
            centerX = p.getX(); centerZ = p.getZ(); centerSeen = true;
        } else if (packet instanceof ClientboundSetChunkCacheRadiusPacket p) {
            require(p.getRadius() >= 2, "chunk cache radius below two");
            cacheRadius = p.getRadius();
        } else if (packet instanceof ClientboundChunkBatchStartPacket) {
            require(!batchOpen, "nested chunk batch");
            batchOpen = true; batchChunks = 0;
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket p) {
            require(batchOpen && centerSeen, "chunk outside batch or before cache center");
            checkChunk(p);
            batchChunks++; chunks++;
        } else if (packet instanceof ClientboundChunkBatchFinishedPacket p) {
            require(batchOpen && p.batchSize() == batchChunks && batchChunks > 0, "batch size differs from actual decoded chunks");
            batchOpen = false;
        } else if (packet instanceof ClientboundPlayerPositionPacket p) {
            var pos = p.change().position();
            require(p.id() > 0 && p.relatives().isEmpty(), "initial teleport must have positive id and absolute position");
            require(Double.isFinite(pos.x()) && Double.isFinite(pos.y()) && Double.isFinite(pos.z()), "non-finite teleport position");
            require(pos.y() >= dimension.minY() && pos.y() < dimension.minY() + dimension.height(), "teleport Y outside network dimension");
            require(Math.floor(pos.x() / 16) == centerX && Math.floor(pos.z() / 16) == centerZ, "teleport outside initial chunk center");
        } else if (packet instanceof ClientboundPlayerAbilitiesPacket p) {
            require(p.isInvulnerable() && p.isFlying() && p.canFly(), "limbo abilities must permit stable flying");
        } else if (packet instanceof ClientboundSystemChatPacket p) {
            checkChat(p);
        } else if (packet instanceof ClientboundSetTitleTextPacket p) {
            checkText(p.text(), "Oracle title", "gold", true);
        } else if (packet instanceof ClientboundSetSubtitleTextPacket p) {
            checkText(p.text(), "Oracle subtitle", "gray", false);
        } else if (packet instanceof ClientboundSetTitlesAnimationPacket p) {
            require(p.getFadeIn() == 10 && p.getStay() == 60 && p.getFadeOut() == 10, "title animation differs from public fixture");
        } else if (packet instanceof ClientboundOpenScreenPacket) {
            int window = ((Number) selectorAccessor(packet, "getContainerId", "containerId")).intValue();
            require(window == 5 || window == 6, "selector window differs");
            require(selectorAccessor(packet, "getType", "type") == net.minecraft.world.inventory.MenuType.GENERIC_9x3, "selector is not three rows");
            Component title = (Component) selectorAccessor(packet, "getTitle", "title");
            require(title.getString().equals("请选择角色") && title.getStyle().getColor() != null &&
                    title.getStyle().getColor().getValue() == 0x22D3EE, "selector title/brand color differs");
        } else if (packet instanceof ClientboundContainerSetContentPacket) {
            int window = ((Number) selectorAccessor(packet, "getContainerId", "containerId")).intValue();
            require(((Number) selectorAccessor(packet, "getStateId", "stateId")).intValue() == 1, "selector state differs");
            List<?> items = (List<?>) selectorAccessor(packet, "getItems", "items");
            require(((net.minecraft.world.item.ItemStack) selectorAccessor(packet, "getCarriedItem", "carriedItem")).isEmpty(), "selector cursor is not empty");
            if (window == 0) {
                require(items.size() == net.minecraft.world.inventory.InventoryMenu.SHIELD_SLOT + 1, "full inventory must include crafting, armor and offhand");
                for (Object item : items) require(((net.minecraft.world.item.ItemStack) item).isEmpty(), "player inventory prediction was not cleared");
                return;
            }
            require(window == 5 || window == 6, "selector content window differs");
            require(items.size() == 63, "selector must contain 27 menu + 36 authoritative player slots");
            for (int slot=0; slot<items.size(); slot++) {
                var stack = (net.minecraft.world.item.ItemStack) items.get(slot);
                if (slot != 12 && slot != 14) { require(stack.isEmpty(), "selector unexpected occupied slot " + slot); continue; }
                require(stack.getItem() == net.minecraft.world.item.Items.PLAYER_HEAD && stack.getCount() == 1, "selector item is not official player head");
                require(stack.has(net.minecraft.core.component.DataComponents.PROFILE), "selector profile missing");
                Object resolvedProfile = stack.get(net.minecraft.core.component.DataComponents.PROFILE);
                Object gameProfile = selectorAccessor(resolvedProfile, "partialProfile", "gameProfile");
                require((slot == 12 ? new UUID(0, 42) : TEST_UUID).equals(selectorAccessor(gameProfile, "id", "getId")),
                        "selector role UUID differs from public fixture");
                require((slot == 12 ? "MicrosoftName" : "OraclePlayer").equals(selectorAccessor(gameProfile, "name", "getName")),
                        "selector role profile name differs from public fixture");
                require(stack.has(net.minecraft.core.component.DataComponents.LORE), "selector lore missing");
                Component name = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
                String category = slot == 12 ? "微软角色" : "正版角色";
                String profileName = slot == 12 ? "MicrosoftName" : "OraclePlayer";
                require(name != null && name.getString().equals(category + " » " + profileName), "selector role label differs");
                checkItemRoot(name, 0xFFFFFF, "name");
                final boolean[] brand = {false};
                name.visit((style, text) -> {
                    if (text.equals(category)) {
                        require(style.getColor() != null && style.getColor().getValue() == 0x22D3EE, "role category must use brand cyan, not success green");
                        brand[0] = true;
                    }
                    return Optional.empty();
                }, Style.EMPTY);
                require(brand[0], "selector role brand fragment missing");
                Object lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
                List<?> lines = (List<?>) selectorAccessor(lore, "lines", "lines");
                require(lines.size() == 1 && ((Component) lines.get(0)).getString().equals("点击进入服务器"), "selector lore differs");
                checkItemRoot((Component) lines.get(0), 0xAAAAAA, "lore");
                var marker = Optional.ofNullable(stack.getComponentsPatch().get(stack.getPrototype(), net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA));
                if (window == 5) require(marker == null || marker.isEmpty(), "unmarked Java selector must not carry CMD");
                else {
                    require(marker != null && marker.isPresent(), "opt-in avatar marker missing");
                    checkAvatarMarker(marker.get(), slot == 12 ? 0x4B5901 : 0x4B5902);
                }
            }
            if (window == 5) javaSelectorSeen = true; else markedSelectorSeen = true;
        } else if (packet instanceof ClientboundContainerClosePacket) {
            int window = ((Number) selectorAccessor(packet, "getContainerId", "containerId")).intValue();
            require(window == 5 || window == 6, "selector close window differs");
        }
    }

    private static void checkItemRoot(Component text, int color, String field) {
        require(text.getStyle().getColor() != null && text.getStyle().getColor().getValue() == color,
                "selector " + field + " base color is not explicit");
        var encoded = ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, text).getOrThrow();
        require(encoded.isJsonObject() && encoded.getAsJsonObject().has("italic") &&
                encoded.getAsJsonObject().get("italic").isJsonPrimitive() &&
                encoded.getAsJsonObject().get("italic").getAsJsonPrimitive().isBoolean() &&
                !encoded.getAsJsonObject().get("italic").getAsBoolean(), "selector " + field + " must explicitly disable italic");
    }

    private static void checkAvatarMarker(Object cmd, int expected) {
        // Fixed official families: 767/768 record value(int), then four-list CustomModelData from 769 onward.
        Object first = selectorAccessor(cmd, "value", "floats");
        if (first instanceof Integer value) require(value == expected, "legacy avatar CMD sentinel differs");
        else {
            require(first instanceof List<?>, "unknown official CMD shape");
            List<?> floats = (List<?>) first;
            require(floats.size() == 1 && floats.get(0) instanceof Float value && Float.isFinite(value) &&
                    Float.compare(value, (float) expected) == 0, "avatar CMD float must be exactly one private finite sentinel");
            for (String field : List.of("flags", "strings", "colors")) {
                Object values = selectorAccessor(cmd, field, field);
                require(values instanceof List<?> && ((List<?>) values).isEmpty(), "avatar CMD auxiliary list must be empty");
            }
        }
    }

    /** Fixed official getter/record families only; this does not parse or fabricate any packet bytes. */
    private static Object selectorAccessor(Object value, String getter, String record) {
        for (String name : List.of(getter, record)) {
            try { return value.getClass().getMethod(name).invoke(value); }
            catch (NoSuchMethodException absent) { /* Next pinned official accessor family. */ }
            catch (ReflectiveOperationException failure) { throw new Violation("Official selector accessor failed: " + name); }
        }
        throw new Violation("Official selector accessor missing: " + getter + "/" + record);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void buildRegistryContext() {
        require(configurationFinished, "play before configuration finished");
        RegistryAccess.Frozen statics = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        List<Registry.PendingTags<?>> pending = new ArrayList<>();
        for (var entry : tags.entrySet()) {
            Optional<Registry<Object>> registry = statics.lookup((ResourceKey) entry.getKey());
            if (registry.isPresent()) {
                validateTagIds(registry.get(), entry.getValue());
                pending.add(registry.get().prepareTagReload(entry.getValue().resolve(registry.get())));
            }
        }
        Map<ResourceKey<? extends Registry<?>>, RegistryDataLoader.NetworkedRegistryData> network = new LinkedHashMap<>();
        entries.forEach((key, value) -> network.put(key,
                new RegistryDataLoader.NetworkedRegistryData(value, tags.getOrDefault(key, NetworkPayload.EMPTY))));
        var loaded = RegistryDataLoader.load(network, ResourceProvider.EMPTY,
                TagLoader.buildUpdatedLookups(statics, pending), RegistryDataLoader.SYNCHRONIZED_REGISTRIES, Runnable::run).join();
        pending.forEach(Registry.PendingTags::apply);
        int entryCount = 0;
        for (var entry : entries.entrySet()) {
            Registry registry = loaded.lookupOrThrow((ResourceKey) entry.getKey());
            require(registry.size() == entry.getValue().size(), "loaded registry size differs from wire entries");
            for (int id = 0; id < entry.getValue().size(); id++) {
                var expected = entry.getValue().get(id).id();
                Object value = registry.byId(id);
                require(value != null && expected.equals(registry.getKey(value)) && registry.getId(value) == id,
                        "loaded registry numeric id/order differs from wire order");
                entryCount++;
            }
            validateTagIds(registry, tags.getOrDefault(entry.getKey(), NetworkPayload.EMPTY));
        }
        access = new RegistryAccess.ImmutableRegistryAccess(Stream.concat(statics.registries(), loaded.registries())).freeze();
        // 26.1+ holder defaults are baked from the official registered initializers only after registry/tag loading.
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(access).forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        play = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access));
        dimension = access.lookupOrThrow(Registries.DIMENSION_TYPE).getValue(OVERWORLD);
        require(dimension != null, "network dimension registry has no overworld");
        require(dimension.minY() % 16 == 0 && dimension.height() % 16 == 0, "dimension is not section aligned");
        log("PASS registry-context registries=" + entries.size() + " entries=" + entryCount
                + " entry-id-order=VERIFIED minY=" + dimension.minY() + " height=" + dimension.height());
    }

    private static void validateTagIds(Registry<?> registry, NetworkPayload payload) {
        for (var ids : payload.tags().values()) {
            for (int id : ids) require(id >= 0 && registry.byId(id) != null, "tag references absent registry id");
        }
    }

    private void checkJoin(ClientboundLoginPacket packet) {
        CommonPlayerSpawnInfo spawn = packet.commonPlayerSpawnInfo();
        require(spawn.dimension().identifier().equals(OVERWORLD), "join dimension differs from public overworld fixture");
        require(spawn.dimensionType().is(ResourceKey.create(Registries.DIMENSION_TYPE, OVERWORLD)), "join dimension type resolves to wrong registry entry");
        require(spawn.dimensionType().value().equals(dimension), "join dimension context differs from network registry");
        require(packet.levels().contains(spawn.dimension()), "join world list omits current dimension");
        require(spawn.gameType() == GameType.ADVENTURE, "join game mode must be adventure");
        require(spawn.previousGameType().isEmpty(), "initial join must preserve absent previous game mode");
        require(spawn.seaLevel() >= dimension.minY() && spawn.seaLevel() < dimension.minY() + dimension.height(), "join sea level outside dimension");
        require(packet.chunkRadius() >= 2 && packet.simulationDistance() >= 2, "join distance below two");
    }

    private void checkChunk(ClientboundLevelChunkWithLightPacket packet) {
        require(cacheRadius >= 2 && Math.abs((long) packet.x() - centerX) <= cacheRadius
                && Math.abs((long) packet.z() - centerZ) <= cacheRadius, "void chunk outside advertised cache radius");
        require(!chunkCoordinates.contains(chunkKey(packet.x(), packet.z())), "duplicate initial chunk coordinates");
        int sections = dimension.height() / 16;
        FriendlyByteBuf data = packet.chunkData().getReadBuffer();
        try {
            var factory = PalettedContainerFactory.create(access);
            for (int index = 0; index < sections; index++) {
                int sectionY = dimension.minY() / 16 + index;
                require(data.isReadable(), "not enough chunk sections for dimension height");
                LevelChunkSection section = new LevelChunkSection(factory);
                section.read(data); // Official LevelChunkSection + both official PalettedContainers.
                require(section.hasOnlyAir() && !section.hasFluid(), "void section has nonzero block/fluid count");
                for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++)
                    require(section.getBlockState(x, y, z).isAir(), "void section contains non-air block state");
                for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++)
                    require(section.getNoiseBiome(x, y, z).is(ResourceKey.create(Registries.BIOME, PLAINS)),
                            "section biome id does not resolve to plains in network registry");
                require(sectionY * 16 >= dimension.minY() && (sectionY + 1) * 16 <= dimension.minY() + dimension.height(), "section Y outside dimension");
            }
            require(!data.isReadable(), "extra section bytes beyond dimension height");
        } finally { data.release(); }
        var maps = packet.chunkData().getHeightmaps();
        require(maps.containsKey(Heightmap.Types.MOTION_BLOCKING), "motion-blocking heightmap missing");
        int bits = 32 - Integer.numberOfLeadingZeros(dimension.height());
        for (long[] values : maps.values()) {
            var storage = new SimpleBitStorage(bits, 256, values);
            for (int i = 0; i < 256; i++) require(storage.get(i) == 0, "void heightmap has nonempty surface");
        }
        packet.chunkData().forEachBlockEntityTag(packet.x(), packet.z(), (pos, type, nbt) -> {
            throw new Violation("void chunk contains block entity");
        });
        var light = packet.lightData();
        validateLights(light.skyYMask(), light.emptySkyYMask(), light.skyUpdates(), sections + 2);
        validateLights(light.blockYMask(), light.emptyBlockYMask(), light.blockUpdates(), sections + 2);
        chunkCoordinates.add(chunkKey(packet.x(), packet.z()));
        // These masks have already been decoded by vanilla's BYTE_ARRAY/BitSet codec, not long-array emulation.
        log("PASS chunk-sections count=" + sections + " Y=" + dimension.minY() / 16 + ".."
                + ((dimension.minY() + dimension.height()) / 16 - 1) + " palettes=VANILLA light-mask=VANILLA_BYTE_ARRAY");
    }

    private static long chunkKey(int x, int z) { return (long) x << 32 | (z & 0xffffffffL); }

    private static void validateLights(BitSet populated, BitSet empty, List<byte[]> arrays, int count) {
        require(populated.length() <= count && empty.length() <= count, "light mask outside dimension sections plus boundaries");
        require(!populated.intersects(empty), "light mask marked populated and empty simultaneously");
        require(populated.cardinality() == arrays.size(), "light mask population differs from update array count");
        for (byte[] values : arrays) require(values.length == 2048, "light update array must contain 2048 bytes");
    }

    private static void checkChat(ClientboundSystemChatPacket packet) {
        require(!packet.overlay(), "fixture chat unexpectedly overlays action bar");
        if (packet.content().getString().equals("验证 𠀋完整链接")) {
            var children = packet.content().getSiblings();
            require(children.size() == 2, "rich text span boundaries lost");
            var style = children.get(0).getStyle();
            require(style.getColor() != null && style.getColor().getValue() == 0x22d3ee, "RGB color lost");
            require(style.isBold() && style.isItalic() && style.isUnderlined() && style.isStrikethrough(), "rich styles lost");
            var click = children.get(1).getStyle().getClickEvent();
            require(click != null, "rich text URL lost");
            String url;
            try {
                java.lang.reflect.Method getter;
                try { getter = click.getClass().getMethod("uri"); }
                catch (NoSuchMethodException oldApi) { getter = click.getClass().getMethod("getValue"); }
                url = getter.invoke(click).toString();
            } catch (ReflectiveOperationException failure) { throw new Violation("unknown official click-event accessor"); }
            require(url.equals("https://example.invalid/limbo-oracle?code=public-fixture&scope=xbox%3A%2F%2Fsignin"), "full rich URL changed or truncated");
            return;
        }
        checkText(packet.content(), "Oracle link", "aqua", true);
        require(packet.content().getStyle().getClickEvent() instanceof ClickEvent.OpenUrl,
                "chat lost open_url click event (old clickEvent/value is silently ignored by vanilla)");
        var click = (ClickEvent.OpenUrl) packet.content().getStyle().getClickEvent();
        require(click.uri().equals(URI.create(TEST_URL)), "chat URL differs from public fixture");
    }

    private static void checkText(Component text, String expected, String color, boolean bold) {
        require(text.getString().equals(expected), "decoded text differs from public fixture");
        require(text.getStyle().getColor() != null && text.getStyle().getColor().serialize().equals(color), "decoded text color differs from public fixture");
        require(text.getStyle().isBold() == bold, "decoded text bold style differs from public fixture");
    }

    private void negativeControls() {
        nativeItemPositiveControl();
        var holder = access.lookupOrThrow(Registries.DIMENSION_TYPE).get(OVERWORLD).orElseThrow();
        var spawn = new CommonPlayerSpawnInfo(holder, Level.OVERWORLD, 0L, GameType.ADVENTURE,
                Optional.empty(), false, false, Optional.empty(), 0, 63);
        var join = new ClientboundLoginPacket(1, false, Set.of(Level.OVERWORLD), 0, 2, 2,
                false, true, false, spawn, false, false);
        Input validJoin = encoded(join);
        checkJoin((ClientboundLoginPacket) decode(validJoin));
        FriendlyByteBuf name = new FriendlyByteBuf(Unpooled.buffer());
        byte[] encodedName;
        try { name.writeUtf(OVERWORLD.toString()); encodedName = bytes(name); }
        finally { name.release(); }
        int secondName = lastIndexOf(validJoin.body, encodedName);
        require(secondName >= 0, "cannot locate official join dimension for historical mutation");
        Input doubleDimension = new Input("play", validJoin.id, "duplicate-dimension",
                insert(validJoin.body, secondName + encodedName.length, encodedName));
        reject("historical-duplicate-dimension-string", () -> checkJoin((ClientboundLoginPacket) decode(doubleDimension)));

        var text = Component.literal("Oracle link").withStyle(style -> style
                .withColor(TextColor.parseColor("aqua").getOrThrow()).withBold(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(TEST_URL))));
        Input goodChat = encoded(new ClientboundSystemChatPacket(text, false));
        checkChat((ClientboundSystemChatPacket) decode(goodChat));
        Input extraRootName = new Input("play", goodChat.id, "named-root",
                insert(goodChat.body, 1, new byte[] {0, 0}));
        reject("historical-nbt-root-extra-00-00", () -> checkChat((ClientboundSystemChatPacket) decode(extraRootName)));

        CompoundTag old = new CompoundTag();
        old.putString("text", "Oracle link"); old.putString("color", "aqua"); old.putBoolean("bold", true);
        CompoundTag click = new CompoundTag(); click.putString("action", "open_url"); click.putString("value", TEST_URL);
        old.put("clickEvent", click);
        RegistryFriendlyByteBuf body = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        try {
            body.writeNbt(old); body.writeBoolean(false);
            Input oldClick = new Input("play", goodChat.id, "old-clickEvent", bytes(body));
            ClientboundSystemChatPacket decoded = (ClientboundSystemChatPacket) decode(oldClick);
            reject("historical-clickEvent-value-semantic-loss", () -> checkChat(decoded));
        } finally { body.release(); }
        log("PASS positive-controls vanilla-generated join/chat and historical negative mutations");
    }

    private void nativeItemPositiveControl() {
        // Real defaults are required: an empty stand-in map must not make the oracle appear healthy.
        var head = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD);
        require(Integer.valueOf(64).equals(head.get(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE)),
                "native player-head default components were not initialized");
        require(nativeProfile != null, "native profile control requires the independently validated login profile");
        head.set(net.minecraft.core.component.DataComponents.PROFILE,
                net.minecraft.world.item.component.ResolvableProfile.createResolved(nativeProfile));
        head.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Native profile control"));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        try {
            net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, head);
            var decoded = net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            require(!buffer.isReadable() && decoded.getItem() == net.minecraft.world.item.Items.PLAYER_HEAD && decoded.getCount() == 1,
                    "native player-head stream codec control failed");
            Object profile = Objects.requireNonNull(decoded.get(net.minecraft.core.component.DataComponents.PROFILE));
            Object gameProfile = selectorAccessor(profile, "partialProfile", "gameProfile");
            require(TEST_UUID.equals(selectorAccessor(gameProfile, "id", "getId")) &&
                    "OraclePlayer".equals(selectorAccessor(gameProfile, "name", "getName")), "native profile control lost UUID/name");
            log("PASS native-player-head-profile-control defaults=VANILLA identity=UUID_AND_NAME codec=ROUND_TRIP");
        } finally { buffer.release(); }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Input encoded(Packet<?> packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ((StreamCodec) play.codec()).encode(buffer, packet);
            int id = buffer.readVarInt();
            return new Input("play", id, "official-control", bytes(buffer));
        } finally { buffer.release(); }
    }

    private void reject(String label, Runnable action) {
        try { action.run(); }
        catch (Throwable rejected) {
            negativeCount++;
            log("PASS negative=" + label + " rejected=" + safeReason(rejected));
            return;
        }
        throw new Violation("negative control unexpectedly accepted: " + label);
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] result = new byte[buffer.readableBytes()]; buffer.getBytes(buffer.readerIndex(), result); return result;
    }
    private static byte[] insert(byte[] original, int at, byte[] extra) {
        byte[] output = new byte[original.length + extra.length];
        System.arraycopy(original, 0, output, 0, at);
        System.arraycopy(extra, 0, output, at, extra.length);
        System.arraycopy(original, at, output, at + extra.length, original.length - at);
        return output;
    }
    private static int lastIndexOf(byte[] haystack, byte[] needle) {
        for (int at = haystack.length - needle.length; at >= 0; at--) {
            boolean equal = true;
            for (int i = 0; i < needle.length; i++) if (haystack[at + i] != needle[i]) { equal = false; break; }
            if (equal) return at;
        }
        return -1;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new Violation(message); }
    private static void log(String message) { System.out.println(PREFIX + message); }
    private void fail(String target, Throwable failure) { failures++; log("FAIL " + target + " " + safeReason(failure)); }
    private static String safeReason(Throwable failure) {
        if (failure instanceof Violation) return failure.getMessage();
        // Vanilla exceptions may embed whole component payloads. Do not print their messages.
        StringBuilder names = new StringBuilder(failure.getClass().getSimpleName());
        Throwable cause = failure.getCause();
        for (int depth = 0; cause != null && depth < 3; depth++, cause = cause.getCause())
            names.append("/").append(cause.getClass().getSimpleName());
        return names.toString();
    }
}
