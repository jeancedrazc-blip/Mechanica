package mcjty.rftoolsbuilder.constructor.plan;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/** One normalized schematic block, relative to the plan origin. */
public record ConstructionEntry(BlockPos relativePos, BlockState sourceState, CompoundTag blockEntityData) {
    public ConstructionEntry {
        relativePos = relativePos.immutable();
        blockEntityData = blockEntityData == null ? null : blockEntityData.copy();
    }

    public ConstructionEntry(BlockPos relativePos, BlockState sourceState) {
        this(relativePos, sourceState, null);
    }

    public CompoundTag blockEntityDataCopy() {
        return blockEntityData == null ? null : blockEntityData.copy();
    }
}
