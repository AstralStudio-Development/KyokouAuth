import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import javax.tools.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;

/** Build-only AST extraction of a narrow official item closure, with pre-allocation guards only. */
final class ItemCodecGenerator {
    private static final String ROOT = "org.geysermc.mcprotocollib.protocol.";
    private static final String OWN = "moe.ryuu.kyokouauth.limbo.session.";
    private static final Set<String> COMPONENTS = Set.of("PROFILE", "CUSTOM_NAME", "LORE", "CUSTOM_MODEL_DATA");
    private final JarFile jar;
    private final int protocol;
    private final Path output;
    private final Map<String, String> evidence = new TreeMap<>();
    private final Map<String, Integer> componentIds = new TreeMap<>();
    private record Parsed(String text, CompilationUnitTree unit, Trees trees) {
        int start(Tree node) { return (int) trees.getSourcePositions().getStartPosition(unit, node); }
        int end(Tree node) { return (int) trees.getSourcePositions().getEndPosition(unit, node); }
        String exact(Tree node) { return text.substring(start(node), end(node)); }
        ClassTree type() { return (ClassTree) unit.getTypeDecls().stream().filter(t -> t instanceof ClassTree).findFirst().orElseThrow(); }
    }
    private record Replacement(int start, int end, String text) {}

    ItemCodecGenerator(JarFile jar, int protocol, Path output) { this.jar = jar; this.protocol = protocol; this.output = output; }

    void generate() throws Exception {
        registry();
        click();
        if (protocol <= 768) legacyHelper();
        else boundedTypes();
        if (protocol >= 769) boundedCustomModelData();
        write(OWN + "ItemDecodeBudget", "package " + OWN.substring(0, OWN.length()-1) + ";\n" +
            "import io.netty.buffer.ByteBuf;\n" +
            "/** Receive-only budgets: checked before variable allocations in the fixed original codec closure. */\n" +
            "public final class ItemDecodeBudget {\n" +
            " public static final int FRAME_BYTES=65536, CHANGED_SLOTS=64, COMPONENTS=4, LIST_ITEMS=8, STRING_CHARS=8192, NBT_BYTES=16384, NBT_DEPTH=16;\n" +
            " public static int limit(int value,int maximum,String field) { if(value<0 || value>maximum) throw new IllegalArgumentException(\"Role selector decode budget: \"+field); return value; }\n" +
            " public static void frame(ByteBuf in) { limit(in.readableBytes(),FRAME_BYTES,\"frame\"); }\n" +
            " private ItemDecodeBudget() {}\n}\n");
        StringBuilder metadata = new StringBuilder("{\"schema\":1,\"protocol\":" + protocol + ",\"source\":\"pinned-official-mcpl-ast-sparse-item-closure\",\"components\":[\"CUSTOM_MODEL_DATA\",\"CUSTOM_NAME\",\"LORE\",\"PROFILE\"],\"frameBytes\":65536,\"changedSlots\":64,\"componentCount\":4,\"cmdListMaxima\":[1,0,0,0],\"cmdFiniteFloats\":true,\"listItems\":8,\"stringChars\":8192,\"nbtBytes\":16384,\"nbtDepth\":16,\"sources\":{");
        boolean first = true;
        for (var item : evidence.entrySet()) {
            if (!first) metadata.append(','); first = false;
            metadata.append('"').append(item.getKey()).append("\":\"").append(item.getValue()).append('"');
        }
        metadata.append("},\"componentIds\":{");
        first = true;
        for (var item : componentIds.entrySet()) {
            if (!first) metadata.append(','); first = false;
            metadata.append('"').append(item.getKey()).append("\":").append(item.getValue());
        }
        metadata.append("}}\n");
        Path resource = output.resolve("resources/limbo/items-" + protocol + ".json");
        Files.createDirectories(resource.getParent()); Files.writeString(resource, metadata, StandardCharsets.UTF_8);
    }

    private Parsed source(String name) throws Exception {
        String path = name.replace('.', '/') + ".java";
        var entry = jar.getJarEntry(path);
        require(entry != null, "Missing pinned item source " + path);
        String text;
        try (var stream = jar.getInputStream(entry)) { text = new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
        evidence.put(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var file = new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return text; }
        };
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(file));
        var unit = task.parse().iterator().next();
        require(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR), "Malformed pinned item source " + path);
        return new Parsed(text, unit, Trees.instance(task));
    }

    private static String replace(String text, String old, String replacement) {
        require(text.contains(old), "Pinned item source guard anchor missing: " + old);
        return text.replace(old, replacement);
    }
    private static String apply(Parsed source, List<Replacement> replacements) {
        StringBuilder result = new StringBuilder(source.text);
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        for (var item : replacements) result.replace(item.start, item.end, item.text);
        return result.toString();
    }
    private static String withoutLombok(String text) {
        return text.replaceAll("(?m)^import lombok\\.[^;]+;\\R", "")
            .replaceAll("(?m)^import org\\.jetbrains\\.annotations\\.[^;]+;\\R", "")
            .replaceAll("(?m)^@(Data|With|Getter)\\R", "")
            .replaceAll("@(NonNull|Nullable|NotNull)\\b\\s*", "");
    }
    private static String imports(Parsed... sources) {
        var imports = new LinkedHashSet<String>();
        for (var source : sources) for (var item : source.unit.getImports()) {
            String name = item.getQualifiedIdentifier().toString();
            if (!name.startsWith("lombok.")) imports.add("import " + name + ";\n");
        }
        return String.join("", imports);
    }
    private static String methods(Parsed source, Set<String> names) {
        StringBuilder result = new StringBuilder();
        var found = new HashSet<String>();
        for (Tree member : source.type().getMembers()) if (member instanceof MethodTree method && names.contains(method.getName().toString())) {
            result.append(source.exact(method)).append('\n'); found.add(method.getName().toString());
        }
        require(found.equals(names), "Missing official bounded method closure " + names + ": " + found);
        return result.toString();
    }

    private void registry() throws Exception {
        String name = ROOT + "data.game.item.component." + (protocol <= 768 ? "DataComponentType" : "DataComponentTypes");
        var parsed = source(name);
        var replacements = new ArrayList<Replacement>();
        int registrationCount = 0;
        var found = new HashSet<String>();
        for (Tree member : parsed.type().getMembers()) if (member instanceof VariableTree field && field.getInitializer() != null &&
                field.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.PUBLIC) &&
                field.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC) &&
                field.getType().toString().contains("ComponentType")) {
            registrationCount++;
            if (COMPONENTS.contains(field.getName().toString())) {
                String component = field.getName().toString();
                Object original = Class.forName(name).getField(component).get(null);
                int id = (int) original.getClass().getMethod("getId").invoke(original);
                require(id == registrationCount - 1, "Official binary/source component ordinal differs: " + component);
                componentIds.put(component, id); found.add(component); continue;
            }
            String declaration = parsed.text.substring(parsed.start(field), parsed.start(field.getInitializer()));
            // Preserve the public symbol as a null tombstone for binary references in unused upstream methods.
            // Every original ordinal is consumed, so the four retained factories keep their official numeric IDs.
            replacements.add(new Replacement(parsed.start(field), parsed.end(field), declaration + "null; static { VALUES.add(null); }"));
        }
        require(found.equals(COMPONENTS) && registrationCount > 40, "Unexpected official component registration layout");
        String result = withoutLombok(apply(parsed, replacements));
        result = "// Generated from pinned upstream AST; non-GUI component registration factories are deliberately absent.\n" + result;
        result = result.replace("return VALUES.get(id);", "if (id < 0 || id >= VALUES.size() || VALUES.get(id) == null) throw new IllegalArgumentException(\"Unsupported role selector component \" + id); return VALUES.get(id);");
        result = result.replace("component -> component.getKey().equals(key)", "component -> component != null && component.getKey().equals(key)");
        result = result.replace("import io.netty.buffer.ByteBuf;", "import io.netty.buffer.ByteBuf;\nimport " + OWN + "ItemDecodeBudget;\n");
        if (protocol <= 768) {
            result = replace(result, "int size = helper.readVarInt(input);", "int size = helper.readVarInt(input); ItemDecodeBudget.limit(size, 8, \"lore\");");
            int end = result.lastIndexOf('}');
            result = result.substring(0, end) + "public int getId() { return id; }\npublic Reader<T> getReader() { return reader; }\npublic Writer<T> getWriter() { return writer; }\npublic DataComponentFactory<T> getDataComponentFactory() { return dataComponentFactory; }\n" + result.substring(end);
        } else if (protocol == 769) {
            result = replace(result, "int size = MinecraftTypes.readVarInt(input);", "int size = MinecraftTypes.readVarInt(input); ItemDecodeBudget.limit(size, 8, \"lore\");");
            result = result.replace("MinecraftTypes::readComponent", OWN + "BoundedItemTypes::readComponent")
                .replace("ItemTypes::readResolvableProfile", OWN + "BoundedItemTypes::readResolvableProfile");
        }
        if (protocol >= 769) {
            result = replace(result, "ItemTypes::readCustomModelData", OWN + "BoundedCustomModelData::readCustomModelData");
        }
        write(name, result);
    }

    private void click() throws Exception {
        String name = ROOT + "packet.ingame.serverbound.inventory.ServerboundContainerClickPacket";
        var parsed = source(name);
        String result = withoutLombok(parsed.text);
        String reader = protocol <= 768 ? "helper.readVarInt(in)" : "MinecraftTypes.readVarInt(in)";
        result = replace(result, "int changedItemsSize = " + reader + ";", "int changedItemsSize = " + reader + "; " + OWN + "ItemDecodeBudget.limit(changedItemsSize,64,\"changedSlots\");");
        String constructor = protocol <= 768 ? "public ServerboundContainerClickPacket(ByteBuf in, MinecraftCodecHelper helper) {" : "public ServerboundContainerClickPacket(ByteBuf in) {";
        result = replace(result, constructor, constructor + "\n" + OWN + "ItemDecodeBudget.frame(in);");
        if (protocol == 769) result = result.replace("MinecraftTypes.readOptionalItemStack(in)", OWN + "BoundedItemTypes.readOptionalItemStack(in)");
        if (protocol >= 770) result = result.replace("MinecraftTypes::readHashedStack", OWN + "BoundedItemTypes::readHashedStack");
        StringBuilder getters = new StringBuilder();
        for (Tree member : parsed.type().getMembers()) if (member instanceof VariableTree field && !field.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC)) {
            String fieldName = field.getName().toString();
            getters.append("public ").append(field.getType().toString().replace("@NonNull ", "")).append(" get").append(Character.toUpperCase(fieldName.charAt(0))).append(fieldName.substring(1))
                .append("() { return ").append(fieldName).append("; }\n");
        }
        int end = result.lastIndexOf('}');
        write(name, result.substring(0, end) + getters + result.substring(end));
    }

    private void legacyHelper() throws Exception {
        var original = source(ROOT + "codec.MinecraftCodecHelper");
        String selected = methods(original, Set.of("readList", "readDataComponentPatch", "readAnyTag"));
        selected = selected.replace("ItemCodecHelper.INSTANCE", "this");
        selected = replace(selected, "int size = this.readVarInt(buf);", "int size = this.readVarInt(buf); ItemDecodeBudget.limit(size,8,\"list\");");
        selected = replace(selected, "int nullComponents = this.readVarInt(buf);", "int nullComponents = this.readVarInt(buf); ItemDecodeBudget.limit(nonNullComponents,ItemDecodeBudget.COMPONENTS,\"components\"); ItemDecodeBudget.limit(nullComponents,ItemDecodeBudget.COMPONENTS-nonNullComponents,\"removedComponents\");");
        selected = replace(selected, "new NBTInputStream(input).readValue(type, 512)", "new NBTInputStream(input,16384L).readValue(type,16)");
        write(OWN + "BoundedItemCodecHelper", "package " + OWN.substring(0, OWN.length()-1) + ";\n" + imports(original) +
            "public final class BoundedItemCodecHelper extends org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemCodecHelper {\n" +
            "public static final BoundedItemCodecHelper INSTANCE=new BoundedItemCodecHelper();\n" +
            "@Override public String readString(ByteBuf in) { return super.readString(in,8192); }\n" +
            "@Override public String readString(ByteBuf in,int max) { return super.readString(in,Math.min(max,8192)); }\n" + selected + "}\n");
    }

    private void boundedTypes() throws Exception {
        var original = source(ROOT + "codec.MinecraftTypes");
        if (protocol >= 770) {
            String selected = methods(original, Set.of("readHashedStack"));
            selected = replace(selected, "int length = MinecraftTypes.readVarInt(buf);", "int length = MinecraftTypes.readVarInt(buf); ItemDecodeBudget.limit(length,ItemDecodeBudget.COMPONENTS,\"hashedComponents\"); int addedCount=length;");
            selected = replace(selected, "\n        length = MinecraftTypes.readVarInt(buf);", "\n        length = MinecraftTypes.readVarInt(buf); ItemDecodeBudget.limit(length,ItemDecodeBudget.COMPONENTS-addedCount,\"hashedComponentCount\");");
            write(OWN + "BoundedItemTypes", "package " + OWN.substring(0,OWN.length()-1) + ";\n" + imports(original) +
                "import " + ROOT + "codec.MinecraftTypes;\n" +
                "public final class BoundedItemTypes {\n" + selected + "}\n");
            return;
        }
        var items = source(ROOT + "data.game.item.component.ItemTypes");
        var names = Set.of("readOptionalItemStack", "readDataComponentPatch", "readAnyTag", "readComponent", "readNullable", "readUUID", "readString", "readList", "readProperty");
        String selected = methods(original, names) + methods(items, Set.of("readResolvableProfile"));
        for (String name : names) selected = selected.replace("MinecraftTypes." + name + "(", "BoundedItemTypes." + name + "(")
            .replace("MinecraftTypes::" + name, "BoundedItemTypes::" + name);
        selected = replace(selected, "int size = MinecraftTypes.readVarInt(buf);", "int size = MinecraftTypes.readVarInt(buf); ItemDecodeBudget.limit(size,8,\"list\");");
        selected = replace(selected, "int length = MinecraftTypes.readVarInt(buf);", "int length = MinecraftTypes.readVarInt(buf); ItemDecodeBudget.limit(length,24576,\"stringBytes\");");
        selected = replace(selected, "int nullComponents = MinecraftTypes.readVarInt(buf);", "int nullComponents = MinecraftTypes.readVarInt(buf); ItemDecodeBudget.limit(nonNullComponents,ItemDecodeBudget.COMPONENTS,\"components\"); ItemDecodeBudget.limit(nullComponents,ItemDecodeBudget.COMPONENTS-nonNullComponents,\"removedComponents\");");
        selected = replace(selected, "Short.MAX_VALUE", "8192");
        selected = replace(selected, "new NBTInputStream(input).readValue(type, 512)", "new NBTInputStream(input,16384L).readValue(type,16)");
        write(OWN + "BoundedItemTypes", "package " + OWN.substring(0,OWN.length()-1) + ";\n" + imports(original, items) +
            "import " + ROOT + "codec.NbtComponentSerializer;\n" +
            "public final class BoundedItemTypes {\n" + selected + "}\n");
    }
    private void boundedCustomModelData() throws Exception {
        var types = source(ROOT + "codec.MinecraftTypes");
        var items = source(ROOT + "data.game.item.component.ItemTypes");
        var listMethods = new ArrayList<MethodTree>();
        for (Tree member : types.type().getMembers()) if (member instanceof MethodTree method &&
                method.getName().contentEquals("readList") && method.getBody() != null &&
                types.exact(method.getBody()).contains("int size = MinecraftTypes.readVarInt(buf);")) listMethods.add(method);
        require(listMethods.size() == 1, "Ambiguous official list implementation for CMD budget");
        MethodTree list = listMethods.get(0);
        require(list.getParameters().size() == 2 || list.getParameters().size() == 3, "Unexpected official list signature");
        String original = types.exact(list);
        int bodyStart = types.start(list.getBody()) - types.start(list);
        String header = replace(original.substring(0, bodyStart), " readList(", " readCmdList(");
        int close = header.lastIndexOf(')');
        require(close >= 0, "Missing official list parameter boundary");
        String boundedList = header.substring(0, close) + ", int maximum" + header.substring(close) + original.substring(bodyStart);
        boundedList = replace(boundedList, "int size = MinecraftTypes.readVarInt(buf);",
            "int size = MinecraftTypes.readVarInt(buf); ItemDecodeBudget.limit(size,maximum,\"customModelData list\");");
        String wrapper = list.getParameters().size() == 3 ?
            "private static <T> List<T> readCmdList(ByteBuf buf, java.util.function.Function<ByteBuf,T> reader, int maximum) { return readCmdList(buf,reader,true,maximum); }\n" : "";
        String read = methods(items, Set.of("readCustomModelData"));
        read = replace(read, "MinecraftTypes.readList(buf, ByteBuf::readFloat)", "readCmdList(buf, ByteBuf::readFloat, 1)");
        read = replace(read, "MinecraftTypes.readList(buf, ByteBuf::readBoolean)", "readCmdList(buf, ByteBuf::readBoolean, 0)");
        read = replace(read, "MinecraftTypes.readList(buf, MinecraftTypes::readString)", "readCmdList(buf, MinecraftTypes::readString, 0)");
        read = replace(read, "MinecraftTypes.readList(buf, ByteBuf::readInt)", "readCmdList(buf, ByteBuf::readInt, 0)");
        read = replace(read, "List<Boolean> flags = ",
            "if (floats.stream().anyMatch(value -> !Float.isFinite(value))) throw new IllegalArgumentException(\"Non-finite avatar customModelData float\");\n        List<Boolean> flags = ");
        write(OWN + "BoundedCustomModelData", "package " + OWN.substring(0,OWN.length()-1) + ";\n" + imports(types, items) +
            "import " + ROOT + "data.game.item.component.CustomModelData;\n" +
            "/** Original fixed CMD reader/list implementation, with pre-allocation 1/0/0/0 caps and finite floats. */\n" +
            "public final class BoundedCustomModelData {\n" + read + wrapper + boundedList + "\n}\n");
    }

    private void write(String name, String content) throws Exception {
        Path target = output.resolve("java/" + name.replace('.', '/') + ".java");
        Files.createDirectories(target.getParent()); Files.writeString(target, withoutLombok(content), StandardCharsets.UTF_8);
    }
    private static void require(boolean condition, String message) { if(!condition) throw new IllegalStateException(message); }
}
