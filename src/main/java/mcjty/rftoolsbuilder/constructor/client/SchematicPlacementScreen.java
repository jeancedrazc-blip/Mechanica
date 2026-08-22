package mcjty.rftoolsbuilder.constructor.client;

import mcjty.rftoolsbuilder.constructor.client.ui.QuantumButton;
import mcjty.rftoolsbuilder.constructor.client.ui.QuantumUiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Compact, non-pausing schematic transform panel.
 *
 * The panel is always drawn as a HUD while a deployed card is held. Pressing
 * ALT temporarily opens this screen and unlocks the cursor, making the same
 * panel interactive without reserving a permanent key binding.
 */
public final class SchematicPlacementScreen extends Screen {
    private static final int PANEL_WIDTH = 350;
    private static final int PANEL_HEIGHT = 96;
    private boolean finished;

    public SchematicPlacementScreen() {
        super(Component.literal("Schematic Transform"));
    }

    private static int left(int screenWidth) {
        return (screenWidth - PANEL_WIDTH) / 2;
    }

    private static int top(int screenHeight) {
        return screenHeight - PANEL_HEIGHT - 8;
    }

    @Override
    protected void init() {
        super.init();
        int x = left(width);
        int y = top(height);

        // MOVE X/Z — always one block relative to the player's current view.
        add(x + 45, y + 34, 18, "▲", () -> SchematicPlacementHandler.nudgeView(1, 0));
        add(x + 25, y + 52, 18, "◀", () -> SchematicPlacementHandler.nudgeView(0, -1));
        add(x + 45, y + 52, 18, "▼", () -> SchematicPlacementHandler.nudgeView(-1, 0));
        add(x + 65, y + 52, 18, "▶", () -> SchematicPlacementHandler.nudgeView(0, 1));

        // MOVE Y — one block per click.
        add(x + 132, y + 34, 24, "▲", () -> SchematicPlacementHandler.nudge(0, 1, 0));
        add(x + 132, y + 54, 24, "▼", () -> SchematicPlacementHandler.nudge(0, -1, 0));

        // ROTATE — clockwise 90 degrees while preserving the schematic center.
        add(x + 205, y + 38, 34, "↻", () -> SchematicPlacementHandler.rotate90(true));

        addRenderableWidget(new QuantumButton(x + 8, y + 75, 50, 17,
                Component.literal("RESET"), SchematicPlacementHandler::reset));
        addRenderableWidget(new QuantumButton(x + 62, y + 75, 50, 17,
                Component.literal("CLEAR"), this::clear, () -> false, QuantumUiTheme.RED));
        addRenderableWidget(new QuantumButton(x + 238, y + 75, 50, 17,
                Component.literal("CANCEL"), this::cancel, () -> false, QuantumUiTheme.RED));
        addRenderableWidget(new QuantumButton(x + 292, y + 75, 50, 17,
                Component.literal("APPLY"), this::apply, () -> false, QuantumUiTheme.GREEN));
    }

    private void add(int x, int y, int size, String label, Runnable action) {
        addRenderableWidget(new QuantumButton(x, y, size, 17, Component.literal(label), action,
                () -> false, QuantumUiTheme.CYAN));
    }

    private void apply() {
        if (!SchematicPlacementHandler.confirm()) return;
        finished = true;
        closeScreen();
    }

    private void clear() {
        if (!SchematicPlacementHandler.clearDeployment()) return;
        finished = true;
        closeScreen();
    }

    private void cancel() {
        SchematicPlacementHandler.cancel();
        finished = true;
        closeScreen();
    }

    /** Closes cursor interaction on ALT release without discarding staged edits. */
    public void suspendForAltRelease() {
        finished = true;
        closeScreen();
    }

    private void closeScreen() {
        Minecraft mc = minecraft;
        if (mc != null) mc.setScreen(null);
    }

    @Override
    public void onClose() {
        if (!finished) SchematicPlacementHandler.cancel();
        finished = true;
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        drawCompactPanel(gui, font, width, height, true);
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
    }

    public static void drawCompactPanel(GuiGraphicsExtractor gui, Font font,
                                        int screenWidth, int screenHeight, boolean interactive) {
        int x = left(screenWidth);
        int y = top(screenHeight);
        QuantumUiTheme.window(gui, x, y, PANEL_WIDTH, PANEL_HEIGHT);
        QuantumUiTheme.title(gui, font, Component.literal("SCHEMATIC TRANSFORM"), x + PANEL_WIDTH / 2, y + 6);

        gui.text(font, Component.literal("MOVE X/Z"), x + 24, y + 23, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal("MOVE Y"), x + 124, y + 23, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal("ROTATE"), x + 197, y + 23, QuantumUiTheme.MUTED, false);

        int statusColor = interactive ? QuantumUiTheme.GREEN : QuantumUiTheme.AMBER;
        gui.text(font, Component.literal(interactive ? "ALT ACTIVE" : "HOLD ALT TO EDIT"),
                x + 257, y + 25, statusColor, false);
        gui.text(font, Component.literal("X " + SchematicPlacementHandler.anchor().getX()
                        + "  Y " + SchematicPlacementHandler.anchor().getY()
                        + "  Z " + SchematicPlacementHandler.anchor().getZ()),
                x + 257, y + 42, QuantumUiTheme.TEXT_SOFT, false);
        gui.text(font, Component.literal("ROT " + SchematicPlacementHandler.rotationDegrees() + "°"),
                x + 257, y + 56, QuantumUiTheme.CYAN, false);
    }
}
