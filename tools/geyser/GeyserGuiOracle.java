package moe.ryuu.kyokouauth.tools.geyser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.item.Items;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.java.BuiltInJavaRegistries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.ComponentCache;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Test-only complete ItemTranslator oracle, run only after the real Geyser lifecycle initializes. */
final class GeyserGuiOracle {
    static void verify() throws Exception {
        require(GeyserImpl.getInstance() != null, "Real Geyser lifecycle is not initialized");
        Path path = Path.of(Objects.requireNonNull(System.getProperty("kyokou.gui.selectorPacket")));
        require(path.getFileName().toString().equals("role-selector.bin") && !Files.isSymbolicLink(path)
                && Files.size(path) > 0 && Files.size(path) <= 32768, "Unsafe isolated selector packet");
        ByteBuf buffer = Unpooled.wrappedBuffer(Files.readAllBytes(path));
        ClientboundContainerSetContentPacket packet;
        try {
            packet = new ClientboundContainerSetContentPacket(buffer);
            require(!buffer.isReadable(), "Trailing bytes after official selector packet");
        } finally { buffer.release(); }
        // A three-row chest has 27 top slots plus the player's 36 bottom slots on the wire.
        require(packet.getContainerId() > 0 && packet.getStateId() >= 0 && packet.getItems().length == 63
                && packet.getCarriedItem() == null, "Not the complete 27-slot selector GUI");
        for (int slot = 0; slot < 63; slot++) if (slot != 12 && slot != 14)
            require(packet.getItems()[slot] == null, "Unexpected selector item");
        GeyserSession session = session();
        for (int slot : new int[]{12, 14}) {
            ItemStack input = packet.getItems()[slot];
            var vanilla = slot == 12 ? Items.NAME_TAG : Items.DIAMOND;
            String label = slot == 12 ? "Microsoft role" : "Java role";
            String role = slot == 12 ? "URole" : "JRole";
            String name = label + " » " + role;
            require(input != null && input.getId() == vanilla.javaId() && input.getAmount() == 1,
                    "Selector icon is not the official vanilla item");
            var patch = input.getDataComponentsPatch();
            require(patch != null && patch.get(DataComponentTypes.PROFILE) == null
                    && patch.get(DataComponentTypes.CUSTOM_MODEL_DATA) == null, "Vanilla GUI has private identity/model components");
            equal(name, sourceText(patch.get(DataComponentTypes.CUSTOM_NAME), 'f'), "Unexpected isolated name");
            var lore = patch.get(DataComponentTypes.LORE);
            require(lore != null && lore.size() == 1, "Missing GUI lore");
            equal("Click to enter the server", sourceText(lore.get(0), '7'), "Unexpected isolated lore");
            var before = new HashMap<>(patch.getDataComponents());
            // Complete production entry: registry defaults, item mapping, rarity, name and lore.
            ItemData output = ItemTranslator.translateToBedrock(session, input);
            equal(before, patch.getDataComponents(), "Full native translator mutated GUI components");
            equal(slot == 12 ? "minecraft:name_tag" : "minecraft:diamond",
                    output.getDefinition().getIdentifier(), "Native translation lost the vanilla icon");
            require(output.getBlockDefinition() == null, "Vanilla nonblock icon acquired a block definition");
            NbtMap display = output.getTag().getCompound("display");
            String actualName = display.getString("Name");
            List<String> actualLore = display.getList("Lore", NbtType.STRING);
            require(actualLore.size() == 1, "Native lore line count differs");
            styled(actualName, name, "b".repeat(label.length()) + "f7f" + "f".repeat(role.length()), 'f', true);
            styled(actualLore.get(0), "Click to enter the server", "7".repeat(25), '5', true);
            System.out.println("KYOKOU_GUI_NATIVE slot=" + slot + " item=" + output.getDefinition().getIdentifier()
                    + " Name=" + escaped(actualName) + " Lore=" + actualLore.stream().map(GeyserGuiOracle::escaped).toList());
        }
        rejects("\u00a7r\u00a75x\u00a77y", "xy", "77");
        rejects("\u00a7r\u00a7o\u00a77x", "x", "7");
    }

    private static GeyserSession session() throws Exception {
        // Locale-only session value; real singleton/registries/component cache are not mocked.
        GeyserSession session = (GeyserSession) allocate(GeyserSession.class);
        Class<?> clientType = Class.forName("org.geysermc.geyser.session.auth.BedrockClientData");
        Object locale = allocate(clientType);
        set(clientType, locale, "languageCode", "en_us");
        set(GeyserSession.class, session, "clientData", locale);
        var entry = Registries.ITEMS.get().int2ObjectEntrySet().stream()
                .filter(value -> value.getValue().getMapping(Items.NAME_TAG) != null
                        && value.getValue().getMapping(Items.DIAMOND) != null)
                .findFirst().orElseThrow(() -> new AssertionError("Real vanilla item mappings absent"));
        session.setItemMappings(entry.getValue());
        session.setBlockMappings(BlockRegistries.BLOCKS.forVersion(entry.getIntKey()));
        require(session.getBlockMappings() != null, "Matching real block registry absent");
        set(GeyserSession.class, session, "javaRegistries", BuiltInJavaRegistries.PROVIDER);
        set(GeyserSession.class, session, "componentCache", new ComponentCache(BuiltInJavaRegistries.PROVIDER));
        set(GeyserSession.class, session, "geyser", GeyserImpl.getInstance());
        return session;
    }
    private static void set(Class<?> type, Object owner, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        require(field.trySetAccessible(), "Native fixture field inaccessible");
        field.set(owner, value);
    }
    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe");
        require(field.trySetAccessible(), "Native fixture allocator inaccessible");
        return unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
    }
    private static String sourceText(Component value, char base) {
        require(value != null, "Missing GUI component");
        String text = (value instanceof TextComponent literal ? literal.content() : "")
                + value.children().stream().map(child -> sourceText(child, base)).reduce("", String::concat);
        String prefix = "\u00a7r\u00a7" + base;
        if (text.startsWith(prefix)) text = text.substring(prefix.length());
        require(text.indexOf('\u00a7') < 0, "Unexpected isolated text payload");
        return text;
    }
    private static String escaped(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(point -> {
            if (point >= 32 && point <= 126 && point != '\\') result.appendCodePoint(point);
            else result.append(String.format("\\u%04x", point));
        });
        return result.toString();
    }
    private static void styled(String actual, String expected, String colors, char active, boolean italic) {
        require(colors.length() == expected.length(), "Invalid oracle color expectation");
        equal(expected, actual.replaceAll("\u00a7.", ""), "Native visible text differs");
        int visible = 0;
        for (int index = 0; index < actual.length(); index++) {
            char c = actual.charAt(index);
            if (c == '\u00a7') {
                require(++index < actual.length(), "Truncated formatting code");
                char code = actual.charAt(index);
                if (code == 'r') { active = 'f'; italic = false; }
                else if ("0123456789abcdefgijmnpqstu".indexOf(code) >= 0) active = code;
                else if (code == 'o') italic = true;
            } else {
                require(!italic && active == colors.charAt(visible), "Native per-character color/nonitalic differs at " + visible);
                visible++;
            }
        }
        equal(expected.length(), visible, "Native visible character coverage differs");
    }
    private static void rejects(String raw, String expected, String colors) {
        try { styled(raw, expected, colors, '5', true); }
        catch (AssertionError expectedFailure) { return; }
        throw new AssertionError("Native style regression escaped the oracle");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) { require(Objects.equals(expected, actual), message); }
    private GeyserGuiOracle() {}
}
