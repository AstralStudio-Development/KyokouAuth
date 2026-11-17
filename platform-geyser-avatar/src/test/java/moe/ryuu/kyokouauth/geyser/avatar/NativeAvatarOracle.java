package moe.ryuu.kyokouauth.geyser.avatar;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.item.Items;
import org.geysermc.geyser.item.TooltipOptions;
import org.geysermc.geyser.item.type.Item;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.type.ItemMapping;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.item.BedrockItemBuilder;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.ResolvableProfile;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.CustomModelData;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

import java.lang.reflect.Proxy;
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
        GeyserSession session = session();
        Item item = Items.PLAYER_HEAD;
        ItemMapping mapping = headMapping();
        Component name = Component.empty().color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("微软角色", TextColor.color(0x22D3EE)))
                .append(Component.text(" » ", NamedTextColor.GRAY)).append(Component.text("Steve", NamedTextColor.WHITE));
        Component line = Component.empty().color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("点击进入服务器"));
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, name);
        components.put(DataComponentTypes.LORE, List.of(line));
        BedrockItemBuilder nbt = new BedrockItemBuilder();
        nbt.setCustomName(ItemTranslator.getCustomName(session, components, mapping, 'f', true, false));
        item.translateComponentsToBedrock(session, components, TooltipOptions.fromComponents(components), nbt);
        ItemData translated = ItemData.builder().definition(mapping.getBedrockDefinition()).count(1).tag(nbt.build()).build();
        NbtMap display = translated.getTag().getCompound("display");
        String actualName = display.getString("Name");
        List<String> actualLore = display.getList("Lore", NbtType.STRING);
        equal("微软角色 » Steve", plain(actualName), "Final Bedrock item name differs");
        equal(List.of("点击进入服务器"), actualLore.stream().map(NativeAvatarOracle::plain).toList(), "Final Bedrock lore differs");
        noItalic(actualName);
        noItalic(actualLore.get(0));
        equal('7', finalColor(actualLore.get(0), '5'), "Lore retained the Bedrock purple default");
        DataComponents ordinary = new DataComponents(new HashMap<>());
        ordinary.put(DataComponentTypes.LORE, List.of(Component.text("ordinary lore")));
        BedrockItemBuilder original = new BedrockItemBuilder();
        item.translateComponentsToBedrock(session, ordinary, TooltipOptions.fromComponents(ordinary), original);
        String ordinaryLore = original.build().getCompound("display").getList("Lore", NbtType.STRING).get(0);
        equal("ordinary lore", ordinaryLore, "An ordinary item was globally restyled");
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
        var registries = GeyserSession.class.getDeclaredField("javaRegistries");
        require(registries.trySetAccessible(), "Native predicate fixture inaccessible");
        if (registries.getType().isInterface()) registries.set(session, Proxy.newProxyInstance(registries.getType().getClassLoader(),
                new Class<?>[]{registries.getType()}, (proxy, method, args) -> { throw new AssertionError("Predicate read unrelated session registries"); }));
        return session;
    }
    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe");
        require(field.trySetAccessible(), "Native value fixture allocator inaccessible");
        return unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
    }
    private static String plain(String value) { return value.replaceAll("\u00a7.", ""); }
    private static boolean color(char value) { return "0123456789abcdefgijmnpqstu".indexOf(value) >= 0; }
    private static void noItalic(String value) {
        boolean italic = true;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == '\u00a7' && index + 1 < value.length()) {
                char code = value.charAt(++index);
                if (code == 'r' || color(code)) italic = false;
                else if (code == 'o') italic = true;
            } else require(!italic, "Final native inventory text still inherits italic defaults");
        }
    }
    private static char finalColor(String value, char initial) {
        char result = initial;
        for (int index = 0; index + 1 < value.length(); index++) if (value.charAt(index) == '\u00a7') {
            char code = value.charAt(++index);
            if (color(code)) result = code;
        }
        return result;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        require(Objects.equals(expected, actual), message);
    }
    private NativeAvatarOracle() {}
}
