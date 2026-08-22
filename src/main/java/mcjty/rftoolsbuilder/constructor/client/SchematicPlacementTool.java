package mcjty.rftoolsbuilder.constructor.client;

import net.minecraft.network.chat.Component;

/**
 * Create-inspired schematic manipulation modes. The tool state is client-side
 * editing state; only the resulting anchor/rotation/mirror are synchronized.
 */
public enum SchematicPlacementTool {
    DEPLOY("DEPLOY", "Place the schematic at a world target"),
    MOVE_XZ("MOVE X/Z", "Move along the selected horizontal axes"),
    MOVE_Y("MOVE Y", "Move the schematic vertically"),
    ROTATE("ROTATE", "Rotate around the schematic center"),
    MIRROR("MIRROR", "Mirror on X or Z"),
    PRECISE("PRECISE", "Fine coordinate controls and exact values");

    private final String label;
    private final String description;

    SchematicPlacementTool(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public Component label() { return Component.literal(label); }
    public Component description() { return Component.literal(description); }
}
