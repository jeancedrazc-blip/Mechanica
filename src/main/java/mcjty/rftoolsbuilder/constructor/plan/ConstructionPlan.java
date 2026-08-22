package mcjty.rftoolsbuilder.constructor.plan;

import mcjty.rftoolsbuilder.constructor.ConstructorRequirement;
import mcjty.rftoolsbuilder.constructor.ConstructorRequirementRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Format-neutral schematic consumed by both preview and Constructor printing.
 * Every adapter is normalized to one zero-based coordinate space. When a format
 * exposes declared bounds, those bounds are preserved even if their outer
 * layers contain only air.
 */
public final class ConstructionPlan {
    private final List<ConstructionEntry> entries;
    private final List<ConstructionEntityEntry> entities;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    public ConstructionPlan(List<ConstructionEntry> sourceEntries) {
        this(sourceEntries, List.of());
    }

    public ConstructionPlan(List<ConstructionEntry> sourceEntries, List<ConstructionEntityEntry> sourceEntities) {
        Bounds inferred = inferBounds(sourceEntries, sourceEntities);
        if (inferred == null) {
            entries = List.of();
            entities = List.of();
            sizeX = sizeY = sizeZ = 0;
            return;
        }
        Normalized normalized = normalize(sourceEntries, sourceEntities, inferred.min(), inferred.sizeX(), inferred.sizeY(), inferred.sizeZ());
        entries = normalized.blocks();
        entities = normalized.entities();
        sizeX = inferred.sizeX();
        sizeY = inferred.sizeY();
        sizeZ = inferred.sizeZ();
    }

    /**
     * Construct a plan with bounds declared by the source format. sourceMin is
     * the minimum occupied coordinate of the declared cuboid, not necessarily
     * the first non-air block.
     */
    public ConstructionPlan(List<ConstructionEntry> sourceEntries, List<ConstructionEntityEntry> sourceEntities,
                            BlockPos sourceMin, int declaredSizeX, int declaredSizeY, int declaredSizeZ) {
        if (sourceMin == null) sourceMin = BlockPos.ZERO;
        if (declaredSizeX < 0 || declaredSizeY < 0 || declaredSizeZ < 0)
            throw new IllegalArgumentException("Negative schematic bounds");

        Normalized normalized = normalize(sourceEntries, sourceEntities, sourceMin,
                declaredSizeX, declaredSizeY, declaredSizeZ);
        entries = normalized.blocks();
        entities = normalized.entities();
        sizeX = declaredSizeX;
        sizeY = declaredSizeY;
        sizeZ = declaredSizeZ;
    }

    private static Normalized normalize(List<ConstructionEntry> sourceEntries,
                                        List<ConstructionEntityEntry> sourceEntities,
                                        BlockPos min, int sx, int sy, int sz) {
        ArrayList<ConstructionEntry> blocks = new ArrayList<>();
        if (sourceEntries != null) {
            for (ConstructionEntry entry : sourceEntries) {
                if (entry == null || entry.relativePos() == null || entry.sourceState() == null) continue;
                BlockPos p = entry.relativePos();
                blocks.add(new ConstructionEntry(
                        new BlockPos(p.getX() - min.getX(), p.getY() - min.getY(), p.getZ() - min.getZ()),
                        entry.sourceState(), entry.blockEntityDataCopy()));
            }
        }

        ArrayList<ConstructionEntityEntry> normalizedEntities = new ArrayList<>();
        if (sourceEntities != null) {
            for (ConstructionEntityEntry entity : sourceEntities) {
                if (entity == null || entity.relativePos() == null || entity.entityData() == null) continue;
                Vec3 p = entity.relativePos();
                normalizedEntities.add(new ConstructionEntityEntry(
                        new Vec3(p.x - min.getX(), p.y - min.getY(), p.z - min.getZ()),
                        entity.entityDataCopy()));
            }
        }

        blocks.sort(Comparator
                .comparingInt((ConstructionEntry e) -> e.relativePos().getY())
                .thenComparingInt(e -> e.relativePos().getZ())
                .thenComparingInt(e -> e.relativePos().getX()));
        normalizedEntities.sort(Comparator
                .comparingDouble((ConstructionEntityEntry e) -> e.relativePos().y)
                .thenComparingDouble(e -> e.relativePos().z)
                .thenComparingDouble(e -> e.relativePos().x));

        return new Normalized(List.copyOf(blocks), List.copyOf(normalizedEntities));
    }

    private static Bounds inferBounds(List<ConstructionEntry> sourceEntries, List<ConstructionEntityEntry> sourceEntities) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;

        if (sourceEntries != null) {
            for (ConstructionEntry entry : sourceEntries) {
                if (entry == null || entry.relativePos() == null) continue;
                BlockPos p = entry.relativePos();
                minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
                any = true;
            }
        }
        if (sourceEntities != null) {
            for (ConstructionEntityEntry entry : sourceEntities) {
                if (entry == null || entry.relativePos() == null) continue;
                Vec3 p = entry.relativePos();
                int x = (int) Math.floor(p.x), y = (int) Math.floor(p.y), z = (int) Math.floor(p.z);
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
                any = true;
            }
        }
        if (!any) return null;
        return new Bounds(new BlockPos(minX, minY, minZ), maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
    }

    private record Bounds(BlockPos min, int sizeX, int sizeY, int sizeZ) {}
    private record Normalized(List<ConstructionEntry> blocks, List<ConstructionEntityEntry> entities) {}

    public List<ConstructionEntry> entries() { return entries; }
    public List<ConstructionEntityEntry> entities() { return entities; }
    public int size() { return entries.size(); }
    public int blockCount() { return entries.size(); }
    public int entityCount() { return entities.size(); }
    public int totalTargets() { return blockCount() + entityCount(); }
    public ConstructionEntry get(int index) { return entries.get(index); }
    public ConstructionEntityEntry getEntity(int index) { return entities.get(index); }
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public long volume() { return (long) sizeX * sizeY * sizeZ; }
    public boolean hasEntities() { return !entities.isEmpty(); }

    /** Block material checklist after substitutions; entity requirements are added server-side. */
    public Map<Item, Integer> materialChecklist(BlockSubstitutionRules substitutions) {
        Map<Item, Integer> result = new LinkedHashMap<>();
        BlockSubstitutionRules rules = substitutions == null ? new BlockSubstitutionRules() : substitutions;
        for (ConstructionEntry entry : entries) {
            BlockState state = rules.apply(entry.sourceState());
            ConstructorRequirement requirement = ConstructorRequirementRegistry.resolve(state, entry.blockEntityDataCopy());
            if (requirement.isEmpty() || requirement.isInvalid()) continue;
            for (ConstructorRequirement.StackRequirement stackRequirement : requirement.requirements()) {
                if (stackRequirement.use() != ConstructorRequirement.Use.CONSUME || stackRequirement.stack().isEmpty()) continue;
                result.merge(stackRequirement.stack().getItem(), stackRequirement.stack().getCount(), Integer::sum);
            }
        }
        return result;
    }
}
