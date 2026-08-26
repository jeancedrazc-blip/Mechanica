package mcjty.rftoolsbuilder.extractor;

import com.mojang.serialization.MapCodec;
import mcjty.rftoolsbuilder.RFToolsBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

/** Open-frame machine that holds and scans one visible sample block. */
public final class ExtractorBlock extends Block implements EntityBlock {
    public static final MapCodec<ExtractorBlock> CODEC = simpleCodec(ExtractorBlock::new);

    public ExtractorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.LIT, false));
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.LIT);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ExtractorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != RFToolsBuilder.EXTRACTOR_BLOCK_ENTITY.get()) return null;
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof ExtractorBlockEntity extractor) {
                ExtractorBlockEntity.tick(tickLevel, pos, tickState, extractor);
            }
        };
    }

    @Override
    public InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                       Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof ExtractorBlockEntity extractor)) return InteractionResult.PASS;

        if (blockItem.getBlock() instanceof EntityBlock) {
            player.sendSystemMessage(Component.translatable("message.rftoolsbuilder.extractor.unsafe_sample"));
            return InteractionResult.SUCCESS;
        }
        if (!extractor.insertSample(stack)) {
            player.sendSystemMessage(Component.translatable("message.rftoolsbuilder.extractor.occupied"));
            return InteractionResult.SUCCESS;
        }
        if (!player.isCreative()) stack.shrink(1);
        player.sendSystemMessage(Component.translatable("message.rftoolsbuilder.extractor.inserted"));
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof ExtractorBlockEntity extractor)) return InteractionResult.PASS;

        if (player.isShiftKeyDown()) {
            ItemStack returned = extractor.ejectSample();
            if (returned.isEmpty()) {
                player.sendSystemMessage(Component.translatable("message.rftoolsbuilder.extractor.empty"));
            } else if (!player.addItem(returned)) {
                player.drop(returned, false);
            }
        } else {
            player.sendSystemMessage(extractor.statusMessage());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && !player.isCreative()
                && level.getBlockEntity(pos) instanceof ExtractorBlockEntity extractor) {
            extractor.dropContents();
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
