package mcjty.rftoolsbuilder.constructor;

import mcjty.rftoolsbuilder.constructor.plan.ConstructionEntityEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Entity stage of the Constructor printer.
 *
 * The Prepared record contains only immutable schematic data and material
 * requirements. No detached Entity instance is kept alive across the shot;
 * the authoritative entity is recreated from sanitized NBT at impact.
 */
public final class ConstructorEntitySupport {
    private ConstructorEntitySupport() {}

    @FunctionalInterface
    public interface RequirementProvider extends ConstructorEntityRequirementRegistry.EntityRequirementProvider {}

    public static void register(EntityType<?> type, RequirementProvider provider) {
        ConstructorEntityRequirementRegistry.register(type, provider);
    }

    public record Prepared(CompoundTag entityData, ConstructorRequirement requirement, Vec3 target,
                           SchematicTransform transform, ItemStack projectileStack) {
        public Prepared {
            entityData = entityData == null ? new CompoundTag() : entityData.copy();
            projectileStack = projectileStack == null ? ItemStack.EMPTY : projectileStack.copyWithCount(1);
        }

        public CompoundTag entityDataCopy() { return entityData.copy(); }
    }

    public static Prepared prepare(ServerLevel level, ConstructionEntityEntry entry, SchematicTransform transform) {
        if (level == null || entry == null || transform == null) return null;
        CompoundTag data = entry.entityDataCopy();
        if (data.isEmpty()) return null;

        ConstructorRequirement requirement = ConstructorEntityRequirementRegistry.resolve(level, data);
        if (requirement.isInvalid()) return null;
        Vec3 target = transform.transformWorld(entry.relativePos());
        Prepared prepared = new Prepared(data, requirement, target, transform, firstVisualStack(requirement));

        // The Create printer puts entities after deferred blocks. Validate
        // hanging/support-sensitive entities here, before FE or materials are
        // reserved, so an impossible target never consumes resources.
        return canSpawn(level, prepared) ? prepared : null;
    }

    public static boolean canSpawn(ServerLevel level, Prepared prepared) {
        return prepared != null && ConstructorEntityDataCompat.canMaterialize(
                level, prepared.entityDataCopy(), prepared.target(), prepared.transform());
    }

    public static boolean spawn(ServerLevel level, Prepared prepared) {
        return prepared != null && ConstructorEntityDataCompat.materialize(
                level, prepared.entityDataCopy(), prepared.target(), prepared.transform());
    }

    private static ItemStack firstVisualStack(ConstructorRequirement requirement) {
        if (requirement == null) return ItemStack.EMPTY;
        for (ConstructorRequirement.StackRequirement stack : requirement.requirements()) {
            if (!stack.stack().isEmpty()) return stack.stack().copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }
}
