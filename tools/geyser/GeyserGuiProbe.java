package moe.ryuu.kyokouauth.tools.geyser;

import org.geysermc.event.PostOrder;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

/** Test-process-only probe; no definitions, packs or production extension dependencies. */
public final class GeyserGuiProbe implements Extension {
    @Subscribe(postOrder = PostOrder.LAST)
    public void verify(GeyserPostInitializeEvent event) {
        try {
            GeyserGuiOracle.verify();
            logger().info("KYOKOU_GUI_NATIVE_PASS");
        } catch (Throwable failure) {
            logger().error("KYOKOU_GUI_NATIVE_FAILED", failure);
        }
    }
}
