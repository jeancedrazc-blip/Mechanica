package mcjty.rftoolsbuilder.client;

import mcjty.rftoolsbuilder.FilterTagPayload;
import mcjty.rftoolsbuilder.QuarryCardItem;
import mcjty.rftoolsbuilder.QuarryFilterMenu;
import mcjty.rftoolsbuilder.constructor.client.ui.QuantumButton;
import mcjty.rftoolsbuilder.constructor.client.ui.QuantumUiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Persistent whitelist/blacklist editor for Quarry Cards. */
public class QuarryFilterScreen extends AbstractContainerScreen<QuarryFilterMenu> {
    private static final int VISIBLE_ROWS = 5;
    private final QuantumButton[] rowButtons = new QuantumButton[VISIBLE_ROWS];
    private int selected;
    private int scroll;
    private QuantumButton ruleButton;
    private QuantumButton damageButton;
    private QuantumButton nbtButton;
    private QuantumButton modButton;
    private QuantumButton removeButton;
    private QuantumButton expandButton;
    private QuantumButton upButton;
    private QuantumButton downButton;
    private EditBox tagBox;

    public QuarryFilterScreen(QuarryFilterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 256, 301);
        this.inventoryLabelY = 208;
        this.titleLabelY = 7;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos;
        int y = topPos;

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            final int row = i;
            rowButtons[i] = addRenderableWidget(new QuantumButton(x + 14, y + 55 + i * 21, 177, 19,
                    Component.empty(), () -> selectRow(row),
                    () -> selected == scroll + row, QuantumUiTheme.CYAN));
        }

        upButton = addRenderableWidget(new QuantumButton(x + 196, y + 55, 20, 20, Component.literal("▲"), () -> scroll(-1)));
        downButton = addRenderableWidget(new QuantumButton(x + 196, y + 139, 20, 20, Component.literal("▼"), () -> scroll(1)));
        removeButton = addRenderableWidget(new QuantumButton(x + 220, y + 55, 25, 20, Component.literal("×"), this::removeSelected,
                () -> false, QuantumUiTheme.RED));
        expandButton = addRenderableWidget(new QuantumButton(x + 220, y + 79, 25, 20, Component.literal("#"), this::expandSelected));
        ruleButton = addRenderableWidget(new QuantumButton(x + 196, y + 103, 49, 20, Component.literal("RULE"), this::toggleSelectedRule,
                () -> selected >= 0 && selected < QuarryCardItem.entryCount(card()) && !QuarryCardItem.entryBlacklist(card(), selected), QuantumUiTheme.AMBER));

        damageButton = addRenderableWidget(new QuantumButton(x + 14, y + 166, 67, 18, Component.literal("DAMAGE"), () -> send(1),
                () -> QuarryCardItem.damageMode(card()), QuantumUiTheme.CYAN));
        nbtButton = addRenderableWidget(new QuantumButton(x + 85, y + 166, 67, 18, Component.literal("NBT/DATA"), () -> send(2),
                () -> QuarryCardItem.nbtMode(card()), QuantumUiTheme.CYAN));
        modButton = addRenderableWidget(new QuantumButton(x + 156, y + 166, 67, 18, Component.literal("MOD ID"), () -> send(3),
                () -> QuarryCardItem.modMode(card()), QuantumUiTheme.CYAN));
        addRenderableWidget(new QuantumButton(x + 227, y + 166, 18, 18, Component.literal("C"), () -> send(4),
                () -> false, QuantumUiTheme.RED));

        tagBox = new EditBox(font, x + 14, y + 190, 177, 16, Component.literal("Block or item tag"));
        tagBox.setMaxLength(128);
        tagBox.setBordered(false);
        tagBox.setTextColor(QuantumUiTheme.TEXT);
        tagBox.setHint(Component.literal("minecraft:logs"));
        addRenderableWidget(tagBox);
        addRenderableWidget(new QuantumButton(x + 196, y + 188, 49, 20, Component.literal("ADD #"), this::addTag,
                () -> false, QuantumUiTheme.GREEN));

        syncButtons();
    }

    private void selectRow(int row) {
        int index = scroll + row;
        if (index >= 0 && index < QuarryCardItem.entryCount(card())) selected = index;
        syncButtons();
    }

    private void scroll(int delta) {
        int count = QuarryCardItem.entryCount(card());
        scroll = Math.max(0, Math.min(Math.max(0, count - VISIBLE_ROWS), scroll + delta));
        if (selected < scroll) selected = scroll;
        if (selected >= scroll + VISIBLE_ROWS) selected = scroll + VISIBLE_ROWS - 1;
        syncButtons();
    }

    private void addTag() {
        if (tagBox == null) return;
        String tag = tagBox.getValue().trim();
        if (tag.isEmpty()) return;
        ClientPacketDistributor.sendToServer(new FilterTagPayload(menu.cardSlot(), tag));
        tagBox.setValue("");
    }

    private void toggleSelectedRule() {
        if (selected >= 0 && selected < QuarryCardItem.entryCount(card())) send(QuarryFilterMenu.TOGGLE_RULE_BASE + selected);
    }

    private void removeSelected() {
        if (selected >= 0 && selected < QuarryCardItem.entryCount(card())) send(QuarryFilterMenu.REMOVE_BASE + selected);
    }

    private void expandSelected() {
        if (selected >= QuarryCardItem.tagCount(card()) && selected < QuarryCardItem.entryCount(card()))
            send(QuarryFilterMenu.EXPAND_BASE + selected);
    }

    private void send(int id) {
        Minecraft mc = minecraft;
        if (mc != null && mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private ItemStack card() { return menu.cardStack(); }

    private String entryLabel(int index) {
        ItemStack card = card();
        int tags = QuarryCardItem.tagCount(card);
        if (index < tags) return "# " + QuarryCardItem.getTag(card, index);
        ItemStack item = entryItem(index);
        if (item.isEmpty()) return "?";
        String id = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
        return id;
    }

    private ItemStack entryItem(int index) {
        int itemIndex = index - QuarryCardItem.tagCount(card());
        if (itemIndex < 0) return ItemStack.EMPTY;
        Minecraft mc = minecraft;
        if (mc == null || mc.level == null) return ItemStack.EMPTY;
        return QuarryCardItem.getFilterItem(card(), itemIndex, mc.level.registryAccess());
    }

    private void syncButtons() {
        ItemStack card = card();
        int count = QuarryCardItem.entryCount(card);
        if (count <= 0) {
            selected = 0;
            scroll = 0;
        } else {
            selected = Math.max(0, Math.min(count - 1, selected));
            scroll = Math.max(0, Math.min(scroll, Math.max(0, count - VISIBLE_ROWS)));
        }

        for (int row = 0; row < VISIBLE_ROWS; row++) {
            QuantumButton button = rowButtons[row];
            if (button == null) continue;
            int index = scroll + row;
            button.visible = index < count;
            button.active = index < count;
            if (index < count) {
                boolean black = QuarryCardItem.entryBlacklist(card, index);
                String prefix = black ? "[-] " : "[+] ";
                String label = entryLabel(index);
                button.setMessage(Component.literal(prefix + trim(label, 23)));
            }
        }
        if (upButton != null) upButton.active = scroll > 0;
        if (downButton != null) downButton.active = scroll + VISIBLE_ROWS < count;
        if (removeButton != null) removeButton.active = count > 0;
        if (ruleButton != null) {
            ruleButton.active = count > 0;
            ruleButton.setMessage(Component.literal(count > 0 && QuarryCardItem.entryBlacklist(card, selected) ? "BLACK" : "WHITE"));
        }
        if (expandButton != null) expandButton.active = count > 0 && selected >= QuarryCardItem.tagCount(card);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        syncButtons();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        int x = leftPos;
        int y = topPos;
        QuantumUiTheme.window(gui, x, y, imageWidth, imageHeight);
        QuantumUiTheme.title(gui, font, Component.literal("QUARRY // FILTER MATRIX"), x + imageWidth / 2, y + 8);
        gui.fill(x + 8, y + 23, x + imageWidth - 8, y + 24, QuantumUiTheme.BORDER_DIM);

        QuantumUiTheme.panel(gui, x + 9, y + 31, x + 247, y + 162);
        QuantumUiTheme.sectionHeader(gui, font, Component.literal("RULE ENTRIES"), x + 14, y + 36, 226);

        int count = QuarryCardItem.entryCount(card());
        int white = QuarryCardItem.whitelistCount(card());
        int black = QuarryCardItem.blacklistCount(card());
        gui.text(font, Component.literal(count + "/" + QuarryCardItem.MAX_FILTER_ENTRIES), x + 207, y + 36, QuantumUiTheme.TEXT_SOFT, false);
        gui.text(font, Component.literal("WHITE " + white), x + 14, y + 149, QuantumUiTheme.GREEN, false);
        gui.text(font, Component.literal("BLACK " + black), x + 78, y + 149, QuantumUiTheme.RED, false);
        gui.text(font, Component.literal("Click inventory item to add · Shift-click = tags"), x + 14, y + 215, QuantumUiTheme.MUTED, false);

        QuantumUiTheme.panel(gui, x + 9, y + 163, x + 247, y + 212);
        QuantumUiTheme.panel(gui, x + 12, y + 187, x + 193, y + 208, QuantumUiTheme.BORDER_DIM, QuantumUiTheme.DEEP);

        gui.fill(x + 8, y + 214, x + imageWidth - 8, y + 215, QuantumUiTheme.BORDER_DIM);
        gui.text(font, Component.literal("PLAYER INVENTORY"), x + 47, y + 208, QuantumUiTheme.MUTED, false);
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }

    @Override protected void extractLabels(GuiGraphicsExtractor gui, int mouseX, int mouseY) { }
}
