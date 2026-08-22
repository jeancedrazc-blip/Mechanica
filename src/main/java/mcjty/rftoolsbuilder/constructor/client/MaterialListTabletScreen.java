package mcjty.rftoolsbuilder.constructor.client;

import mcjty.rftoolsbuilder.constructor.MaterialListTabletItem;
import mcjty.rftoolsbuilder.constructor.client.ui.QuantumButton;
import mcjty.rftoolsbuilder.constructor.client.ui.QuantumUiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.List;

/** Tall glass-fronted material list UI based on the approved sketch. */
public final class MaterialListTabletScreen extends Screen {
    private static final int PANEL_W = 246;
    private static final int PANEL_H = 286;
    private ItemStack tablet;
    private final InteractionHand hand;
    private final List<MaterialRow> rows = new ArrayList<>();
    private final List<MaterialRow> filteredRows = new ArrayList<>();
    private String schematicName = "-";
    private int total;
    private int scroll;
    private Filter filter = Filter.ALL;
    private QuantumButton upButton;
    private QuantumButton downButton;

    private String dataSignature = "";
    private String syncStatus = "CONNECTING";

    public MaterialListTabletScreen(ItemStack tablet, InteractionHand hand) {
        super(Component.literal("MATERIAL LIST TABLET"));
        this.tablet = tablet;
        this.hand = hand;
        readTablet();
    }

    private void readTablet() {
        rows.clear();
        CustomData data = tablet.get(DataComponents.CUSTOM_DATA);
        if (data == null) return;
        var tag = data.copyTag();
        schematicName = tag.getString("QTSchematicName").orElse("-");
        total = tag.getIntOr("QTMaterialTotal", 0);
        String encoded = tag.getString("QTMaterials").orElse("");
        for (String token : encoded.split(";")) {
            String[] fields = token.split("=");
            if (fields.length < 2) continue;
            try {
                Identifier id = Identifier.parse(fields[0]);
                int count = Integer.parseInt(fields[1]);
                int available = fields.length >= 3 ? Integer.parseInt(fields[2]) : 0;
                var block = BuiltInRegistries.BLOCK.getValue(id);
                rows.add(new MaterialRow(block.getName().getString(), count, available,
                        new ItemStack(block.asItem())));
            } catch (RuntimeException ignored) {
            }
        }
        applyFilter(Filter.ALL);
        dataSignature = encoded;
    }

    public void applyServerData(String name, int blockTotal, String encoded, String status) {
        this.schematicName = name;
        this.total = blockTotal;
        this.syncStatus = status;
        readEncodedMaterials(encoded);
    }

    private void readEncodedMaterials(String encoded) {
        rows.clear();
        for (String token : encoded.split(";")) {
            String[] fields = token.split("=");
            if (fields.length < 2) continue;
            try {
                Identifier id = Identifier.parse(fields[0]);
                int count = Integer.parseInt(fields[1]);
                int available = fields.length >= 3 ? Integer.parseInt(fields[2]) : 0;
                var block = BuiltInRegistries.BLOCK.getValue(id);
                rows.add(new MaterialRow(block.getName().getString(), count, available,
                        new ItemStack(block.asItem())));
            } catch (RuntimeException ignored) {
            }
        }
        applyFilter(filter);
        dataSignature = encoded;
    }

    @Override
    protected void init() {
        super.init();
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        addRenderableWidget(new QuantumButton(left + PANEL_W - 23, top + 7, 16, 16,
                Component.literal("×"), this::closeTablet, () -> false, QuantumUiTheme.RED));
        addRenderableWidget(new QuantumButton(left + 18, top + 68, 50, 14,
                Component.literal("ALL"), () -> applyFilter(Filter.ALL), () -> filter == Filter.ALL, QuantumUiTheme.CYAN));
        addRenderableWidget(new QuantumButton(left + 72, top + 68, 60, 14,
                Component.literal("MISSING"), () -> applyFilter(Filter.MISSING), () -> filter == Filter.MISSING, QuantumUiTheme.RED));
        addRenderableWidget(new QuantumButton(left + 136, top + 68, 71, 14,
                Component.literal("AVAILABLE"), () -> applyFilter(Filter.AVAILABLE), () -> filter == Filter.AVAILABLE, QuantumUiTheme.GREEN));
        upButton = addRenderableWidget(new QuantumButton(left + PANEL_W - 17, top + 88, 10, 16,
                Component.literal("▲"), () -> scroll(-1), () -> false, QuantumUiTheme.CYAN));
        downButton = addRenderableWidget(new QuantumButton(left + PANEL_W - 17, top + 248, 10, 16,
                Component.literal("▼"), () -> scroll(1), () -> false, QuantumUiTheme.CYAN));
        updateScrollButtons();
    }

    private void closeTablet() {
        onClose();
    }

    private void applyFilter(Filter requested) {
        filter = requested;
        filteredRows.clear();
        for (MaterialRow row : rows) {
            if (requested == Filter.ALL
                    || requested == Filter.MISSING && row.available() < row.required()
                    || requested == Filter.AVAILABLE && row.available() >= row.required()) {
                filteredRows.add(row);
            }
        }
        scroll = 0;
        updateScrollButtons();
    }

    private void scroll(int direction) {
        scroll = Math.max(0, Math.min(Math.max(0, filteredRows.size() - 8), scroll + direction));
        updateScrollButtons();
    }

    private void updateScrollButtons() {
        if (upButton != null) upButton.active = scroll > 0;
        if (downButton != null) downButton.active = scroll + 8 < filteredRows.size();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;

        QuantumUiTheme.window(gui, left, top, PANEL_W, PANEL_H);
        QuantumUiTheme.title(gui, font, Component.literal("MATERIAL LIST // TABLET"), left + PANEL_W / 2, top + 10);
        QuantumUiTheme.panel(gui, left + 11, top + 31, left + PANEL_W - 18, top + PANEL_H - 13,
                QuantumUiTheme.BORDER, 0xFF071118);

        gui.text(font, Component.literal(MaterialListTabletItem.isWritten(tablet) ? schematicName : "BLANK TABLET"),
                left + 20, top + 42, QuantumUiTheme.CYAN, false);
        gui.text(font, Component.literal(MaterialListTabletItem.isWritten(tablet)
                        ? total + " blocks · " + rows.size() + " material types"
                        : "Insert this tablet in the Constructor input"),
                left + 20, top + 57, QuantumUiTheme.TEXT_SOFT, false);
        int statusColor = "LIVE".equals(syncStatus) ? QuantumUiTheme.GREEN : QuantumUiTheme.AMBER;
        gui.text(font, Component.literal(syncStatus), left + PANEL_W - 21 - font.width(syncStatus), top + 42,
                statusColor, false);

        int rowY = top + 88;
        for (int row = 0; row < 8; row++) {
            int index = scroll + row;
            int y = rowY + row * 22;
            QuantumUiTheme.panel(gui, left + 18, y, left + PANEL_W - 28, y + 19,
                    QuantumUiTheme.BORDER_DIM, row % 2 == 0 ? 0xFF0A1820 : 0xFF0C1C25);
            QuantumUiTheme.slotFrame(gui, left + 21, y + 1, index < filteredRows.size(), QuantumUiTheme.CYAN);
            if (index < filteredRows.size()) {
                MaterialRow material = filteredRows.get(index);
                gui.fakeItem(material.stack(), left + 22, y + 2);
                gui.text(font, Component.literal(trim(material.name(), 20)), left + 47, y + 4,
                        QuantumUiTheme.TEXT, false);
                int color = material.available() >= material.required() ? QuantumUiTheme.GREEN
                        : material.available() > 0 ? QuantumUiTheme.AMBER : QuantumUiTheme.RED;
                gui.text(font, Component.literal(material.available() + " / " + material.required()), left + 47, y + 11,
                        color, false);
            }
        }

        // Thin textured scrollbar from the sketch.
        int sx = left + PANEL_W - 13;
        gui.fill(sx, top + 34, sx + 4, top + PANEL_H - 16, QuantumUiTheme.DEEP);
        int trackTop = top + 106;
        int trackBottom = top + 246;
        int maxScroll = Math.max(0, filteredRows.size() - 8);
        int thumbHeight = maxScroll == 0 ? trackBottom - trackTop : Math.max(12,
                (trackBottom - trackTop) * 8 / Math.max(8, filteredRows.size()));
        int thumbY = maxScroll == 0 ? trackTop
                : trackTop + scroll * (trackBottom - trackTop - thumbHeight) / maxScroll;
        gui.fill(sx + 1, thumbY, sx + 3, thumbY + thumbHeight, QuantumUiTheme.CYAN_DIM);
        gui.fill(sx + 1, thumbY + 1, sx + 2, thumbY + thumbHeight - 1, QuantumUiTheme.CYAN);

        // Screen owns widget extraction. Without this call every button disappears,
        // including close/filter/scroll controls, and item rendering is not finalized.
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft == null || minecraft.player == null) return;
        ItemStack current = minecraft.player.getItemInHand(hand);
        if (!(current.getItem() instanceof MaterialListTabletItem)) return;
        CustomData data = current.get(DataComponents.CUSTOM_DATA);
        String signature = data == null ? "" : data.copyTag().getString("QTMaterials").orElse("");
        if (!signature.equals(dataSignature)) {
            tablet = current;
            readTablet();
        }
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private record MaterialRow(String name, int required, int available, ItemStack stack) {}

    private enum Filter { ALL, MISSING, AVAILABLE }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;
        if (mouseX >= left + 11 && mouseX < left + PANEL_W - 7
                && mouseY >= top + 84 && mouseY < top + PANEL_H - 13 && scrollY != 0) {
            scroll(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
