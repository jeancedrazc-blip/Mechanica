package mcjty.rftoolsbuilder.constructor.plan;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-job material override table.
 * Priority: exact BlockState -> exact Block -> tag -> original state.
 */
public final class BlockSubstitutionRules {
    private final Map<BlockState, BlockState> stateRules = new LinkedHashMap<>();
    private final Map<Block, BlockState> blockRules = new LinkedHashMap<>();
    private final Map<TagKey<Block>, BlockState> tagRules = new LinkedHashMap<>();

    public BlockSubstitutionRules replace(BlockState source, BlockState replacement) {
        stateRules.put(source, replacement);
        return this;
    }

    public BlockSubstitutionRules replace(Block source, BlockState replacement) {
        blockRules.put(source, replacement);
        return this;
    }

    public BlockSubstitutionRules replace(TagKey<Block> source, BlockState replacement) {
        tagRules.put(source, replacement);
        return this;
    }

    public BlockState apply(BlockState source) {
        BlockState exactState = stateRules.get(source);
        if (exactState != null) {
            return exactState;
        }

        BlockState exactBlock = blockRules.get(source.getBlock());
        if (exactBlock != null) {
            return exactBlock;
        }

        for (Map.Entry<TagKey<Block>, BlockState> rule : tagRules.entrySet()) {
            if (source.is(rule.getKey())) {
                return rule.getValue();
            }
        }
        return source;
    }

    public boolean isEmpty() {
        return stateRules.isEmpty() && blockRules.isEmpty() && tagRules.isEmpty();
    }
}
