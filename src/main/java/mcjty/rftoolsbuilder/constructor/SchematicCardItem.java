package mcjty.rftoolsbuilder.constructor;

import mcjty.rftoolsbuilder.constructor.plan.BlockSubstitutionRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;

/** A portable reference to a validated server schematic plus its world deployment transform. */
public final class SchematicCardItem extends Item {
    /** Effectively unbounded for real schematics while still guarding malformed NBT. */
    public static final int MAX_REPLACEMENTS = 4096;
    public static final int SCHEMA_VERSION = 2;
    private static final String P = "QTSchematic";

    public SchematicCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    private static CompoundTag root(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static void saveRoot(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static void setSource(ItemStack stack, String displayName, String serverFile, String clientFile,
                                 String sourceType, String sha256, int sizeX, int sizeY, int sizeZ) {
        CompoundTag tag = root(stack);
        tag.putInt(P + "Schema", SCHEMA_VERSION);
        tag.putString(P + "SourceType", safe(sourceType));
        tag.putString(P + "SourceName", safe(displayName));
        tag.putString(P + "SourceFile", safe(serverFile));
        tag.putString(P + "ClientFile", safe(clientFile));
        tag.putString(P + "Sha256", safe(sha256));
        tag.putInt(P + "SizeX", Math.max(0, sizeX));
        tag.putInt(P + "SizeY", Math.max(0, sizeY));
        tag.putInt(P + "SizeZ", Math.max(0, sizeZ));
        tag.putBoolean(P + "Deployed", false);
        tag.putLong(P + "Anchor", BlockPos.ZERO.asLong());
        tag.putInt(P + "Rotation", 0);
        tag.putInt(P + "Mirror", 0);
        // Old dev.4 offset fields are intentionally removed during migration.
        tag.remove(P + "OffsetX");
        tag.remove(P + "OffsetY");
        tag.remove(P + "OffsetZ");
        saveRoot(stack, tag);
    }

    /** Compatibility overload for older development code. */
    public static void setSource(ItemStack stack, String displayName, String serverFile, String sourceType) {
        setSource(stack, displayName, serverFile, displayName, sourceType, "", 0, 0, 0);
    }

    public static void setSource(ItemStack stack, String fileName, String sourceType) {
        setSource(stack, fileName, fileName, fileName, sourceType, "", 0, 0, 0);
    }

    private static String safe(String value) { return value == null ? "" : value; }

    public static boolean hasSource(ItemStack stack) {
        return !sourceFile(stack).isBlank() && !sourceType(stack).isBlank();
    }

    public static boolean hasBounds(ItemStack stack) {
        return sizeX(stack) > 0 && sizeY(stack) > 0 && sizeZ(stack) > 0;
    }

    public static String sourceName(ItemStack stack) {
        String value = root(stack).getString(P + "SourceName").orElse("");
        return value.isBlank() ? "Empty Schematic Card" : value;
    }

    /** Server-authoritative uploaded file path. */
    public static String sourceFile(ItemStack stack) { return root(stack).getString(P + "SourceFile").orElse(""); }
    /** Original path relative to the client's schematics folder, used only for preview rendering. */
    public static String clientFile(ItemStack stack) {
        String value = root(stack).getString(P + "ClientFile").orElse("");
        return value.isBlank() ? sourceName(stack) : value;
    }
    public static String sourceType(ItemStack stack) { return root(stack).getString(P + "SourceType").orElse(""); }
    public static String sha256(ItemStack stack) { return root(stack).getString(P + "Sha256").orElse(""); }
    public static int sizeX(ItemStack stack) { return Math.max(0, root(stack).getIntOr(P + "SizeX", 0)); }
    public static int sizeY(ItemStack stack) { return Math.max(0, root(stack).getIntOr(P + "SizeY", 0)); }
    public static int sizeZ(ItemStack stack) { return Math.max(0, root(stack).getIntOr(P + "SizeZ", 0)); }
    public static int rotation(ItemStack stack) { return Math.floorMod(root(stack).getIntOr(P + "Rotation", 0), 4); }
    public static int mirror(ItemStack stack) { return Math.max(0, Math.min(2, root(stack).getIntOr(P + "Mirror", 0))); }
    public static boolean deployed(ItemStack stack) { return root(stack).getBooleanOr(P + "Deployed", false); }
    public static BlockPos anchor(ItemStack stack) { return BlockPos.of(root(stack).getLongOr(P + "Anchor", BlockPos.ZERO.asLong())); }

    public static SchematicTransform transform(ItemStack stack) {
        return new SchematicTransform(anchor(stack), rotation(stack), mirror(stack), sizeX(stack), sizeY(stack), sizeZ(stack));
    }

    public static void setDeployment(ItemStack stack, BlockPos anchor, int rotation, int mirror, boolean deployed) {
        CompoundTag tag = root(stack);
        tag.putLong(P + "Anchor", (anchor == null ? BlockPos.ZERO : anchor).asLong());
        tag.putInt(P + "Rotation", Math.floorMod(rotation, 4));
        tag.putInt(P + "Mirror", Math.max(0, Math.min(2, mirror)));
        tag.putBoolean(P + "Deployed", deployed);
        saveRoot(stack, tag);
    }

    public static void clearDeployment(ItemStack stack) {
        setDeployment(stack, BlockPos.ZERO, 0, 0, false);
    }

    /** Old prototype API retained only so older dev cards do not crash while migrating. */
    @Deprecated public static int offsetX(ItemStack stack) { return 0; }
    @Deprecated public static int offsetY(ItemStack stack) { return 0; }
    @Deprecated public static int offsetZ(ItemStack stack) { return 0; }
    @Deprecated public static void setConfig(ItemStack stack, int rotation, int mirror, int x, int y, int z) {
        setDeployment(stack, anchor(stack), rotation, mirror, deployed(stack));
    }

    public static int replacementCount(ItemStack stack) {
        return Math.max(0, Math.min(MAX_REPLACEMENTS, root(stack).getIntOr(P + "ReplacementCount", 0)));
    }

    /**
     * Stable signature for preview content. Placement fields are deliberately
     * excluded so moving a card never forces the schematic file to be parsed
     * again or makes the hologram flicker while the server confirms an anchor.
     */
    public static int replacementSignature(ItemStack stack) {
        CompoundTag tag = root(stack);
        int count = Math.max(0, Math.min(MAX_REPLACEMENTS, tag.getIntOr(P + "ReplacementCount", 0)));
        int hash = count;
        for (int i = 0; i < count; i++) {
            hash = 31 * hash + tag.getString(P + "ReplacementFrom" + i).orElse("").hashCode();
            hash = 31 * hash + tag.getString(P + "ReplacementTo" + i).orElse("").hashCode();
        }
        return hash;
    }

    public static boolean addReplacement(ItemStack stack, Block from, Block to) {
        Identifier fromId = BuiltInRegistries.BLOCK.getKey(from);
        Identifier toId = BuiltInRegistries.BLOCK.getKey(to);
        if (fromId == null || toId == null || from == to) return false;

        CompoundTag tag = root(stack);
        int count = Math.max(0, Math.min(MAX_REPLACEMENTS, tag.getIntOr(P + "ReplacementCount", 0)));
        String fromString = fromId.toString();
        for (int i = 0; i < count; i++) {
            String existing = tag.getString(P + "ReplacementFrom" + i).orElse("");
            if (existing.equals(fromString)) {
                tag.putString(P + "ReplacementTo" + i, toId.toString());
                saveRoot(stack, tag);
                return true;
            }
        }
        if (count >= MAX_REPLACEMENTS) return false;
        tag.putString(P + "ReplacementFrom" + count, fromString);
        tag.putString(P + "ReplacementTo" + count, toId.toString());
        tag.putInt(P + "ReplacementCount", count + 1);
        saveRoot(stack, tag);
        return true;
    }

    public static boolean removeReplacement(ItemStack stack, Block from) {
        Identifier fromId = BuiltInRegistries.BLOCK.getKey(from);
        if (fromId == null) return false;
        CompoundTag tag = root(stack);
        int count = Math.max(0, Math.min(MAX_REPLACEMENTS, tag.getIntOr(P + "ReplacementCount", 0)));
        for (int i = 0; i < count; i++) {
            if (!fromId.toString().equals(tag.getString(P + "ReplacementFrom" + i).orElse(""))) continue;
            for (int move = i; move < count - 1; move++) {
                tag.putString(P + "ReplacementFrom" + move,
                        tag.getString(P + "ReplacementFrom" + (move + 1)).orElse(""));
                tag.putString(P + "ReplacementTo" + move,
                        tag.getString(P + "ReplacementTo" + (move + 1)).orElse(""));
            }
            tag.remove(P + "ReplacementFrom" + (count - 1));
            tag.remove(P + "ReplacementTo" + (count - 1));
            tag.putInt(P + "ReplacementCount", count - 1);
            saveRoot(stack, tag);
            return true;
        }
        return false;
    }

    public static Block replacementFor(ItemStack stack, Block source) {
        Identifier sourceId = BuiltInRegistries.BLOCK.getKey(source);
        CompoundTag tag = root(stack);
        int count = Math.max(0, Math.min(MAX_REPLACEMENTS, tag.getIntOr(P + "ReplacementCount", 0)));
        for (int i = 0; i < count; i++) {
            if (!sourceId.toString().equals(tag.getString(P + "ReplacementFrom" + i).orElse(""))) continue;
            String target = tag.getString(P + "ReplacementTo" + i).orElse("");
            try {
                return BuiltInRegistries.BLOCK.getValue(Identifier.parse(target));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    public static void clearReplacements(ItemStack stack) {
        CompoundTag tag = root(stack);
        int count = Math.max(0, Math.min(MAX_REPLACEMENTS, tag.getIntOr(P + "ReplacementCount", 0)));
        for (int i = 0; i < count; i++) {
            tag.remove(P + "ReplacementFrom" + i);
            tag.remove(P + "ReplacementTo" + i);
        }
        tag.putInt(P + "ReplacementCount", 0);
        saveRoot(stack, tag);
    }

    public static void applyReplacements(ItemStack stack, BlockSubstitutionRules rules) {
        CompoundTag tag = root(stack);
        int count = Math.max(0, Math.min(MAX_REPLACEMENTS, tag.getIntOr(P + "ReplacementCount", 0)));
        for (int i = 0; i < count; i++) {
            String fromString = tag.getString(P + "ReplacementFrom" + i).orElse("");
            String toString = tag.getString(P + "ReplacementTo" + i).orElse("");
            if (fromString.isEmpty() || toString.isEmpty()) continue;
            try {
                Block from = BuiltInRegistries.BLOCK.getValue(Identifier.parse(fromString));
                Block to = BuiltInRegistries.BLOCK.getValue(Identifier.parse(toString));
                if (from != null && to != null) rules.replace(from, to.defaultBlockState());
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> text, TooltipFlag flag) {
        if (!hasSource(stack)) {
            text.accept(Component.literal("Empty — write a schematic at the Schematic Table"));
            return;
        }
        text.accept(Component.literal(sourceName(stack)));
        SchematicFolderIndex.Format format = SchematicFolderIndex.Format.fromId(sourceType(stack));
        text.accept(Component.literal("Format: " + (format == null ? sourceType(stack) : format.label())));
        if (hasBounds(stack)) text.accept(Component.literal("Size: " + sizeX(stack) + " × " + sizeY(stack) + " × " + sizeZ(stack)));
        text.accept(Component.literal(deployed(stack) ? "Positioned at " + anchor(stack).toShortString() : "Not positioned — deploy in world first"));
        text.accept(Component.literal(deployed(stack)
                ? "Sneak + right-click to edit placement"
                : "Right-click a block to deploy the live preview"));
        int replacements = replacementCount(stack);
        if (replacements > 0) text.accept(Component.literal("Replacements: " + replacements));
    }
}
