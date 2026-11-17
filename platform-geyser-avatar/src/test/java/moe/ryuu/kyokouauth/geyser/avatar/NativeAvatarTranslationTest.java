package moe.ryuu.kyokouauth.geyser.avatar;

import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.ArrayList;

/** These exact assertions also run in the test-only real-loader probe; a value-only JVM is not Geyser. */
@EnabledIf(value = "nativeInitialized", disabledReason = "Requires actual Geyser registry lifecycle; execute geyser-avatar-smoke.py run --native-probe")
class NativeAvatarTranslationTest {
    static boolean nativeInitialized() { return GeyserImpl.getInstance() != null; }

    @Test void realCustomTranslatorSelectsOnlyExactMarkedHeadAndClearsBlockWhileKeepingRealProfile() throws Exception {
        var definitions = new ArrayList<CustomItemDefinition>();
        RoleAvatarDefinitions.register((javaItem, definition) -> definitions.add(definition));
        NativeAvatarOracle.custom(definitions);
    }
    @Test void realBedrockItemNbtResetsNameAndLoreDefaultsWhileAnOrdinaryLoreIsUntouched() throws Exception {
        NativeAvatarOracle.styles();
    }
}
