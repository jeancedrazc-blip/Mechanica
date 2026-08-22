package mcjty.rftoolsbuilder.constructor;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/** State filters prevent transient/unsafe state from being reproduced verbatim. */
public final class ConstructorStateFilterRegistry {
    private static final Map<Block, UnaryOperator<BlockState>> FILTERS = new ConcurrentHashMap<>();

    private ConstructorStateFilterRegistry() {}

    public static void register(Block block, UnaryOperator<BlockState> filter) {
        if (block != null && filter != null) FILTERS.put(block, filter);
    }

    public static BlockState sanitize(BlockState input) {
        if (input == null) return Blocks.AIR.defaultBlockState();
        BlockState state = input;

        if (state.hasProperty(BlockStateProperties.AGE_1)) state = state.setValue(BlockStateProperties.AGE_1, 0);
        if (state.hasProperty(BlockStateProperties.AGE_2)) state = state.setValue(BlockStateProperties.AGE_2, 0);
        if (state.hasProperty(BlockStateProperties.AGE_3)) state = state.setValue(BlockStateProperties.AGE_3, 0);
        if (state.hasProperty(BlockStateProperties.AGE_5)) state = state.setValue(BlockStateProperties.AGE_5, 0);
        if (state.hasProperty(BlockStateProperties.AGE_7)) state = state.setValue(BlockStateProperties.AGE_7, 0);
        if (state.hasProperty(BlockStateProperties.AGE_15)) state = state.setValue(BlockStateProperties.AGE_15, 0);
        if (state.hasProperty(BlockStateProperties.AGE_25)) state = state.setValue(BlockStateProperties.AGE_25, 0);
        if (state.hasProperty(BlockStateProperties.HATCH)) state = state.setValue(BlockStateProperties.HATCH, 0);
        if (state.hasProperty(BlockStateProperties.STAGE)) state = state.setValue(BlockStateProperties.STAGE, 0);
        if (state.hasProperty(BlockStateProperties.LEVEL_HONEY)) state = state.setValue(BlockStateProperties.LEVEL_HONEY, 0);
        if (state.hasProperty(BlockStateProperties.EXTENDED)) state = state.setValue(BlockStateProperties.EXTENDED, false);
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) state = state.setValue(BlockStateProperties.WATERLOGGED, false);
        if (state.is(net.minecraft.tags.BlockTags.CAULDRONS)) state = Blocks.CAULDRON.defaultBlockState();
        if (state.getBlock() == Blocks.COMPOSTER) state = Blocks.COMPOSTER.defaultBlockState();

        // A copied occupied bookshelf state would visually duplicate books even though the inventory NBT is stripped.
        if (state.getBlock() == Blocks.CHISELED_BOOKSHELF) {
            for (var property : ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES) {
                if (state.hasProperty(property)) state = state.setValue(property, false);
            }
        }

        UnaryOperator<BlockState> filter = FILTERS.get(state.getBlock());
        if (filter != null) {
            try {
                BlockState filtered = filter.apply(state);
                if (filtered != null) state = filtered;
            } catch (RuntimeException ignored) {
            }
        }
        return state;
    }
}
