package moe.ryuu.kyokouauth.geyser.avatar;

import org.geysermc.api.Geyser;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.api.item.custom.v2.CustomItemIcons;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.api.pack.option.PriorityOption;
import org.geysermc.geyser.pack.option.GeyserPriorityOption;
import org.geysermc.geyser.impl.IdentifierImpl;
import org.geysermc.geyser.item.custom.GeyserCustomItemBedrockOptions;
import org.geysermc.geyser.item.custom.GeyserCustomItemDefinition;
import org.geysermc.geyser.item.custom.GeyserCustomItemIcons;

import java.lang.reflect.Proxy;

/** Only native value/builder providers: no sessions, authentication SDK services or transport. */
final class NativeGeyserFixture {
    private static GeyserApi installed;
    static synchronized void install() {
        if (installed != null) return;
        if (Geyser.isRegistered()) throw new AssertionError("Unexpected native API owner in isolated avatar test JVM");
        installed = (GeyserApi) Proxy.newProxyInstance(GeyserApi.class.getClassLoader(), new Class<?>[]{GeyserApi.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("provider")) throw new AssertionError("Unexpected API service: " + method.getName());
                    Class<?> type = (Class<?>) args[0];
                    Object[] values = (Object[]) args[1];
                    if (type == Identifier.class) return values.length == 1 ? IdentifierImpl.parse((String) values[0])
                            : IdentifierImpl.of((String) values[0], (String) values[1]);
                    if (type == CustomItemDefinition.Builder.class)
                        return new GeyserCustomItemDefinition.Builder((Identifier) values[0], (Identifier) values[1]);
                    if (type == CustomItemBedrockOptions.Builder.class) return new GeyserCustomItemBedrockOptions.Builder();
                    if (type == CustomItemIcons.Builder.class) return new GeyserCustomItemIcons.Builder();
                    if (type == PriorityOption.class) return new GeyserPriorityOption((Integer) values[0]);
                    throw new AssertionError("No fixture for native value provider: " + type.getName());
                });
        Geyser.set(installed);
    }
    private NativeGeyserFixture() {}
}
