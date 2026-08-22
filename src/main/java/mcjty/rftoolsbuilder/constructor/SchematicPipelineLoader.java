package mcjty.rftoolsbuilder.constructor;

import mcjty.rftoolsbuilder.constructor.plan.ConstructionEntityEntry;
import mcjty.rftoolsbuilder.constructor.plan.ConstructionEntry;
import mcjty.rftoolsbuilder.constructor.plan.ConstructionPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Complete format-neutral schematic loader used by preview and printing.
 *
 * The mature block adapters remain in {@link SchematicPlanLoader}; this layer
 * restores the source format's declared cuboid (including empty margins) and
 * imports entities into the exact same normalized coordinate space.
 */
public final class SchematicPipelineLoader {
    private static final long MAX_NBT_BYTES = 0x20000000L;

    private record Geometry(BlockPos declaredMin, int sizeX, int sizeY, int sizeZ, BlockPos populatedMin) {
        Geometry {
            declaredMin = declaredMin == null ? BlockPos.ZERO : declaredMin.immutable();
            populatedMin = populatedMin == null ? declaredMin : populatedMin.immutable();
            sizeX = Math.max(0, sizeX);
            sizeY = Math.max(0, sizeY);
            sizeZ = Math.max(0, sizeZ);
        }
    }

    private SchematicPipelineLoader() {}

    public static ConstructionPlan load(SchematicFolderIndex.Entry entry, boolean includeAir) throws IOException {
        if (entry == null) throw new IOException("No schematic selected");
        CompoundTag root = readCompressed(entry.fileName());
        Geometry geometry = readGeometry(entry.format(), root, includeAir);
        ConstructionPlan sparseBlocks = SchematicPlanLoader.load(entry, includeAir);
        List<ConstructionEntityEntry> entities = loadEntities(entry.format(), root, geometry);

        // SchematicPlanLoader historically normalized its sparse block list to
        // the first populated block. Restore those coordinates before applying
        // the source format's declared cuboid. This preserves leading/trailing
        // air without materializing millions of AIR entries in memory.
        int dx = geometry.populatedMin().getX() - geometry.declaredMin().getX();
        int dy = geometry.populatedMin().getY() - geometry.declaredMin().getY();
        int dz = geometry.populatedMin().getZ() - geometry.declaredMin().getZ();
        ArrayList<ConstructionEntry> restoredBlocks = new ArrayList<>(sparseBlocks.blockCount());
        for (ConstructionEntry entryBlock : sparseBlocks.entries()) {
            BlockPos p = entryBlock.relativePos().offset(dx, dy, dz);
            restoredBlocks.add(new ConstructionEntry(p, entryBlock.sourceState(), entryBlock.blockEntityDataCopy()));
        }

        // Entities are normalized into the same zero-based declared cuboid.
        return new ConstructionPlan(restoredBlocks, entities, BlockPos.ZERO,
                geometry.sizeX(), geometry.sizeY(), geometry.sizeZ());
    }

    public static ConstructionPlan load(SchematicFolderIndex.Entry entry) throws IOException {
        return load(entry, false);
    }

    public static ConstructionPlan loadCard(ItemStack card, boolean includeAir) throws IOException {
        String fileName = SchematicCardItem.sourceFile(card);
        String sourceType = SchematicCardItem.sourceType(card);
        if (fileName.isBlank()) throw new IOException("Schematic Card is empty");
        SchematicFolderIndex.Format format = SchematicFolderIndex.Format.fromId(sourceType);
        if (format == null) format = SchematicFolderIndex.Format.fromFileName(fileName);
        if (format == null) throw new IOException("Unsupported schematic type: " + sourceType);
        return load(new SchematicFolderIndex.Entry(fileName, format), includeAir);
    }

    public static ConstructionPlan loadCard(ItemStack card) throws IOException {
        return loadCard(card, false);
    }

    private static CompoundTag readCompressed(String fileName) throws IOException {
        Path path = SchematicFolderIndex.resolve(fileName);
        if (path == null || !Files.isRegularFile(path)) throw new IOException("Missing schematic: " + fileName);
        try (DataInputStream stream = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            CompoundTag root = NbtIo.readCompressed(stream, NbtAccounter.create(MAX_NBT_BYTES));
            if (root == null) throw new IOException("Invalid schematic NBT: " + fileName);
            return root;
        }
    }

    // ---------------------------------------------------------------------
    // Geometry — declared cuboid + populated minimum
    // ---------------------------------------------------------------------

    private static Geometry readGeometry(SchematicFolderIndex.Format format, CompoundTag root, boolean includeAir) throws IOException {
        return switch (format) {
            case VANILLA_NBT -> vanillaGeometry(root, includeAir);
            case SPONGE_SCHEM -> spongeGeometry(root, includeAir);
            case LITEMATICA -> litematicGeometry(root, includeAir);
            case LEGACY_SCHEMATIC -> legacyGeometry(root, includeAir);
        };
    }

    private static Geometry vanillaGeometry(CompoundTag originalRoot, boolean includeAir) throws IOException {
        CompoundTag root = originalRoot;
        ListTag palette = root.getListOrEmpty("palette");
        if (palette.isEmpty()) {
            CompoundTag nested = root.getCompoundOrEmpty("Schematic");
            if (!nested.isEmpty()) root = nested;
            palette = root.getListOrEmpty("palette");
        }

        BlockPos declaredMin = BlockPos.ZERO;
        ListTag size = root.getListOrEmpty("size");
        int sx = size.size() >= 3 ? size.getInt(0).orElse(0) : 0;
        int sy = size.size() >= 3 ? size.getInt(1).orElse(0) : 0;
        int sz = size.size() >= 3 ? size.getInt(2).orElse(0) : 0;
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            // Older/wrapped exporters occasionally omit size. In that case the
            // explicit block bounds become the declared cuboid, including a
            // non-zero source minimum if the exporter used one.
            int[] bounds = blockListBounds(root.getListOrEmpty("blocks"), null);
            if (bounds == null) throw new IOException("Structure has no declared or populated bounds");
            declaredMin = new BlockPos(bounds[0], bounds[1], bounds[2]);
            sx = bounds[3] - bounds[0] + 1;
            sy = bounds[4] - bounds[1] + 1;
            sz = bounds[5] - bounds[2] + 1;
        }

        if (includeAir) {
            // A structure NBT may still be sparse even when the caller wants
            // AIR targets. Restore from the minimum actually present in the
            // block list instead of assuming the file serialized AIR entries.
            int[] present = blockListBounds(root.getListOrEmpty("blocks"), null);
            BlockPos min = present == null ? declaredMin : new BlockPos(present[0], present[1], present[2]);
            return new Geometry(declaredMin, sx, sy, sz, min);
        }

        Set<Integer> airStates = new HashSet<>();
        for (int i = 0; i < palette.size(); i++) {
            if (!(palette.get(i) instanceof CompoundTag state)) continue;
            if (isAirName(state.getString("Name").orElse("minecraft:air"))) airStates.add(i);
        }
        int[] populated = blockListBounds(root.getListOrEmpty("blocks"), airStates);
        BlockPos min = populated == null ? declaredMin : new BlockPos(populated[0], populated[1], populated[2]);
        return new Geometry(declaredMin, sx, sy, sz, min);
    }

    private static int[] blockListBounds(ListTag blocks, Set<Integer> ignoredStates) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (int i = 0; i < blocks.size(); i++) {
            if (!(blocks.get(i) instanceof CompoundTag block)) continue;
            int state = block.getIntOr("state", -1);
            if (ignoredStates != null && ignoredStates.contains(state)) continue;
            ListTag pos = block.getListOrEmpty("pos");
            if (pos.size() < 3) continue;
            int x = pos.getInt(0).orElse(0);
            int y = pos.getInt(1).orElse(0);
            int z = pos.getInt(2).orElse(0);
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            any = true;
        }
        return any ? new int[]{minX, minY, minZ, maxX, maxY, maxZ} : null;
    }

    private static Geometry spongeGeometry(CompoundTag originalRoot, boolean includeAir) throws IOException {
        CompoundTag root = originalRoot;
        CompoundTag nested = originalRoot.getCompoundOrEmpty("Schematic");
        boolean v3 = !nested.isEmpty() && number(nested, "Version", 0) >= 3;
        if (v3) root = nested;

        int sx = number(root, "Width", 0);
        int sy = number(root, "Height", 0);
        int sz = number(root, "Length", 0);
        checkedVolume(sx, sy, sz, "Sponge schematic");
        if (includeAir) return new Geometry(BlockPos.ZERO, sx, sy, sz, BlockPos.ZERO);

        CompoundTag palette = v3 ? root.getCompoundOrEmpty("Blocks").getCompoundOrEmpty("Palette")
                : root.getCompoundOrEmpty("Palette");
        Set<Integer> airIds = new HashSet<>();
        for (String state : palette.keySet()) {
            if (isAirName(state)) {
                int id = number(palette, state, -1);
                if (id >= 0) airIds.add(id);
            }
        }
        byte[] data = v3 ? bytes(root.getCompoundOrEmpty("Blocks"), "Data") : bytes(root, "BlockData");
        int volume = sx * sy * sz;
        int[] ids = decodeVarInts(data, volume);
        BlockPos min = populatedMinLinear(ids, airIds, sx, sz);
        return new Geometry(BlockPos.ZERO, sx, sy, sz, min == null ? BlockPos.ZERO : min);
    }

    private static Geometry legacyGeometry(CompoundTag root, boolean includeAir) throws IOException {
        int sx = number(root, "Width", 0);
        int sy = number(root, "Height", 0);
        int sz = number(root, "Length", 0);
        int volume = checkedVolume(sx, sy, sz, "Legacy schematic");
        if (includeAir) return new Geometry(BlockPos.ZERO, sx, sy, sz, BlockPos.ZERO);

        byte[] blocks = bytes(root, "Blocks");
        byte[] addBlocks = bytes(root, "AddBlocks");
        if (blocks.length < volume) throw new IOException("Legacy schematic block array is truncated");
        BlockPos min = null;
        for (int i = 0; i < volume; i++) {
            int id = blocks[i] & 0xFF;
            if ((i >> 1) < addBlocks.length) {
                int extra = (i & 1) == 0 ? (addBlocks[i >> 1] & 0x0F) : ((addBlocks[i >> 1] >> 4) & 0x0F);
                id |= extra << 8;
            }
            if (id == 0) continue;
            BlockPos p = positionFromLinear(sx, sz, i);
            min = min == null ? p : new BlockPos(Math.min(min.getX(), p.getX()), Math.min(min.getY(), p.getY()), Math.min(min.getZ(), p.getZ()));
        }
        return new Geometry(BlockPos.ZERO, sx, sy, sz, min == null ? BlockPos.ZERO : min);
    }

    private static Geometry litematicGeometry(CompoundTag root, boolean includeAir) throws IOException {
        CompoundTag regions = root.getCompoundOrEmpty("Regions");
        if (regions.isEmpty()) throw new IOException("Litematic has no Regions");

        int declaredMinX = Integer.MAX_VALUE, declaredMinY = Integer.MAX_VALUE, declaredMinZ = Integer.MAX_VALUE;
        int declaredMaxX = Integer.MIN_VALUE, declaredMaxY = Integer.MIN_VALUE, declaredMaxZ = Integer.MIN_VALUE;
        int populatedMinX = Integer.MAX_VALUE, populatedMinY = Integer.MAX_VALUE, populatedMinZ = Integer.MAX_VALUE;
        boolean anyRegion = false;
        boolean anyPopulated = false;

        for (String regionName : regions.keySet()) {
            CompoundTag region = regions.getCompoundOrEmpty(regionName);
            if (region.isEmpty()) continue;
            BlockPos rp = compoundPos(region.getCompoundOrEmpty("Position"));
            BlockPos signed = compoundPos(region.getCompoundOrEmpty("Size"));
            int sx = Math.abs(signed.getX());
            int sy = Math.abs(signed.getY());
            int sz = Math.abs(signed.getZ());
            int volume = checkedVolume(sx, sy, sz, "Litematic region " + regionName);
            int signX = signed.getX() < 0 ? -1 : 1;
            int signY = signed.getY() < 0 ? -1 : 1;
            int signZ = signed.getZ() < 0 ? -1 : 1;

            int otherX = rp.getX() + signX * (sx - 1);
            int otherY = rp.getY() + signY * (sy - 1);
            int otherZ = rp.getZ() + signZ * (sz - 1);
            declaredMinX = Math.min(declaredMinX, Math.min(rp.getX(), otherX));
            declaredMinY = Math.min(declaredMinY, Math.min(rp.getY(), otherY));
            declaredMinZ = Math.min(declaredMinZ, Math.min(rp.getZ(), otherZ));
            declaredMaxX = Math.max(declaredMaxX, Math.max(rp.getX(), otherX));
            declaredMaxY = Math.max(declaredMaxY, Math.max(rp.getY(), otherY));
            declaredMaxZ = Math.max(declaredMaxZ, Math.max(rp.getZ(), otherZ));
            anyRegion = true;

            if (includeAir) continue;
            ListTag palette = region.getListOrEmpty("BlockStatePalette");
            Set<Integer> air = new HashSet<>();
            for (int i = 0; i < palette.size(); i++) {
                if (palette.get(i) instanceof CompoundTag state && isAirName(state.getString("Name").orElse("minecraft:air"))) air.add(i);
            }
            long[] packed = longs(region, "BlockStates");
            if (packed.length == 0) continue;
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            for (int index = 0; index < volume; index++) {
                int paletteIndex = packedValue(packed, bits, index);
                if (air.contains(paletteIndex)) continue;
                BlockPos local = positionFromLinear(sx, sz, index);
                int x = rp.getX() + local.getX() * signX;
                int y = rp.getY() + local.getY() * signY;
                int z = rp.getZ() + local.getZ() * signZ;
                populatedMinX = Math.min(populatedMinX, x);
                populatedMinY = Math.min(populatedMinY, y);
                populatedMinZ = Math.min(populatedMinZ, z);
                anyPopulated = true;
            }
        }

        if (!anyRegion) throw new IOException("Litematic contains no valid regions");
        BlockPos declaredMin = new BlockPos(declaredMinX, declaredMinY, declaredMinZ);
        int dx = declaredMaxX - declaredMinX + 1;
        int dy = declaredMaxY - declaredMinY + 1;
        int dz = declaredMaxZ - declaredMinZ + 1;
        BlockPos populatedMin = includeAir || !anyPopulated
                ? declaredMin
                : new BlockPos(populatedMinX, populatedMinY, populatedMinZ);
        return new Geometry(declaredMin, dx, dy, dz, populatedMin);
    }

    private static BlockPos populatedMinLinear(int[] ids, Set<Integer> airIds, int width, int length) {
        BlockPos min = null;
        for (int i = 0; i < ids.length; i++) {
            if (airIds.contains(ids[i])) continue;
            BlockPos p = positionFromLinear(width, length, i);
            min = min == null ? p : new BlockPos(Math.min(min.getX(), p.getX()), Math.min(min.getY(), p.getY()), Math.min(min.getZ(), p.getZ()));
        }
        return min;
    }

    // ---------------------------------------------------------------------
    // Entities
    // ---------------------------------------------------------------------

    private static List<ConstructionEntityEntry> loadEntities(SchematicFolderIndex.Format format, CompoundTag root, Geometry geometry) {
        return switch (format) {
            case VANILLA_NBT -> readVanillaEntities(root, geometry.declaredMin());
            case SPONGE_SCHEM -> readSpongeEntities(root);
            case LITEMATICA -> readLitematicEntities(root, geometry.declaredMin());
            case LEGACY_SCHEMATIC -> readLegacyEntities(root);
        };
    }

    private static List<ConstructionEntityEntry> readVanillaEntities(CompoundTag originalRoot, BlockPos declaredMin) {
        CompoundTag root = originalRoot;
        ListTag entities = root.getListOrEmpty("entities");
        if (entities.isEmpty()) {
            CompoundTag nested = root.getCompoundOrEmpty("Schematic");
            if (!nested.isEmpty()) {
                root = nested;
                entities = root.getListOrEmpty("entities");
            }
        }

        Vec3 sourceMin = new Vec3(declaredMin.getX(), declaredMin.getY(), declaredMin.getZ());
        ArrayList<ConstructionEntityEntry> result = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag wrapper)) continue;
            Vec3 stored = readVec(wrapper.getListOrEmpty("pos"));
            if (stored == null) continue;
            Vec3 relative = stored.subtract(sourceMin);
            CompoundTag data = wrapper.getCompoundOrEmpty("nbt").copy();
            if (data.isEmpty()) continue;
            normalizeEntityPayload(data, relative);
            result.add(new ConstructionEntityEntry(relative, data));
        }
        return result;
    }

    private static List<ConstructionEntityEntry> readSpongeEntities(CompoundTag originalRoot) {
        CompoundTag root = originalRoot;
        CompoundTag nested = originalRoot.getCompoundOrEmpty("Schematic");
        boolean v3 = !nested.isEmpty() && number(nested, "Version", 0) >= 3;
        if (v3) root = nested;

        Vec3 minimum = Vec3.ZERO;
        if (!v3) {
            int[] offset = intArray(root, "Offset");
            if (offset.length >= 3) minimum = new Vec3(offset[0], offset[1], offset[2]);
        }

        ArrayList<ConstructionEntityEntry> result = new ArrayList<>();
        ListTag entities = root.getListOrEmpty("Entities");
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag wrapper)) continue;
            Vec3 storedPos = readVec(wrapper.getListOrEmpty("Pos"));
            if (storedPos == null) continue;
            Vec3 relative = v3 ? storedPos : storedPos.subtract(minimum);

            CompoundTag data;
            if (v3) {
                data = wrapper.getCompoundOrEmpty("Data").copy();
            } else {
                data = wrapper.copy();
                data.remove("Id");
                data.remove("Pos");
            }
            String id = wrapper.getString("Id").orElse("");
            if (!id.isBlank()) data.putString("id", id);
            normalizeEntityPayload(data, relative);
            if (!data.isEmpty()) result.add(new ConstructionEntityEntry(relative, data));
        }
        return result;
    }

    private static List<ConstructionEntityEntry> readLitematicEntities(CompoundTag root, BlockPos declaredMin) {
        ArrayList<ConstructionEntityEntry> result = new ArrayList<>();
        int version = number(root, "Version", 2);
        Vec3 globalMin = new Vec3(declaredMin.getX(), declaredMin.getY(), declaredMin.getZ());
        CompoundTag regions = root.getCompoundOrEmpty("Regions");
        for (String regionName : regions.keySet()) {
            CompoundTag region = regions.getCompoundOrEmpty(regionName);
            if (region.isEmpty()) continue;
            CompoundTag regionPosTag = region.getCompoundOrEmpty("Position");
            Vec3 regionPos = new Vec3(number(regionPosTag, "x", 0), number(regionPosTag, "y", 0), number(regionPosTag, "z", 0));
            ListTag entities = region.getListOrEmpty("Entities");
            for (int i = 0; i < entities.size(); i++) {
                if (!(entities.get(i) instanceof CompoundTag wrapper)) continue;
                Vec3 local = readVec(wrapper.getListOrEmpty("Pos"));
                if (local == null) continue;
                CompoundTag data = version == 1 ? wrapper.getCompoundOrEmpty("EntityData").copy() : wrapper.copy();
                // Litematica stores entity positions relative to the region origin.
                // Rebase the region-space coordinate by the global declared
                // minimum so entities and blocks share one zero-based cuboid.
                Vec3 relative = local.add(regionPos).subtract(globalMin);
                normalizeEntityPayload(data, relative);
                if (!data.isEmpty()) result.add(new ConstructionEntityEntry(relative, data));
            }
        }
        return result;
    }

    private static List<ConstructionEntityEntry> readLegacyEntities(CompoundTag root) {
        ArrayList<ConstructionEntityEntry> result = new ArrayList<>();
        ListTag entities = root.getListOrEmpty("Entities");
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag data)) continue;
            Vec3 pos = readVec(data.getListOrEmpty("Pos"));
            if (pos == null) continue;
            CompoundTag copy = data.copy();
            normalizeEntityPayload(copy, pos);
            result.add(new ConstructionEntityEntry(pos, copy));
        }
        return result;
    }

    /** Remove captured identity and rewrite Pos to normalized schematic-relative coordinates. */
    private static void normalizeEntityPayload(CompoundTag data, Vec3 relative) {
        data.remove("UUID");
        data.remove("UUIDMost");
        data.remove("UUIDLeast");
        data.remove("Dimension");
        data.remove("PortalCooldown");
        data.remove("Passengers");
        data.remove("RootVehicle");
        data.remove("Leash");
        ListTag pos = new ListTag();
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(relative.x));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(relative.y));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(relative.z));
        data.put("Pos", pos);
        ListTag motion = new ListTag();
        motion.add(net.minecraft.nbt.DoubleTag.valueOf(0.0D));
        motion.add(net.minecraft.nbt.DoubleTag.valueOf(0.0D));
        motion.add(net.minecraft.nbt.DoubleTag.valueOf(0.0D));
        data.put("Motion", motion);
    }

    // ---------------------------------------------------------------------
    // NBT helpers
    // ---------------------------------------------------------------------

    private static boolean isAirName(String state) {
        if (state == null) return true;
        String id = state.toLowerCase(Locale.ROOT);
        int bracket = id.indexOf('[');
        if (bracket >= 0) id = id.substring(0, bracket);
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    private static Vec3 readVec(ListTag list) {
        if (list.size() < 3) return null;
        Tag x = list.get(0), y = list.get(1), z = list.get(2);
        if (!(x instanceof NumericTag nx) || !(y instanceof NumericTag ny) || !(z instanceof NumericTag nz)) return null;
        return new Vec3(nx.doubleValue(), ny.doubleValue(), nz.doubleValue());
    }

    private static int[] intArray(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        return value instanceof IntArrayTag ints ? ints.getAsIntArray() : new int[0];
    }

    private static byte[] bytes(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        return value instanceof ByteArrayTag array ? array.getAsByteArray() : new byte[0];
    }

    private static long[] longs(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        return value instanceof LongArrayTag array ? array.getAsLongArray() : new long[0];
    }

    private static int number(CompoundTag tag, String key, int fallback) {
        Tag value = tag.get(key);
        return value instanceof NumericTag numeric ? numeric.intValue() : fallback;
    }

    private static BlockPos compoundPos(CompoundTag tag) {
        return new BlockPos(number(tag, "x", 0), number(tag, "y", 0), number(tag, "z", 0));
    }

    private static int checkedVolume(int width, int height, int length, String label) throws IOException {
        if (width <= 0 || height <= 0 || length <= 0) throw new IOException("Invalid schematic dimensions: " + label);
        long volume = (long) width * height * length;
        if (volume > 16_000_000L) throw new IOException("Schematic exceeds safety limit of 16000000 blocks: " + label);
        return (int) volume;
    }

    private static BlockPos positionFromLinear(int width, int length, int index) {
        int layer = width * length;
        int y = index / layer;
        int inLayer = index % layer;
        int z = inLayer / width;
        int x = inLayer % width;
        return new BlockPos(x, y, z);
    }

    private static int[] decodeVarInts(byte[] data, int expected) throws IOException {
        int[] result = new int[expected];
        int count = 0;
        int offset = 0;
        while (offset < data.length && count < expected) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (offset >= data.length) throw new IOException("Truncated VarInt block data");
                int b = data[offset++] & 0xFF;
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 28) throw new IOException("Invalid VarInt in schematic block data");
            }
            result[count++] = value;
        }
        if (count < expected) throw new IOException("Schematic block data is shorter than its dimensions");
        return result;
    }

    private static int packedValue(long[] data, int bits, long index) {
        long mask = (1L << bits) - 1L;
        long startOffset = index * bits;
        int startArray = (int) (startOffset >>> 6);
        int endArray = (int) (((index + 1L) * bits - 1L) >>> 6);
        int startBit = (int) (startOffset & 63L);
        if (startArray < 0 || startArray >= data.length) return 0;
        if (startArray == endArray) return (int) ((data[startArray] >>> startBit) & mask);
        if (endArray < 0 || endArray >= data.length) return 0;
        int firstBits = 64 - startBit;
        return (int) ((data[startArray] >>> startBit | data[endArray] << firstBits) & mask);
    }
}
