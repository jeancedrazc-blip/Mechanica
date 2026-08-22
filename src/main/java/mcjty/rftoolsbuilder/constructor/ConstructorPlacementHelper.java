package mcjty.rftoolsbuilder.constructor;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Authoritative world-placement path for Constructor projectiles. */
public final class ConstructorPlacementHelper {
    private ConstructorPlacementHelper() {}

    public static boolean shouldIgnore(BlockState state) {
        if (state == null || state.getBlock() == Blocks.STRUCTURE_VOID) return true;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return true;
        return state.getBlock() instanceof PistonHeadBlock;
    }

    public static BlockState sanitizeState(BlockState state) {
        return ConstructorStateFilterRegistry.sanitize(state);
    }

    public static boolean place(ServerLevel level, BlockPos target, BlockState requested, ItemStack placementStack,
                                CompoundTag rawBlockEntityData) {
        BlockState state = sanitizeState(requested);
        if (state.isAir()) return level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        CompoundTag safeData = ConstructorSafeBlockEntityData.sanitize(state, rawBlockEntityData);
        if (!level.setBlock(target, state, Block.UPDATE_ALL)) return false;

        if (safeData != null && !safeData.isEmpty()) {
            BlockEntity blockEntity = level.getBlockEntity(target);
            if (blockEntity != null) {
                CompoundTag relocated = safeData.copy();
                relocated.putInt("x", target.getX());
                relocated.putInt("y", target.getY());
                relocated.putInt("z", target.getZ());
                ConstructorBlockEntityDataCompat.apply(blockEntity, relocated, level);
            }
        }

        try {
            state.getBlock().setPlacedBy(level, target, state, null, placementStack == null ? ItemStack.EMPTY : placementStack);
        } catch (RuntimeException ignored) {
            // A broken placement hook cannot roll back an already-authoritative impact.
        }
        return true;
    }
}
