package mcjty.rftoolsbuilder.constructor.plan;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** One normalized schematic entity with a precise position relative to the plan origin. */
public record ConstructionEntityEntry(Vec3 relativePos, CompoundTag entityData) {
    public ConstructionEntityEntry {
        relativePos = relativePos == null ? Vec3.ZERO : relativePos;
        entityData = entityData == null ? new CompoundTag() : entityData.copy();
    }

    public CompoundTag entityDataCopy() {
        return entityData.copy();
    }
}
