package mcjty.rftoolsbuilder.constructor;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class SchematicTableMenu extends AbstractContainerMenu {
    private final SchematicTableBlockEntity table;
    private final ContainerData data;
    private final BlockPos blockPos;

    public SchematicTableMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, readTable(inventory, buffer), new SimpleContainerData(4));
    }

    private static SchematicTableBlockEntity readTable(Inventory inventory, FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        return inventory.player.level().getBlockEntity(pos) instanceof SchematicTableBlockEntity found ? found : null;
    }

    public SchematicTableMenu(int id, Inventory inventory, SchematicTableBlockEntity table, ContainerData data) {
        super(ConstructorBootstrap.SCHEMATIC_TABLE_MENU.get(), id);
        this.table = table;
        this.data = data;
        this.blockPos = table == null ? BlockPos.ZERO : table.getBlockPos();

        if (table != null) {
            addSlot(new Slot(table, SchematicTableBlockEntity.SLOT_INPUT, 25, 64) {
                @Override public boolean mayPlace(ItemStack stack) { return SchematicTableBlockEntity.isWritableInput(stack) && !table.isUploading(); }
                @Override public boolean mayPickup(Player player) { return !table.isUploading(); }
                @Override public int getMaxStackSize() { return 1; }
            });
            addSlot(new Slot(table, SchematicTableBlockEntity.SLOT_OUTPUT, 255, 64) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public int getMaxStackSize() { return 1; }
            });
        }

        int invX = 67;
        int invY = 174;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, invX + col * 18, invY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, invX + col * 18, 232));
        }
        addDataSlots(data);
    }

    public ContainerData data() { return data; }
    public SchematicTableBlockEntity table() { return table; }
    public BlockPos blockPos() { return blockPos; }
    public int status() { return data.get(0); }
    public int progress() { return data.get(1); }
    public boolean hasOutput() { return data.get(2) != 0; }
    public boolean hasReservedInput() { return data.get(3) != 0; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack source = slot.getItem();
        ItemStack copy = source.copy();
        int machineSlots = table == null ? 0 : 2;

        if (index < machineSlots) {
            if (!moveItemStackTo(source, machineSlots, slots.size(), true)) return ItemStack.EMPTY;
        } else if (SchematicTableBlockEntity.isWritableInput(source)) {
            if (!moveItemStackTo(source, 0, 1, false)) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;
        }

        if (source.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return table != null && table.stillValid(player);
    }
}
