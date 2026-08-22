package mcjty.rftoolsbuilder.constructor;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Built-in compatibility rules. Modded types use the public registries. */
final class ConstructorCompatibilityBootstrap {
    private static boolean registered;

    private ConstructorCompatibilityBootstrap() {}

    static synchronized void registerDefaults() {
        if (registered) return;
        registered = true;

        // Cosmetic-only data. Container/content fields are intentionally not copied.
        registerSafe("minecraft:banner", "patterns", "CustomName", "custom_name");
        registerSafe("minecraft:skull", "profile", "note_block_sound", "CustomName", "custom_name");
        registerSafe("minecraft:decorated_pot", "sherds", "CustomName", "custom_name");
        registerSafe("minecraft:beacon", "primary_effect", "secondary_effect", "CustomName", "custom_name");
    }

    private static void registerSafe(String id, String... allowedKeys) {
        BlockEntityType<?> type;
        try {
            type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(Identifier.parse(id));
        } catch (RuntimeException ignored) {
            return;
        }
        if (type == null) return;
        ConstructorSafeBlockEntityData.register(type, (state, source) -> copyAllowed(source, allowedKeys));
    }

    private static CompoundTag copyAllowed(CompoundTag source, String[] keys) {
        CompoundTag result = new CompoundTag();
        for (String key : keys) {
            Tag value = source.get(key);
            if (value != null) result.put(key, value.copy());
        }
        return result;
    }
}
