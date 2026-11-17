# Exact offline artifact tool API. No full MinecraftCodec, listener, auth client, or SDK keep.
# Generated per-protocol rules separately retain production reflection/SPI roots and data constructors.
# io.netty.* is a provided library: never keep/include it as program input.

-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.LimboSettings {
    public <init>();
    public *** component*();
    public moe.ryuu.kyokouauth.limbo.LimboSettings copy(java.lang.String,java.lang.String,int,int,int,int,moe.ryuu.kyokouauth.limbo.LimboSettings$Spawn,long,long,long,moe.ryuu.kyokouauth.limbo.LimboSettings$Messages,java.lang.String);
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.protocol.TextComponent {
    public <init>(java.lang.String,java.lang.String,java.lang.Boolean,java.lang.Boolean,java.lang.Boolean,java.lang.Boolean,moe.ryuu.kyokouauth.limbo.protocol.TextComponent$Click,java.util.List);
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.protocol.TextComponent$Click$OpenUrl {
    public <init>(java.lang.String);
}
-keep,allowoptimization interface moe.ryuu.kyokouauth.limbo.LimboListener
-keep,allowoptimization interface moe.ryuu.kyokouauth.limbo.LimboLogger
-keep,allowoptimization interface moe.ryuu.kyokouauth.limbo.ForwardingMode
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.LimboServer {
    public <init>(moe.ryuu.kyokouauth.limbo.LimboSettings,moe.ryuu.kyokouauth.limbo.LimboListener,moe.ryuu.kyokouauth.limbo.LimboLogger,java.net.InetSocketAddress,byte[],moe.ryuu.kyokouauth.limbo.ForwardingMode);
    public java.net.InetSocketAddress start();
    public java.util.Map startAll();
    public void close();
    private java.util.Map startProtocols(java.util.Set);
}

-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.session.GeneratedCodec {
    public static *** CODEC;
    public static int PROTOCOL;
    public static java.lang.String VERSION;
    public static java.lang.String MANIFEST_SHA256;
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.session.RegistrySnapshot {
    public static *** Companion;
    public java.util.List getRegistries();
    public *** getTags();
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.session.RegistrySnapshot$Companion {
    public *** load();
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.session.LimboWorld {
    public <init>(moe.ryuu.kyokouauth.limbo.LimboSettings,moe.ryuu.kyokouauth.limbo.engine.p*.session.RegistrySnapshot);
    public java.util.List joinPackets(int);
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.session.ProtocolPackets {
    public static *** INSTANCE;
    public *** loginFinished(moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.auth.GameProfile);
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.session.RoleSelectorPackets {
    public *** playerInventory(int);
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.session.LibraryComponentsKt {
    public static *** toLibrary(moe.ryuu.kyokouauth.limbo.protocol.TextComponent);
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.auth.GameProfile {
    public <init>(java.util.UUID,java.lang.String);
}

# State/registry/serializer interface names are loaded by name, while getters are invoked on instances.
-keep,allowoptimization enum moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.protocol.data.ProtocolState {
    public static final *** LOGIN;
    public static final *** CONFIGURATION;
    public static final *** GAME;
}
-keepclassmembers,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.protocol.codec.PacketCodec {
    public int getProtocolVersion();
    public java.lang.String getMinecraftVersion();
    public *** getCodec(moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.protocol.data.ProtocolState);
    public java.util.function.Supplier getHelperFactory();
}
-keepclassmembers,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.network.packet.PacketRegistry {
    public int getClientboundId(java.lang.Class);
    public *** getClientboundDefinition(int);
}
-keepclassmembers,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.network.codec.PacketDefinition {
    public *** getSerializer();
}
-keep,allowoptimization interface moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.network.packet.Packet
-keep,allowoptimization interface moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.network.codec.PacketSerializer {
    public void serialize(io.netty.buffer.ByteBuf,moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.network.packet.Packet);
    public void serialize(io.netty.buffer.ByteBuf,moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.network.codec.PacketCodecHelper,moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.network.packet.Packet);
}
-keep,allowoptimization interface moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.network.codec.PacketCodecHelper
-keep,allowoptimization interface moe.ryuu.kyokouauth.limbo.text.*.net.kyori.adventure.key.Key {
    public static *** key(java.lang.String);
}

# These seven clientbound data constructors are deliberately exercised by NewExporter.
# Login/world packet constructor variation remains in each generated protocol's exact keep-rules.pro.
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundUpdateEnabledFeaturesPacket {
    public <init>(***[]);
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundSelectKnownPacks {
    public <init>(java.util.List);
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket {
    public <init>();
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket {
    public <init>(***,boolean);
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitlesAnimationPacket {
    public <init>(int,int,int);
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket {
    public <init>(***);
}
-keep,allowoptimization class moe.ryuu.kyokouauth.limbo.engine.p*.libs.org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket {
    public <init>(***);
}
