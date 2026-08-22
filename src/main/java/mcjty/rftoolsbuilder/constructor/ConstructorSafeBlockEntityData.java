package mcjty.rftoolsbuilder.constructor;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in safe BlockEntity NBT bridge. Unknown BlockEntities intentionally do
 * not copy arbitrary payloads: inventories, tanks, energy and ownership data
 * must never be duplicated by a schematic printer.
 */
public final class ConstructorSafeBlockEntityData {
    @FunctionalInterface
    public interface Sanitizer {
        CompoundTag sanitize(BlockState state, CompoundTag source);
    }

    private static final Map<BlockEntityType<?>, Sanitizer> SANITIZERS = new ConcurrentHashMap<>();

    private ConstructorSafeBlockEntityData() {}

    public static void register(BlockEntityType<?> type, Sanitizer sanitizer) {
        if (type != null && sanitizer != null) SANITIZERS.put(type, sanitizer);
    }

    public static BlockEntityType<?> resolveType(CompoundTag raw) {
        if (raw == null || raw.isEmpty()) return null;
        String id = raw.getString("id").orElse("");
        if (id.isBlank()) id = raw.getString("Id").orElse("");
        if (id.isBlank()) return null;
        try {
            return BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(Identifier.parse(id));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static CompoundTag sanitize(BlockState state, CompoundTag raw) {
        if (raw == null || raw.isEmpty() || state == null || !state.hasBlockEntity()) return null;
        BlockEntityType<?> type = resolveType(raw);
        if (type == null) return null;
        Sanitizer sanitizer = SANITIZERS.get(type);
        if (sanitizer == null) return null;
        try {
            CompoundTag result = sanitizer.sanitize(state, raw.copy());
            if (result == null || result.isEmpty()) return null;
            result.remove("x");
            result.remove("y");
            result.remove("z");
            result.remove("id");
            result.remove("Id");
            return result;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
