package mcjty.rftoolsbuilder.constructor;

import mcjty.rftoolsbuilder.constructor.plan.ConstructionPlan;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;

/**
 * Compatibility facade retained for development builds that already reference
 * this class. The authoritative implementation is SchematicPipelineLoader,
 * which preserves declared bounds without expanding the schematic into AIR
 * entries and also imports the entity stage.
 */
@Deprecated(forRemoval = false)
public final class UniversalSchematicLoader {
    private UniversalSchematicLoader() {}

    public static ConstructionPlan load(SchematicFolderIndex.Entry entry, boolean includeAir) throws IOException {
        return SchematicPipelineLoader.load(entry, includeAir);
    }

    public static ConstructionPlan load(SchematicFolderIndex.Entry entry) throws IOException {
        return SchematicPipelineLoader.load(entry);
    }

    public static ConstructionPlan loadCard(ItemStack card, boolean includeAir) throws IOException {
        return SchematicPipelineLoader.loadCard(card, includeAir);
    }

    public static ConstructionPlan loadCard(ItemStack card) throws IOException {
        return SchematicPipelineLoader.loadCard(card);
    }
}
