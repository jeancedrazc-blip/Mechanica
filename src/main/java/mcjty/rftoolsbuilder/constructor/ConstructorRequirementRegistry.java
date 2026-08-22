package mcjty.rftoolsbuilder.constructor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Public compatibility hook for modded schematic placement requirements. */
public final class ConstructorRequirementRegistry {
    @FunctionalInterface
    public interface BlockRequirementProvider {
        ConstructorRequirement get(BlockState state, CompoundTag blockEntityData);
    }

    @FunctionalInterface
    public interface BlockEntityRequirementProvider {
        ConstructorRequirement get(BlockState state, CompoundTag blockEntityData);
    }

    private static final Map<Block, BlockRequirementProvider> BLOCKS = new ConcurrentHashMap<>();
    private static final Map<BlockEntityType<?>, BlockEntityRequirementProvider> BLOCK_ENTITIES = new ConcurrentHashMap<>();

    private ConstructorRequirementRegistry() {}

    public static void register(Block block, BlockRequirementProvider provider) {
        if (block != null && provider != null) BLOCKS.put(block, provider);
    }

    public static void register(BlockEntityType<?> type, BlockEntityRequirementProvider provider) {
        if (type != null && provider != null) BLOCK_ENTITIES.put(type, provider);
    }

    public static ConstructorRequirement resolve(BlockState state, CompoundTag blockEntityData) {
        if (state == null) return ConstructorRequirement.INVALID;
        BlockRequirementProvider blockProvider = BLOCKS.get(state.getBlock());
        ConstructorRequirement result = blockProvider == null
                ? ConstructorRequirement.defaultFor(state, blockEntityData)
                : safe(blockProvider, state, blockEntityData);

        if (result.isInvalid() || !state.hasBlockEntity() || blockEntityData == null || blockEntityData.isEmpty()) return result;
        BlockEntityType<?> type = ConstructorSafeBlockEntityData.resolveType(blockEntityData);
        if (type == null) return result;
        BlockEntityRequirementProvider beProvider = BLOCK_ENTITIES.get(type);
        if (beProvider == null) return result;
        return result.union(safe(beProvider, state, blockEntityData));
    }

    private static ConstructorRequirement safe(BlockRequirementProvider provider, BlockState state, CompoundTag data) {
        try {
            ConstructorRequirement requirement = provider.get(state, data == null ? null : data.copy());
            return requirement == null ? ConstructorRequirement.INVALID : requirement;
        } catch (RuntimeException ignored) {
            return ConstructorRequirement.INVALID;
        }
    }

    private static ConstructorRequirement safe(BlockEntityRequirementProvider provider, BlockState state, CompoundTag data) {
        try {
            ConstructorRequirement requirement = provider.get(state, data == null ? null : data.copy());
            return requirement == null ? ConstructorRequirement.INVALID : requirement;
        } catch (RuntimeException ignored) {
            return ConstructorRequirement.INVALID;
        }
    }
}
