package moe.ryuu.kyokouauth.geyser.avatar;

import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.predicate.MinecraftPredicate;
import org.geysermc.geyser.api.predicate.context.item.ItemPredicateContext;
import org.geysermc.geyser.api.util.Identifier;

public final class RoleAvatarDefinitions {
    public static final int MICROSOFT_MARKER = 0x4B5901;
    public static final int PREMIUM_MARKER = 0x4B5902;
    public static final String MICROSOFT_ICON = "kyokouauth_role_u";
    public static final String PREMIUM_ICON = "kyokouauth_role_j";
    private static final String JAVA_HEAD = "minecraft:player_head";
    private RoleAvatarDefinitions() {}

    public static void register(Registrar registrar) {
        register(registrar, (bedrock, model, icon, predicate) -> CustomItemDefinition.builder(bedrock, model)
                .predicate(predicate).bedrockOptions(CustomItemBedrockOptions.builder().icon(icon)).build());
    }
    @FunctionalInterface public interface Registrar { void register(Identifier javaItem, CustomItemDefinition definition); }
    @FunctionalInterface interface Factory {
        CustomItemDefinition create(Identifier bedrock, Identifier model, String icon, MinecraftPredicate<ItemPredicateContext> predicate);
    }
    static void register(Registrar registrar, Factory factory) {
        // Identifier.of uses Geyser's provider: defer it until the native define-items event.
        Identifier head = Identifier.of(JAVA_HEAD);
        registrar.register(head, factory.create(Identifier.of("kyokouauth:role_u"), head, MICROSOFT_ICON, exact(MICROSOFT_MARKER)));
        registrar.register(head, factory.create(Identifier.of("kyokouauth:role_j"), head, PREMIUM_ICON, exact(PREMIUM_MARKER)));
    }

    private static MinecraftPredicate<ItemPredicateContext> exact(int sentinel) {
        final float marker = sentinel;
        return context -> {
            float actual = context.customModelDataFloat(0);
            return Float.isFinite(actual) && actual == marker;
        };
    }
}
