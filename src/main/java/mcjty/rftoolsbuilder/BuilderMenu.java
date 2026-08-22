package mcjty.rftoolsbuilder;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class BuilderMenu extends AbstractContainerMenu {
    public static final int CONFIG_BASE = 1000;
    public static final int CONFIG_BIAS = 16384;
    public static final int CONFIG_RANGE = 32769;

    private final BuilderBlockEntity builder;
    private final ContainerData data;

    public BuilderMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        this(containerId, playerInventory,
                (BuilderBlockEntity) playerInventory.player.level().getBlockEntity(extraData.readBlockPos()),
                new SimpleContainerData(16));
    }

    public BuilderMenu(int containerId, Inventory playerInventory, BuilderBlockEntity builder, ContainerData data) {
        super(RFToolsBuilder.BUILDER_MENU.get(), containerId);
        this.builder = builder;
        this.data = data;

        addSlot(new Slot(builder, BuilderBlockEntity.SLOT_SHAPE, 84, 42) {
            @Override public boolean mayPlace(ItemStack stack) { return stack.getItem() instanceof ShapeCardItem; }
            @Override public int getMaxStackSize() { return 1; }
        });
        addSlot(new Slot(builder, BuilderBlockEntity.SLOT_QUARRY, 120, 42) {
            @Override public boolean mayPlace(ItemStack stack) { return stack.getItem() instanceof QuarryCardItem; }
            @Override public int getMaxStackSize() { return 1; }
        });

        int playerX = 47;
        int playerY = 148;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, playerX + col * 18, playerY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(playerInventory, col, playerX + col * 18, 206));
        addDataSlots(data);
    }

    public ContainerData data() { return data; }
    public BuilderBlockEntity builder() { return builder; }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (builder == null) return false;
        if (id == 0) { builder.primaryAction(); return true; }
        if (id == 1) { builder.stopWork(); return true; }
        if (id >= CONFIG_BASE && id < CONFIG_BASE + 6 * CONFIG_RANGE) {
            int code = id - CONFIG_BASE;
            int field = code / CONFIG_RANGE;
            int value = code % CONFIG_RANGE - CONFIG_BIAS;
            value = field < 3 ? Math.max(1, Math.min(512, value)) : Math.max(-16384, Math.min(16384, value));
            builder.setConfigValue(field, value);
            return true;
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        int machineSlots = 2;
        if (index < machineSlots) {
            if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) return ItemStack.EMPTY;
        } else if (stack.getItem() instanceof ShapeCardItem) {
            if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        } else if (stack.getItem() instanceof QuarryCardItem) {
            if (!moveItemStackTo(stack, 1, 2, false)) return ItemStack.EMPTY;
        } else return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) { return builder != null && builder.stillValid(player); }
}
