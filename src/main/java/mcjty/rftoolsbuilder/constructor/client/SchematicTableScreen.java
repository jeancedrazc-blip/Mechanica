package mcjty.rftoolsbuilder.constructor.client;

import mcjty.rftoolsbuilder.constructor.SchematicFolderIndex;
import mcjty.rftoolsbuilder.constructor.SchematicTableBlockEntity;
import mcjty.rftoolsbuilder.constructor.SchematicTableMenu;
import mcjty.rftoolsbuilder.constructor.client.ui.QuantumButton;
import mcjty.rftoolsbuilder.constructor.client.ui.QuantumUiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Schematic import terminal with Create-like folder/upload workflow. */
public final class SchematicTableScreen extends AbstractContainerScreen<SchematicTableMenu> {
    private static final int ROWS = 6;

    private final QuantumButton[] rowButtons = new QuantumButton[ROWS];
    private List<SchematicFolderIndex.Entry> schematics = List.of();
    private int selectedIndex = -1;
    private int firstVisible;
    private QuantumButton confirmButton;
    private QuantumButton upButton;
    private QuantumButton downButton;
    private QuantumButton folderButton;
    private QuantumButton refreshButton;

    public SchematicTableScreen(SchematicTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 296, 252);
        this.inventoryLabelY = 166;
        this.titleLabelY = 8;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos;
        int y = topPos;

        for (int i = 0; i < ROWS; i++) {
            final int row = i;
            rowButtons[i] = addRenderableWidget(new QuantumButton(x + 66, y + 42 + i * 15, 169, 14,
                    Component.empty(), () -> selectRow(row),
                    () -> firstVisible + row == selectedIndex, QuantumUiTheme.CYAN));
        }

        upButton = addRenderableWidget(new QuantumButton(x + 239, y + 42, 18, 18,
                Component.literal("▲"), () -> scroll(-1)));
        downButton = addRenderableWidget(new QuantumButton(x + 239, y + 99, 18, 18,
                Component.literal("▼"), () -> scroll(1)));
        folderButton = addRenderableWidget(new QuantumButton(x + 66, y + 136, 62, 18,
                Component.literal("FOLDER"), this::openFolder));
        refreshButton = addRenderableWidget(new QuantumButton(x + 132, y + 136, 42, 18,
                Component.literal("↻"), this::refreshSchematics));
        confirmButton = addRenderableWidget(new QuantumButton(x + 178, y + 136, 79, 18,
                Component.literal("WRITE CARD"), this::writeSelected,
                () -> menu.status() == SchematicTableBlockEntity.STATUS_UPLOADING,
                QuantumUiTheme.GREEN));

        refreshSchematics();
    }

    private void openFolder() {
        try {
            Path folder = Path.of("schematics").toAbsolutePath().normalize();
            Files.createDirectories(folder);
            Util.getPlatform().openFile(folder.toFile());
        } catch (Exception ignored) {
        }
    }

    private void refreshSchematics() {
        schematics = SchematicFolderIndex.list();
        if (schematics.isEmpty()) {
            selectedIndex = -1;
            firstVisible = 0;
        } else {
            if (selectedIndex < 0 || selectedIndex >= schematics.size()) selectedIndex = 0;
            firstVisible = Math.max(0, Math.min(firstVisible, Math.max(0, schematics.size() - ROWS)));
            ensureSelectedVisible();
        }
        updateRows();
    }

    private void selectRow(int row) {
        if (menu.status() == SchematicTableBlockEntity.STATUS_UPLOADING) return;
        int index = firstVisible + row;
        if (index < 0 || index >= schematics.size()) return;
        selectedIndex = index;
        updateRows();
    }

    private void scroll(int direction) {
        if (schematics.size() <= ROWS) return;
        firstVisible = Math.max(0, Math.min(firstVisible + direction, schematics.size() - ROWS));
        updateRows();
    }

    private void ensureSelectedVisible() {
        if (selectedIndex < firstVisible) firstVisible = selectedIndex;
        if (selectedIndex >= firstVisible + ROWS) firstVisible = selectedIndex - ROWS + 1;
    }

    private void updateRows() {
        boolean busy = menu.status() == SchematicTableBlockEntity.STATUS_UPLOADING;
        for (int row = 0; row < ROWS; row++) {
            QuantumButton button = rowButtons[row];
            if (button == null) continue;
            int index = firstVisible + row;
            boolean exists = index >= 0 && index < schematics.size();
            button.visible = exists;
            button.active = exists && !busy;
            if (exists) {
                SchematicFolderIndex.Entry entry = schematics.get(index);
                String marker = switch (entry.format()) {
                    case VANILLA_NBT -> "NBT";
                    case SPONGE_SCHEM -> "SCHEM";
                    case LITEMATICA -> "LITEM";
                    case LEGACY_SCHEMATIC -> "LEGACY";
                };
                button.setMessage(Component.literal("[" + marker + "]  " + trim(entry.fileName(), 21)));
            }
        }

        boolean inputPresent = menu.table() != null && SchematicTableBlockEntity.isWritableInput(menu.table().inputCard());
        if (confirmButton != null) confirmButton.active = selectedIndex >= 0 && inputPresent && !menu.hasOutput() && !busy;
        if (upButton != null) upButton.active = !busy && firstVisible > 0;
        if (downButton != null) downButton.active = !busy && firstVisible + ROWS < schematics.size();
        if (folderButton != null) folderButton.active = !busy;
        if (refreshButton != null) refreshButton.active = !busy;
    }

    private void writeSelected() {
        if (selectedIndex < 0 || selectedIndex >= schematics.size()) return;
        if (menu.status() == SchematicTableBlockEntity.STATUS_UPLOADING || menu.hasOutput()) return;
        ClientSchematicUploader.upload(menu.blockPos(), schematics.get(selectedIndex));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        int x = leftPos;
        int y = topPos;

        QuantumUiTheme.window(gui, x, y, imageWidth, imageHeight);
        QuantumUiTheme.title(gui, font, Component.literal("SCHEMATIC // IMPORT TERMINAL"), x + imageWidth / 2, y + 8);
        gui.fill(x + 8, y + 23, x + imageWidth - 8, y + 24, QuantumUiTheme.BORDER_DIM);

        QuantumUiTheme.panel(gui, x + 9, y + 31, x + 58, y + 158);
        QuantumUiTheme.panel(gui, x + 62, y + 31, x + 261, y + 158);
        QuantumUiTheme.panel(gui, x + 265, y + 31, x + 287, y + 158);

        gui.text(font, Component.literal("INPUT"), x + 18, y + 38, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal("SUPPORTED SCHEMATICS"), x + 67, y + 36, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal("OUT"), x + 266, y + 38, QuantumUiTheme.MUTED, false);

        QuantumUiTheme.slotFrame(gui, x + 25, y + 64, menu.hasReservedInput(), QuantumUiTheme.CYAN);
        QuantumUiTheme.slotFrame(gui, x + 255, y + 64, menu.hasOutput(), QuantumUiTheme.GREEN);

        gui.text(font, Component.literal("creator"), x + 15, y + 90, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal("card"), x + 20, y + 101, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal(menu.hasOutput() ? "ready" : "—"), x + 266, y + 90,
                menu.hasOutput() ? QuantumUiTheme.GREEN : QuantumUiTheme.MUTED, false);

        if (schematics.isEmpty()) {
            gui.text(font, Component.literal("No supported schematic files found"), x + 68, y + 64, QuantumUiTheme.AMBER, false);
            gui.text(font, Component.literal("NBT · SCHEM · LITEMATIC · SCHEMATIC"), x + 68, y + 78, QuantumUiTheme.MUTED, false);
            gui.text(font, Component.literal("Open FOLDER, add files, then refresh"), x + 68, y + 94, QuantumUiTheme.TEXT_SOFT, false);
        } else {
            int visibleFrom = firstVisible + 1;
            int visibleTo = Math.min(firstVisible + ROWS, schematics.size());
            gui.text(font, Component.literal(visibleFrom + "–" + visibleTo + " / " + schematics.size()),
                    x + 211, y + 119, QuantumUiTheme.MUTED, false);
        }

        drawTransferStatus(gui, x, y);

        gui.fill(x + 8, y + 164, x + imageWidth - 8, y + 165, QuantumUiTheme.BORDER_DIM);
        gui.text(font, Component.literal("PLAYER INVENTORY"), x + 67, y + 166, QuantumUiTheme.MUTED, false);
    }

    private void drawTransferStatus(GuiGraphicsExtractor gui, int x, int y) {
        int status = menu.status();
        if (status == SchematicTableBlockEntity.STATUS_UPLOADING) {
            QuantumUiTheme.segmentedBar(gui, x + 67, y + 120, 168, 10,
                    Math.max(0, Math.min(10_000, menu.progress())), 10_000, QuantumUiTheme.CYAN, 12);
            gui.text(font, Component.literal((menu.progress() / 100) + "%"), x + 214, y + 121, QuantumUiTheme.TEXT, false);
        }

        int color = statusColor(status);
        QuantumUiTheme.statusLamp(gui, x + 12, y + 145, color,
                status != SchematicTableBlockEntity.STATUS_NO_CARD);
        gui.text(font, Component.literal(statusText(status)), x + 25, y + 144, color, false);
    }

    private static String statusText(int status) {
        return switch (status) {
            case SchematicTableBlockEntity.STATUS_READY -> "READY — select a file";
            case SchematicTableBlockEntity.STATUS_UPLOADING -> "VALIDATING / UPLOADING";
            case SchematicTableBlockEntity.STATUS_FINISHED -> "CARD WRITTEN";
            case SchematicTableBlockEntity.STATUS_ERROR -> "UPLOAD REJECTED";
            default -> "INSERT A CREATOR CARD";
        };
    }

    private static int statusColor(int status) {
        return switch (status) {
            case SchematicTableBlockEntity.STATUS_FINISHED -> QuantumUiTheme.GREEN;
            case SchematicTableBlockEntity.STATUS_ERROR -> QuantumUiTheme.RED;
            case SchematicTableBlockEntity.STATUS_UPLOADING -> QuantumUiTheme.CYAN;
            case SchematicTableBlockEntity.STATUS_READY -> QuantumUiTheme.TEXT_SOFT;
            default -> QuantumUiTheme.MUTED;
        };
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateRows();
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
    }
}
