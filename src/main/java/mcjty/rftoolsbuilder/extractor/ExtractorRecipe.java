package mcjty.rftoolsbuilder.extractor;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import mcjty.rftoolsbuilder.RFToolsBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Datapack-driven Extractor process. The displayed sample is never consumed;
 * recipes only describe its environment, output and processing duration.
 */
public record ExtractorRecipe(
        Ingredient sample,
        Optional<Ingredient> catalyst,
        List<Ingredient> adjacent,
        ItemStackTemplate result,
        int ticks
) implements Recipe<ExtractorInput> {
    private static final StreamCodec<RegistryFriendlyByteBuf, List<Ingredient>> INGREDIENT_LIST_STREAM_CODEC =
            Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list());

    public static final MapCodec<ExtractorRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Ingredient.CODEC.fieldOf("sample").forGetter(ExtractorRecipe::sample),
            Ingredient.CODEC.optionalFieldOf("catalyst").forGetter(ExtractorRecipe::catalyst),
            Ingredient.CODEC.listOf().optionalFieldOf("adjacent", List.of()).forGetter(ExtractorRecipe::adjacent),
            ItemStackTemplate.CODEC.fieldOf("result").forGetter(ExtractorRecipe::result),
            Codec.intRange(1, 72_000).optionalFieldOf("ticks", 200).forGetter(ExtractorRecipe::ticks)
    ).apply(instance, ExtractorRecipe::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExtractorRecipe> STREAM_CODEC = StreamCodec.composite(
            Ingredient.CONTENTS_STREAM_CODEC, ExtractorRecipe::sample,
            Ingredient.OPTIONAL_CONTENTS_STREAM_CODEC, ExtractorRecipe::catalyst,
            INGREDIENT_LIST_STREAM_CODEC, ExtractorRecipe::adjacent,
            ItemStackTemplate.STREAM_CODEC, ExtractorRecipe::result,
            ByteBufCodecs.VAR_INT, ExtractorRecipe::ticks,
            ExtractorRecipe::new
    );

    @Override
    public boolean matches(ExtractorInput input, Level level) {
        if (!sample.test(input.sample())) return false;
        if (catalyst.isPresent() && !catalyst.get().test(input.catalyst())) return false;

        // Each required adjacent ingredient must claim a distinct horizontal neighbour.
        List<ItemStack> available = new ArrayList<>(input.adjacent());
        for (Ingredient requirement : adjacent) {
            int match = -1;
            for (int i = 0; i < available.size(); i++) {
                if (requirement.test(available.get(i))) {
                    match = i;
                    break;
                }
            }
            if (match < 0) return false;
            available.remove(match);
        }
        return true;
    }

    @Override
    public ItemStack assemble(ExtractorInput input) {
        return result.create();
    }

    @Override public boolean isSpecial() { return true; }
    @Override public boolean showNotification() { return false; }
    @Override public String group() { return ""; }
    @Override public PlacementInfo placementInfo() { return PlacementInfo.NOT_PLACEABLE; }
    @Override public RecipeBookCategory recipeBookCategory() { return RecipeBookCategories.CRAFTING_MISC; }

    @Override
    public RecipeSerializer<? extends Recipe<ExtractorInput>> getSerializer() {
        return RFToolsBuilder.EXTRACTOR_RECIPE_SERIALIZER.get();
    }

    @Override
    public RecipeType<? extends Recipe<ExtractorInput>> getType() {
        return RFToolsBuilder.EXTRACTOR_RECIPE_TYPE.get();
    }
}
