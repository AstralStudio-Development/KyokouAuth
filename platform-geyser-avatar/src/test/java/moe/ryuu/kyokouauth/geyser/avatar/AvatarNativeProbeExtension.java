package moe.ryuu.kyokouauth.geyser.avatar;

import org.geysermc.event.PostOrder;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Test-only extension, assembled by the isolated smoke runner; never included in the production Jar. */
public final class AvatarNativeProbeExtension implements Extension {
    private final List<CustomItemDefinition> registered = new ArrayList<>();

    @Subscribe(postOrder = PostOrder.LAST)
    public void capture(GeyserDefineCustomItemsEvent event) {
        for (Object group : ((Map<?, ?>) event.customItemDefinitions()).values()) {
            if (!(group instanceof Iterable<?> values)) throw new AssertionError("Unexpected native v2 definition collection");
            for (Object value : values) if (value instanceof CustomItemDefinition definition &&
                    definition.bedrockIdentifier().namespace().equals("kyokouauth") &&
                    (definition.bedrockIdentifier().path().equals("role_u") || definition.bedrockIdentifier().path().equals("role_j")))
                registered.add(definition);
        }
    }

    @Subscribe(postOrder = PostOrder.LAST)
    public void verify(GeyserPostInitializeEvent event) {
        try {
            NativeAvatarOracle.custom(List.copyOf(registered));
            logger().info("KYOKOU_AVATAR_NATIVE_CUSTOM_PASS");
            NativeAvatarOracle.styles();
            logger().info("KYOKOU_AVATAR_NATIVE_STYLES_PASS");
        } catch (Throwable failure) {
            logger().error("KYOKOU_AVATAR_NATIVE_PROBE_FAILED", failure);
        }
    }
}
