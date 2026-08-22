package mcjty.rftoolsbuilder.constructor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import java.util.List;

public final class ConstructorBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<ConstructorBlock> CODEC = simpleCodec(ConstructorBlock::new);

    public ConstructorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(BlockStateProperties.LIT, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING, BlockStateProperties.LIT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(BlockStateProperties.LIT, false);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ConstructorBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != ConstructorBootstrap.CONSTRUCTOR_BLOCK_ENTITY.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<ConstructorBlockEntity>) (tickLevel, tickPos, tickState, constructor) -> {
            ConstructorBlockEntity.tick(tickLevel, tickPos, tickState, constructor);
            if (!tickLevel.isClientSide()) {
                BlockState current = tickLevel.getBlockState(tickPos);
                boolean lit = constructor.isRunning();
                if (current.hasProperty(BlockStateProperties.LIT)
                        && current.getValue(BlockStateProperties.LIT) != lit) {
                    tickLevel.setBlock(tickPos, current.setValue(BlockStateProperties.LIT, lit), 3);
                }
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return open(level, pos, player);
    }

    @Override
    public InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return open(level, pos, player);
    }

    private InteractionResult open(Level level, BlockPos pos, Player player) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof ConstructorBlockEntity constructor) {
            player.openMenu(constructor, buffer -> buffer.writeBlockPos(pos));
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && !player.isCreative() && level.getBlockEntity(pos) instanceof ConstructorBlockEntity constructor) {
            Containers.dropContents(level, pos, constructor);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        BlockEntity blockEntity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (blockEntity instanceof ConstructorBlockEntity constructor) {
            for (ItemStack drop : drops) {
                if (!drop.is(this.asItem())) continue;
                CompoundTag tag = new CompoundTag();
                CustomData existing = drop.get(DataComponents.CUSTOM_DATA);
                if (existing != null) tag = existing.copyTag();
                tag.putInt("QTEnergy", constructor.storedEnergy());
                tag.putInt("QTSpeed", constructor.speed().ordinal());
                tag.putInt("QTEnergyBlockProgress", constructor.energyBlockProgress());
                drop.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                break;
            }
        }
        return drops;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (!level.isClientSide() && data != null && level.getBlockEntity(pos) instanceof ConstructorBlockEntity constructor) {
            CompoundTag tag = data.copyTag();
            constructor.restorePortableState(tag.getIntOr("QTEnergy", 0), tag.getIntOr("QTSpeed", 0),
                    tag.getIntOr("QTEnergyBlockProgress", 0));
        }
    }
}
