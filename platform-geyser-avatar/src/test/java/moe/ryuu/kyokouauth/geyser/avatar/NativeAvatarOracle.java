package moe.ryuu.kyokouauth.geyser.avatar;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.item.Items;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.java.BuiltInJavaRegistries;
import org.geysermc.geyser.registry.type.ItemMapping;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.ComponentCache;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.ResolvableProfile;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.CustomModelData;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Test-only oracle: invoke after real Geyser registry initialization; never fake its singleton/logger. */
final class NativeAvatarOracle {
    private static void ready() {
        require(GeyserImpl.getInstance() != null, "Native translation requires the real initialized Geyser lifecycle");
    }

    static void custom(List<CustomItemDefinition> registered) throws Exception {
        ready();
        require(registered.size() == 2, "Both production definitions must be present");
        ItemMapping mapping = headMapping();
        var own = mapping.getCustomItemDefinitions().values().stream()
                .filter(value -> value.definition().bedrockIdentifier().toString().startsWith("kyokouauth:role_")).toList();
        require(own.size() == 2, "Production head mappings did not retain both registered definitions");
        var u = own.stream().filter(value -> value.definition().bedrockIdentifier().toString().equals("kyokouauth:role_u"))
                .findFirst().orElseThrow().itemDefinition();
        var j = own.stream().filter(value -> value.definition().bedrockIdentifier().toString().equals("kyokouauth:role_j"))
                .findFirst().orElseThrow().itemDefinition();
        GeyserSession session = session();
        var vanilla = mapping.getBedrockDefinition();
        var block = new SimpleBlockDefinition("minecraft:player_head", 999, NbtMap.EMPTY);
        for (Float marker : new Float[]{null, 0F, (float) 0x4B5900, (float) 0x4B5901, (float) 0x4B5902,
                (float) 0x4B5903, Float.NaN, Float.POSITIVE_INFINITY}) {
            DataComponents components = components(marker);
            Object originalProfile = components.get(DataComponentTypes.PROFILE);
            var before = new HashMap<>(components.getDataComponents());
            ItemData.Builder output = ItemData.builder().definition(vanilla).blockDefinition(block).count(1);
            // The actual fixed native post-player-head entry, with its actual populated registry.
            ItemTranslator.translateCustomItem(session, 1, components, output, mapping);
            ItemData translated = output.build();
            if (marker != null && marker == (float) 0x4B5901) {
                require(translated.getDefinition() == u && translated.getBlockDefinition() == null, "Microsoft definition did not clear the head block");
            } else if (marker != null && marker == (float) 0x4B5902) {
                require(translated.getDefinition() == j && translated.getBlockDefinition() == null, "Premium definition did not clear the head block");
            } else {
                require(translated.getDefinition() == vanilla && translated.getBlockDefinition() == block,
                        "Unmarked, adjacent or non-finite CMD changed an ordinary head");
            }
            require(originalProfile == components.get(DataComponentTypes.PROFILE), "Real Java PROFILE was replaced");
            equal(before, components.getDataComponents(), "Native custom path mutated input components");
        }
        ItemMapping other = Registries.ITEMS.get().values().iterator().next().getMapping(Items.STONE);
        ItemData.Builder untouched = ItemData.builder().definition(other.getBedrockDefinition()).count(1);
        ItemTranslator.translateCustomItem(session, 1, components((float) 0x4B5901), untouched, other);
        require(untouched.build().getDefinition() == other.getBedrockDefinition(), "Custom mapping escaped the player-head Java item");
    }

    static void styles() throws Exception {
        ready();
        String source = System.getProperty("kyokou.avatar.selectorPacket");
        require(source != null, "Native style regression requires the explicit isolated real-Gate selector packet");
        Path path = Path.of(source);
        require(path.getFileName().toString().equals("role-selector.bin") && !Files.isSymbolicLink(path) &&
                Files.size(path) > 0 && Files.size(path) <= 32768, "Unsafe synthetic selector packet");
        ByteBuf buffer = Unpooled.wrappedBuffer(Files.readAllBytes(path));
        ClientboundContainerSetContentPacket packet;
        try {
            // Decode the exact official serialize() export, not reconstructed components or private packet layouts.
            packet = new ClientboundContainerSetContentPacket(buffer);
            require(!buffer.isReadable(), "Trailing bytes after the official selector packet");
        } finally { buffer.release(); }
        require(packet.getContainerId() > 0 && packet.getStateId() >= 0 && packet.getItems().length == 63 &&
                packet.getCarriedItem() == null, "Not a complete isolated native selector GUI");
        for (int slot = 0; slot < 63; slot++) if (slot != 12 && slot != 14)
            require(packet.getItems()[slot] == null, "Unexpected item in the isolated selector fixture");
        GeyserSession session = session();
        var defaults = Items.PLAYER_HEAD.gatherComponents(session.getComponentCache(), null);
        require(defaults.get(DataComponentTypes.RARITY) != null && defaults.get(DataComponentTypes.ITEM_NAME) != null,
                "Full translator must gather real registry head defaults, including rarity and item name");
        List<String> styleFailures = new ArrayList<>();
        for (int slot : new int[]{12, 14}) {
            ItemStack input = packet.getItems()[slot];
            String label = slot == 12 ? "Microsoft role" : "Java role";
            String roleName = slot == 12 ? "URole" : "JRole";
            String expectedName = label + " » " + roleName;
            require(input != null && input.getId() == Items.PLAYER_HEAD.javaId() && input.getAmount() == 1,
                    "Selector fixture is not the fixed registry player head");
            var patch = input.getDataComponentsPatch();
            require(patch != null, "Real selector head has no patch");
            equal(expectedName, syntheticText(patch.get(DataComponentTypes.CUSTOM_NAME), 'f'), "Fixture custom name is not synthetic role text");
            var lore = patch.get(DataComponentTypes.LORE);
            require(lore != null && lore.size() == 1 && syntheticText(lore.get(0), '7').equals("Click to enter the server"),
                    "Fixture lore is not the isolated enter-server text");
            var profile = patch.get(DataComponentTypes.PROFILE);
            require(profile != null && profile.getProfile() != null && roleName.equals(profile.getProfile().getName()),
                    "Real Java PROFILE is not the synthetic role fixture");
            var marker = patch.get(DataComponentTypes.CUSTOM_MODEL_DATA);
            require(marker != null && marker.floats().equals(List.of((float) (slot == 12 ? 0x4B5901 : 0x4B5902))) &&
                    marker.flags().isEmpty() && marker.strings().isEmpty() && marker.colors().isEmpty(), "Private role CMD changed");
            System.out.println("KYOKOU_SELECTOR_COMPONENTS slot=" + slot + " Name=" + shape(patch.get(DataComponentTypes.CUSTOM_NAME)) +
                    " Lore=" + shape(lore.get(0)));
            var before = new HashMap<>(patch.getDataComponents());
            // This is the complete native production entry: registry defaults, head/profile, rarity, name/lore and custom mapping.
            ItemData output = ItemTranslator.translateToBedrock(session, input);
            require(profile == patch.get(DataComponentTypes.PROFILE), "Complete native translator replaced Java PROFILE");
            equal(before, patch.getDataComponents(), "Complete native translator mutated GUI components");
            equal("kyokouauth:role_" + (slot == 12 ? "u" : "j"), output.getDefinition().getIdentifier(), "Complete path lost private CMD mapping");
            require(output.getBlockDefinition() == null, "Complete custom-avatar path retained a head block definition");
            NbtMap display = output.getTag().getCompound("display");
            String actualName = display.getString("Name");
            List<String> actualLore = display.getList("Lore", NbtType.STRING);
            equal(expectedName, plain(actualName), "Complete native name text differs");
            equal(List.of("Click to enter the server"), actualLore.stream().map(NativeAvatarOracle::plain).toList(), "Complete native lore text differs");
            // Only these previously allowlisted synthetic strings are printed; no PROFILE/skin/credential payload is logged.
            System.out.println("KYOKOU_SELECTOR_NATIVE slot=" + slot + " defaultRarity=" + defaults.get(DataComponentTypes.RARITY) +
                    " Name=" + escaped(actualName) + " Lore=" + actualLore.stream().map(NativeAvatarOracle::escaped).toList());
            String expectedColors = "b".repeat(label.length()) + "f7f" + "f".repeat(roleName.length());
            try { styled(actualName, expectedName, expectedColors, 'f', true); }
            catch (AssertionError failure) { styleFailures.add("slot=" + slot + " Name: " + failure.getMessage()); }
            try { styled(actualLore.get(0), "Click to enter the server", "7".repeat("Click to enter the server".length()), '5', true); }
            catch (AssertionError failure) { styleFailures.add("slot=" + slot + " Lore: " + failure.getMessage()); }
        }
        require(styleFailures.isEmpty(), String.join("; ", styleFailures));
        // Keep the final section sign explicit: checking only the final color used to miss a purple prefix.
        rejects("\u00a7r\u00a75x\u00a77y", "xy", "77", '5', true);
        rejects("\u00a7r\u00a7o\u00a77x", "x", "7", '5', true);
        var ordinary = new DataComponents(new HashMap<>(packet.getItems()[12].getDataComponentsPatch().getDataComponents()));
        ordinary.getDataComponents().remove(DataComponentTypes.CUSTOM_MODEL_DATA);
        ordinary.getDataComponents().remove(DataComponentTypes.CUSTOM_NAME);
        ordinary.put(DataComponentTypes.LORE, List.of(Component.text("ordinary lore")));
        ItemData original = ItemTranslator.translateToBedrock(session, new ItemStack(Items.PLAYER_HEAD.javaId(), 1, ordinary));
        equal("ordinary lore", original.getTag().getCompound("display").getList("Lore", NbtType.STRING).get(0), "An ordinary head was globally restyled");
        require(original.getDefinition() == headMapping().getBedrockDefinition(), "Ordinary PROFILE head acquired the private avatar definition");
    }

    private static ItemMapping headMapping() {
        return Registries.ITEMS.get().values().stream().map(items -> items.getMapping(Items.PLAYER_HEAD))
                .filter(mapping -> mapping.getCustomItemDefinitions() != null && mapping.getCustomItemDefinitions().values().stream()
                        .anyMatch(value -> value.definition().bedrockIdentifier().toString().equals("kyokouauth:role_u")))
                .findFirst().orElseThrow(() -> new AssertionError("Native populated head registry has no production avatars"));
    }
    private static DataComponents components(Float marker) {
        DataComponents result = new DataComponents(new HashMap<>());
        result.put(DataComponentTypes.ITEM_MODEL, Key.key("minecraft:player_head"));
        result.put(DataComponentTypes.PROFILE, new ResolvableProfile(new org.geysermc.mcprotocollib.auth.GameProfile(
                UUID.fromString("12345678-1234-4234-9234-123456789abc"), "RealRole")));
        if (marker != null) result.put(DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelData(List.of(marker), List.of(), List.of(), List.of()));
        return result;
    }
    private static GeyserSession session() throws Exception {
        GeyserSession session = (GeyserSession) allocate(GeyserSession.class);
        Class<?> clientType = Class.forName("org.geysermc.geyser.session.auth.BedrockClientData");
        Object localeOnly = allocate(clientType);
        var language = clientType.getDeclaredField("languageCode");
        require(language.trySetAccessible(), "Native locale fixture inaccessible"); language.set(localeOnly, "en_us");
        var client = GeyserSession.class.getDeclaredField("clientData");
        require(client.trySetAccessible(), "Native locale container inaccessible"); client.set(session, localeOnly);
        var nativeEntry = Registries.ITEMS.get().int2ObjectEntrySet().stream()
                .filter(entry -> entry.getValue().getMapping(Items.PLAYER_HEAD) == headMapping())
                .findFirst().orElseThrow(() -> new AssertionError("Native item mapping protocol is absent"));
        session.setItemMappings(nativeEntry.getValue());
        session.setBlockMappings(BlockRegistries.BLOCKS.forVersion(nativeEntry.getIntKey()));
        require(session.getBlockMappings() != null, "Matching native block registry is absent");
        var registries = GeyserSession.class.getDeclaredField("javaRegistries");
        require(registries.trySetAccessible(), "Native registries fixture inaccessible");
        registries.set(session, BuiltInJavaRegistries.PROVIDER);
        var cache = GeyserSession.class.getDeclaredField("componentCache");
        require(cache.trySetAccessible(), "Native component cache fixture inaccessible");
        cache.set(session, new ComponentCache(BuiltInJavaRegistries.PROVIDER));
        var geyser = GeyserSession.class.getDeclaredField("geyser");
        require(geyser.trySetAccessible(), "Native lifecycle fixture inaccessible");
        geyser.set(session, GeyserImpl.getInstance());
        return session;
    }
    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe");
        require(field.trySetAccessible(), "Native value fixture allocator inaccessible");
        return unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
    }
    private static String plain(String value) { return value.replaceAll("\u00a7.", ""); }
    private static String text(Component value) {
        require(value != null, "Missing synthetic GUI text");
        StringBuilder result = new StringBuilder(value instanceof TextComponent literal ? literal.content() : "");
        for (Component child : value.children()) result.append(text(child));
        return result.toString();
    }
    private static String escaped(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(point -> {
            if (point >= 32 && point <= 126 && point != '\\') result.appendCodePoint(point);
            else result.append(String.format("\\u%04x", point));
        });
        return result.toString();
    }
    private static String shape(Component component) {
        return "{text=" + escaped(component instanceof TextComponent literal ? literal.content() : "") +
                ",color=" + (component.color() == null ? "inherit" : component.color().asHexString()) +
                ",italic=" + component.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC) +
                ",children=" + component.children().stream().map(NativeAvatarOracle::shape).toList() + "}";
    }
    private static boolean color(char value) { return "0123456789abcdefgijmnpqstu".indexOf(value) >= 0; }
    private static String syntheticText(Component component, char baseCode) {
        String value = text(component);
        String prefix = "\u00a7r\u00a7" + baseCode;
        // Both the original red fixture and the corrected exact Floodgate prefix remain replayable.
        if (value.startsWith(prefix)) value = value.substring(prefix.length());
        require(value.indexOf('\u00a7') < 0, "Unexpected formatting payload in synthetic source text");
        return value;
    }
    private static void styled(String actual, String expected, String expectedColors, char initialColor, boolean initialItalic) {
        equal(expected, plain(actual), "Visible synthetic text differs");
        require(expectedColors.length() == expected.length(), "Invalid oracle color expectation");
        char activeColor = initialColor;
        boolean italic = initialItalic;
        int visible = 0;
        for (int index = 0; index < actual.length(); index++) {
            char c = actual.charAt(index);
            if (c == '\u00a7') {
                require(index + 1 < actual.length(), "Truncated final native formatting code");
                char code = actual.charAt(++index);
                if (code == 'r') { activeColor = 'f'; italic = false; }
                else if (color(code)) activeColor = code; // Bedrock color codes do not clear decorations.
                else if (code == 'o') italic = true;
            } else {
                require(!italic && activeColor == expectedColors.charAt(visible), "Final native text style differs at visible character " + visible +
                        ": expected section-" + expectedColors.charAt(visible) + " non-italic, got section-" + activeColor + " italic=" + italic);
                visible++;
            }
        }
        equal(expected.length(), visible, "Final native visible-character coverage differs");
    }
    private static void rejects(String raw, String expected, String colors, char initialColor, boolean initialItalic) {
        boolean rejected = false;
        try { styled(raw, expected, colors, initialColor, initialItalic); }
        catch (AssertionError expectedFailure) { rejected = true; }
        require(rejected, "Final-color-only or color-clears-italic regression escaped the per-character oracle");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        require(Objects.equals(expected, actual), message);
    }
    private NativeAvatarOracle() {}
}
