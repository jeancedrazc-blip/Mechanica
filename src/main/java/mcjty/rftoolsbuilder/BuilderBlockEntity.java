package mcjty.rftoolsbuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Unit;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-authoritative Builder/Miner engine reconstructed from the approved
 * legacy bytecode. It scans the configured cuboid chunk-by-chunk, consumes FE,
 * applies quarry-card filters, preserves fortune/silk behaviour and buffers
 * drops into nine output slots before exporting to adjacent inventories.
 */
public class BuilderBlockEntity extends BlockEntity implements Container, MenuProvider {
    public static final int SLOT_SHAPE = 0;
    public static final int SLOT_QUARRY = 1;
    public static final int FIRST_OUTPUT = 2;
    public static final int OUTPUT_SLOTS = 9;
    public static final int TOTAL_SLOTS = 11;
    public static final int MAX_ENERGY = 2_000_000;
    public static final int MAX_RECEIVE = 100_000;
    private static final int BASE_ENERGY = 500;
    private static final int SCAN_BUDGET = 4096;

    public static final int STATUS_IDLE = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_NO_CARD = 2;
    public static final int STATUS_NO_ENERGY = 3;
    public static final int STATUS_OUTPUT_FULL = 4;
    public static final int STATUS_DONE = 5;
    public static final int STATUS_PAUSED = 6;

    public static final TagKey<Block> QUARRY_BLACKLIST = TagKey.create(
            Registries.BLOCK,
            Identifier.fromNamespaceAndPath(RFToolsBuilder.MOD_ID, "builder_quarry_blacklist"));

    private final NonNullList<ItemStack> items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);
    private final BuilderEnergyStorage energy = new BuilderEnergyStorage(MAX_ENERGY, MAX_RECEIVE);
    private boolean running;
    private int sizeX = 16;
    private int sizeY = 64;
    private int sizeZ = 16;
    private int offsetX = -8;
    private int offsetY = -64;
    private int offsetZ = -8;
    private long cursor;
    private int scanChunkIndex;
    private long cursorInChunk;
    private int status = STATUS_IDLE;
    private int hologramSyncTicker;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> energy.getEnergyStored();
                case 1 -> energy.getMaxEnergyStored();
                case 2 -> running ? 1 : 0;
                case 3 -> sizeX;
                case 4 -> sizeY;
                case 5 -> sizeZ;
                case 6 -> offsetX;
                case 7 -> offsetY;
                case 8 -> offsetZ;
                case 9 -> (int) Math.min(Integer.MAX_VALUE, cursor);
                case 10 -> (int) Math.min(Integer.MAX_VALUE, volume());
                case 11 -> status;
                case 12 -> energy.getEnergyStored() & 0xFFFF;
                case 13 -> energy.getEnergyStored() >>> 16;
                case 14 -> energy.getMaxEnergyStored() & 0xFFFF;
                case 15 -> energy.getMaxEnergyStored() >>> 16;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            switch (index) {
                case 2 -> running = value != 0;
                case 3 -> sizeX = clampSize(value);
                case 4 -> sizeY = clampSize(value);
                case 5 -> sizeZ = clampSize(value);
                case 6 -> offsetX = clampOffset(value);
                case 7 -> offsetY = clampOffset(value);
                case 8 -> offsetZ = clampOffset(value);
                case 9 -> cursor = Math.max(0L, value);
                case 11 -> status = value;
                default -> { }
            }
        }

        @Override public int getCount() { return 16; }
    };

    public BuilderBlockEntity(BlockPos pos, BlockState state) {
        super(RFToolsBuilder.BUILDER_BLOCK_ENTITY.get(), pos, state);
    }

    public BuilderEnergyStorage energyStorage() { return energy; }
    public int storedEnergy() { return energy.getEnergyStored(); }
    public void restoreEnergy(int amount) { energy.setStored(amount); setChanged(); syncClientState(); }
    public ContainerData data() { return data; }

    public static void tick(Level level, BlockPos pos, BlockState state, BuilderBlockEntity builder) {
        if (level.isClientSide()) return;
        if (++builder.hologramSyncTicker >= 10) {
            builder.hologramSyncTicker = 0;
            builder.syncClientState();
        }
        builder.pushOutputs();
        if (!builder.running) {
            if (builder.status == STATUS_RUNNING) builder.status = STATUS_IDLE;
            return;
        }

        ItemStack shapeStack = builder.items.get(SLOT_SHAPE);
        ItemStack quarryStack = builder.items.get(SLOT_QUARRY);
        if (!(shapeStack.getItem() instanceof ShapeCardItem)
                || !(quarryStack.getItem() instanceof QuarryCardItem card)) {
            builder.status = STATUS_NO_CARD;
            return;
        }
        builder.work((ServerLevel) level, card.mode(), quarryStack);
    }

    private void work(ServerLevel level, QuarryMode mode, ItemStack quarryCard) {
        long totalVolume = volume();
        int totalChunks = chunkCount();
        if (totalVolume <= 0 || cursor >= totalVolume || scanChunkIndex >= totalChunks) {
            finishWork(totalVolume);
            return;
        }

        int scanned = 0;
        while (cursor < totalVolume && scanChunkIndex < totalChunks && scanned < SCAN_BUDGET) {
            long currentChunkVolume = chunkVolume(scanChunkIndex);
            if (currentChunkVolume <= 0 || cursorInChunk >= currentChunkVolume) {
                scanChunkIndex++;
                cursorInChunk = 0;
                continue;
            }

            ChunkPos chunkPos = chunkPosForIndex(scanChunkIndex);
            level.getChunkSource().getChunk(chunkPos.x(), chunkPos.z(), ChunkStatus.FULL, true);

            while (cursorInChunk < currentChunkVolume && scanned++ < SCAN_BUDGET) {
                BlockPos target = positionForChunkCursor(scanChunkIndex, cursorInChunk);
                BlockState state = level.getBlockState(target);

                if (!isMineableTarget(level, target, state)) {
                    advanceChunkCursor();
                    continue;
                }
                if (!QuarryCardItem.allowsBlock(quarryCard, state, level.registryAccess())) {
                    advanceChunkCursor();
                    continue;
                }

                int cost = energyCost(level, target, state, mode);
                if (energy.getEnergyStored() < cost) {
                    status = STATUS_NO_ENERGY;
                    return;
                }

                BlockEntity blockEntity = level.getBlockEntity(target);
                List<ItemStack> drops = getDrops(level, target, state, blockEntity, mode);
                if (!canFitDrops(drops)) {
                    status = STATUS_OUTPUT_FULL;
                    return;
                }

                energy.consume(cost);
                insertDrops(drops);
                level.destroyBlock(target, false);
                if (!mode.isClear()) level.setBlockAndUpdate(target, Blocks.DIRT.defaultBlockState());
                level.levelEvent(2001, target, Block.getId(state));
                advanceChunkCursor();
                status = STATUS_RUNNING;
                setChanged();
                return;
            }

            if (cursorInChunk >= currentChunkVolume) {
                scanChunkIndex++;
                cursorInChunk = 0;
            }
        }

        if (cursor >= totalVolume || scanChunkIndex >= totalChunks) finishWork(totalVolume);
        else status = STATUS_RUNNING;
    }

    private void finishWork(long totalVolume) {
        cursor = Math.max(0L, totalVolume);
        scanChunkIndex = chunkCount();
        cursorInChunk = 0;
        running = false;
        status = STATUS_DONE;
        setChanged();
        syncClientState();
    }

    private void advanceChunkCursor() {
        cursorInChunk++;
        cursor++;
    }

    private boolean isMineableTarget(ServerLevel level, BlockPos target, BlockState state) {
        if (state.isAir()) return false;
        if (target.equals(worldPosition)) return false;
        if (state.is(QUARRY_BLACKLIST)) return false;
        if (!state.getFluidState().isEmpty() && state.getBlock() == Blocks.WATER) return false;
        return state.getDestroySpeed(level, target) >= 0.0f;
    }

    private int energyCost(ServerLevel level, BlockPos target, BlockState state, QuarryMode mode) {
        float hardness = Math.max(0.0f, Math.min(50.0f, state.getDestroySpeed(level, target)));
        int cost = BASE_ENERGY + Math.round(hardness * 200.0f);
        if (mode.isFortune()) cost = Math.round(cost * 1.75f);
        if (mode.isSilk()) cost = Math.round(cost * 2.0f);
        return Math.max(BASE_ENERGY, cost);
    }

    private List<ItemStack> getDrops(ServerLevel level, BlockPos pos, BlockState state,
                                     BlockEntity blockEntity, QuarryMode mode) {
        ItemStack tool = new ItemStack(Items.NETHERITE_PICKAXE);
        tool.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        if (mode.isFortune()) {
            Holder.Reference<?> holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.FORTUNE);
            @SuppressWarnings({"rawtypes", "unchecked"}) Holder enchantment = holder;
            tool.enchant(enchantment, 3);
        } else if (mode.isSilk()) {
            Holder.Reference<?> holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.SILK_TOUCH);
            @SuppressWarnings({"rawtypes", "unchecked"}) Holder enchantment = holder;
            tool.enchant(enchantment, 1);
        }

        LootParams.Builder params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.TOOL, tool)
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, blockEntity);
        List<ItemStack> drops = new ArrayList<>(state.getDrops(params));

        if (drops.isEmpty() && !mode.isSilk()) {
            ItemStack shears = new ItemStack(Items.SHEARS);
            LootParams.Builder shearParams = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                    .withParameter(LootContextParams.TOOL, shears)
                    .withOptionalParameter(LootContextParams.BLOCK_ENTITY, blockEntity);
            drops.addAll(state.getDrops(shearParams));
        }
        return drops;
    }

    private boolean canFitDrops(List<ItemStack> drops) {
        NonNullList<ItemStack> simulated = NonNullList.withSize(OUTPUT_SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < OUTPUT_SLOTS; i++) simulated.set(i, items.get(FIRST_OUTPUT + i).copy());
        for (ItemStack drop : drops) if (!insertIntoList(simulated, drop.copy())) return false;
        return true;
    }

    private void insertDrops(List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            ItemStack remaining = drop.copy();
            for (int slot = FIRST_OUTPUT; slot < TOTAL_SLOTS && !remaining.isEmpty(); slot++) {
                ItemStack existing = items.get(slot);
                if (existing.isEmpty()) {
                    int move = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                    items.set(slot, remaining.copyWithCount(move));
                    remaining.shrink(move);
                } else if (ItemStack.isSameItemSameComponents(existing, remaining)) {
                    int room = existing.getMaxStackSize() - existing.getCount();
                    if (room > 0) {
                        int move = Math.min(room, remaining.getCount());
                        existing.grow(move);
                        remaining.shrink(move);
                    }
                }
            }
        }
    }

    private static boolean insertIntoList(NonNullList<ItemStack> list, ItemStack stack) {
        for (int i = 0; i < list.size() && !stack.isEmpty(); i++) {
            ItemStack existing = list.get(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                int room = existing.getMaxStackSize() - existing.getCount();
                int move = Math.min(room, stack.getCount());
                if (move > 0) {
                    existing.grow(move);
                    stack.shrink(move);
                }
            }
        }
        for (int i = 0; i < list.size() && !stack.isEmpty(); i++) {
            if (list.get(i).isEmpty()) {
                int move = Math.min(stack.getCount(), stack.getMaxStackSize());
                list.set(i, stack.copyWithCount(move));
                stack.shrink(move);
            }
        }
        return stack.isEmpty();
    }

    private void pushOutputs() {
        if (!(level instanceof ServerLevel server)) return;
        boolean changed = false;
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(direction);
            ResourceHandler<?> capability = server.getCapability(Capabilities.Item.BLOCK, neighborPos, direction.getOpposite());
            if (capability == null) continue;
            @SuppressWarnings("unchecked")
            IItemHandler handler = IItemHandler.of((ResourceHandler) capability);
            for (int slot = FIRST_OUTPUT; slot < TOTAL_SLOTS; slot++) {
                ItemStack current = items.get(slot);
                if (current.isEmpty()) continue;
                ItemStack remaining = current.copy();
                for (int targetSlot = 0; targetSlot < handler.getSlots() && !remaining.isEmpty(); targetSlot++) {
                    remaining = handler.insertItem(targetSlot, remaining, false);
                }
                if (remaining.getCount() != current.getCount()) {
                    items.set(slot, remaining);
                    changed = true;
                }
            }
        }
        if (changed) setChanged();
    }

    public void primaryAction() {
        if (running) {
            running = false;
            status = STATUS_PAUSED;
            setChanged();
            syncClientState();
            return;
        }
        if (status == STATUS_PAUSED) {
            running = true;
            status = STATUS_RUNNING;
            setChanged();
            syncClientState();
            return;
        }
        if (status == STATUS_DONE || cursor >= volume()) resetProgress();
        running = true;
        status = STATUS_RUNNING;
        setChanged();
        syncClientState();
    }

    public void stopWork() {
        running = false;
        cursor = 0;
        scanChunkIndex = 0;
        cursorInChunk = 0;
        status = STATUS_IDLE;
        setChanged();
        syncClientState();
    }

    public void toggleRunning() { primaryAction(); }
    public boolean hasShapeCard() { return items.get(SLOT_SHAPE).getItem() instanceof ShapeCardItem; }
    public boolean hasQuarryCard() { return items.get(SLOT_QUARRY).getItem() instanceof QuarryCardItem; }

    public void setConfigValue(int field, int value) {
        ItemStack shapeStack = items.get(SLOT_SHAPE);
        if (!(shapeStack.getItem() instanceof ShapeCardItem)) return;
        int normalized = field < 3 ? clampSize(value) : clampOffset(value);
        switch (field) {
            case 0 -> sizeX = normalized;
            case 1 -> sizeY = normalized;
            case 2 -> sizeZ = normalized;
            case 3 -> offsetX = normalized;
            case 4 -> offsetY = normalized;
            case 5 -> offsetZ = normalized;
            default -> { return; }
        }
        ShapeCardItem.setField(shapeStack, field, normalized);
        resetProgress();
        setChanged();
        syncClientState();
    }

    private void loadShapeCardConfig() {
        ItemStack shapeStack = items.get(SLOT_SHAPE);
        if (!(shapeStack.getItem() instanceof ShapeCardItem)) return;
        sizeX = clampSize(ShapeCardItem.getField(shapeStack, 0));
        sizeY = clampSize(ShapeCardItem.getField(shapeStack, 1));
        sizeZ = clampSize(ShapeCardItem.getField(shapeStack, 2));
        offsetX = clampOffset(ShapeCardItem.getField(shapeStack, 3));
        offsetY = clampOffset(ShapeCardItem.getField(shapeStack, 4));
        offsetZ = clampOffset(ShapeCardItem.getField(shapeStack, 5));
    }

    public void resetProgress() {
        cursor = 0;
        scanChunkIndex = 0;
        cursorInChunk = 0;
        status = running ? STATUS_RUNNING : STATUS_IDLE;
        setChanged();
        syncClientState();
    }

    private static int clampSize(int value) { return Math.max(1, Math.min(512, value)); }
    private static int clampOffset(int value) { return Math.max(-16384, Math.min(16384, value)); }
    private long volume() { return (long) sizeX * sizeY * sizeZ; }
    private int startX() { return worldPosition.getX() + offsetX; }
    private int startY() { return worldPosition.getY() + offsetY; }
    private int startZ() { return worldPosition.getZ() + offsetZ; }
    private int endX() { return startX() + sizeX - 1; }
    private int endZ() { return startZ() + sizeZ - 1; }
    private int minChunkX() { return Math.floorDiv(startX(), 16); }
    private int maxChunkX() { return Math.floorDiv(endX(), 16); }
    private int minChunkZ() { return Math.floorDiv(startZ(), 16); }
    private int maxChunkZ() { return Math.floorDiv(endZ(), 16); }
    private int chunksX() { return Math.max(1, maxChunkX() - minChunkX() + 1); }
    private int chunksZ() { return Math.max(1, maxChunkZ() - minChunkZ() + 1); }
    private int chunkCount() { return chunksX() * chunksZ(); }

    private ChunkPos chunkPosForIndex(int index) {
        int safe = Math.max(0, Math.min(index, chunkCount() - 1));
        return new ChunkPos(minChunkX() + safe % chunksX(), minChunkZ() + safe / chunksX());
    }

    private int chunkMinX(int index) { return Math.max(startX(), chunkPosForIndex(index).getMinBlockX()); }
    private int chunkMaxX(int index) { return Math.min(endX(), chunkPosForIndex(index).getMaxBlockX()); }
    private int chunkMinZ(int index) { return Math.max(startZ(), chunkPosForIndex(index).getMinBlockZ()); }
    private int chunkMaxZ(int index) { return Math.min(endZ(), chunkPosForIndex(index).getMaxBlockZ()); }

    private long chunkVolume(int index) {
        if (index < 0 || index >= chunkCount()) return 0;
        int width = chunkMaxX(index) - chunkMinX(index) + 1;
        int depth = chunkMaxZ(index) - chunkMinZ(index) + 1;
        return (long) width * depth * sizeY;
    }

    private BlockPos positionForChunkCursor(int chunkIndex, long localIndex) {
        int minX = chunkMinX(chunkIndex);
        int minZ = chunkMinZ(chunkIndex);
        int width = chunkMaxX(chunkIndex) - minX + 1;
        int depth = chunkMaxZ(chunkIndex) - minZ + 1;
        long layer = (long) width * depth;
        int yLayer = (int) (localIndex / layer);
        long inLayer = localIndex % layer;
        int z = (int) (inLayer / width);
        int x = (int) (inLayer % width);
        int y = sizeY - 1 - yLayer;
        return new BlockPos(minX + x, startY() + y, minZ + z);
    }

    private void restoreChunkCursorFromTotal() {
        long remaining = Math.max(0L, Math.min(cursor, volume()));
        scanChunkIndex = 0;
        cursorInChunk = 0;
        while (scanChunkIndex < chunkCount()) {
            long cv = chunkVolume(scanChunkIndex);
            if (remaining < cv) {
                cursorInChunk = remaining;
                return;
            }
            remaining -= cv;
            scanChunkIndex++;
        }
        cursorInChunk = 0;
    }

    public boolean hologramVisible() { return hasShapeCard() && hasQuarryCard(); }
    public int hologramStatus() { return status; }

    public BlockPos hologramTarget() {
        if (volume() <= 0 || scanChunkIndex >= chunkCount()) return worldPosition;
        long cv = chunkVolume(scanChunkIndex);
        if (cv <= 0) return worldPosition;
        long local = Math.max(0L, Math.min(cursorInChunk, cv - 1));
        return positionForChunkCursor(scanChunkIndex, local);
    }

    public int hologramChunkIndex() { return Math.min(scanChunkIndex + 1, Math.max(1, chunkCount())); }
    public int hologramChunkCount() { return Math.max(1, chunkCount()); }
    public int hologramProgressPermille() {
        long v = Math.max(1L, volume());
        return (int) (1000L * Math.min(cursor, v) / v);
    }

    private void syncClientState() {
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 2);
        }
    }

    @Override public net.minecraft.nbt.CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    public void dropContents() {
        if (level == null) return;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5, stack);
                items.set(i, ItemStack.EMPTY);
            }
        }
        setChanged();
    }

    @Override public Component getDisplayName() { return Component.translatable("block.rftoolsbuilder.builder"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new BuilderMenu(id, inventory, this, data); }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("Running", running);
        output.putInt("SizeX", sizeX);
        output.putInt("SizeY", sizeY);
        output.putInt("SizeZ", sizeZ);
        output.putInt("OffsetX", offsetX);
        output.putInt("OffsetY", offsetY);
        output.putInt("OffsetZ", offsetZ);
        output.putLong("Cursor", cursor);
        output.putInt("ScanChunkIndex", scanChunkIndex);
        output.putLong("CursorInChunk", cursorInChunk);
        output.putInt("Status", status);
        output.putInt("Energy", energy.getEnergyStored());
        for (int i = 0; i < items.size(); i++) if (!items.get(i).isEmpty()) output.store("Item" + i, ItemStack.CODEC, items.get(i));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        running = input.getBooleanOr("Running", false);
        sizeX = clampSize(input.getIntOr("SizeX", 16));
        sizeY = clampSize(input.getIntOr("SizeY", 64));
        sizeZ = clampSize(input.getIntOr("SizeZ", 16));
        offsetX = clampOffset(input.getIntOr("OffsetX", -8));
        offsetY = clampOffset(input.getIntOr("OffsetY", -64));
        offsetZ = clampOffset(input.getIntOr("OffsetZ", -8));
        cursor = Math.max(0L, input.getLongOr("Cursor", 0L));
        scanChunkIndex = Math.max(0, input.getIntOr("ScanChunkIndex", 0));
        cursorInChunk = Math.max(0L, input.getLongOr("CursorInChunk", 0L));
        if (cursor > 0 && scanChunkIndex == 0 && cursorInChunk == 0) restoreChunkCursorFromTotal();
        status = input.getIntOr("Status", STATUS_IDLE);
        energy.setStored(input.getIntOr("Energy", 0));
        for (int i = 0; i < items.size(); i++) items.set(i, input.read("Item" + i, ItemStack.CODEC).orElse(ItemStack.EMPTY));
    }

    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { for (ItemStack stack : items) if (!stack.isEmpty()) return false; return true; }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack stack = items.get(slot);
        items.set(slot, ItemStack.EMPTY);
        return stack;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot == SLOT_SHAPE && !stack.isEmpty() && !(stack.getItem() instanceof ShapeCardItem)) return;
        if (slot == SLOT_QUARRY && !stack.isEmpty() && !(stack.getItem() instanceof QuarryCardItem)) return;
        items.set(slot, stack);
        if (slot == SLOT_SHAPE) {
            loadShapeCardConfig();
            resetProgress();
        } else if (slot == SLOT_QUARRY) resetProgress();
        setChanged();
    }

    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }

    @Override
    public void clearContent() {
        for (int i = 0; i < items.size(); i++) items.set(i, ItemStack.EMPTY);
        setChanged();
    }

    public static final class BuilderEnergyStorage extends SimpleEnergyHandler {
        private BuilderEnergyStorage(int capacity, int maxReceive) { super(capacity, maxReceive, 0, 0); }
        public int getEnergyStored() { return (int) Math.min(Integer.MAX_VALUE, getAmountAsLong()); }
        public int getMaxEnergyStored() { return (int) Math.min(Integer.MAX_VALUE, getCapacityAsLong()); }

        public int receiveEnergy(int amount, boolean simulate) {
            try (Transaction tx = Transaction.openRoot()) {
                int inserted = insert(Math.max(0, amount), tx);
                if (!simulate) tx.commit();
                return inserted;
            }
        }

        public int extractEnergy(int amount, boolean simulate) {
            try (Transaction tx = Transaction.openRoot()) {
                int extracted = extract(Math.max(0, amount), tx);
                if (!simulate) tx.commit();
                return extracted;
            }
        }

        private void consume(int amount) { energy = Math.max(0, energy - Math.max(0, amount)); }
        private void setStored(int amount) { energy = Math.max(0, Math.min(capacity, amount)); }
    }
}
