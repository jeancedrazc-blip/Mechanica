package mcjty.rftoolsbuilder.constructor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirtPathBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Material requirement for one normalized schematic target. */
public final class ConstructorRequirement {
    public enum Use { CONSUME, DAMAGE }

    public record StackRequirement(ItemStack stack, Use use, boolean strictComponents) {
        public StackRequirement {
            stack = stack == null ? ItemStack.EMPTY : stack.copy();
        }

        public boolean matches(ItemStack other) {
            if (other == null || other.isEmpty() || stack.isEmpty()) return false;
            return strictComponents
                    ? ItemStack.isSameItemSameComponents(stack, other)
                    : ItemStack.isSameItem(stack, other);
        }
    }

    public static final ConstructorRequirement NONE = new ConstructorRequirement(List.of(), false);
    public static final ConstructorRequirement INVALID = new ConstructorRequirement(List.of(), true);

    private final List<StackRequirement> requirements;
    private final boolean invalid;

    public ConstructorRequirement(List<StackRequirement> requirements) {
        this(requirements, false);
    }

    private ConstructorRequirement(List<StackRequirement> requirements, boolean invalid) {
        this.requirements = Collections.unmodifiableList(new ArrayList<>(requirements));
        this.invalid = invalid;
    }

    public static ConstructorRequirement consume(Item item, int count) {
        if (item == null || item == Items.AIR || count <= 0) return INVALID;
        return new ConstructorRequirement(List.of(new StackRequirement(new ItemStack(item, count), Use.CONSUME, false)));
    }

    public static ConstructorRequirement consume(ItemStack stack, boolean strictComponents) {
        if (stack == null || stack.isEmpty()) return INVALID;
        return new ConstructorRequirement(List.of(new StackRequirement(stack, Use.CONSUME, strictComponents)));
    }

    public static ConstructorRequirement damage(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return INVALID;
        return new ConstructorRequirement(List.of(new StackRequirement(stack.copyWithCount(1), Use.DAMAGE, false)));
    }

    public ConstructorRequirement union(ConstructorRequirement other) {
        if (invalid || other == null || other.invalid) return INVALID;
        if (requirements.isEmpty()) return other;
        if (other.requirements.isEmpty()) return this;
        ArrayList<StackRequirement> result = new ArrayList<>(requirements);
        result.addAll(other.requirements);
        return new ConstructorRequirement(result);
    }

    public boolean isEmpty() { return !invalid && requirements.isEmpty(); }
    public boolean isInvalid() { return invalid; }
    public List<StackRequirement> requirements() { return requirements; }

    static ConstructorRequirement defaultFor(BlockState state, CompoundTag blockEntityData) {
        if (state == null || state.isAir() || state.getBlock() == Blocks.STRUCTURE_VOID) return NONE;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return NONE;
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return NONE;
        if (state.getBlock() instanceof PistonHeadBlock) return NONE;

        Block block = state.getBlock();
        Item item = block.asItem();
        if (item == Items.AIR) return INVALID;

        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return consume(item, 2);
        if (block instanceof TurtleEggBlock && state.hasProperty(BlockStateProperties.EGGS)) return consume(item, state.getValue(BlockStateProperties.EGGS));
        if (block instanceof SeaPickleBlock && state.hasProperty(BlockStateProperties.PICKLES)) return consume(item, state.getValue(BlockStateProperties.PICKLES));
        if (block instanceof SnowLayerBlock && state.hasProperty(BlockStateProperties.LAYERS)) return consume(item, state.getValue(BlockStateProperties.LAYERS));
        if (state.hasProperty(BlockStateProperties.CANDLES)) return consume(item, state.getValue(BlockStateProperties.CANDLES));
        if (block instanceof FarmlandBlock || block instanceof DirtPathBlock) return consume(Items.DIRT, 1);
        if (block == Blocks.TALL_GRASS) return consume(Items.SHORT_GRASS, 2);
        if (block == Blocks.LARGE_FERN) return consume(Items.FERN, 2);
        if (block instanceof AbstractBannerBlock) return consume(item, 1);
        return consume(item, 1);
    }
}
