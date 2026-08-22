package mcjty.rftoolsbuilder.constructor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

public final class SchematicTableBlockEntity extends BlockEntity implements Container, MenuProvider {
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_OUTPUT = 1;
    public static final int TOTAL_SLOTS = 2;

    public static final int STATUS_NO_CARD = 0;
    public static final int STATUS_READY = 1;
    public static final int STATUS_UPLOADING = 2;
    public static final int STATUS_FINISHED = 3;
    public static final int STATUS_ERROR = 4;

    private final NonNullList<ItemStack> items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);
    private ItemStack pendingInput = ItemStack.EMPTY;
    private int status = STATUS_NO_CARD;
    private int uploadProgress = 0;
    private String uploadingName = "";
    private UUID uploader;

    private final ContainerData data = new ContainerData() {
        @Override public int get(int index) {
            return switch (index) {
                case 0 -> status;
                case 1 -> uploadProgress;
                case 2 -> outputCard().isEmpty() ? 0 : 1;
                case 3 -> pendingInput.isEmpty() ? 0 : 1;
                default -> 0;
            };
        }
        @Override public void set(int index, int value) {
            if (index == 0) status = value;
            if (index == 1) uploadProgress = value;
        }
        @Override public int getCount() { return 4; }
    };

    public SchematicTableBlockEntity(BlockPos pos, BlockState state) {
        super(ConstructorBootstrap.SCHEMATIC_TABLE_BLOCK_ENTITY.get(), pos, state);
    }

    public ContainerData data() { return data; }
    public ItemStack inputCard() { return items.get(SLOT_INPUT); }
    public ItemStack outputCard() { return items.get(SLOT_OUTPUT); }
    public boolean isUploading() { return status == STATUS_UPLOADING; }
    public int uploadProgress() { return uploadProgress; }
    public String uploadingName() { return uploadingName; }

    public boolean canStartUpload(Player player) {
        return !isUploading() && pendingInput.isEmpty() && outputCard().isEmpty()
                && isWritableInput(inputCard())
                && player.distanceToSqr(worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5) <= 64.0;
    }

    public static boolean isWritableInput(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() instanceof SchematicCreatorCardItem) return true;
        // Compatibility for blank cards created by builds before the orange
        // creator card became a separate item.
        return stack.getItem() instanceof SchematicCardItem && !SchematicCardItem.hasSource(stack);
    }

    public boolean beginUpload(Player player, String displayName) {
        if (!canStartUpload(player)) return false;
        pendingInput = inputCard().copy();
        pendingInput.setCount(1);
        items.set(SLOT_INPUT, ItemStack.EMPTY);
        status = STATUS_UPLOADING;
        uploadProgress = 0;
        uploadingName = displayName == null ? "" : displayName;
        uploader = player.getUUID();
        setChanged();
        syncBlock();
        return true;
    }

    public boolean isUploadOwner(Player player) {
        return uploader != null && uploader.equals(player.getUUID()) && isUploading();
    }

    public void updateUploadProgress(long uploaded, long total) {
        if (!isUploading()) return;
        uploadProgress = total <= 0 ? 0 : Math.max(0, Math.min(10_000, (int) ((uploaded * 10_000L) / total)));
        setChanged();
        syncBlock();
    }

    public void finishUpload(String displayName, String serverRelativeFile, String clientRelativeFile,
                             String formatId, String sha256, int sizeX, int sizeY, int sizeZ) {
        if (!isUploading() || pendingInput.isEmpty()) return;
        ItemStack result = new ItemStack(ConstructorBootstrap.SCHEMATIC_CARD.get());
        SchematicCardItem.setSource(result, displayName, serverRelativeFile, clientRelativeFile,
                formatId, sha256, sizeX, sizeY, sizeZ);
        items.set(SLOT_OUTPUT, result);
        pendingInput = ItemStack.EMPTY;
        status = STATUS_FINISHED;
        uploadProgress = 10_000;
        uploadingName = displayName == null ? "" : displayName;
        uploader = null;
        setChanged();
        syncBlock();
    }

    public void cancelUpload(boolean markError) {
        if (!pendingInput.isEmpty()) {
            if (items.get(SLOT_INPUT).isEmpty()) items.set(SLOT_INPUT, pendingInput);
            else if (level != null) net.minecraft.world.Containers.dropItemStack(level, worldPosition.getX() + .5, worldPosition.getY() + 1, worldPosition.getZ() + .5, pendingInput);
        }
        pendingInput = ItemStack.EMPTY;
        uploader = null;
        uploadingName = "";
        uploadProgress = 0;
        status = markError ? STATUS_ERROR : statusForContents();
        setChanged();
        syncBlock();
    }

    private int statusForContents() {
        if (!outputCard().isEmpty()) return STATUS_FINISHED;
        return isWritableInput(inputCard()) ? STATUS_READY : STATUS_NO_CARD;
    }

    private void refreshIdleStatus() {
        if (!isUploading()) status = statusForContents();
    }

    private void syncBlock() {
        if (level != null && !level.isClientSide()) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    @Override public Component getDisplayName() { return Component.literal("Schematic Table"); }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new SchematicTableMenu(id, inventory, this, data);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("Status", status);
        output.putInt("UploadProgress", uploadProgress);
        output.putString("UploadingName", uploadingName);
        if (uploader != null) output.putString("Uploader", uploader.toString());
        if (!items.get(SLOT_INPUT).isEmpty()) output.store("InputCard", ItemStack.CODEC, items.get(SLOT_INPUT));
        if (!items.get(SLOT_OUTPUT).isEmpty()) output.store("OutputCard", ItemStack.CODEC, items.get(SLOT_OUTPUT));
        if (!pendingInput.isEmpty()) output.store("PendingInput", ItemStack.CODEC, pendingInput);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items.set(SLOT_INPUT, input.read("InputCard", ItemStack.CODEC).orElse(ItemStack.EMPTY));
        items.set(SLOT_OUTPUT, input.read("OutputCard", ItemStack.CODEC).orElse(ItemStack.EMPTY));
        pendingInput = input.read("PendingInput", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        status = input.getIntOr("Status", STATUS_NO_CARD);
        uploadProgress = input.getIntOr("UploadProgress", 0);
        uploadingName = input.getStringOr("UploadingName", "");
        String uuid = input.getStringOr("Uploader", "");
        try { uploader = uuid.isBlank() ? null : UUID.fromString(uuid); } catch (IllegalArgumentException ignored) { uploader = null; }
        if (status == STATUS_UPLOADING || !pendingInput.isEmpty()) {
            if (items.get(SLOT_INPUT).isEmpty() && !pendingInput.isEmpty()) items.set(SLOT_INPUT, pendingInput);
            pendingInput = ItemStack.EMPTY;
            uploader = null;
            uploadingName = "";
            uploadProgress = 0;
            status = statusForContents();
        } else refreshIdleStatus();
    }

    @Override public int getContainerSize() { return TOTAL_SLOTS; }
    @Override public boolean isEmpty() { return inputCard().isEmpty() && outputCard().isEmpty(); }
    @Override public ItemStack getItem(int slot) { return slot >= 0 && slot < TOTAL_SLOTS ? items.get(slot) : ItemStack.EMPTY; }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= TOTAL_SLOTS || isUploading()) return ItemStack.EMPTY;
        ItemStack stack = items.get(slot);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = stack.split(amount);
        if (stack.isEmpty()) items.set(slot, ItemStack.EMPTY);
        refreshIdleStatus();
        setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= TOTAL_SLOTS || isUploading()) return ItemStack.EMPTY;
        ItemStack result = items.get(slot);
        items.set(slot, ItemStack.EMPTY);
        refreshIdleStatus();
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= TOTAL_SLOTS || isUploading()) return;
        if (slot == SLOT_INPUT && !stack.isEmpty() && !isWritableInput(stack)) return;
        if (slot == SLOT_OUTPUT && !stack.isEmpty()) return;
        items.set(slot, stack);
        if (!stack.isEmpty() && stack.getCount() > 1) stack.setCount(1);
        refreshIdleStatus();
        setChanged();
    }

    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }

    @Override
    public void clearContent() {
        if (isUploading()) cancelUpload(false);
        items.set(SLOT_INPUT, ItemStack.EMPTY);
        items.set(SLOT_OUTPUT, ItemStack.EMPTY);
        pendingInput = ItemStack.EMPTY;
        refreshIdleStatus();
        setChanged();
    }
}
