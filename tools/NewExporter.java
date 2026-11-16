import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Reflectively exports the actual isolated artifact's typed packet serializers.
 * Login/config/text follow the current connection template; world packets come directly
 * from LimboWorld.joinPackets(77). This is an artifact/codec probe, not a live session test.
 * Usage: java NewExporter artifact.jar output-directory [protocol=777] [--netty-classpath proxy.jar]
 * Netty is provided by explicit proxy JARs, never a complete MCPL/test dependency classpath.
 * Run/compile under .narrafork so dependencies cannot leak logs outside that directory.
 */
public final class NewExporter {
    private static final String ROOT = "moe.ryuu.kyokouauth.limbo.";
    private final String LIB;
    private final String PROTOCOL;
    private final String SESSION;
    private final int protocol;
    private final Object helper;
    private static final UUID PUBLIC_UUID = UUID.fromString("38aef317-7922-4c5a-aaf4-e973691f0c15");
    private final ClassLoader loader;
    private final Path output;
    private final List<String> manifest = new ArrayList<>();
    private final Object codecs;
    private final String version;
    private final String codecManifestSha;
    private final String codecName;
    private final Class<?> byteBufType;
    private int sequence;

    private NewExporter(ClassLoader loader, Path output, int protocol) throws Exception {
        this.loader = loader;
        this.output = output;
        this.protocol = protocol;
        LIB = ROOT + "engine.p" + protocol + ".libs.";
        SESSION = ROOT + "engine.p" + protocol + ".session.";
        PROTOCOL = LIB + "org.geysermc.mcprotocollib.protocol.";
        codecName = SESSION + "GeneratedCodec";
        Class<?> generated = type(codecName);
        codecs = generated.getField("CODEC").get(null);
        version = (String) generated.getField("VERSION").get(null);
        int actual = (Integer) codecs.getClass().getMethod("getProtocolVersion").invoke(codecs);
        if (actual != protocol || generated.getField("PROTOCOL").getInt(null) != protocol)
            throw new IllegalStateException("Artifact GeneratedCodec protocol differs");
        if (!version.equals(codecs.getClass().getMethod("getMinecraftVersion").invoke(codecs)))
            throw new IllegalStateException("Artifact GeneratedCodec version differs");
        try (var input = loader.getResourceAsStream("limbo/codec-" + protocol + ".json")) {
            if (input == null) throw new IllegalStateException("Missing generated codec manifest");
            codecManifestSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
        if (!codecManifestSha.equals(generated.getField("MANIFEST_SHA256").get(null)))
            throw new IllegalStateException("Artifact GeneratedCodec manifest hash differs");
        helper = protocol <= 768 ? ((java.util.function.Supplier<?>)codecs.getClass().getMethod("getHelperFactory").invoke(codecs)).get() : null;
        byteBufType = type("io.netty.buffer.ByteBuf");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: NewExporter artifact.jar output-directory [protocol=777] [--netty-classpath provided-jars]");
        int protocol = 777;
        String netty = null;
        boolean selected = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--netty-classpath") && i + 1 < args.length) netty = args[++i];
            else if (!selected && args[i].matches("[0-9]+")) { protocol = Integer.parseInt(args[i]); selected = true; }
            else throw new IllegalArgumentException("Unexpected exporter argument: " + args[i]);
        }
        if (protocol < 767 || protocol > 777) throw new IllegalArgumentException("Protocol must be 767..777");
        Path artifact = Path.of(args[0]).toRealPath();
        Path output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new java.security.DigestInputStream(Files.newInputStream(artifact), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        String sha = HexFormat.of().formatHex(digest.digest());
        try (var loader = openArtifact(artifact, netty)) {
            NewExporter exporter = new NewExporter(loader, output, protocol);
            exporter.run();
            Files.write(output.resolve("manifest.tsv"), exporter.manifest, StandardCharsets.UTF_8);
            Files.writeString(output.resolve("provenance.txt"), "mode=artifact-typed-packets-not-live-session\nartifact-sha256="
                    + sha + "\nprotocol=" + protocol + "\nversion=" + exporter.version + "\nproduction-codec=" + exporter.codecName
                    + "\ncodec-manifest-sha256=" + exporter.codecManifestSha + "\nprovided-netty=" + loader.nettyProvenance
                    + "\npackets=" + exporter.sequence + "\n", StandardCharsets.UTF_8);
            System.out.println("EXPORTED new packets=" + exporter.sequence + " artifact-sha256=" + sha
                    + " production-codec=" + exporter.codecName + " codec-manifest-sha256=" + exporter.codecManifestSha
                    + " mode=artifact-typed-packets-not-live-session");
        }
    }

    private void run() throws Exception {
        Object companion = type(SESSION + "RegistrySnapshot").getField("Companion").get(null);
        Object snapshot = companion.getClass().getMethod("load").invoke(companion);
        Object defaults = construct(ROOT + "LimboSettings");
        Method copy = java.util.Arrays.stream(defaults.getClass().getMethods()).filter(m -> m.getName().equals("copy")).findFirst().orElseThrow();
        Object[] fields = new Object[copy.getParameterCount()];
        for (int i = 0; i < fields.length; i++) fields[i] = defaults.getClass().getMethod("component" + (i + 1)).invoke(defaults);
        fields[2] = protocol;
        fields[fields.length - 1] = "minecraft:plains";
        Object settings = copy.invoke(defaults, fields);
        Object world = construct(SESSION + "LimboWorld", settings, snapshot);

        Object profile = construct(LIB + "org.geysermc.mcprotocollib.auth.GameProfile", PUBLIC_UUID, "OraclePlayer");
        Object packetFactory = type(SESSION + "ProtocolPackets").getField("INSTANCE").get(null);
        write("login", packetFactory.getClass().getMethod("loginFinished", profile.getClass()).invoke(packetFactory, profile));

        String family = protocol <= 769 ? "legacy" : protocol <= 775 ? "json" : "nbt";
        Class<?> keyType = type(ROOT + "text." + family + ".net.kyori.adventure.key.Key");
        Object vanilla = keyType.getMethod("key", String.class).invoke(null, "minecraft:vanilla");
        Object features = Array.newInstance(keyType, 1);
        Array.set(features, 0, vanilla);
        write("config", construct(PROTOCOL + "packet.configuration.clientbound.ClientboundUpdateEnabledFeaturesPacket", features));
        write("config", construct(PROTOCOL + "packet.configuration.clientbound.ClientboundSelectKnownPacks", List.of()));
        List<?> registries = (List<?>) snapshot.getClass().getMethod("getRegistries").invoke(snapshot);
        for (Object packet : registries) write("config", packet);
        write("config", snapshot.getClass().getMethod("getTags").invoke(snapshot));
        write("config", construct(PROTOCOL + "packet.configuration.clientbound.ClientboundFinishConfigurationPacket"));

        List<?> packets = (List<?>) world.getClass().getMethod("joinPackets", int.class).invoke(world, 77);
        for (Object packet : packets) write("play", packet);

        Object click = construct(ROOT + "protocol.TextComponent$Click$OpenUrl", "https://example.invalid/limbo-oracle");
        Object message = model("Oracle link", "aqua", true, click);
        write("play", construct(PROTOCOL + "packet.ingame.clientbound.ClientboundSystemChatPacket", component(message), false));
        Object rgb = construct(ROOT + "protocol.TextComponent", "验证 𠀋", "#22d3ee", true, true, true, true, null, List.of());
        Object fullClick = construct(ROOT + "protocol.TextComponent$Click$OpenUrl", "https://example.invalid/limbo-oracle?code=public-fixture&scope=xbox%3A%2F%2Fsignin");
        Object linked = model("完整链接", "gold", false, fullClick);
        Object rich = construct(ROOT + "protocol.TextComponent", "", null, null, null, null, null, null, List.of(rgb, linked));
        write("play", construct(PROTOCOL + "packet.ingame.clientbound.ClientboundSystemChatPacket", component(rich), false));
        write("play", construct(PROTOCOL + "packet.ingame.clientbound.title.ClientboundSetTitlesAnimationPacket", 10, 60, 10));
        write("play", construct(PROTOCOL + "packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket",
                component(model("Oracle subtitle", "gray", null, null))));
        write("play", construct(PROTOCOL + "packet.ingame.clientbound.title.ClientboundSetTitleTextPacket",
                component(model("Oracle title", "gold", true, null))));
    }

    private Object model(String text, String color, Boolean bold, Object click) throws Exception {
        return construct(ROOT + "protocol.TextComponent", text, color, bold, null, null, null, click, List.of());
    }

    private Object component(Object model) throws Exception {
        return type(SESSION + "LibraryComponentsKt").getMethod("toLibrary", type(ROOT + "protocol.TextComponent"))
                .invoke(null, model);
    }

    private void write(String state, Object packet) throws Exception {
        String libraryState = switch (state) { case "login" -> "LOGIN"; case "config" -> "CONFIGURATION"; default -> "GAME"; };
        Class<?> stateType = type(PROTOCOL + "data.ProtocolState");
        Object registry = codecs.getClass().getMethod("getCodec", stateType)
                .invoke(codecs, stateType.getField(libraryState).get(null));
        int id = (Integer) registry.getClass().getMethod("getClientboundId", Class.class).invoke(registry, packet.getClass());
        Object definition = registry.getClass().getMethod("getClientboundDefinition", int.class).invoke(registry, id);
        Object serializer = definition.getClass().getMethod("getSerializer").invoke(definition);
        Class<?> serializerType = type(LIB + "org.geysermc.mcprotocollib.network.codec.PacketSerializer");
        Class<?> packetType = type(LIB + "org.geysermc.mcprotocollib.network.packet.Packet");
        Object buffer = type("io.netty.buffer.Unpooled").getMethod("buffer").invoke(null);
        try {
            if (helper == null) serializerType.getMethod("serialize", byteBufType, packetType).invoke(serializer, buffer, packet);
            else {
                Class<?> helperType = type(LIB + "org.geysermc.mcprotocollib.network.codec.PacketCodecHelper");
                serializerType.getMethod("serialize", byteBufType, helperType, packetType).invoke(serializer, buffer, helper, packet);
            }
            int length = (Integer) byteBufType.getMethod("readableBytes").invoke(buffer);
            byte[] payload = new byte[length];
            byteBufType.getMethod("readBytes", byte[].class).invoke(buffer, (Object) payload);
            String name = String.format("%03d-%s-%03d.bin", sequence++, state, id);
            Files.write(output.resolve(name), payload);
            manifest.add(state + "\t" + id + "\t" + name);
        } finally {
            byteBufType.getMethod("release").invoke(buffer);
        }
    }

    /** Only the artifact supplies application classes; provided JARs can supply io.netty.* alone. */
    public static ArtifactLoader openArtifact(Path artifact, String classpath) throws Exception {
        artifact = artifact.toRealPath();
        try (var jar = new JarFile(artifact.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.endsWith(".class") && (name.startsWith("io/netty/") || name.contains("/io/netty/")
                        || name.endsWith("/protocol/codec/MinecraftCodec.class")))
                    throw new IllegalArgumentException("Artifact is not trimmed/provided-Netty: " + name);
            }
        }
        if (classpath == null) classpath = System.getenv("LIMBO_NETTY_CLASSPATH");
        if (classpath == null) classpath = "run-proxy/velocity.jar";
        List<URL> urls = new ArrayList<>();
        List<String> provenance = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        boolean byteBuf = false, unpooled = false;
        for (String entry : classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
            if (entry.isBlank() || entry.contains("*")) throw new IllegalArgumentException("Netty requires explicit JAR paths");
            Path path = Path.of(entry).toRealPath();
            if (path.equals(artifact) || !seen.add(path) || !path.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                throw new IllegalArgumentException("Invalid/duplicate provided Netty JAR: " + path);
            try (var jar = new JarFile(path.toFile())) {
                byteBuf |= jar.getJarEntry("io/netty/buffer/ByteBuf.class") != null;
                unpooled |= jar.getJarEntry("io/netty/buffer/Unpooled.class") != null;
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(".class") && (name.contains("moe/ryuu/kyokouauth/limbo/")
                            || name.contains("org/geysermc/") || name.startsWith("net/minecraft/")))
                        throw new IllegalArgumentException("Provided Netty cannot contain Limbo/MCPL/game classes: " + path);
                }
            }
            urls.add(path.toUri().toURL());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new java.security.DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            provenance.add(path + "@" + HexFormat.of().formatHex(digest.digest()));
        }
        if (!byteBuf || !unpooled) throw new IllegalArgumentException("Provided Netty is missing ByteBuf/Unpooled; pass the actual proxy dependency JAR set");
        return new ArtifactLoader(artifact, urls.toArray(URL[]::new), String.join(";", provenance));
    }

    public static final class ArtifactLoader extends URLClassLoader {
        private final URLClassLoader netty;
        public final String nettyProvenance;
        private ArtifactLoader(Path artifact, URL[] provided, String provenance) throws Exception {
            super(new URL[] { artifact.toUri().toURL() }, ClassLoader.getPlatformClassLoader());
            nettyProvenance = provenance;
            netty = new URLClassLoader(provided, ClassLoader.getPlatformClassLoader()) {
                @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (!name.startsWith("io.netty.")) throw new ClassNotFoundException("Provided JARs expose Netty only: " + name);
                    return super.findClass(name);
                }
            };
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("io.netty.")) return netty.loadClass(name);
            return super.loadClass(name, resolve);
        }
        @Override public void close() throws IOException {
            try { super.close(); } finally { netty.close(); }
        }
    }

    private Class<?> type(String name) throws Exception { return Class.forName(name, true, loader); }

    private Object construct(String name, Object... values) throws Exception {
        Class<?> type = type(name);
        for (Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != values.length) continue;
            boolean matches = true;
            for (int i = 0; i < parameters.length; i++) {
                Class<?> parameter = parameters[i];
                if (parameter == int.class) parameter = Integer.class;
                else if (parameter == boolean.class) parameter = Boolean.class;
                else if (parameter == long.class) parameter = Long.class;
                if (values[i] != null && !parameter.isInstance(values[i])) { matches = false; break; }
                if (values[i] == null && parameters[i].isPrimitive()) { matches = false; break; }
            }
            if (matches) return constructor.newInstance(values);
        }
        throw new NoSuchMethodException("No public constructor matches " + name + " arity=" + values.length);
    }
}