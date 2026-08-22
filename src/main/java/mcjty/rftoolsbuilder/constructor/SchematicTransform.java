package mcjty.rftoolsbuilder.constructor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Discrete schematic transform shared by client preview and server printing.
 * The anchor is the minimum corner of the transformed footprint.
 */
public record SchematicTransform(BlockPos anchor, int rotationQuarterTurns, int mirrorMode, int sizeX, int sizeY, int sizeZ) {
    public SchematicTransform {
        anchor = anchor == null ? BlockPos.ZERO : anchor.immutable();
        rotationQuarterTurns = Math.floorMod(rotationQuarterTurns, 4);
        mirrorMode = Math.max(0, Math.min(2, mirrorMode));
        sizeX = Math.max(0, sizeX);
        sizeY = Math.max(0, sizeY);
        sizeZ = Math.max(0, sizeZ);
    }

    public Rotation vanillaRotation() {
        return switch (rotationQuarterTurns) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public Mirror vanillaMirror() {
        return switch (mirrorMode) {
            case 1 -> Mirror.LEFT_RIGHT;
            case 2 -> Mirror.FRONT_BACK;
            default -> Mirror.NONE;
        };
    }

    public int transformedSizeX() { return (rotationQuarterTurns & 1) == 0 ? sizeX : sizeZ; }
    public int transformedSizeZ() { return (rotationQuarterTurns & 1) == 0 ? sizeZ : sizeX; }

    public BlockPos transformRelative(BlockPos source) {
        int x = source.getX();
        int y = source.getY();
        int z = source.getZ();

        // Match vanilla Mirror semantics: LEFT_RIGHT flips Z, FRONT_BACK flips X.
        if (mirrorMode == 1) z = sizeZ - 1 - z;
        if (mirrorMode == 2) x = sizeX - 1 - x;

        int tx;
        int tz;
        switch (rotationQuarterTurns) {
            case 1 -> {
                tx = sizeZ - 1 - z;
                tz = x;
            }
            case 2 -> {
                tx = sizeX - 1 - x;
                tz = sizeZ - 1 - z;
            }
            case 3 -> {
                tx = z;
                tz = sizeX - 1 - x;
            }
            default -> {
                tx = x;
                tz = z;
            }
        }
        return new BlockPos(tx, y, tz);
    }

    /** Continuous equivalent used for schematic entities. */
    public Vec3 transformRelative(Vec3 source) {
        double x = source.x;
        double y = source.y;
        double z = source.z;

        // Points live on footprint boundaries (0..size), unlike block indices (0..size-1).
        if (mirrorMode == 1) z = sizeZ - z;
        if (mirrorMode == 2) x = sizeX - x;

        double tx;
        double tz;
        switch (rotationQuarterTurns) {
            case 1 -> {
                tx = sizeZ - z;
                tz = x;
            }
            case 2 -> {
                tx = sizeX - x;
                tz = sizeZ - z;
            }
            case 3 -> {
                tx = z;
                tz = sizeX - x;
            }
            default -> {
                tx = x;
                tz = z;
            }
        }
        return new Vec3(tx, y, tz);
    }

    public BlockPos transformWorld(BlockPos source) { return anchor.offset(transformRelative(source)); }

    public Vec3 transformWorld(Vec3 source) {
        Vec3 transformed = transformRelative(source);
        return transformed.add(anchor.getX(), anchor.getY(), anchor.getZ());
    }

    public BlockState transformState(BlockState source) {
        BlockState result = source;
        Mirror mirror = vanillaMirror();
        if (mirror != Mirror.NONE) result = result.mirror(mirror);
        Rotation rotation = vanillaRotation();
        if (rotation != Rotation.NONE) result = result.rotate(rotation);
        return result;
    }

    public SchematicTransform move(int dx, int dy, int dz) {
        return new SchematicTransform(anchor.offset(dx, dy, dz), rotationQuarterTurns, mirrorMode, sizeX, sizeY, sizeZ);
    }

    /** Rotate around the current footprint center instead of snapping around the minimum corner. */
    public SchematicTransform rotateKeepingCenter(int quarterTurns) {
        int nextRotation = Math.floorMod(rotationQuarterTurns + quarterTurns, 4);
        int oldX = transformedSizeX();
        int oldZ = transformedSizeZ();
        int newX = (nextRotation & 1) == 0 ? sizeX : sizeZ;
        int newZ = (nextRotation & 1) == 0 ? sizeZ : sizeX;

        long center2X = 2L * anchor.getX() + oldX - 1L;
        long center2Z = 2L * anchor.getZ() + oldZ - 1L;
        int nextX = (int) Math.floorDiv(center2X - (newX - 1L), 2L);
        int nextZ = (int) Math.floorDiv(center2Z - (newZ - 1L), 2L);
        BlockPos nextAnchor = new BlockPos(nextX, anchor.getY(), nextZ);
        return new SchematicTransform(nextAnchor, nextRotation, mirrorMode, sizeX, sizeY, sizeZ);
    }

    public SchematicTransform withMirror(int mode) {
        return new SchematicTransform(anchor, rotationQuarterTurns, mode, sizeX, sizeY, sizeZ);
    }
}
