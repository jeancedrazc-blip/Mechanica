package mcjty.rftoolsbuilder.constructor;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/** Blank orange media consumed by the Schematic Table to create a written blue card. */
public final class SchematicCreatorCardItem extends Item {
    public SchematicCreatorCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> text, TooltipFlag flag) {
        text.accept(Component.literal("Insert into a Schematic Table"));
        text.accept(Component.literal("Creates a written Schematic Card from a local file"));
    }
}
