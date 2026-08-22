package mcjty.rftoolsbuilder.constructor.client;

import mcjty.rftoolsbuilder.constructor.ConstructorBlockEntity;
import mcjty.rftoolsbuilder.constructor.ConstructorMenu;
import mcjty.rftoolsbuilder.constructor.ConstructorReplaceMode;
import mcjty.rftoolsbuilder.constructor.ConstructorSpeed;
import mcjty.rftoolsbuilder.constructor.ConstructorStatus;
import mcjty.rftoolsbuilder.constructor.SchematicCardItem;
import mcjty.rftoolsbuilder.constructor.client.ui.QuantumButton;
import mcjty.rftoolsbuilder.constructor.client.ui.QuantumUiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Constructor control terminal using the shared Quantum Tools UI system. */
public final class ConstructorScreen extends AbstractContainerScreen<ConstructorMenu> {
    private QuantumButton startButton;
    private QuantumButton pauseButton;
    private QuantumButton stopButton;
    private QuantumButton configButton;
    private QuantumButton materialUpButton;
    private QuantumButton materialDownButton;
    private QuantumButton replacementCloseButton;
    private QuantumButton configCloseButton;
    private QuantumButton speedButton;
    private final java.util.List<QuantumButton> configWidgets = new java.util.ArrayList<>();
    private boolean configOpen;

    public ConstructorScreen(ConstructorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 272, 288);
        this.titleLabelY = 7;
        this.inventoryLabelY = 198;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos;
        int y = topPos;

        startButton = addRenderableWidget(new QuantumButton(x + 54, y + 145, 54, 19,
                Component.literal("▶ START"), () -> sendButton(2),
                () -> menu.data().get(6) != 0, QuantumUiTheme.GREEN));
        pauseButton = addRenderableWidget(new QuantumButton(x + 112, y + 145, 56, 19,
                Component.literal("Ⅱ PAUSE"), () -> sendButton(0),
                () -> status(menu.data().get(2)) == ConstructorStatus.PAUSED, QuantumUiTheme.AMBER));
        stopButton = addRenderableWidget(new QuantumButton(x + 172, y + 145, 54, 19,
                Component.literal("■ STOP"), () -> sendButton(1),
                () -> false, QuantumUiTheme.RED));

        configButton = addRenderableWidget(new QuantumButton(x + 236, y + 5, 26, 17,
                Component.literal("⚙"), () -> sendButton(configOpen ? 11 : 10),
                () -> configOpen, QuantumUiTheme.AMBER));

        materialUpButton = addRenderableWidget(new QuantumButton(x + 215, y + 55, 10, 18,
                Component.literal("▲"), () -> sendButton(8), () -> false, QuantumUiTheme.CYAN));
        materialDownButton = addRenderableWidget(new QuantumButton(x + 215, y + 112, 10, 18,
                Component.literal("▼"), () -> sendButton(9), () -> false, QuantumUiTheme.CYAN));
        replacementCloseButton = addRenderableWidget(new QuantumButton(x + 202, y + 34, 13, 13,
                Component.literal("×"), () -> sendButton(12), () -> false, QuantumUiTheme.RED));
        configCloseButton = addRenderableWidget(new QuantumButton(x + 202, y + 34, 13, 13,
                Component.literal("×"), () -> sendButton(11), () -> false, QuantumUiTheme.RED));

        addConfigModeButton(x + 62, y + 55, 74, "AIR ONLY", ConstructorReplaceMode.DONT_REPLACE);
        addConfigModeButton(x + 140, y + 55, 74, "SOLID", ConstructorReplaceMode.REPLACE_SOLID);
        addConfigModeButton(x + 62, y + 77, 74, "ANY", ConstructorReplaceMode.REPLACE_ANY);
        addConfigModeButton(x + 140, y + 77, 74, "+ AIR", ConstructorReplaceMode.REPLACE_EMPTY);
        configWidgets.add(addRenderableWidget(new QuantumButton(x + 62, y + 103, 74, 18,
                Component.literal("SKIP MISSING"), () -> sendButton(4),
                () -> menu.data().get(10) != 0, QuantumUiTheme.AMBER)));
        configWidgets.add(addRenderableWidget(new QuantumButton(x + 140, y + 103, 74, 18,
                Component.literal("BLOCK ENT."), () -> sendButton(5),
                () -> menu.data().get(11) != 0, QuantumUiTheme.AMBER)));
        speedButton = addRenderableWidget(new QuantumButton(x + 62, y + 125, 74, 18,
                Component.literal("NORMAL 1x"), () -> sendButton(13),
                () -> false, QuantumUiTheme.CYAN));
        configWidgets.add(speedButton);
        configWidgets.add(addRenderableWidget(new QuantumButton(x + 140, y + 125, 74, 18,
                Component.literal("CLEAR RULES"), () -> sendButton(7),
                () -> false, QuantumUiTheme.RED)));
        setConfigWidgetsVisible(false);
        replacementCloseButton.visible = false;
        configCloseButton.visible = false;
    }

    private void addModeButton(int x, int y, int width, String label, ConstructorReplaceMode mode) {
        addRenderableWidget(new QuantumButton(x, y, width, 17, Component.literal(label),
                () -> setReplaceMode(mode), () -> replaceMode(menu.data().get(9)) == mode,
                QuantumUiTheme.CYAN));
    }

    private void addConfigModeButton(int x, int y, int width, String label, ConstructorReplaceMode mode) {
        QuantumButton button = new QuantumButton(x, y, width, 18, Component.literal(label),
                () -> setReplaceMode(mode), () -> replaceMode(menu.data().get(9)) == mode,
                QuantumUiTheme.CYAN);
        configWidgets.add(addRenderableWidget(button));
    }

    private void setConfigWidgetsVisible(boolean visible) {
        for (QuantumButton widget : configWidgets) widget.visible = visible;
    }

    private void setReplaceMode(ConstructorReplaceMode requested) {
        int current = replaceMode(menu.data().get(9)).ordinal();
        int target = requested.ordinal();
        int count = ConstructorReplaceMode.values().length;
        int steps = Math.floorMod(target - current, count);
        for (int i = 0; i < steps; i++) sendButton(3);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        ConstructorStatus state = status(menu.data().get(2));
        configOpen = menu.uiMode() == 1;
        boolean replacementOpen = menu.selectedMaterial() >= 0;
        setConfigWidgetsVisible(configOpen);
        if (configCloseButton != null) configCloseButton.visible = configOpen;
        if (replacementCloseButton != null) replacementCloseButton.visible = replacementOpen;
        if (materialUpButton != null) {
            materialUpButton.visible = !configOpen && !replacementOpen && menu.materialCount() > ConstructorMenu.MATERIAL_PAGE_SIZE;
            materialUpButton.active = menu.materialScroll() > 0;
        }
        if (materialDownButton != null) {
            materialDownButton.visible = !configOpen && !replacementOpen && menu.materialCount() > ConstructorMenu.MATERIAL_PAGE_SIZE;
            materialDownButton.active = menu.materialScroll() + ConstructorMenu.MATERIAL_PAGE_SIZE < menu.materialCount();
        }
        boolean modalOpen = configOpen || replacementOpen;
        boolean hasCard = menu.data().get(8) != 0;
        boolean shotInFlight = state == ConstructorStatus.FIRING;
        boolean hasJob = menu.data().get(4) > 0 || state != ConstructorStatus.IDLE;

        if (startButton != null) {
            startButton.active = !modalOpen && hasCard && !shotInFlight
                    && state != ConstructorStatus.AIMING && state != ConstructorStatus.CHARGING;
        }
        if (pauseButton != null) {
            pauseButton.active = !modalOpen && hasJob && state != ConstructorStatus.COMPLETE && state != ConstructorStatus.ERROR;
            pauseButton.setMessage(Component.literal(state == ConstructorStatus.PAUSED ? "▶ RESUME" : "Ⅱ PAUSE"));
        }
        if (stopButton != null) stopButton.active = !modalOpen && hasJob && !shotInFlight;
        if (configButton != null) configButton.active = !replacementOpen;
        if (speedButton != null) {
            ConstructorSpeed selected = ConstructorSpeed.byOrdinal(menu.data().get(19));
            speedButton.setMessage(Component.literal(selected.label()));
            speedButton.active = !shotInFlight;
        }
    }

    private void sendButton(int id) {
        Minecraft mc = this.minecraft;
        if (mc != null && mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!configOpen && menu.selectedMaterial() < 0
                && mouseX >= leftPos + 54 && mouseX < leftPos + 226
                && mouseY >= topPos + 31 && mouseY < topPos + 138 && scrollY != 0) {
            sendButton(scrollY > 0 ? 8 : 9);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        int x = leftPos;
        int y = topPos;

        QuantumUiTheme.window(gui, x, y, imageWidth, imageHeight);
        QuantumUiTheme.title(gui, font, Component.literal("CONSTRUCTOR"), x + imageWidth / 2, y + 8);
        gui.fill(x + 8, y + 23, x + imageWidth - 8, y + 24, QuantumUiTheme.BORDER_DIM);

        // Left rail: energy and schematic card.
        QuantumUiTheme.panel(gui, x + 10, y + 31, x + 50, y + 138);
        gui.text(font, Component.literal("FE"), x + 24, y + 38, QuantumUiTheme.MUTED, false);
        QuantumUiTheme.verticalGauge(gui, x + 20, y + 51, 20, 76,
                syncedInt(menu.data().get(14), menu.data().get(15)),
                Math.max(1, syncedInt(menu.data().get(16), menu.data().get(17))), QuantumUiTheme.AMBER, 10);
        int energy = syncedInt(menu.data().get(14), menu.data().get(15));
        int capacity = Math.max(1, syncedInt(menu.data().get(16), menu.data().get(17)));
        String pct = formatPercent(energy, capacity);
        gui.text(font, Component.literal(pct), x + 30 - font.width(pct) / 2, y + 130, QuantumUiTheme.AMBER, false);
        gui.text(font, Component.literal("CARD"), x + 14, y + 143, QuantumUiTheme.MUTED, false);
        QuantumUiTheme.slotFrame(gui, x + 22, y + 158, menu.data().get(8) != 0, QuantumUiTheme.CYAN);

        // Large central region reserved for real schematic material icons.
        QuantumUiTheme.panel(gui, x + 54, y + 31, x + 226, y + 138);
        boolean replacementOpen = menu.selectedMaterial() >= 0;
        if (!replacementOpen && !configOpen) {
            gui.text(font, Component.literal("MATERIALS IN SCHEMATIC"), x + 63, y + 39, QuantumUiTheme.CYAN, false);
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 7; col++) {
                    int slot = 3 + col + row * 7;
                    QuantumUiTheme.slotFrame(gui, x + 64 + col * 22, y + 55 + row * 25,
                            menu.getSlot(slot).hasItem(), QuantumUiTheme.CYAN);
                }
            }
            if (menu.materialCount() > ConstructorMenu.MATERIAL_PAGE_SIZE) {
                int trackTop = y + 75;
                int trackBottom = y + 110;
                gui.fill(x + 218, trackTop, x + 222, trackBottom, QuantumUiTheme.DEEP);
                int range = Math.max(1, menu.materialCount() - ConstructorMenu.MATERIAL_PAGE_SIZE);
                int thumbY = trackTop + menu.materialScroll() * Math.max(1, trackBottom - trackTop - 8) / range;
                gui.fill(x + 219, thumbY, x + 221, thumbY + 8, QuantumUiTheme.CYAN);
            }
            gui.text(font, Component.literal("CLICK A MATERIAL TO REPLACE"), x + 68, y + 132,
                    QuantumUiTheme.MUTED, false);
        }

        if (replacementOpen) {
            QuantumUiTheme.panel(gui, x + 56, y + 31, x + 220, y + 138,
                    QuantumUiTheme.AMBER, QuantumUiTheme.SURFACE_2);
            gui.text(font, Component.literal("MATERIAL FILTER"),
                    x + 102, y + 41, QuantumUiTheme.TEXT, false);
            gui.text(font, Component.literal("REPLACE WITH"), x + 103, y + 77,
                    QuantumUiTheme.AMBER, false);
            QuantumUiTheme.slotFrame(gui, x + 127, y + 96, menu.getSlot(24).hasItem(), QuantumUiTheme.AMBER);
            gui.text(font, Component.literal("HOLD A BLOCK AND CLICK THE SLOT"), x + 66, y + 120,
                    QuantumUiTheme.MUTED, false);
        }

        // Tablet writer rail: blank input, animated transfer path and output.
        QuantumUiTheme.panel(gui, x + 230, y + 31, x + 262, y + 138);
        gui.text(font, Component.literal("IN"), x + 241, y + 39, QuantumUiTheme.MUTED, false);
        QuantumUiTheme.slotFrame(gui, x + 240, y + 57, menu.getSlot(1).hasItem(), QuantumUiTheme.CYAN);
        int scan = Math.max(0, Math.min(ConstructorBlockEntity.TABLET_SCAN_TICKS, menu.data().get(18)));
        int pulse = scan == 0 ? -1 : scan * 3 / Math.max(1, ConstructorBlockEntity.TABLET_SCAN_TICKS);
        for (int i = 0; i < 3; i++) {
            gui.fill(x + 248 - i, y + 82 + i * 7, x + 254 + i, y + 85 + i * 7,
                    i == pulse ? QuantumUiTheme.CYAN : QuantumUiTheme.CYAN_DIM);
        }
        gui.text(font, Component.literal("OUT"), x + 236, y + 106, QuantumUiTheme.MUTED, false);
        QuantumUiTheme.slotFrame(gui, x + 240, y + 119, menu.getSlot(2).hasItem(), QuantumUiTheme.GREEN);

        int progress = status(menu.data().get(2)) == ConstructorStatus.COMPLETE
                ? menu.data().get(4) : Math.min(menu.data().get(3), menu.data().get(4));
        QuantumUiTheme.segmentedBar(gui, x + 54, y + 169, 172, 8,
                progress, Math.max(1, menu.data().get(4)), QuantumUiTheme.AMBER, 16);
        gui.text(font, Component.literal(progress + " / " + Math.max(0, menu.data().get(4))),
                x + 117, y + 180, QuantumUiTheme.TEXT_SOFT, false);

        if (configOpen) {
            QuantumUiTheme.panel(gui, x + 56, y + 31, x + 220, y + 148,
                    QuantumUiTheme.AMBER, QuantumUiTheme.SURFACE_2);
            QuantumUiTheme.title(gui, font, Component.literal("PLACEMENT CONFIG"), x + 138, y + 38);
        }

        gui.fill(x + 8, y + 204, x + imageWidth - 8, y + 205, QuantumUiTheme.BORDER_DIM);
        gui.text(font, Component.literal("PLAYER INVENTORY"), x + 48, y + 196, QuantumUiTheme.MUTED, false);
    }

    private void drawEnergy(GuiGraphicsExtractor gui, int x, int y) {
        int energy = syncedInt(menu.data().get(14), menu.data().get(15));
        int capacity = Math.max(1, syncedInt(menu.data().get(16), menu.data().get(17)));
        ConstructorStatus state = status(menu.data().get(2));

        gui.text(font, Component.literal("POWER CORE"), x + 16, y + 38, QuantumUiTheme.MUTED, false);
        QuantumUiTheme.verticalGauge(gui, x + 19, y + 53, 17, 60, energy, capacity, QuantumUiTheme.CYAN, 8);
        gui.text(font, Component.literal(formatFe(energy)), x + 42, y + 57, QuantumUiTheme.TEXT, false);
        gui.text(font, Component.literal(formatPercent(energy, capacity)), x + 42, y + 70, QuantumUiTheme.CYAN, false);
        gui.text(font, Component.literal("CAP"), x + 42, y + 87, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal(formatFe(capacity)), x + 42, y + 99, QuantumUiTheme.TEXT_SOFT, false);

        int stateColor = statusColor(state);
        QuantumUiTheme.statusLamp(gui, x + 17, y + 119, stateColor, state != ConstructorStatus.IDLE);
        gui.text(font, Component.literal(statusText(state)), x + 30, y + 118, stateColor, false);
    }

    private void drawJob(GuiGraphicsExtractor gui, int x, int y) {
        int index = Math.max(0, menu.data().get(3));
        int total = Math.max(0, menu.data().get(4));
        int shot = Math.max(0, menu.data().get(5));
        int flightTicks = Math.max(1, menu.data().get(12));
        ConstructorStatus state = status(menu.data().get(2));

        gui.text(font, Component.literal("SCHEMATIC JOB"), x + 96, y + 38, QuantumUiTheme.MUTED, false);
        int shown = total <= 0 ? 0 : Math.min(total, index + (state == ConstructorStatus.COMPLETE ? 0 : 1));
        gui.text(font, Component.literal(shown + " / " + total), x + 96, y + 52, QuantumUiTheme.TEXT, false);

        int progress = state == ConstructorStatus.COMPLETE ? total : Math.min(total, index);
        QuantumUiTheme.segmentedBar(gui, x + 96, y + 66, 88, 10, progress, Math.max(1, total), QuantumUiTheme.CYAN, 10);

        BlockState target = targetState();
        gui.text(font, Component.literal("CURRENT"), x + 96, y + 82, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal(target == null ? (menu.data().get(13) != 0 ? "entity" : "-") : shortId(target)),
                x + 96, y + 94, QuantumUiTheme.TEXT_SOFT, false);

        String card = cardName();
        gui.text(font, Component.literal("CARD"), x + 96, y + 106, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal(trim(card, 10)), x + 96, y + 118, QuantumUiTheme.TEXT, false);

        if (state == ConstructorStatus.FIRING) {
            int shotPct = Math.min(100, shot * 100 / flightTicks);
            gui.text(font, Component.literal("PROJECTILE " + shotPct + "%"), x + 96, y + 130, QuantumUiTheme.GREEN, false);
        } else {
            int rules = replacementCount();
            gui.text(font, Component.literal(rules == 0 ? "no substitution rules" : rules + " substitution rule" + (rules == 1 ? "" : "s")),
                    x + 96, y + 130, rules == 0 ? QuantumUiTheme.MUTED : QuantumUiTheme.AMBER, false);
        }
    }

    private void drawTarget(GuiGraphicsExtractor gui, int x, int y) {
        BlockPos target = targetPos();
        ConstructorStatus state = status(menu.data().get(2));

        gui.text(font, Component.literal("TARGET"), x + 200, y + 38, QuantumUiTheme.MUTED, false);
        if (target == null) {
            gui.text(font, Component.literal("No target"), x + 200, y + 53, QuantumUiTheme.TEXT_SOFT, false);
        } else {
            gui.text(font, Component.literal("X " + target.getX()), x + 200, y + 52, QuantumUiTheme.TEXT, false);
            gui.text(font, Component.literal("Y " + target.getY()), x + 200, y + 64, QuantumUiTheme.TEXT, false);
            gui.text(font, Component.literal("Z " + target.getZ()), x + 200, y + 76, QuantumUiTheme.TEXT, false);
        }

        gui.text(font, Component.literal("ENERGY RATE"), x + 200, y + 92, QuantumUiTheme.MUTED, false);
        gui.text(font, Component.literal("1 FE / 500"), x + 200, y + 104, QuantumUiTheme.CYAN, false);

        if (state == ConstructorStatus.WAITING_MATERIAL) {
            gui.text(font, Component.literal("MISSING MATERIAL"), x + 200, y + 121, QuantumUiTheme.AMBER, false);
        } else if (state == ConstructorStatus.WAITING_ENERGY) {
            gui.text(font, Component.literal("ENERGY REQUIRED"), x + 200, y + 121, QuantumUiTheme.AMBER, false);
        } else if (state == ConstructorStatus.WAITING_CHUNK) {
            gui.text(font, Component.literal("CHUNK UNLOADED"), x + 200, y + 121, QuantumUiTheme.YELLOW, false);
        } else if (state == ConstructorStatus.BLOCKED) {
            gui.text(font, Component.literal("PLACEMENT BLOCKED"), x + 200, y + 121, QuantumUiTheme.RED, false);
        } else if (state == ConstructorStatus.ERROR) {
            gui.text(font, Component.literal("SCHEMATIC ERROR"), x + 200, y + 121, QuantumUiTheme.RED, false);
        }
    }

    private int replacementCount() {
        if (menu.constructor() == null) return 0;
        ItemStack card = menu.constructor().schematicCard();
        return card.getItem() instanceof SchematicCardItem ? SchematicCardItem.replacementCount(card) : 0;
    }

    private String cardName() {
        if (menu.constructor() == null) return "No card";
        ItemStack card = menu.constructor().schematicCard();
        if (!(card.getItem() instanceof SchematicCardItem)) return "No card";
        return SchematicCardItem.sourceName(card);
    }

    private BlockState targetState() { return menu.constructor() == null ? null : menu.constructor().targetState(); }
    private BlockPos targetPos() { return menu.constructor() == null ? null : menu.constructor().targetPos(); }

    private static String shortId(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        int colon = id.indexOf(':');
        String compact = colon >= 0 ? id.substring(colon + 1) : id;
        return compact.length() <= 13 ? compact : compact.substring(0, 12) + "…";
    }

    private static ConstructorStatus status(int value) {
        ConstructorStatus[] values = ConstructorStatus.values();
        return values[Math.max(0, Math.min(values.length - 1, value))];
    }

    private static ConstructorReplaceMode replaceMode(int value) {
        ConstructorReplaceMode[] values = ConstructorReplaceMode.values();
        return values[Math.max(0, Math.min(values.length - 1, value))];
    }

    private static String statusText(ConstructorStatus status) {
        return switch (status) {
            case IDLE -> "IDLE";
            case READY -> "READY";
            case AIMING -> "AIMING";
            case CHARGING -> "CHARGING";
            case FIRING -> "FIRING";
            case WAITING_ENERGY -> "WAIT FE";
            case WAITING_MATERIAL -> "WAIT ITEM";
            case WAITING_CHUNK -> "WAIT CHUNK";
            case PAUSED -> "PAUSED";
            case BLOCKED -> "BLOCKED";
            case COMPLETE -> "COMPLETE";
            case ERROR -> "ERROR";
        };
    }

    private static int statusColor(ConstructorStatus status) {
        return switch (status) {
            case AIMING, CHARGING, FIRING, READY -> QuantumUiTheme.CYAN;
            case COMPLETE -> QuantumUiTheme.GREEN;
            case WAITING_ENERGY, WAITING_MATERIAL, WAITING_CHUNK, PAUSED -> QuantumUiTheme.AMBER;
            case BLOCKED, ERROR -> QuantumUiTheme.RED;
            default -> QuantumUiTheme.TEXT_SOFT;
        };
    }

    private static String formatFe(int value) {
        if (value >= 1_000_000) return String.format("%.2f MFE", value / 1_000_000.0);
        if (value >= 1_000) return String.format("%.1f kFE", value / 1_000.0);
        return value + " FE";
    }

    private static String formatPercent(int value, int max) {
        if (max <= 0) return "0%";
        return Math.min(100, Math.max(0, (int) ((long) value * 100 / max))) + "%";
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static int syncedInt(int low, int high) {
        return (low & 0xFFFF) | (high & 0xFFFF) << 16;
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
    }
}
