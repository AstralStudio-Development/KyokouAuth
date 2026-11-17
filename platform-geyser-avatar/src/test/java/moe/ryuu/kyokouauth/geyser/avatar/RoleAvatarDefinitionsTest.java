package moe.ryuu.kyokouauth.geyser.avatar;

import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.api.predicate.MinecraftPredicate;
import org.geysermc.geyser.api.predicate.context.item.ItemPredicateContext;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.item.custom.GeyserCustomItemBedrockOptions;
import org.geysermc.geyser.item.custom.GeyserCustomItemDefinition;
import org.geysermc.geyser.item.custom.GeyserCustomItemIcons;
import org.geysermc.geyser.item.custom.GeyserItemPredicateContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoleAvatarDefinitionsTest {
    @BeforeAll static void installNativeValueProviders() { NativeGeyserFixture.install(); }
    static CustomItemDefinition nativeDefinition(Identifier bedrock, Identifier model, String icon,
                                                  MinecraftPredicate<ItemPredicateContext> predicate) {
        return new GeyserCustomItemDefinition.Builder(bedrock, model)
                .predicate(predicate)
                .bedrockOptions(new GeyserCustomItemBedrockOptions.Builder().icons(new GeyserCustomItemIcons(icon, null)))
                .build();
    }

    static ItemPredicateContext context(Float marker) {
        return new GeyserItemPredicateContext(() -> Identifier.of("minecraft:overworld"), 1,
                () -> 64, () -> 0, () -> 0, () -> false, () -> false, () -> 0F, () -> null,
                List::of, List::of, List::of, List::of, () -> marker == null ? List.of() : List.of(marker));
    }

    @Test void registersOnlyTwoExactPrivateHeadDefinitionsUsingRealNativePredicateContext() {
        List<CustomItemDefinition> definitions = new ArrayList<>();
        List<Identifier> javaItems = new ArrayList<>();
        RoleAvatarDefinitions.register((javaItem, definition) -> {
            javaItems.add(javaItem); definitions.add(definition);
        }, RoleAvatarDefinitionsTest::nativeDefinition);
        assertEquals(2, definitions.size(), "Both independently marked role avatars must be registered");
        assertTrue(javaItems.stream().allMatch(item -> item.equals(Identifier.of("minecraft:player_head"))));
        float[] sentinels = {(float) 0x4B5901, (float) 0x4B5902};
        for (int i = 0; i < definitions.size(); i++) {
            CustomItemDefinition definition = definitions.get(i);
            assertEquals(Identifier.of("minecraft:player_head"), definition.model());
            assertEquals(i == 0 ? "kyokouauth_role_u" : "kyokouauth_role_j", definition.icon());
            @SuppressWarnings("unchecked")
            MinecraftPredicate<ItemPredicateContext> predicate = (MinecraftPredicate<ItemPredicateContext>) definition.predicates().get(0);
            assertTrue(predicate.test(context(sentinels[i])));
            for (Float rejected : new Float[]{null, 0F, sentinels[1 - i], sentinels[i] - 1F, sentinels[i] + 2F,
                    Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                assertFalse(predicate.test(context(rejected)), "Unmarked, adjacent, other-role and non-finite values must not match");
            }
        }
    }
}
