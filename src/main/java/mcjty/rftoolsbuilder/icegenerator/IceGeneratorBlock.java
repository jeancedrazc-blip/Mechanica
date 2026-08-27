package mcjty.rftoolsbuilder.icegenerator;

import com.mojang.serialization.MapCodec;
import mcjty.rftoolsbuilder.RFToolsBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Directional machine whose cyan intake consumes a water source directly in front. */
public final class IceGeneratorBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<IceGeneratorBlock> CODEC = simpleCodec(IceGeneratorBlock::new);

    public IceGeneratorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new IceGeneratorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != RFToolsBuilder.ICE_GENERATOR_BLOCK_ENTITY.get()) return null;
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof IceGeneratorBlockEntity generator) {
                IceGeneratorBlockEntity.tick(tickLevel, pos, tickState, generator);
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof IceGeneratorBlockEntity generator)) {
            return InteractionResult.PASS;
        }

        ItemStack result = generator.ejectOutput();
        if (!result.isEmpty()) {
            if (!player.addItem(result)) player.drop(result, false);
            player.sendSystemMessage(Component.translatable(
                    "message.rftoolsbuilder.ice_generator.collected", result.getCount(), result.getHoverName()));
        } else {
            player.sendSystemMessage(generator.statusMessage());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && !player.isCreative()
                && level.getBlockEntity(pos) instanceof IceGeneratorBlockEntity generator) {
            generator.dropContents();
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
