package mcjty.rftoolsbuilder.extractor;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;

import java.util.List;

/** The live sample and surrounding blocks checked by an Extractor recipe. */
public record ExtractorInput(ItemStack sample, ItemStack catalyst, List<ItemStack> adjacent) implements RecipeInput {
    @Override
    public ItemStack getItem(int index) {
        if (index == 0) return sample;
        if (index == 1) return catalyst;
        int adjacentIndex = index - 2;
        if (adjacentIndex >= 0 && adjacentIndex < adjacent.size()) return adjacent.get(adjacentIndex);
        throw new IllegalArgumentException("No extractor item for index " + index);
    }

    @Override
    public int size() {
        return 2 + adjacent.size();
    }
}
