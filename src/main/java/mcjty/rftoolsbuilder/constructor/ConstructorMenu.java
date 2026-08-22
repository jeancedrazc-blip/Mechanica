package mcjty.rftoolsbuilder.constructor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;

public final class ConstructorMenu extends AbstractContainerMenu {
    public static final int MATERIAL_PAGE_SIZE = 21;
    private final ConstructorBlockEntity constructor;
    private final ContainerData data;
    private final SimpleContainer materialDisplay = new SimpleContainer(MATERIAL_PAGE_SIZE);
    private final SimpleContainer replacementFilter = new SimpleContainer(1);
    private final java.util.List<Block> materialSources = new java.util.ArrayList<>();
    private final DataSlot selectedMaterial = DataSlot.standalone();
    private final DataSlot materialScroll = DataSlot.standalone();
    private final DataSlot materialTotal = DataSlot.standalone();
    private final DataSlot uiMode = DataSlot.standalone();
    private int lastCardSignature;

    public ConstructorMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory,
                inventory.player.level().getBlockEntity(buffer.readBlockPos()) instanceof ConstructorBlockEntity found ? found : null,
                new SimpleContainerData(21));
    }

    public ConstructorMenu(int id, Inventory inventory, ConstructorBlockEntity constructor, ContainerData data) {
        super(ConstructorBootstrap.CONSTRUCTOR_MENU.get(), id);
        this.constructor = constructor;
        this.data = data;

        if (constructor != null) {
            addSlot(new Slot(constructor, ConstructorBlockEntity.SLOT_SCHEMATIC, 22, 158) {
                @Override public boolean mayPlace(ItemStack stack) {
                    return constructor.canRemoveCard() && stack.getItem() instanceof SchematicCardItem;
                }
                @Override public boolean mayPickup(Player player) { return constructor.canRemoveCard(); }
                @Override public int getMaxStackSize() { return 1; }
                @Override public boolean isActive() { return mainUiActive(); }
            });
            addSlot(new Slot(constructor, ConstructorBlockEntity.SLOT_TABLET_INPUT, 240, 57) {
                @Override public boolean mayPlace(ItemStack stack) {
                    return stack.getItem() instanceof MaterialListTabletItem;
                }
                @Override public int getMaxStackSize() { return 1; }
                @Override public boolean isActive() { return mainUiActive(); }
            });
            addSlot(new Slot(constructor, ConstructorBlockEntity.SLOT_TABLET_OUTPUT, 240, 119) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public int getMaxStackSize() { return 1; }
                @Override public boolean isActive() { return mainUiActive(); }
            });
        }

        if (constructor != null) {
            populateMaterialDisplay(constructor.schematicCard());
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 7; col++) {
                    addSlot(new Slot(materialDisplay, col + row * 7, 64 + col * 22, 55 + row * 25) {
                        @Override public boolean mayPlace(ItemStack stack) { return false; }
                        @Override public boolean mayPickup(Player player) { return false; }
                        @Override public boolean isActive() { return mainUiActive(); }
                    });
                }
            }
            addSlot(new Slot(replacementFilter, 0, 127, 96) {
                @Override public boolean mayPlace(ItemStack stack) { return stack.getItem() instanceof BlockItem; }
                @Override public boolean mayPickup(Player player) { return false; }
                @Override public int getMaxStackSize() { return 1; }
                @Override public boolean isActive() { return selectedMaterial.get() >= 0; }
            });
        }

        int invX = 48;
        int invY = 208;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row * 9 + 9, invX + col * 18, invY + row * 18));
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, invX + col * 18, invY + 58));
        addDataSlots(data);
        selectedMaterial.set(-1);
        addDataSlot(selectedMaterial);
        materialScroll.set(0);
        addDataSlot(materialScroll);
        materialTotal.set(materialSources.size());
        addDataSlot(materialTotal);
        uiMode.set(0);
        addDataSlot(uiMode);
        lastCardSignature = cardSignature();
    }

    private void populateMaterialDisplay(ItemStack card) {
        materialDisplay.clearContent();
        materialSources.clear();
        materialTotal.set(0);
        if (!SchematicCardItem.hasSource(card)) return;
        try {
            var plan = UniversalSchematicLoader.loadCard(card, false);
            java.util.LinkedHashSet<Block> seen = new java.util.LinkedHashSet<>();
            for (var entry : plan.entries()) {
                Block source = entry.sourceState().getBlock();
                var item = source.asItem();
                if (item == net.minecraft.world.item.Items.AIR || !seen.add(source)) continue;
                materialSources.add(source);
            }
            materialScroll.set(Math.min(materialScroll.get(), maxMaterialScroll()));
            materialTotal.set(materialSources.size());
            refreshMaterialPage(card);
        } catch (java.io.IOException | RuntimeException ignored) {
        }
    }

    private void refreshMaterialPage(ItemStack card) {
        materialDisplay.clearContent();
        int first = materialScroll.get();
        for (int slot = 0; slot < MATERIAL_PAGE_SIZE && first + slot < materialSources.size(); slot++) {
            Block source = materialSources.get(first + slot);
            Block shown = SchematicCardItem.replacementFor(card, source);
            materialDisplay.setItem(slot, new ItemStack((shown == null ? source : shown).asItem()));
        }
    }

    private int maxMaterialScroll() {
        return Math.max(0, materialSources.size() - MATERIAL_PAGE_SIZE);
    }

    private boolean mainUiActive() {
        return selectedMaterial.get() < 0 && uiMode.get() == 0;
    }

    public ConstructorBlockEntity constructor() { return constructor; }
    public ContainerData data() { return data; }
    public int selectedMaterial() { return selectedMaterial.get(); }
    public int materialScroll() { return materialScroll.get(); }
    public int materialCount() { return materialTotal.get(); }
    public int uiMode() { return uiMode.get(); }
    public ItemStack selectedSource() {
        int selected = selectedMaterial.get();
        return selected >= 0 && selected < materialSources.size()
                ? new ItemStack(materialSources.get(selected)) : ItemStack.EMPTY;
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
        if (slotId >= 3 && slotId < 24 && clickType == ContainerInput.PICKUP && constructor != null) {
            int materialIndex = materialScroll.get() + slotId - 3;
            if (materialIndex < materialSources.size()) {
                if (selectedMaterial.get() == materialIndex) {
                    selectedMaterial.set(-1);
                    replacementFilter.clearContent();
                } else selectMaterial(materialIndex);
            }
            return;
        }
        if (slotId == 24 && clickType == ContainerInput.PICKUP && constructor != null
                && selectedMaterial.get() >= 0 && selectedMaterial.get() < materialSources.size()) {
            int selected = selectedMaterial.get();
            Block source = materialSources.get(selected);
            ItemStack carried = getCarried();
            boolean changed;
            if (carried.getItem() instanceof BlockItem blockItem) {
                changed = blockItem.getBlock() == source
                        ? SchematicCardItem.removeReplacement(constructor.schematicCard(), source)
                        : SchematicCardItem.addReplacement(constructor.schematicCard(), source, blockItem.getBlock());
            } else if (carried.isEmpty()) {
                changed = SchematicCardItem.removeReplacement(constructor.schematicCard(), source);
            } else return;
            if (changed) {
                selectMaterial(selected);
                constructor.refreshMaterialTablet();
                constructor.setChangedAndSync();
            }
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    private void selectMaterial(int index) {
        selectedMaterial.set(index);
        Block source = materialSources.get(index);
        Block replacement = SchematicCardItem.replacementFor(constructor.schematicCard(), source);
        replacementFilter.setItem(0, new ItemStack((replacement == null ? source : replacement).asItem()));
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (constructor == null) return false;
        if (id == 8 || id == 9) {
            if (selectedMaterial.get() >= 0 || uiMode.get() != 0) return false;
            int direction = id == 8 ? -MATERIAL_PAGE_SIZE : MATERIAL_PAGE_SIZE;
            materialScroll.set(Math.max(0, Math.min(maxMaterialScroll(), materialScroll.get() + direction)));
            refreshMaterialPage(constructor.schematicCard());
            return true;
        }
        if (id == 10) {
            selectedMaterial.set(-1);
            replacementFilter.clearContent();
            uiMode.set(1);
            return true;
        }
        if (id == 11) {
            uiMode.set(0);
            return true;
        }
        if (id == 12) {
            selectedMaterial.set(-1);
            replacementFilter.clearContent();
            return true;
        }
        if (!constructor.handleMenuButton(id)) return false;
        if (id == 7) {
            populateMaterialDisplay(constructor.schematicCard());
            int selected = selectedMaterial.get();
            if (selected >= 0 && selected < materialSources.size()) selectMaterial(selected);
            refreshMaterialPage(constructor.schematicCard());
        }
        return true;
    }

    @Override
    public void broadcastChanges() {
        int signature = cardSignature();
        if (signature != lastCardSignature) {
            lastCardSignature = signature;
            selectedMaterial.set(-1);
            materialScroll.set(0);
            uiMode.set(0);
            replacementFilter.clearContent();
            populateMaterialDisplay(constructor == null ? ItemStack.EMPTY : constructor.schematicCard());
        }
        super.broadcastChanges();
    }

    private int cardSignature() {
        if (constructor == null) return 0;
        ItemStack card = constructor.schematicCard();
        if (!(card.getItem() instanceof SchematicCardItem)) return 0;
        int hash = SchematicCardItem.sourceFile(card).hashCode();
        return 31 * hash + SchematicCardItem.replacementSignature(card);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack source = slot.getItem();
        ItemStack copy = source.copy();
        int machineSlots = constructor == null ? 0 : 25;

        if (index < machineSlots) {
            if (constructor != null && !constructor.canRemoveCard()) return ItemStack.EMPTY;
            if (!moveItemStackTo(source, machineSlots, slots.size(), true)) return ItemStack.EMPTY;
        } else if (source.getItem() instanceof SchematicCardItem) {
            if (constructor == null || !constructor.canRemoveCard() || !moveItemStackTo(source, 0, 1, false)) return ItemStack.EMPTY;
        } else if (source.getItem() instanceof MaterialListTabletItem) {
            if (constructor == null || !moveItemStackTo(source, 1, 2, false)) return ItemStack.EMPTY;
        } else return ItemStack.EMPTY;

        if (source.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        if (constructor == null || constructor.isRemoved() || constructor.getLevel() != player.level()) return false;
        var pos = constructor.getBlockPos();
        return player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64.0;
    }
}
