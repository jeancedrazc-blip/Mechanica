package mcjty.rftoolsbuilder;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class QuarryFilterMenu extends AbstractContainerMenu {
    public static final int REMOVE_BASE = 100;
    public static final int EXPAND_BASE = 200;
    public static final int TOGGLE_RULE_BASE = 300;

    private final Inventory inventory;
    private final int cardSlot;

    public QuarryFilterMenu(int containerId, Inventory inventory, FriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readVarInt());
    }

    public QuarryFilterMenu(int containerId, Inventory inventory, int cardSlot) {
        super(RFToolsBuilder.QUARRY_FILTER_MENU.get(), containerId);
        this.inventory = inventory;
        this.cardSlot = cardSlot;

        int x = 47;
        int y = 217;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, x + col * 18, y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, x + col * 18, y + 58));
        }
    }

    public int cardSlot() { return cardSlot; }

    public ItemStack cardStack() {
        if (cardSlot < 0 || cardSlot >= inventory.getContainerSize()) return ItemStack.EMPTY;
        return inventory.getItem(cardSlot);
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (slotId >= 0 && slotId < slots.size()) {
            ItemStack source = slots.get(slotId).getItem();
            if (!source.isEmpty() && !(source.getItem() instanceof QuarryCardItem)) {
                if (!player.level().isClientSide()) {
                    if (input == ContainerInput.QUICK_MOVE) {
                        QuarryCardItem.addTagsFromItem(cardStack(), source);
                    } else {
                        QuarryCardItem.addFilterItem(cardStack(), source, player.registryAccess());
                    }
                    broadcastChanges();
                }
                return;
            }
        }
        super.clicked(slotId, button, input, player);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        ItemStack card = cardStack();
        if (!(card.getItem() instanceof QuarryCardItem)) return false;
        if (id >= 1 && id <= 3) {
            QuarryCardItem.toggle(card, id);
            broadcastChanges();
            return true;
        }
        if (id == 4) {
            QuarryCardItem.clearFilter(card);
            broadcastChanges();
            return true;
        }
        if (id >= REMOVE_BASE && id < REMOVE_BASE + QuarryCardItem.MAX_FILTER_ENTRIES) {
            QuarryCardItem.removeEntry(card, id - REMOVE_BASE, player.registryAccess());
            broadcastChanges();
            return true;
        }
        if (id >= EXPAND_BASE && id < EXPAND_BASE + QuarryCardItem.MAX_FILTER_ENTRIES) {
            QuarryCardItem.expandEntryToTags(card, id - EXPAND_BASE, player.registryAccess());
            broadcastChanges();
            return true;
        }
        if (id >= TOGGLE_RULE_BASE && id < TOGGLE_RULE_BASE + QuarryCardItem.MAX_FILTER_ENTRIES) {
            QuarryCardItem.toggleEntryRule(card, id - TOGGLE_RULE_BASE);
            broadcastChanges();
            return true;
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        ItemStack source = slots.get(index).getItem();
        if (source.isEmpty() || source.getItem() instanceof QuarryCardItem) return ItemStack.EMPTY;
        if (!player.level().isClientSide()) {
            QuarryCardItem.addTagsFromItem(cardStack(), source);
            broadcastChanges();
        }
        return source.copy();
    }

    @Override
    public boolean stillValid(Player player) {
        return cardStack().getItem() instanceof QuarryCardItem;
    }
}
