package mcjty.rftoolsbuilder.client;

import mcjty.rftoolsbuilder.BuilderBlockEntity;
import mcjty.rftoolsbuilder.BuilderMenu;
import mcjty.rftoolsbuilder.constructor.client.ui.QuantumButton;
import mcjty.rftoolsbuilder.constructor.client.ui.QuantumUiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** High-density Builder/Miner control terminal using the common Quantum UI kit. */
public class BuilderScreen extends AbstractContainerScreen<BuilderMenu> {
    private final EditBox[] fields = new EditBox[6];
    private QuantumButton primaryButton;
    private QuantumButton stopButton;
    private boolean syncingFields;

    public BuilderScreen(BuilderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 256, 232);
        this.inventoryLabelY = 139;
        this.titleLabelY = 7;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos;
        int y = topPos;

        primaryButton = addRenderableWidget(new QuantumButton(x + 158, y + 28, 39, 18,
                Component.literal("START"), () -> sendButton(0), () -> menu.data().get(2) != 0, QuantumUiTheme.GREEN));
        stopButton = addRenderableWidget(new QuantumButton(x + 201, y + 28, 39, 18,
                Component.literal("STOP"), () -> sendButton(1), () -> false, QuantumUiTheme.RED));

        int[] xs = {68, 127, 186, 68, 127, 186};
        int[] ys = {85, 85, 85, 111, 111, 111};
        for (int i = 0; i < fields.length; i++) {
            final int field = i;
            EditBox box = new EditBox(font, x + xs[i], y + ys[i], 38, 16, Component.literal("Builder configuration"));
            box.setMaxLength(6);
            box.setFilter(value -> value.isEmpty() || value.equals("-") || value.matches("-?\\d+"));
            box.setBordered(false);
            box.setTextColor(QuantumUiTheme.TEXT);
            box.setTextColorUneditable(QuantumUiTheme.DISABLED);
            syncingFields = true;
            box.setValue(Integer.toString(menu.data().get(3 + i)));
            syncingFields = false;
            box.setResponder(value -> {
                if (syncingFields || value.isBlank() || value.equals("-")) return;
                try { sendConfig(field, Integer.parseInt(value)); }
                catch (NumberFormatException ignored) { }
            });
            fields[i] = addRenderableWidget(box);
        }
        updateWidgets();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateWidgets();
        syncingFields = true;
        for (int i = 0; i < fields.length; i++) {
            EditBox field = fields[i];
            if (field != null && !field.isFocused()) {
                String expected = Integer.toString(menu.data().get(3 + i));
                if (!expected.equals(field.getValue())) field.setValue(expected);
            }
        }
        syncingFields = false;
    }

    private void updateWidgets() {
        boolean shape = menu.getSlot(0).hasItem();
        for (EditBox field : fields) if (field != null) field.active = shape;
        int status = menu.data().get(11);
        boolean running = menu.data().get(2) != 0;
        if (primaryButton != null) {
            primaryButton.active = menu.getSlot(0).hasItem() && menu.getSlot(1).hasItem();
            primaryButton.setMessage(Component.literal(running ? "PAUSE" : status == BuilderBlockEntity.STATUS_PAUSED ? "RESUME" : "START"));
        }
        if (stopButton != null) stopButton.active = running || status == BuilderBlockEntity.STATUS_PAUSED || menu.data().get(9) > 0;
    }

    private void sendConfig(int field, int value) {
        Minecraft mc = minecraft;
        if (mc == null || mc.gameMode == null) return;
        int normalized = field < 3 ? Math.max(1, Math.min(512, value)) : Math.max(-16384, Math.min(16384, value));
        int id = BuilderMenu.CONFIG_BASE + field * BuilderMenu.CONFIG_RANGE + normalized + BuilderMenu.CONFIG_BIAS;
        mc.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private void sendButton(int id) {
        Minecraft mc = minecraft;
        if (mc != null && mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private static String compact(long value) {
        if (value >= 1_000_000) return String.format("%.2fM", value / 1_000_000.0);
        if (value >= 1_000) return String.format("%.1fk", value / 1_000.0);
        return Long.toString(value);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        int x = leftPos;
        int y = topPos;
        QuantumUiTheme.window(gui, x, y, imageWidth, imageHeight);
        QuantumUiTheme.title(gui, font, Component.literal("BUILDER // QUARRY TERMINAL"), x + imageWidth / 2, y + 8);
        gui.fill(x + 8, y + 23, x + imageWidth - 8, y + 24, QuantumUiTheme.BORDER_DIM);

        QuantumUiTheme.panel(gui, x + 10, y + 30, x + 148, y + 73);
        QuantumUiTheme.panel(gui, x + 151, y + 50, x + 246, y + 73);
        QuantumUiTheme.panel(gui, x + 10, y + 78, x + 246, y + 133);

        gui.text(font, Component.literal("CARDS"), x + 17, y + 35, QuantumUiTheme.MUTED, false);
        QuantumUiTheme.slotFrame(gui, x + 84, y + 42, menu.getSlot(0).hasItem(), QuantumUiTheme.CYAN);
        QuantumUiTheme.slotFrame(gui, x + 120, y + 42, menu.getSlot(1).hasItem(), QuantumUiTheme.AMBER);
        gui.text(font, Component.literal("SHAPE"), x + 71, y + 64, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal("QUARRY"), x + 111, y + 64, QuantumUiTheme.MUTED, false);

        int energy = syncedInt(menu.data().get(12), menu.data().get(13));
        int capacity = Math.max(1, syncedInt(menu.data().get(14), menu.data().get(15)));
        gui.text(font, Component.literal("FE CORE"), x + 158, y + 54, QuantumUiTheme.MUTED, false);
        QuantumUiTheme.segmentedBar(gui, x + 158, y + 64, 78, 7, energy, capacity, QuantumUiTheme.CYAN, 8);
        gui.text(font, Component.literal(compact(energy) + " / " + compact(capacity)), x + 158, y + 75, QuantumUiTheme.TEXT_SOFT, false);

        gui.text(font, Component.literal("VOLUME"), x + 17, y + 82, QuantumUiTheme.CYAN, false);
        gui.text(font, Component.literal("X"), x + 57, y + 89, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal("Y"), x + 116, y + 89, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal("Z"), x + 175, y + 89, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal("OFFSET"), x + 17, y + 108, QuantumUiTheme.AMBER, false);
        gui.text(font, Component.literal("X"), x + 57, y + 115, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal("Y"), x + 116, y + 115, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal("Z"), x + 175, y + 115, QuantumUiTheme.MUTED, false);
        for (int i = 0; i < fields.length; i++) {
            int fx = fields[i].getX() - 2;
            int fy = fields[i].getY() - 2;
            QuantumUiTheme.panel(gui, fx, fy, fx + 42, fy + 20, QuantumUiTheme.BORDER_DIM, QuantumUiTheme.DEEP);
        }

        long done = Integer.toUnsignedLong(menu.data().get(9));
        long total = Integer.toUnsignedLong(menu.data().get(10));
        QuantumUiTheme.segmentedBar(gui, x + 15, y + 127, 226, 5, (int)Math.min(Integer.MAX_VALUE, done), (int)Math.max(1, Math.min(Integer.MAX_VALUE, total)), QuantumUiTheme.CYAN, 16);
        gui.text(font, statusText(menu.data().get(11)), x + 15, y + 135, statusColor(menu.data().get(11)), false);
        gui.text(font, Component.literal(compact(done) + " / " + compact(total)), x + 184, y + 135, QuantumUiTheme.TEXT_SOFT, false);

        gui.fill(x + 8, y + 144, x + imageWidth - 8, y + 145, QuantumUiTheme.BORDER_DIM);
    }

    private static Component statusText(int status) {
        return Component.literal(switch (status) {
            case BuilderBlockEntity.STATUS_RUNNING -> "MINING // ACTIVE";
            case BuilderBlockEntity.STATUS_NO_CARD -> "INSERT SHAPE + QUARRY CARD";
            case BuilderBlockEntity.STATUS_NO_ENERGY -> "WAITING FOR FE";
            case BuilderBlockEntity.STATUS_OUTPUT_FULL -> "OUTPUT BUFFER FULL";
            case BuilderBlockEntity.STATUS_DONE -> "QUARRY COMPLETE";
            case BuilderBlockEntity.STATUS_PAUSED -> "PAUSED";
            default -> "STANDBY";
        });
    }

    private static int syncedInt(int low, int high) {
        return (low & 0xFFFF) | (high & 0xFFFF) << 16;
    }

    private static int statusColor(int status) {
        return switch (status) {
            case BuilderBlockEntity.STATUS_RUNNING -> QuantumUiTheme.CYAN;
            case BuilderBlockEntity.STATUS_DONE -> QuantumUiTheme.GREEN;
            case BuilderBlockEntity.STATUS_NO_ENERGY, BuilderBlockEntity.STATUS_PAUSED -> QuantumUiTheme.AMBER;
            case BuilderBlockEntity.STATUS_OUTPUT_FULL, BuilderBlockEntity.STATUS_NO_CARD -> QuantumUiTheme.RED;
            default -> QuantumUiTheme.MUTED;
        };
    }

    @Override protected void extractLabels(GuiGraphicsExtractor gui, int mouseX, int mouseY) { }
}
