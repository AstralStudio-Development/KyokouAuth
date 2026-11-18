import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import javax.tools.*;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import java.util.jar.JarFile;

/** Build-only: no dependency on MCPL at compile time, no class is shipped at runtime. */
public final class CodecGenerator {
    private static final String LIB = "org.geysermc.mcprotocollib.";
    private static final String CODEC = LIB + "protocol.codec.";
    private static final String OWN = "moe.ryuu.kyokouauth.limbo.session.";
    private static final String SOURCE = (CODEC + "MinecraftCodec").replace('.', '/') + ".java";
    private static final Set<String> LOGIN = Set.of("ClientboundLoginDisconnectPacket", "ClientboundCustomQueryPacket",
        "ClientboundGameProfilePacket", "ClientboundLoginFinishedPacket", "ServerboundHelloPacket",
        "ServerboundCustomQueryAnswerPacket", "ServerboundLoginAcknowledgedPacket");
    private static final Set<String> CONFIG = Set.of("ClientboundCustomPayloadPacket", "ClientboundDisconnectPacket",
        "ClientboundFinishConfigurationPacket", "ClientboundKeepAlivePacket", "ClientboundRegistryDataPacket",
        "ClientboundUpdateEnabledFeaturesPacket", "ClientboundUpdateTagsPacket", "ClientboundSelectKnownPacks",
        "ServerboundClientInformationPacket", "ServerboundCustomPayloadPacket", "ServerboundFinishConfigurationPacket",
        "ServerboundKeepAlivePacket", "ServerboundPongPacket", "ServerboundSelectKnownPacks");
    private static final Set<String> GAME = Set.of("ClientboundLoginPacket", "ClientboundPlayerAbilitiesPacket",
        "ClientboundGameEventPacket", "ClientboundSetChunkCacheRadiusPacket", "ClientboundSetChunkCacheCenterPacket",
        "ClientboundChunkBatchStartPacket", "ClientboundLevelChunkWithLightPacket", "ClientboundChunkBatchFinishedPacket",
        "ClientboundPlayerPositionPacket", "ClientboundSystemChatPacket", "ClientboundSetTitlesAnimationPacket",
        "ClientboundSetSubtitleTextPacket", "ClientboundSetTitleTextPacket", "ClientboundClearTitlesPacket",
        "ClientboundDisconnectPacket", "ClientboundKeepAlivePacket", "ClientboundPongResponsePacket",
        "ServerboundKeepAlivePacket", "ServerboundChunkBatchReceivedPacket", "ServerboundAcceptTeleportationPacket",
        "ServerboundPingRequestPacket", "ServerboundPlayerLoadedPacket",
        "ClientboundOpenScreenPacket", "ClientboundContainerSetContentPacket", "ClientboundContainerClosePacket",
        "ServerboundContainerClickPacket", "ServerboundContainerClosePacket");
    private record Parsed(String text, CompilationUnitTree tree, Trees trees) {
        String exact(Tree node) {
            var positions = trees.getSourcePositions();
            return text.substring((int) positions.getStartPosition(tree, node), (int) positions.getEndPosition(tree, node));
        }
    }
    private record Factory(String state, String direction, String name, String expression, int line) {}
    private record Entry(String state, String direction, int id, Class<?> type, String factory, int line,
                         boolean terminal, String disposition) {
        String key() { return state + ":" + direction + ":" + type.getName(); }
        Map<String, Object> metadata() {
            var result = new LinkedHashMap<String, Object>();
            result.put("state", state); result.put("direction", direction); result.put("id", id);
            result.put("class", type.getName()); result.put("factory", factory); result.put("sourceLine", line);
            result.put("terminal", terminal); result.put("disposition", disposition);
            return result;
        }
    }
    private final JarFile sources;
    private final int protocol;
    private final Path output;
    private final Object upstream;
    private final Parsed source;
    private final Map<String, String> imports = new LinkedHashMap<>();
    private final Map<String, Boolean> terminalCache = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private String helper;

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) throw new IllegalArgumentException("protocol binary.jar sources.jar output [--verify]");
        int protocol = Integer.parseInt(args[0]);
        Path binary = Path.of(args[1]).toRealPath();
        Class<?> codecClass = Class.forName(CODEC + "MinecraftCodec");
        Path actual = Path.of(codecClass.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        require(binary.equals(actual), "Wrong MCPL classpath origin: " + actual);
        try (var sources = new JarFile(args[2])) {
            var generator = new CodecGenerator(protocol, sources, Path.of(args[3]), codecClass.getField("CODEC").get(null));
            generator.extract();
            if (args.length == 5 && args[4].equals("--verify")) generator.verify();
            else generator.generate(binary, Path.of(args[2]));
        }
    }

    private CodecGenerator(int protocol, JarFile sources, Path output, Object upstream) throws Exception {
        this.protocol = protocol; this.sources = sources; this.output = output; this.upstream = upstream;
        source = parse(read(SOURCE), SOURCE);
        for (ImportTree item : source.tree.getImports()) {
            require(!item.isStatic(), "Static upstream imports need explicit AST support");
            String full = item.getQualifiedIdentifier().toString();
            require(!full.endsWith(".*"), "Wildcard import needs explicit AST support");
            imports.put(full.substring(full.lastIndexOf('.') + 1), full);
        }
    }

    private static Parsed parse(String text, String name) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "A complete JDK (21+) is required for AST extraction");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///" + name), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return text; }
        };
        var task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(file));
        CompilationUnitTree tree = task.parse().iterator().next();
        for (var diagnostic : diagnostics.getDiagnostics())
            require(diagnostic.getKind() != Diagnostic.Kind.ERROR, "Malformed pinned source: " + diagnostic);
        return new Parsed(text, tree, Trees.instance(task));
    }

    private String read(String name) throws Exception {
        var item = sources.getJarEntry(name);
        require(item != null, "Missing pinned source: " + name);
        try (var stream = sources.getInputStream(item)) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private void extract() throws Exception {
        require((int) call(upstream, "getProtocolVersion") == protocol, "Requested protocol differs from binary");
        final ExpressionTree[] initializer = {null};
        for (Tree declaration : source.tree.getTypeDecls()) if (declaration instanceof ClassTree type)
            for (Tree member : type.getMembers()) if (member instanceof VariableTree field && field.getName().contentEquals("CODEC"))
                initializer[0] = field.getInitializer();
        require(initializer[0] != null, "No upstream CODEC initializer");
        var factories = new LinkedHashMap<String, Factory>();
        var states = new LinkedHashSet<String>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                String method = method(node);
                if (method.equals("state")) {
                    require(node.getArguments().size() == 2, "Unexpected state AST");
                    String state = ((MemberSelectTree) node.getArguments().get(0)).getIdentifier().toString();
                    require(states.add(state), "Duplicate upstream state " + state);
                    List<MethodInvocationTree> chain = new ArrayList<>();
                    unwind(node.getArguments().get(1), chain);
                    for (var registration : chain) {
                        String register = method(registration);
                        if (register.equals("builder")) continue;
                        require(register.equals("registerServerboundPacket") || register.equals("registerClientboundPacket"),
                            "Unsupported upstream registry operation " + register);
                        require(registration.getArguments().size() == 2, "Unexpected registration AST");
                        var literal = (MemberSelectTree) registration.getArguments().get(0);
                        require(literal.getIdentifier().contentEquals("class"), "Expected class literal");
                        String simple = literal.getExpression().toString();
                        String name = imports.get(simple);
                        require(name != null, "Unresolved exact upstream import " + simple);
                        String direction = register.equals("registerServerboundPacket") ? "SERVERBOUND" : "CLIENTBOUND";
                        String key = state + ":" + direction + ":" + name;
                        var factory = new Factory(state, direction, name, source.exact(registration.getArguments().get(1)),
                            (int) source.tree.getLineMap().getLineNumber(source.trees.getSourcePositions().getStartPosition(source.tree, registration)));
                        require(factories.put(key, factory) == null, "Duplicate class in upstream state " + key);
                    }
                } else if (method.equals("helper")) {
                    require(helper == null && node.getArguments().size() == 1, "Ambiguous helper factory");
                    helper = source.exact(node.getArguments().get(0));
                } else if (method.equals("protocolVersion")) {
                    require(((Number) ((LiteralTree) node.getArguments().get(0)).getValue()).intValue() == protocol,
                        "Binary/source protocol mismatch");
                } else if (method.equals("minecraftVersion")) {
                    try { require(((LiteralTree) node.getArguments().get(0)).getValue().equals(call(upstream, "getMinecraftVersion")),
                        "Binary/source version mismatch"); } catch (Exception failure) { throw new IllegalStateException(failure); }
                }
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(initializer[0], null);
        require(states.equals(Set.of("HANDSHAKE", "STATUS", "LOGIN", "CONFIGURATION", "GAME")), "Unexpected protocol states " + states);
        require((protocol <= 768) == (helper != null), "Unexpected upstream helper family");
        Class<?> stateClass = Class.forName(LIB + "protocol.data.ProtocolState");
        for (String state : List.of("HANDSHAKE", "STATUS", "LOGIN", "CONFIGURATION", "GAME")) {
            Object registry = upstream.getClass().getMethod("getCodec", stateClass).invoke(upstream, stateClass.getField(state).get(null));
            for (String direction : List.of("CLIENTBOUND", "SERVERBOUND")) {
                Field field = registry.getClass().getDeclaredField(direction.toLowerCase(Locale.ROOT));
                field.setAccessible(true);
                Map<?, ?> definitions = (Map<?, ?>) field.get(registry);
                for (Object definition : definitions.values()) {
                    int id = (int) call(definition, "getId");
                    Class<?> type = (Class<?>) call(definition, "getPacketClass");
                    String key = state + ":" + direction + ":" + type.getName();
                    Factory factory = factories.remove(key);
                    require(factory != null, "Loaded registry has no exact source registration: " + key);
                    boolean terminal = terminal(type);
                    String decision = typed(state, type.getSimpleName()) ? "typed" :
                        state.equals("GAME") && direction.equals("SERVERBOUND") && !terminal ? "ignored" : "rejected";
                    entries.add(new Entry(state, direction, id, type, factory.expression, factory.line, terminal, decision));
                }
            }
        }
        require(factories.isEmpty(), "Source registrations absent from loaded registry: " + factories.keySet());
        entries.sort(Comparator.comparing(Entry::state).thenComparing(Entry::direction).thenComparingInt(Entry::id));
        require(entries.stream().filter(e -> e.disposition.equals("ignored")).noneMatch(Entry::terminal), "Terminal packet marked ignored");
        for (String required : List.of("ServerboundKeepAlivePacket", "ServerboundAcceptTeleportationPacket", "ServerboundChunkBatchReceivedPacket"))
            require(entries.stream().anyMatch(e -> e.state.equals("GAME") && e.type.getSimpleName().equals(required) && e.disposition.equals("typed")),
                "Missing required typed packet " + required);
        if (protocol >= 769) require(entries.stream().anyMatch(e -> e.type.getSimpleName().equals("ServerboundPlayerLoadedPacket") && e.disposition.equals("typed")), "Missing typed loaded packet");
    }

    private boolean typed(String state, String name) {
        return switch (state) {
            case "HANDSHAKE", "STATUS" -> true;
            case "LOGIN" -> LOGIN.contains(name);
            case "CONFIGURATION" -> CONFIG.contains(name);
            case "GAME" -> GAME.contains(name) || protocol <= 768 && name.startsWith("ServerboundMovePlayer");
            default -> throw new IllegalStateException(state);
        };
    }

    private boolean terminal(Class<?> type) throws Exception {
        Method method = type.getMethod("isTerminal");
        String declaring = method.getDeclaringClass().getName();
        if (terminalCache.containsKey(declaring)) return terminalCache.get(declaring);
        Parsed parsed = parse(read(declaring.replace('.', '/') + ".java"), declaring.replace('.', '/') + ".java");
        final Boolean[] answer = {null};
        new TreeScanner<Void, Void>() {
            @Override public Void visitMethod(MethodTree node, Void unused) {
                if (node.getName().contentEquals("isTerminal") && node.getParameters().isEmpty()) {
                    require(node.getBody() != null && node.getBody().getStatements().size() == 1, "Nonconstant isTerminal: " + declaring);
                    Tree statement = node.getBody().getStatements().get(0);
                    require(statement instanceof ReturnTree returned && returned.getExpression() instanceof LiteralTree,
                        "Unrecognized isTerminal: " + declaring);
                    Object value = ((LiteralTree) ((ReturnTree) statement).getExpression()).getValue();
                    require(value instanceof Boolean, "Nonboolean isTerminal");
                    answer[0] = (Boolean) value;
                }
                return super.visitMethod(node, unused);
            }
        }.scan(parsed.tree, null);
        require(answer[0] != null, "Missing terminal method source: " + declaring);
        terminalCache.put(declaring, answer[0]);
        return answer[0];
    }

    private static String method(MethodInvocationTree tree) {
        return tree.getMethodSelect() instanceof MemberSelectTree selected ? selected.getIdentifier().toString() : tree.getMethodSelect().toString();
    }
    private static void unwind(ExpressionTree expression, List<MethodInvocationTree> chain) {
        require(expression instanceof MethodInvocationTree, "Unexpected registry expression");
        var node = (MethodInvocationTree) expression;
        if (node.getMethodSelect() instanceof MemberSelectTree selected && selected.getExpression() instanceof MethodInvocationTree nested)
            unwind(nested, chain);
        chain.add(node);
    }

    private void generate(Path binary, Path sourcesPath) throws Exception {
        new ItemCodecGenerator(sources, protocol, output).generate();
        String version = (String) call(upstream, "getMinecraftVersion");
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("schema", 1); manifest.put("protocol", protocol); manifest.put("minecraftVersion", version);
        manifest.put("binarySha256", sha(Files.readAllBytes(binary))); manifest.put("sourcesSha256", sha(Files.readAllBytes(sourcesPath)));
        manifest.put("codecSourceSha256", sha(source.text.getBytes(StandardCharsets.UTF_8)));
        manifest.put("generatorSha256", generatorSha());
        manifest.put("ignoredSemantics", "Only known nonterminal serverbound GAME payloads are skipped after upstream framing; unused PLAY field semantics are not validated. Never sent.");
        var dependencies = new ArrayList<Map<String, Object>>();
        for (String name : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path path = Path.of(name);
            if (Files.isRegularFile(path) && name.endsWith(".jar")) {
                var dependency = new LinkedHashMap<String, Object>();
                dependency.put("file", path.getFileName().toString());
                dependency.put("sha256", sha(Files.readAllBytes(path)));
                dependencies.add(dependency);
            }
        }
        dependencies.sort(Comparator.comparing(item -> (String) item.get("file")));
        manifest.put("dependencies", dependencies.stream().distinct().toList());
        manifest.put("packets", entries.stream().map(Entry::metadata).toList());
        String manifestText = json(manifest) + "\n";
        write("resources/limbo/codec-" + protocol + ".json", manifestText);
        write("resources/limbo/ignored-" + protocol + ".json", json(entries.stream().filter(e -> e.disposition.equals("ignored")).map(Entry::metadata).toList()) + "\n");
        StringBuilder java = new StringBuilder("package " + OWN.substring(0, OWN.length() - 1) + ";\n\n");
        java.append("import org.geysermc.mcprotocollib.protocol.codec.*;\nimport org.geysermc.mcprotocollib.network.packet.PacketRegistry;\nimport org.geysermc.mcprotocollib.protocol.data.ProtocolState;\n");
        String expressions = entries.stream().filter(e -> e.disposition.equals("typed")).map(e -> e.type.getSimpleName() + " " + e.factory).reduce("", (a, b) -> a + " " + b);
        for (var item : imports.entrySet()) if (!item.getKey().equals("ProtocolState") && containsIdentifier(expressions, item.getKey()))
            java.append("import ").append(item.getValue()).append(";\n");
        java.append("\n/** Generated from complete upstream registry + exact javac AST. Do not edit IDs/factories. */\npublic final class GeneratedCodec {\n");
        java.append("    public static final int PROTOCOL = ").append(protocol).append(";\n");
        java.append("    public static final String VERSION = ").append(json(version)).append(";\n");
        java.append("    public static final String MANIFEST_SHA256 = ").append(json(sha(manifestText.getBytes(StandardCharsets.UTF_8)))).append(";\n");
        java.append("    public static final PacketCodec CODEC = create();\n    private GeneratedCodec() {}\n    private static PacketCodec create() {\n");
        java.append("        PacketCodec codec = PacketCodec.builder().protocolVersion(PROTOCOL).minecraftVersion(VERSION)\n");
        if (helper != null) java.append("            .helper(").append(helper).append(")\n");
        for (String state : List.of("HANDSHAKE", "STATUS", "LOGIN", "CONFIGURATION", "GAME"))
            java.append("            .state(ProtocolState.").append(state).append(", MinecraftPacketRegistry.builder())\n");
        java.append("            .build();\n");
        for (String state : List.of("HANDSHAKE", "STATUS", "LOGIN", "CONFIGURATION", "GAME")) {
            java.append("        PacketRegistry ").append(state.toLowerCase(Locale.ROOT)).append(" = codec.getCodec(ProtocolState.").append(state).append(");\n");
            for (Entry entry : entries) if (entry.state.equals(state) && !entry.disposition.equals("rejected")) {
                boolean ignored = entry.disposition.equals("ignored");
                String factory = entry.factory;
                if (protocol <= 768 && entry.type.getSimpleName().equals("ServerboundContainerClickPacket"))
                    factory = "(input, helper) -> new ServerboundContainerClickPacket(input, BoundedItemCodecHelper.INSTANCE)";
                java.append("        ").append(state.toLowerCase(Locale.ROOT)).append(".register").append(entry.direction.equals("SERVERBOUND") ? "Serverbound" : "Clientbound")
                    .append("(").append(entry.id).append(", ").append(ignored ? "IgnoredPlayPacket" : entry.type.getSimpleName())
                    .append(".class, new MinecraftPacketSerializer<>(").append(ignored ? "IgnoredPlayPacket::new" : factory).append("));\n");
            }
        }
        java.append("        return codec;\n    }\n}\n");
        write("java/" + OWN.replace('.', '/') + "GeneratedCodec.java", java.toString());
        write("java/" + OWN.replace('.', '/') + "IgnoredPlayPacket.java", ignoredSource());
        write("java/" + OWN.replace('.', '/') + "SparseMinecraftProtocol.java", protocolSource());
        write("keep-rules.pro", keepRules());
        System.out.println("CODEC generated protocol=" + protocol + " upstream=" + entries.size() + " typed=" + entries.stream().filter(e -> e.disposition.equals("typed")).count()
            + " ignored=" + entries.stream().filter(e -> e.disposition.equals("ignored")).count() + " manifest-sha256=" + sha(manifestText.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean containsIdentifier(String text, String identifier) {
        return java.util.regex.Pattern.compile("(?<![\\w$])" + java.util.regex.Pattern.quote(identifier) + "(?![\\w$])").matcher(text).find();
    }
    private String ignoredSource() {
        String helperParameter = protocol <= 768 ? ", MinecraftCodecHelper helper" : "";
        return "package " + OWN.substring(0, OWN.length() - 1) + ";\n"
            + "import io.netty.buffer.ByteBuf;\nimport org.geysermc.mcprotocollib.protocol.codec.*;\n"
            + "/** Receive-only sentinel for explicitly generated, nonterminal GAME IDs; payload is already framed. */\n"
            + "public final class IgnoredPlayPacket implements MinecraftPacket {\n"
            + "    public IgnoredPlayPacket(ByteBuf payload" + helperParameter + ") { payload.skipBytes(payload.readableBytes()); }\n"
            + "    @Override public void serialize(ByteBuf out" + helperParameter + ") { throw new UnsupportedOperationException(\"Ignored packets are receive-only\"); }\n}\n";
    }
    private String protocolSource() throws Exception {
        Class<?> protocolClass = Class.forName(LIB + "protocol.MinecraftProtocol");
        Method client = Arrays.stream(protocolClass.getDeclaredMethods()).filter(m -> m.getName().equals("newClientSession")).findFirst().orElseThrow();
        String args = client.getParameterCount() == 2 ? "Session session, boolean transferring" : "Session session";
        require(client.getParameterCount() == 1 || client.getParameterCount() == 2, "Unsupported session initializer");
        return "package " + OWN.substring(0, OWN.length() - 1) + ";\n"
            + "import org.geysermc.mcprotocollib.network.Server;\nimport org.geysermc.mcprotocollib.network.Session;\n"
            + "import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;\n"
            + "/** Server-only root: does not call upstream initializers or instantiate client/auth/default server listeners. */\n"
            + "public final class SparseMinecraftProtocol extends MinecraftProtocol {\n"
            + "    public SparseMinecraftProtocol() { super(GeneratedCodec.CODEC); }\n"
            + "    @Override public void newClientSession(" + args + ") { resetStates(); }\n"
            + "    @Override public void newServerSession(Server server, Session session) { resetStates(); }\n}\n";
    }

    private String keepRules() throws Exception {
        StringBuilder rules = new StringBuilder("# Exact reflection/SPI roots for protocol " + protocol + "; no blanket MCPL SDK keep.\n");
        rules.append("-keep,allowoptimization class ").append(OWN).append("EngineFactory { public <init>(); public int getProtocolVersion(); public java.lang.String getVersionName(); public java.util.List biomeKeys(); public moe.ryuu.kyokouauth.limbo.LimboEngine create(moe.ryuu.kyokouauth.limbo.EngineContext); }\n");
        rules.append("-keep,allowoptimization class ").append(OWN).append("GeneratedCodec { public static *** CODEC; public static int PROTOCOL; public static java.lang.String VERSION; public static java.lang.String MANIFEST_SHA256; }\n");
        var reflective = new LinkedHashMap<String, Integer>();
        reflective.put("packet.login.clientbound." + (protocol == 767 ? "ClientboundGameProfilePacket" : "ClientboundLoginFinishedPacket"), protocol == 767 || protocol >= 776 ? 2 : 1);
        reflective.put("data.game.entity.player.PlayerSpawnInfo", protocol >= 768 ? 10 : 9);
        reflective.put("packet.ingame.clientbound.ClientboundLoginPacket", protocol >= 776 ? 12 : 11);
        reflective.put("data.game.chunk.ChunkSection", protocol >= 773 ? 4 : 0);
        reflective.put("packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket", protocol == 767 ? 7 : 10);
        reflective.put("packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket", 6);
        if (protocol >= 773) reflective.put("data.game.entity.player.ResolvableProfile", 1);
        // The offline exporter constructs these exact typed data constructors, not arbitrary SDK classes.
        reflective.put("packet.configuration.clientbound.ClientboundUpdateEnabledFeaturesPacket", 1);
        reflective.put("packet.configuration.clientbound.ClientboundSelectKnownPacks", 1);
        reflective.put("packet.configuration.clientbound.ClientboundFinishConfigurationPacket", 0);
        reflective.put("packet.ingame.clientbound.ClientboundSystemChatPacket", 2);
        reflective.put("packet.ingame.clientbound.title.ClientboundSetTitlesAnimationPacket", 3);
        reflective.put("packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket", 1);
        reflective.put("packet.ingame.clientbound.title.ClientboundSetTitleTextPacket", 1);
        for (var item : reflective.entrySet()) {
            Class<?> type = Class.forName(LIB + "protocol." + item.getKey());
            List<Constructor<?>> options = Arrays.stream(type.getConstructors()).filter(c -> c.getParameterCount() == item.getValue())
                .filter(c -> c.getParameterCount() == 0 || !c.getParameterTypes()[0].getName().startsWith("io.netty."))
                // Mirror ProtocolPackets.teleport's actual argument type: 767 passes emptyList; newer versions pass PositionElement[].
                .filter(c -> !item.getKey().endsWith("ClientboundPlayerPositionPacket") ||
                    (protocol == 767 ? c.getParameterTypes()[c.getParameterCount() - 1] == List.class :
                        c.getParameterTypes()[c.getParameterCount() - 1].isArray()))
                // ProtocolPackets.section passes (0, 1, biome, biomeCount), not prebuilt DataPalette instances.
                .filter(c -> !item.getKey().endsWith("ChunkSection") || protocol < 773 ||
                    Arrays.stream(c.getParameterTypes()).allMatch(parameter -> parameter == int.class))
                .toList();
            require(options.size() == 1, "Ambiguous reflected data constructor: " + type.getName() + " " + options);
            rules.append("-keep,allowoptimization class ").append(type.getName()).append(" { public <init>(")
                .append(String.join(",", Arrays.stream(options.get(0).getParameterTypes()).map(Class::getCanonicalName).toList())).append("); }\n");
        }
        if (protocol >= 768) rules.append("-keep,allowoptimization class ").append(LIB).append("protocol.data.game.entity.player.PositionElement { }\n");
        rules.append("-keep,allowoptimization class ").append(LIB).append("protocol.data.game.level.block.BlockEntityInfo { }\n");
        if (protocol >= 770) {
            String enumName = LIB + "protocol.data.game.level.HeightmapTypes";
            rules.append("-keep,allowoptimization enum ").append(enumName).append(" { public static ").append(enumName).append("[] values(); public static final ").append(enumName).append(" *; }\n");
        }
        if (protocol < 773) {
            Class<?> section = Class.forName(LIB + "protocol.data.game.chunk.ChunkSection");
            Class<?> palette = section.getMethod("getBiomeData").getReturnType();
            Method set = palette.getMethod("set", int.class, int.class, int.class, int.class);
            rules.append("-keepclassmembers,allowoptimization class ").append(section.getName()).append(" { public ").append(palette.getName()).append(" getBiomeData(); }\n");
            rules.append("-keepclassmembers,allowoptimization class ").append(palette.getName()).append(" { public ").append(set.getReturnType().getCanonicalName()).append(" set(int,int,int,int); }\n");
        }
        if (protocol == 777) {
            rules.append("-keep,allowoptimization class ").append(OWN).append("Protocol263 { public static *** INSTANCE; }\n");
            rules.append("-keep,allowoptimization class ").append(OWN).append("Login263 { public <init>(").append(LIB).append("protocol.packet.ingame.clientbound.ClientboundLoginPacket); }\n");
        }
        String componentOwner = LIB + "protocol.data.game.item.component." + (protocol <= 768 ? "DataComponentType" : "DataComponentTypes");
        rules.append("-keepclassmembers,allowoptimization class ").append(componentOwner).append(" { public static *** PROFILE; public static *** CUSTOM_NAME; public static *** LORE; }\n");
        rules.append("-keep,allowoptimization class ").append(OWN).append("RoleSelectorPackets { public static *** INSTANCE; public *** open(int,moe.ryuu.kyokouauth.limbo.RoleSelectorMenu); public *** content(int,int,moe.ryuu.kyokouauth.limbo.RoleSelectorMenu); public *** close(int); }\n");
        // Typed-packet export is an intentional tool API and must survive member shrinking.
        rules.append("-keep,allowoptimization class ").append(OWN).append("RegistrySnapshot { public static *** Companion; public java.util.List getRegistries(); public *** getTags(); }\n");
        rules.append("-keep,allowoptimization class ").append(OWN).append("RegistrySnapshot$Companion { public *** load(); }\n");
        rules.append("-keep,allowoptimization class ").append(OWN).append("LimboWorld { public <init>(moe.ryuu.kyokouauth.limbo.LimboSettings,").append(OWN).append("RegistrySnapshot); public java.util.List joinPackets(int); }\n");
        rules.append("-keep,allowoptimization class ").append(OWN).append("ProtocolPackets { public static *** INSTANCE; public *** loginFinished(").append(LIB).append("auth.GameProfile); }\n");
        rules.append("-keep,allowoptimization class ").append(OWN).append("LibraryComponentsKt { public static *** toLibrary(moe.ryuu.kyokouauth.limbo.protocol.TextComponent); }\n");
        rules.append("-keep,allowoptimization class ").append(LIB).append("auth.GameProfile { public <init>(java.util.UUID,java.lang.String); }\n");
        rules.append("-keepclassmembers,allowoptimization class ").append(CODEC).append("PacketCodec { public int getProtocolVersion(); public *** getCodec(").append(LIB).append("protocol.data.ProtocolState);");
        if (helper != null) rules.append(" public java.util.function.Supplier getHelperFactory();");
        rules.append(" }\n");
        rules.append("-keepclassmembers,allowoptimization class ").append(LIB).append("network.packet.PacketRegistry { public int getClientboundId(java.lang.Class); public *** getClientboundDefinition(int); }\n");
        rules.append("-keepclassmembers,allowoptimization class ").append(LIB).append("network.codec.PacketDefinition { public *** getSerializer(); }\n");
        rules.append("-keep,allowoptimization interface ").append(LIB).append("network.codec.PacketSerializer { public void serialize(io.netty.buffer.ByteBuf,");
        if (helper != null) rules.append(LIB).append("network.codec.PacketCodecHelper,");
        rules.append(LIB).append("network.packet.Packet); }\n");
        rules.append("-keepclassmembers,allowoptimization interface net.kyori.adventure.key.Key { public static net.kyori.adventure.key.Key key(java.lang.String); }\n");
        rules.append("-keep,allowoptimization enum ").append(LIB).append("protocol.data.ProtocolState { public static final *** *; }\n");
        return rules.toString();
    }

    private void verify() throws Exception {
        Class<?> generated = Class.forName(OWN + "GeneratedCodec");
        Object codec = generated.getField("CODEC").get(null);
        Class<?> ignoredClass = Class.forName(OWN + "IgnoredPlayPacket");
        Class<?> stateType = Class.forName(LIB + "protocol.data.ProtocolState");
        Object helperInstance = helper == null ? null : ((Supplier<?>) call(codec, "getHelperFactory")).get();
        int ignored = 0, rejected = 0, typed = 0;
        for (Entry entry : entries) {
            Object registry = codec.getClass().getMethod("getCodec", stateType).invoke(codec, stateType.getField(entry.state).get(null));
            String direction = entry.direction.equals("SERVERBOUND") ? "Serverbound" : "Clientbound";
            Method lookup = registry.getClass().getMethod("get" + direction + "Class", int.class);
            if (entry.disposition.equals("rejected")) { expectRejected(lookup, registry, entry.id); rejected++; continue; }
            Class<?> actual = (Class<?>) lookup.invoke(registry, entry.id);
            require(actual == (entry.disposition.equals("ignored") ? ignoredClass : entry.type), "Registry mismatch " + entry.key());
            if (entry.disposition.equals("ignored")) {
                Object buffer = Class.forName("io.netty.buffer.Unpooled").getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) new byte[] {(byte) 255, 0, 127});
                try {
                    Class<?> byteBuf = Class.forName("io.netty.buffer.ByteBuf");
                    Object packet;
                    if (helperInstance == null) packet = registry.getClass().getMethod("createServerboundPacket", int.class, byteBuf).invoke(registry, entry.id, buffer);
                    else packet = registry.getClass().getMethod("createServerboundPacket", int.class, byteBuf, Class.forName(LIB + "network.codec.PacketCodecHelper")).invoke(registry, entry.id, buffer, helperInstance);
                    require(packet.getClass() == ignoredClass && !(boolean) byteBuf.getMethod("isReadable").invoke(buffer), "Ignored payload not consumed exactly");
                    require(!(boolean) ignoredClass.getMethod("isTerminal").invoke(packet), "Ignored terminal packet");
                    Method serialize = Arrays.stream(ignoredClass.getMethods()).filter(m -> m.getName().equals("serialize")).findFirst().orElseThrow();
                    try { if (helperInstance == null) serialize.invoke(packet, buffer); else serialize.invoke(packet, buffer, helperInstance); throw new AssertionError("Ignored packet sent"); }
                    catch (InvocationTargetException expected) { require(expected.getCause() instanceof UnsupportedOperationException, "Unexpected ignored send result"); }
                } finally { Class.forName("io.netty.util.ReferenceCounted").getMethod("release").invoke(buffer); }
                ignored++;
            } else typed++;
        }
        for (String state : List.of("HANDSHAKE", "STATUS", "LOGIN", "CONFIGURATION", "GAME")) {
            Object registry = codec.getClass().getMethod("getCodec", stateType).invoke(codec, stateType.getField(state).get(null));
            expectRejected(registry.getClass().getMethod("getServerboundClass", int.class), registry, Integer.MAX_VALUE);
            expectRejected(registry.getClass().getMethod("getClientboundClass", int.class), registry, Integer.MAX_VALUE);
        }
        Class<?> sparse = Class.forName(OWN + "SparseMinecraftProtocol");
        Object instance = sparse.getConstructor().newInstance();
        // Null sessions deliberately prove initializers neither access flags nor register listeners.
        for (Method method : sparse.getDeclaredMethods()) if (method.getName().equals("newClientSession") || method.getName().equals("newServerSession")) {
            Object[] arguments = new Object[method.getParameterCount()];
            if (method.getParameterCount() == 2 && method.getParameterTypes()[1] == boolean.class) arguments[1] = false;
            method.invoke(instance, arguments);
        }
        System.out.println("CODEC verified protocol=" + protocol + " typed=" + typed + " ignored=" + ignored + " rejected=" + rejected + " unknown=10 listener-free=true");
    }
    private static void expectRejected(Method lookup, Object target, int id) throws Exception {
        try { lookup.invoke(target, id); throw new AssertionError("Rejected/unknown ID accepted: " + id); }
        catch (InvocationTargetException expected) { require(expected.getCause() instanceof IllegalArgumentException, "Unexpected rejection " + expected.getCause()); }
    }
    private static Object call(Object target, String method) throws Exception { return target.getClass().getMethod(method).invoke(target); }
    private void write(String relative, String contents) throws Exception {
        Path path = output.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, contents, StandardCharsets.UTF_8);
    }
    private static String generatorSha() throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (Class<?> type : List.of(CodecGenerator.class, ItemCodecGenerator.class)) {
            try (var stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                digest.update(Objects.requireNonNull(stream).readAllBytes());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var items = new ArrayList<String>();
            for (var item : map.entrySet()) items.add(json(item.getKey().toString()) + ":" + json(item.getValue()));
            return "{" + String.join(",", items) + "}";
        }
        if (value instanceof Collection<?> items) return "[" + String.join(",", items.stream().map(CodecGenerator::json).toList()) + "]";
        StringBuilder escaped = new StringBuilder("\"");
        for (char character : value.toString().toCharArray()) switch (character) {
            case '\\' -> escaped.append("\\\\"); case '"' -> escaped.append("\\\""); case '\n' -> escaped.append("\\n");
            case '\r' -> escaped.append("\\r"); case '\t' -> escaped.append("\\t");
            default -> { if (character < 32) escaped.append(String.format("\\u%04x", (int) character)); else escaped.append(character); }
        }
        return escaped.append('"').toString();
    }
}
