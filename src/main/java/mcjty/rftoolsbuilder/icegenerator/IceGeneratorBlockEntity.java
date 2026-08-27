package mcjty.rftoolsbuilder.icegenerator;

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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;

/**
 * Produces exactly two ice blocks every 20 ticks, consuming one real water
 * source at the block directly in front of the machine. No FE is required.
 */
public final class IceGeneratorBlockEntity extends BlockEntity implements Container {
    public static final int STATUS_NO_WATER = 0;
    public static final int STATUS_READY = 1;
    public static final int STATUS_OUTPUT_FULL = 2;
    public static final int TICKS_PER_BATCH = 20;
    public static final int ICE_PER_BATCH = 2;

    private ItemStack output = ItemStack.EMPTY;
    private int cooldown;
    private int status = STATUS_NO_WATER;

    public IceGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(RFToolsBuilder.ICE_GENERATOR_BLOCK_ENTITY.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, IceGeneratorBlockEntity generator) {
        if (level.isClientSide()) return;
        ServerLevel server = (ServerLevel) level;

        if (server.getGameTime() % 5L == 0L) generator.pushOutput();
        if (!generator.canFitBatch()) {
            generator.cooldown = 0;
            generator.setStatus(STATUS_OUTPUT_FULL);
            return;
        }

        generator.cooldown++;
        if (generator.cooldown < TICKS_PER_BATCH) {
            generator.setChanged();
            return;
        }
        generator.cooldown = 0;

        BlockPos waterPos = generator.intakePos();
        BlockState waterState = server.getBlockState(waterPos);
        FluidState fluid = waterState.getFluidState();
        if (!waterState.is(Blocks.WATER) || !fluid.is(FluidTags.WATER) || !fluid.isSource()) {
            generator.setStatus(STATUS_NO_WATER);
            generator.markChangedAndSync();
            return;
        }

        server.setBlock(waterPos, Blocks.AIR.defaultBlockState(), 3);
        generator.insertBatch();
        generator.setStatus(STATUS_READY);
        generator.pushOutput();
        generator.markChangedAndSync();
    }

    public BlockPos intakePos() {
        Direction facing = getBlockState().getValue(IceGeneratorBlock.FACING);
        return worldPosition.relative(facing);
    }

    private boolean canFitBatch() {
        return output.isEmpty()
                || (output.is(Items.ICE) && output.getCount() <= output.getMaxStackSize() - ICE_PER_BATCH);
    }

    private void insertBatch() {
        if (output.isEmpty()) output = new ItemStack(Items.ICE, ICE_PER_BATCH);
        else output.grow(ICE_PER_BATCH);
    }

    public ItemStack ejectOutput() {
        if (output.isEmpty()) return ItemStack.EMPTY;
        ItemStack returned = output;
        output = ItemStack.EMPTY;
        setStatus(STATUS_NO_WATER);
        markChangedAndSync();
        return returned;
    }

    public Component statusMessage() {
        if (!output.isEmpty()) {
            return Component.translatable("message.rftoolsbuilder.ice_generator.buffered", output.getCount());
        }
        if (level != null) {
            BlockState state = level.getBlockState(intakePos());
            FluidState fluid = state.getFluidState();
            if (state.is(Blocks.WATER) && fluid.is(FluidTags.WATER) && fluid.isSource()) {
                return Component.translatable("message.rftoolsbuilder.ice_generator.ready");
            }
        }
        return Component.translatable("message.rftoolsbuilder.ice_generator.no_water", intakePos().toShortString());
    }

    private void pushOutput() {
        if (!(level instanceof ServerLevel server) || output.isEmpty()) return;
        for (Direction direction : Direction.values()) {
            BlockPos targetPos = worldPosition.relative(direction);
            ResourceHandler<?> capability = server.getCapability(
                    Capabilities.Item.BLOCK, targetPos, direction.getOpposite());
            if (capability == null) continue;

            @SuppressWarnings("unchecked")
            IItemHandler handler = IItemHandler.of((ResourceHandler) capability);
            ItemStack remaining = output.copy();
            for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
                remaining = handler.insertItem(slot, remaining, false);
            }
            if (remaining.getCount() != output.getCount()) {
                output = remaining;
                markChangedAndSync();
                if (output.isEmpty()) return;
            }
        }
    }

    private void setStatus(int newStatus) {
        if (status == newStatus) return;
        status = newStatus;
        markChangedAndSync();
    }

    private void markChangedAndSync() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public void dropContents() {
        if (level == null || output.isEmpty()) return;
        Containers.dropItemStack(level, worldPosition.getX() + .5, worldPosition.getY() + .5,
                worldPosition.getZ() + .5, output);
        output = ItemStack.EMPTY;
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput tag) {
        super.saveAdditional(tag);
        if (!output.isEmpty()) tag.store("Output", ItemStack.CODEC, output);
        tag.putInt("Cooldown", cooldown);
        tag.putInt("Status", status);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        output = input.read("Output", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        cooldown = Math.max(0, Math.min(TICKS_PER_BATCH - 1, input.getIntOr("Cooldown", 0)));
        status = input.getIntOr("Status", STATUS_NO_WATER);
    }

    @Override public net.minecraft.nbt.CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

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
