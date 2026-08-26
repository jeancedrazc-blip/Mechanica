package mcjty.rftoolsbuilder.extractor;

import mcjty.rftoolsbuilder.RFToolsBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;

import java.util.List;
import java.util.Optional;

/** Server-authoritative processing, inventory and synchronization for the Extractor. */
public final class ExtractorBlockEntity extends BlockEntity implements Container {
    public static final int STATUS_EMPTY = 0;
    public static final int STATUS_INVALID = 1;
    public static final int STATUS_RUNNING = 2;
    public static final int STATUS_OUTPUT_BLOCKED = 3;
    public static final int STATUS_REDSTONE = 4;

    private ItemStack sample = ItemStack.EMPTY;
    private ItemStack output = ItemStack.EMPTY;
    private int progress;
    private int processTicks;
    private int status = STATUS_EMPTY;
    private int syncTicker;

    public ExtractorBlockEntity(BlockPos pos, BlockState state) {
        super(RFToolsBuilder.EXTRACTOR_BLOCK_ENTITY.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ExtractorBlockEntity extractor) {
        if (level.isClientSide()) return;
        ServerLevel server = (ServerLevel) level;

        if (server.getGameTime() % 10L == 0L) extractor.pushOutput();
        if (extractor.sample.isEmpty()) {
            extractor.progress = 0;
            extractor.processTicks = 0;
            extractor.setStatus(STATUS_EMPTY);
            extractor.updateLit(false);
            return;
        }
        if (server.hasNeighborSignal(pos)) {
            extractor.setStatus(STATUS_REDSTONE);
            extractor.updateLit(false);
            return;
        }

        ExtractorInput input = extractor.createInput(server);
        Optional<RecipeHolder<ExtractorRecipe>> match = server.recipeAccess().getRecipeFor(
                RFToolsBuilder.EXTRACTOR_RECIPE_TYPE.get(), input, server);
        if (match.isEmpty()) {
            extractor.progress = 0;
            extractor.processTicks = 0;
            extractor.setStatus(STATUS_INVALID);
            extractor.updateLit(false);
            return;
        }

        ExtractorRecipe recipe = match.get().value();
        ItemStack result = recipe.assemble(input);
        extractor.processTicks = recipe.ticks();
        if (!extractor.canFit(result)) {
            extractor.setStatus(STATUS_OUTPUT_BLOCKED);
            extractor.updateLit(false);
            return;
        }

        extractor.setStatus(STATUS_RUNNING);
        extractor.updateLit(true);
        extractor.progress++;
        if (extractor.progress >= recipe.ticks()) {
            extractor.insertOutput(result);
            extractor.progress = 0;
            extractor.pushOutput();
            extractor.markChangedAndSync();
        } else if (++extractor.syncTicker >= 10) {
            extractor.syncTicker = 0;
            extractor.markChangedAndSync();
        } else {
            extractor.setChanged();
        }
    }

    private ExtractorInput createInput(ServerLevel level) {
        return new ExtractorInput(
                sample,
                worldStack(level, worldPosition.below()),
                List.of(
                        worldStack(level, worldPosition.north()),
                        worldStack(level, worldPosition.east()),
                        worldStack(level, worldPosition.south()),
                        worldStack(level, worldPosition.west())
                )
        );
    }

    private static ItemStack worldStack(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        ItemStack stack = new ItemStack(state.getBlock().asItem());
        if (!stack.isEmpty()) return stack;

        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.WATER)) return new ItemStack(Items.WATER_BUCKET);
        if (fluid.is(FluidTags.LAVA)) return new ItemStack(Items.LAVA_BUCKET);
        return ItemStack.EMPTY;
    }

    public boolean insertSample(ItemStack held) {
        if (!sample.isEmpty() || !(held.getItem() instanceof BlockItem)) return false;
        sample = held.copyWithCount(1);
        progress = 0;
        processTicks = 0;
        status = STATUS_INVALID;
        markChangedAndSync();
        return true;
    }

    public ItemStack ejectSample() {
        if (sample.isEmpty()) return ItemStack.EMPTY;
        ItemStack returned = sample;
        sample = ItemStack.EMPTY;
        progress = 0;
        processTicks = 0;
        status = STATUS_EMPTY;
        updateLit(false);
        markChangedAndSync();
        return returned;
    }

    public BlockState sampleState() {
        if (sample.getItem() instanceof BlockItem blockItem) return blockItem.getBlock().defaultBlockState();
        return null;
    }

    public int status() { return status; }
    public int progress() { return progress; }
    public int processTicks() { return processTicks; }

    public Component statusMessage() {
        String key = switch (status) {
            case STATUS_INVALID -> "message.rftoolsbuilder.extractor.invalid";
            case STATUS_RUNNING -> "message.rftoolsbuilder.extractor.running";
            case STATUS_OUTPUT_BLOCKED -> "message.rftoolsbuilder.extractor.output_blocked";
            case STATUS_REDSTONE -> "message.rftoolsbuilder.extractor.redstone";
            default -> "message.rftoolsbuilder.extractor.empty";
        };
        return status == STATUS_RUNNING
                ? Component.translatable(key, progress, Math.max(1, processTicks))
                : Component.translatable(key);
    }

    private boolean canFit(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (output.isEmpty()) return true;
        return ItemStack.isSameItemSameComponents(output, stack)
                && output.getCount() + stack.getCount() <= output.getMaxStackSize();
    }

    private void insertOutput(ItemStack stack) {
        if (output.isEmpty()) output = stack.copy();
        else output.grow(stack.getCount());
    }

    private void pushOutput() {
        if (!(level instanceof ServerLevel server) || output.isEmpty()) return;
        BlockPos targetPos = worldPosition.above();
        ResourceHandler<?> capability = server.getCapability(Capabilities.Item.BLOCK, targetPos, Direction.DOWN);
        if (capability == null) return;
        @SuppressWarnings("unchecked")
        IItemHandler handler = IItemHandler.of((ResourceHandler) capability);
        ItemStack remaining = output.copy();
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = handler.insertItem(slot, remaining, false);
        }
        if (remaining.getCount() != output.getCount()) {
            output = remaining;
            markChangedAndSync();
        }
    }

    private void setStatus(int newStatus) {
        if (status == newStatus) return;
        status = newStatus;
        markChangedAndSync();
    }

    private void updateLit(boolean lit) {
        if (level == null || !getBlockState().hasProperty(BlockStateProperties.LIT)
                || getBlockState().getValue(BlockStateProperties.LIT) == lit) return;
        level.setBlock(worldPosition, getBlockState().setValue(BlockStateProperties.LIT, lit), 3);
    }

    private void markChangedAndSync() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public void dropContents() {
        if (level == null) return;
        if (!sample.isEmpty()) Containers.dropItemStack(level, worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5, sample);
        if (!output.isEmpty()) Containers.dropItemStack(level, worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5, output);
        sample = ItemStack.EMPTY;
        output = ItemStack.EMPTY;
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput outputTag) {
        super.saveAdditional(outputTag);
        if (!sample.isEmpty()) outputTag.store("Sample", ItemStack.CODEC, sample);
        if (!output.isEmpty()) outputTag.store("Output", ItemStack.CODEC, output);
        outputTag.putInt("Progress", progress);
        outputTag.putInt("ProcessTicks", processTicks);
        outputTag.putInt("Status", status);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        sample = input.read("Sample", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        output = input.read("Output", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        progress = Math.max(0, input.getIntOr("Progress", 0));
        processTicks = Math.max(0, input.getIntOr("ProcessTicks", 0));
        status = input.getIntOr("Status", sample.isEmpty() ? STATUS_EMPTY : STATUS_INVALID);
    }

    @Override public net.minecraft.nbt.CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    // One output-only automation slot. The sample is controlled exclusively by player interaction.
    @Override public int getContainerSize() { return 1; }
    @Override public boolean isEmpty() { return output.isEmpty(); }
    @Override public ItemStack getItem(int slot) { return slot == 0 ? output : ItemStack.EMPTY; }
    @Override public ItemStack removeItem(int slot, int amount) {
        if (slot != 0 || output.isEmpty()) return ItemStack.EMPTY;
        ItemStack removed = output.split(amount);
        if (output.isEmpty()) output = ItemStack.EMPTY;
        markChangedAndSync();
        return removed;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) {
        if (slot != 0) return ItemStack.EMPTY;
        ItemStack removed = output;
        output = ItemStack.EMPTY;
        return removed;
    }
    @Override public void setItem(int slot, ItemStack stack) {
        if (slot != 0) return;
        output = stack.copy();
        output.limitSize(getMaxStackSize(output));
        markChangedAndSync();
    }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
    @Override public void setChanged() { super.setChanged(); }
    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    @Override public void clearContent() { output = ItemStack.EMPTY; markChangedAndSync(); }
}
