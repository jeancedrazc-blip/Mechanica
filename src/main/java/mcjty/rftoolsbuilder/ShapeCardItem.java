package mcjty.rftoolsbuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/** Legacy Builder shape card configuration. */
public class ShapeCardItem extends Item {
    public static final int DEFAULT_SIZE_X = 16;
    public static final int DEFAULT_SIZE_Y = 64;
    public static final int DEFAULT_SIZE_Z = 16;
    public static final int DEFAULT_OFFSET_X = -8;
    public static final int DEFAULT_OFFSET_Y = -64;
    public static final int DEFAULT_OFFSET_Z = -8;

    private static final String[] KEYS = {"SizeX", "SizeY", "SizeZ", "OffsetX", "OffsetY", "OffsetZ"};
    private static final int[] DEFAULTS = {
            DEFAULT_SIZE_X, DEFAULT_SIZE_Y, DEFAULT_SIZE_Z,
            DEFAULT_OFFSET_X, DEFAULT_OFFSET_Y, DEFAULT_OFFSET_Z
    };

    public ShapeCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static int getField(ItemStack stack, int field) {
        if (field < 0 || field >= KEYS.length) return 0;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return DEFAULTS[field];
        CompoundTag tag = data.copyTag();
        return tag.getInt(KEYS[field]).orElse(DEFAULTS[field]);
    }

    public static void setField(ItemStack stack, int field, int value) {
        if (field < 0 || field >= KEYS.length) return;
        CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag root = existing == null ? new CompoundTag() : existing.copyTag();
        root.putInt(KEYS[field], value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, builder, flag);
        builder.accept(Component.literal("Area: " + getField(stack, 0) + " × " + getField(stack, 1) + " × " + getField(stack, 2))
                .withStyle(ChatFormatting.AQUA));
        builder.accept(Component.literal("Offset: " + getField(stack, 3) + ", " + getField(stack, 4) + ", " + getField(stack, 5))
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
