import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/** Extracts canonical state keys and their per-release wire IDs from the official server. */
public final class BlockStateFixture {
    private static <T extends Comparable<T>> String name(BlockState state, Property<T> property) {
        return property.getName() + "=" + property.getName(state.getValue(property));
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var predicate = net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING.isOpaque();
        var motionKeys = args.length == 0 ? null : new java.util.HashSet<>(java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[0])));
        var out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        out.write("BLOCK_STATES_BEGIN\n");
        for (int id = 0; id < Block.BLOCK_STATE_REGISTRY.size(); id++) {
            var state = Block.BLOCK_STATE_REGISTRY.byId(id);
            var block = state.getBlock();
            var key = BuiltInRegistries.BLOCK.getKey(block).toString();
            boolean fluid = !state.getFluidState().isEmpty();
            boolean motion = motionKeys == null ? predicate.test(state) : motionKeys.contains(key) || fluid;
            int flags = (state.isAir() ? 1 : 0) | (motion ? 2 : 0) | (fluid ? 4 : 0) | (state == block.defaultBlockState() ? 8 : 0);
            String properties = state.getProperties().stream().map(property -> name(state, property)).sorted()
                .collect(Collectors.joining(",", "[", "]"));
            out.write(BuiltInRegistries.BLOCK.getKey(block) + (properties.equals("[]") ? "" : properties) + "\t" + flags + "\n");
        }
        out.write("BLOCK_STATES_END\n");
        out.flush();
    }
}
