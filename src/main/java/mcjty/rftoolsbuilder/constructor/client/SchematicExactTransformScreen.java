package mcjty.rftoolsbuilder.constructor.client;

import mcjty.rftoolsbuilder.constructor.client.ui.QuantumButton;
import mcjty.rftoolsbuilder.constructor.client.ui.QuantumUiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Exact transform editor, equivalent in purpose to Create's schematic edit
 * screen. It is deliberately secondary to the world tools: exact coordinates
 * are available when needed without turning the normal placement flow into a
 * spreadsheet.
 */
public final class SchematicExactTransformScreen extends Screen {
    private static final int W = 292;
    private static final int H = 164;

    private EditBox xField;
    private EditBox yField;
    private EditBox zField;
    private int rotation;
    private int mirror;
    private String validationError = "";

    public SchematicExactTransformScreen() {
        super(Component.literal("Exact Schematic Transform"));
        this.rotation = SchematicPlacementHandler.rotationQuarter();
        this.mirror = SchematicPlacementHandler.mirror();
    }

    @Override
    protected void init() {
        super.init();
        int left = (width - W) / 2;
        int top = (height - H) / 2;
        BlockPos anchor = SchematicPlacementHandler.anchor();

        xField = coordinateField(left + 39, top + 47, anchor.getX());
        yField = coordinateField(left + 117, top + 47, anchor.getY());
        zField = coordinateField(left + 195, top + 47, anchor.getZ());
        addRenderableWidget(xField);
        addRenderableWidget(yField);
        addRenderableWidget(zField);

        int ry = top + 84;
        addRenderableWidget(new QuantumButton(left + 16, ry, 56, 18, Component.literal("0°"),
                () -> rotation = 0, () -> rotation == 0, QuantumUiTheme.CYAN));
        addRenderableWidget(new QuantumButton(left + 76, ry, 56, 18, Component.literal("90°"),
                () -> rotation = 1, () -> rotation == 1, QuantumUiTheme.CYAN));
        addRenderableWidget(new QuantumButton(left + 136, ry, 56, 18, Component.literal("180°"),
                () -> rotation = 2, () -> rotation == 2, QuantumUiTheme.CYAN));
        addRenderableWidget(new QuantumButton(left + 196, ry, 56, 18, Component.literal("270°"),
                () -> rotation = 3, () -> rotation == 3, QuantumUiTheme.CYAN));

        int my = top + 109;
        addRenderableWidget(new QuantumButton(left + 16, my, 74, 18, Component.literal("NO MIRROR"),
                () -> mirror = 0, () -> mirror == 0, QuantumUiTheme.AMBER));
        addRenderableWidget(new QuantumButton(left + 94, my, 74, 18, Component.literal("MIRROR X"),
                () -> mirror = 1, () -> mirror == 1, QuantumUiTheme.AMBER));
        addRenderableWidget(new QuantumButton(left + 172, my, 80, 18, Component.literal("MIRROR Z"),
                () -> mirror = 2, () -> mirror == 2, QuantumUiTheme.AMBER));

        addRenderableWidget(new QuantumButton(left + W - 126, top + H - 27, 52, 18,
                Component.literal("BACK"), this::back, () -> false, QuantumUiTheme.RED));
        addRenderableWidget(new QuantumButton(left + W - 69, top + H - 27, 53, 18,
                Component.literal("APPLY"), this::apply, () -> false, QuantumUiTheme.GREEN));
    }

    private EditBox coordinateField(int x, int y, int value) {
        EditBox field = new EditBox(font, x, y, 64, 16, Component.literal("Coordinate"));
        field.setMaxLength(11);
        field.setBordered(false);
        field.setTextColor(QuantumUiTheme.TEXT);
        field.setTextColorUneditable(QuantumUiTheme.DISABLED);
        field.setValue(Integer.toString(value));
        return field;
    }

    private void apply() {
        Integer x = parse(xField.getValue());
        Integer y = parse(yField.getValue());
        Integer z = parse(zField.getValue());
        if (x == null || y == null || z == null) {
            validationError = "Coordinates must be signed whole numbers";
            return;
        }

        validationError = "";
        SchematicPlacementHandler.setAnchor(new BlockPos(x, y, z));
        SchematicPlacementHandler.setRotationAtAnchor(rotation);
        SchematicPlacementHandler.setMirror(mirror);
        back();
    }

    private static Integer parse(String value) {
        if (value == null || value.isBlank() || value.equals("-")) return null;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    private void back() {
        Minecraft mc = minecraft;
        if (mc != null) mc.setScreen(new SchematicPlacementScreen());
    }

    @Override
    public void onClose() {
        back();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        int left = (width - W) / 2;
        int top = (height - H) / 2;

        QuantumUiTheme.window(gui, left, top, W, H);
        QuantumUiTheme.title(gui, font, Component.literal("EXACT TRANSFORM // SCHEMATIC"), left + W / 2, top + 8);
        gui.fill(left + 10, top + 25, left + W - 10, top + 26, QuantumUiTheme.BORDER_DIM);

        gui.text(font, Component.literal("WORLD ANCHOR"), left + 16, top + 32, QuantumUiTheme.MUTED, false);
        drawFieldFrame(gui, left + 37, top + 45, "X");
        drawFieldFrame(gui, left + 115, top + 45, "Y");
        drawFieldFrame(gui, left + 193, top + 45, "Z");

        QuantumUiTheme.sectionHeader(gui, font, Component.literal("ROTATION"), left + 16, top + 72, W - 32);
        QuantumUiTheme.sectionHeader(gui, font, Component.literal("MIRROR"), left + 16, top + 102, W - 32);

        if (!validationError.isBlank())
            gui.text(font, Component.literal(validationError), left + 16, top + H - 23, QuantumUiTheme.RED, false);

        super.extractRenderState(gui, mouseX, mouseY, partialTick);
    }

    private void drawFieldFrame(GuiGraphicsExtractor gui, int x, int y, String axis) {
        QuantumUiTheme.panel(gui, x, y, x + 68, y + 20, QuantumUiTheme.BORDER_DIM, QuantumUiTheme.DEEP);
        gui.text(font, Component.literal(axis), x + 4, y + 6, QuantumUiTheme.CYAN, false);
    }
}
