# Only Limbo and its private libraries are program input. Core/platform code is not transformed.
-dontobfuscate
# Keep JVM sealed/interface inheritance intact; remove unreachable members without hierarchy rewriting.
-dontoptimize
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod,*Annotation*,Record,PermittedSubclasses

# The stable, JDK-only facade and message model are also used by the unprocessed platform layer.
-keep class moe.ryuu.kyokouauth.limbo.* { *; }
-keep class moe.ryuu.kyokouauth.limbo.protocol.TextComponent** { *; }
-keep class moe.ryuu.kyokouauth.limbo.session.ConnectionLifecycle** { *; }

# Independent artifact export uses these small registry interfaces, not the complete MinecraftCodec.
-keep class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.protocol.codec.PacketCodec { *; }
-keep class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.network.packet.PacketRegistry { *; }
-keep class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.network.codec.PacketDefinition { *; }
-keep interface moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.network.codec.PacketSerializer { *; }
-keep interface moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.network.packet.Packet { *; }
-keep interface moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.network.codec.PacketCodecHelper { *; }
-keep interface moe.ryuu.kyokouauth.limbo.text.*.net.kyori.adventure.key.Key { public static *** key(java.lang.String); }

# Preserve every text type and its generic/sealed hierarchy, without retaining unrelated members.
# Actual serializer/provider entry points and reflective TypeToken contracts are pinned separately.
-keep class moe.ryuu.kyokouauth.limbo.text.**
-keep class moe.ryuu.kyokouauth.limbo.shared.com.google.gson.reflect.TypeToken { *; }
-keep class * extends moe.ryuu.kyokouauth.limbo.shared.com.google.gson.reflect.TypeToken { *; }

# Values/names used through exact reflection must retain enum identity.
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
