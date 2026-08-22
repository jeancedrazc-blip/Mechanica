package mcjty.rftoolsbuilder.constructor;

import mcjty.rftoolsbuilder.constructor.plan.ConstructionEntry;
import mcjty.rftoolsbuilder.constructor.plan.ConstructionPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts external schematic formats into the Constructor's neutral plan.
 *
 * Supported adapters:
 * - Create / vanilla structure .nbt
 * - WorldEdit / Sponge .schem v1-v3
 * - Litematica .litematic
 * - legacy MCEdit/Schematica .schematic (best-effort vanilla numeric mapping)
 */
public final class SchematicPlanLoader {
    private static final long MAX_NBT_BYTES = 0x20000000L;
    private static final int MAX_BLOCKS = 16_000_000;

    private SchematicPlanLoader() {}

    public static ConstructionPlan load(SchematicFolderIndex.Entry entry) throws IOException {
        return load(entry, false);
    }

    public static ConstructionPlan load(SchematicFolderIndex.Entry entry, boolean includeAir) throws IOException {
        if (entry == null) throw new IOException("No schematic selected");
        return switch (entry.format()) {
            case VANILLA_NBT -> loadVanillaStructure(entry.fileName(), includeAir);
            case SPONGE_SCHEM -> loadSponge(entry.fileName(), includeAir);
            case LITEMATICA -> loadLitematic(entry.fileName(), includeAir);
            case LEGACY_SCHEMATIC -> loadLegacy(entry.fileName(), includeAir);
        };
    }

    public static ConstructionPlan loadCard(ItemStack card) throws IOException {
        return loadCard(card, false);
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
    // Create / vanilla structure NBT
    // ---------------------------------------------------------------------

    private static ConstructionPlan loadVanillaStructure(String fileName, boolean includeAir) throws IOException {
        CompoundTag root = readCompressed(fileName);
        ListTag paletteTag = root.getListOrEmpty("palette");
        if (paletteTag.isEmpty()) {
            // Some structure tools wrap the template in a root compound.
            CompoundTag nested = root.getCompoundOrEmpty("Schematic");
            if (!nested.isEmpty()) root = nested;
            paletteTag = root.getListOrEmpty("palette");
        }

        List<BlockState> palette = readCompoundPalette(paletteTag);
        if (palette.isEmpty()) throw new IOException("Structure has no palette: " + fileName);

        ListTag blocksTag = root.getListOrEmpty("blocks");
        ArrayList<ConstructionEntry> entries = new ArrayList<>(blocksTag.size());
        for (int i = 0; i < blocksTag.size(); i++) {
            if (!(blocksTag.get(i) instanceof CompoundTag blockTag)) continue;
            int stateIndex = blockTag.getIntOr("state", -1);
            if (stateIndex < 0 || stateIndex >= palette.size()) continue;
            BlockState state = palette.get(stateIndex);
            if (state.isAir() && !includeAir) continue;

            ListTag pos = blockTag.getListOrEmpty("pos");
            if (pos.size() < 3) continue;
            BlockPos relative = new BlockPos(
                    pos.getInt(0).orElse(0),
                    pos.getInt(1).orElse(0),
                    pos.getInt(2).orElse(0)
            );
            CompoundTag blockEntityData = blockTag.getCompoundOrEmpty("nbt");
            entries.add(new ConstructionEntry(relative, state, blockEntityData.isEmpty() ? null : blockEntityData));
        }
        return new ConstructionPlan(entries);
    }

    // ---------------------------------------------------------------------
    // Sponge / WorldEdit .schem v1-v3
    // ---------------------------------------------------------------------

    private static ConstructionPlan loadSponge(String fileName, boolean includeAir) throws IOException {
        CompoundTag root = readCompressed(fileName);
        CompoundTag schematic = root.getCompoundOrEmpty("Schematic");
        if (!schematic.isEmpty() && number(schematic, "Version", 0) >= 3) {
            return loadSpongeV3(fileName, schematic, includeAir);
        }
        return loadSpongeV1V2(fileName, root, includeAir);
    }

    private static ConstructionPlan loadSpongeV1V2(String fileName, CompoundTag root, boolean includeAir) throws IOException {
        int width = number(root, "Width", 0);
        int height = number(root, "Height", 0);
        int length = number(root, "Length", 0);
        int volume = checkedVolume(width, height, length, fileName);

        Map<Integer, BlockState> palette = readStringPalette(root.getCompoundOrEmpty("Palette"));
        byte[] data = bytes(root, "BlockData");
        int[] ids = decodeVarInts(data, volume);
        Map<BlockPos, CompoundTag> blockEntities = readSpongeBlockEntities(root.getListOrEmpty("BlockEntities"));

        ArrayList<ConstructionEntry> entries = new ArrayList<>(Math.min(volume, ids.length));
        for (int index = 0; index < volume && index < ids.length; index++) {
            BlockState state = palette.getOrDefault(ids[index], Blocks.AIR.defaultBlockState());
            if (state.isAir() && !includeAir) continue;
            BlockPos pos = positionFromLinear(width, length, index);
            entries.add(new ConstructionEntry(pos, state, blockEntities.get(pos)));
        }
        return new ConstructionPlan(entries);
    }

    private static ConstructionPlan loadSpongeV3(String fileName, CompoundTag root, boolean includeAir) throws IOException {
        int width = number(root, "Width", 0);
        int height = number(root, "Height", 0);
        int length = number(root, "Length", 0);
        int volume = checkedVolume(width, height, length, fileName);

        CompoundTag blocks = root.getCompoundOrEmpty("Blocks");
        Map<Integer, BlockState> palette = readStringPalette(blocks.getCompoundOrEmpty("Palette"));
        int[] ids = decodeVarInts(bytes(blocks, "Data"), volume);
        Map<BlockPos, CompoundTag> blockEntities = readSpongeBlockEntities(blocks.getListOrEmpty("BlockEntities"));

        ArrayList<ConstructionEntry> entries = new ArrayList<>(Math.min(volume, ids.length));
        for (int index = 0; index < volume && index < ids.length; index++) {
            BlockState state = palette.getOrDefault(ids[index], Blocks.AIR.defaultBlockState());
            if (state.isAir() && !includeAir) continue;
            BlockPos pos = positionFromLinear(width, length, index);
            entries.add(new ConstructionEntry(pos, state, blockEntities.get(pos)));
        }
        return new ConstructionPlan(entries);
    }

    private static Map<BlockPos, CompoundTag> readSpongeBlockEntities(ListTag list) {
        Map<BlockPos, CompoundTag> result = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof CompoundTag tag)) continue;
            BlockPos pos = intArrayPos(tag, "Pos");
            if (pos == null) {
                pos = new BlockPos(number(tag, "x", 0), number(tag, "y", 0), number(tag, "z", 0));
            }
            result.put(pos, tag.copy());
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Litematica .litematic
    // ---------------------------------------------------------------------

    private static ConstructionPlan loadLitematic(String fileName, boolean includeAir) throws IOException {
        CompoundTag root = readCompressed(fileName);
        CompoundTag regions = root.getCompoundOrEmpty("Regions");
        if (regions.isEmpty()) throw new IOException("Litematic has no Regions: " + fileName);

        LinkedHashMap<BlockPos, ConstructionEntry> byPosition = new LinkedHashMap<>();
        for (String regionName : regions.keySet()) {
            CompoundTag region = regions.getCompoundOrEmpty(regionName);
            if (region.isEmpty()) continue;

            BlockPos regionPos = compoundPos(region.getCompoundOrEmpty("Position"));
            BlockPos signedSize = compoundPos(region.getCompoundOrEmpty("Size"));
            int sx = Math.abs(signedSize.getX());
            int sy = Math.abs(signedSize.getY());
            int sz = Math.abs(signedSize.getZ());
            int volume = checkedVolume(sx, sy, sz, fileName + "/" + regionName);

            List<BlockState> palette = readCompoundPalette(region.getListOrEmpty("BlockStatePalette"));
            if (palette.isEmpty()) continue;
            long[] packed = longs(region, "BlockStates");
            if (packed.length == 0) continue;
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));

            Map<BlockPos, CompoundTag> blockEntities = readLitematicBlockEntities(region.getListOrEmpty("TileEntities"));
            int signX = signedSize.getX() < 0 ? -1 : 1;
            int signY = signedSize.getY() < 0 ? -1 : 1;
            int signZ = signedSize.getZ() < 0 ? -1 : 1;

            for (int index = 0; index < volume; index++) {
                int paletteIndex = packedValue(packed, bits, index);
                BlockState state = paletteIndex >= 0 && paletteIndex < palette.size()
                        ? palette.get(paletteIndex) : Blocks.AIR.defaultBlockState();
                if (state.isAir() && !includeAir) continue;

                BlockPos local = positionFromLinear(sx, sz, index);
                BlockPos relative = regionPos.offset(
                        local.getX() * signX,
                        local.getY() * signY,
                        local.getZ() * signZ
                );
                CompoundTag be = blockEntities.get(local);
                if (be == null) be = blockEntities.get(relative);
                byPosition.put(relative, new ConstructionEntry(relative, state, be));
            }
        }
        return new ConstructionPlan(new ArrayList<>(byPosition.values()));
    }

    private static Map<BlockPos, CompoundTag> readLitematicBlockEntities(ListTag list) {
        Map<BlockPos, CompoundTag> result = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof CompoundTag tag)) continue;
            BlockPos pos = new BlockPos(number(tag, "x", 0), number(tag, "y", 0), number(tag, "z", 0));
            result.put(pos, tag.copy());
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Legacy MCEdit / Schematica .schematic
    // ---------------------------------------------------------------------

    private static ConstructionPlan loadLegacy(String fileName, boolean includeAir) throws IOException {
        CompoundTag root = readCompressed(fileName);
        int width = number(root, "Width", 0);
        int height = number(root, "Height", 0);
        int length = number(root, "Length", 0);
        int volume = checkedVolume(width, height, length, fileName);

        byte[] blocks = bytes(root, "Blocks");
        byte[] data = bytes(root, "Data");
        byte[] addBlocks = bytes(root, "AddBlocks");
        if (blocks.length < volume) throw new IOException("Legacy schematic block array is truncated: " + fileName);

        ArrayList<ConstructionEntry> entries = new ArrayList<>(volume);
        for (int index = 0; index < volume; index++) {
            int id = blocks[index] & 0xFF;
            if ((index >> 1) < addBlocks.length) {
                int extra = (index & 1) == 0 ? (addBlocks[index >> 1] & 0x0F) : ((addBlocks[index >> 1] >> 4) & 0x0F);
                id |= extra << 8;
            }
            int meta = index < data.length ? data[index] & 0x0F : 0;
            BlockState state = legacyState(id, meta);
            if (state.isAir() && !includeAir) continue;
            entries.add(new ConstructionEntry(positionFromLinear(width, length, index), state));
        }
        return new ConstructionPlan(entries);
    }

    /**
     * Best-effort vanilla legacy ID mapping. Modded numeric IDs from old 1.12-era
     * modpacks are inherently not portable without the original registry map.
     */
    private static BlockState legacyState(int id, int data) {
        String name = switch (id) {
            case 0 -> "minecraft:air";
            case 1 -> data == 1 ? "minecraft:granite" : data == 2 ? "minecraft:polished_granite"
                    : data == 3 ? "minecraft:diorite" : data == 4 ? "minecraft:polished_diorite"
                    : data == 5 ? "minecraft:andesite" : data == 6 ? "minecraft:polished_andesite" : "minecraft:stone";
            case 2 -> "minecraft:grass_block";
            case 3 -> data == 1 ? "minecraft:coarse_dirt" : data == 2 ? "minecraft:podzol" : "minecraft:dirt";
            case 4 -> "minecraft:cobblestone";
            case 5 -> switch (data & 7) { case 1 -> "minecraft:spruce_planks"; case 2 -> "minecraft:birch_planks"; case 3 -> "minecraft:jungle_planks"; case 4 -> "minecraft:acacia_planks"; case 5 -> "minecraft:dark_oak_planks"; default -> "minecraft:oak_planks"; };
            case 7 -> "minecraft:bedrock";
            case 8, 9 -> "minecraft:water";
            case 10, 11 -> "minecraft:lava";
            case 12 -> data == 1 ? "minecraft:red_sand" : "minecraft:sand";
            case 13 -> "minecraft:gravel";
            case 14 -> "minecraft:gold_ore";
            case 15 -> "minecraft:iron_ore";
            case 16 -> "minecraft:coal_ore";
            case 17 -> switch (data & 3) { case 1 -> "minecraft:spruce_log"; case 2 -> "minecraft:birch_log"; case 3 -> "minecraft:jungle_log"; default -> "minecraft:oak_log"; };
            case 18 -> switch (data & 3) { case 1 -> "minecraft:spruce_leaves"; case 2 -> "minecraft:birch_leaves"; case 3 -> "minecraft:jungle_leaves"; default -> "minecraft:oak_leaves"; };
            case 20 -> "minecraft:glass";
            case 21 -> "minecraft:lapis_ore";
            case 22 -> "minecraft:lapis_block";
            case 24 -> data == 1 ? "minecraft:chiseled_sandstone" : data == 2 ? "minecraft:cut_sandstone" : "minecraft:sandstone";
            case 30 -> "minecraft:cobweb";
            case 35 -> legacyColor(data) + "_wool";
            case 41 -> "minecraft:gold_block";
            case 42 -> "minecraft:iron_block";
            case 45 -> "minecraft:bricks";
            case 46 -> "minecraft:tnt";
            case 47 -> "minecraft:bookshelf";
            case 48 -> "minecraft:mossy_cobblestone";
            case 49 -> "minecraft:obsidian";
            case 50 -> "minecraft:torch";
            case 52 -> "minecraft:spawner";
            case 54 -> "minecraft:chest";
            case 56 -> "minecraft:diamond_ore";
            case 57 -> "minecraft:diamond_block";
            case 58 -> "minecraft:crafting_table";
            case 61, 62 -> "minecraft:furnace";
            case 73, 74 -> "minecraft:redstone_ore";
            case 79 -> "minecraft:ice";
            case 80 -> "minecraft:snow_block";
            case 82 -> "minecraft:clay";
            case 87 -> "minecraft:netherrack";
            case 88 -> "minecraft:soul_sand";
            case 89 -> "minecraft:glowstone";
            case 98 -> data == 1 ? "minecraft:mossy_stone_bricks" : data == 2 ? "minecraft:cracked_stone_bricks" : data == 3 ? "minecraft:chiseled_stone_bricks" : "minecraft:stone_bricks";
            case 103 -> "minecraft:melon";
            case 112 -> "minecraft:nether_bricks";
            case 121 -> "minecraft:end_stone";
            case 129 -> "minecraft:emerald_ore";
            case 133 -> "minecraft:emerald_block";
            case 152 -> "minecraft:redstone_block";
            case 155 -> data == 1 ? "minecraft:chiseled_quartz_block" : "minecraft:quartz_block";
            case 159 -> legacyColor(data) + "_terracotta";
            case 165 -> "minecraft:slime_block";
            case 168 -> data == 1 ? "minecraft:prismarine_bricks" : data == 2 ? "minecraft:dark_prismarine" : "minecraft:prismarine";
            case 169 -> "minecraft:sea_lantern";
            case 172 -> "minecraft:terracotta";
            case 173 -> "minecraft:coal_block";
            case 174 -> "minecraft:packed_ice";
            case 179 -> data == 1 ? "minecraft:chiseled_red_sandstone" : data == 2 ? "minecraft:cut_red_sandstone" : "minecraft:red_sandstone";
            case 201 -> "minecraft:purpur_block";
            case 206 -> "minecraft:end_stone_bricks";
            case 213 -> "minecraft:magma_block";
            case 214 -> "minecraft:nether_wart_block";
            case 215 -> "minecraft:red_nether_bricks";
            case 216 -> "minecraft:bone_block";
            case 251 -> legacyColor(data) + "_concrete";
            case 252 -> legacyColor(data) + "_concrete_powder";
            default -> "minecraft:air";
        };
        return stateById(name);
    }

    private static String legacyColor(int data) {
        String color = switch (data & 15) {
            case 1 -> "orange"; case 2 -> "magenta"; case 3 -> "light_blue"; case 4 -> "yellow";
            case 5 -> "lime"; case 6 -> "pink"; case 7 -> "gray"; case 8 -> "light_gray";
            case 9 -> "cyan"; case 10 -> "purple"; case 11 -> "blue"; case 12 -> "brown";
            case 13 -> "green"; case 14 -> "red"; case 15 -> "black"; default -> "white";
        };
        return "minecraft:" + color;
    }

    // ---------------------------------------------------------------------
    // Shared decoding helpers
    // ---------------------------------------------------------------------

    private static List<BlockState> readCompoundPalette(ListTag paletteTag) {
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            if (paletteTag.get(i) instanceof CompoundTag stateTag) palette.add(readState(stateTag));
            else palette.add(Blocks.AIR.defaultBlockState());
        }
        return palette;
    }

    private static Map<Integer, BlockState> readStringPalette(CompoundTag paletteTag) {
        Map<Integer, BlockState> result = new LinkedHashMap<>();
        for (String stateString : paletteTag.keySet()) {
            int paletteId = number(paletteTag, stateString, -1);
            if (paletteId >= 0) result.put(paletteId, parseStateString(stateString));
        }
        return result;
    }

    private static BlockState readState(CompoundTag stateTag) {
        String id = stateTag.getString("Name").orElse("minecraft:air");
        BlockState state = stateById(id);
        Block block = state.getBlock();
        CompoundTag properties = stateTag.getCompoundOrEmpty("Properties");
        for (String key : properties.keySet()) {
            Property<?> property = block.getStateDefinition().getProperty(key);
            if (property == null) continue;
            state = applyProperty(state, property, properties.getString(key).orElse(""));
        }
        return state;
    }

    private static BlockState parseStateString(String value) {
        if (value == null || value.isBlank()) return Blocks.AIR.defaultBlockState();
        int bracket = value.indexOf('[');
        String id = bracket < 0 ? value : value.substring(0, bracket);
        BlockState state = stateById(id);
        Block block = state.getBlock();
        if (bracket < 0 || !value.endsWith("]")) return state;

        String body = value.substring(bracket + 1, value.length() - 1);
        if (body.isBlank()) return state;
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq).trim();
            String propertyValue = pair.substring(eq + 1).trim();
            Property<?> property = block.getStateDefinition().getProperty(key);
            if (property != null) state = applyProperty(state, property, propertyValue);
        }
        return state;
    }

    private static BlockState stateById(String id) {
        try {
            Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id.toLowerCase(Locale.ROOT)));
            return block == null ? Blocks.AIR.defaultBlockState() : block.defaultBlockState();
        } catch (RuntimeException ignored) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
    }

    private static int number(CompoundTag tag, String key, int fallback) {
        Tag value = tag.get(key);
        return value instanceof NumericTag numeric ? numeric.intValue() : fallback;
    }

    private static byte[] bytes(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        return value instanceof ByteArrayTag array ? array.getAsByteArray() : new byte[0];
    }

    private static int[] ints(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        return value instanceof IntArrayTag array ? array.getAsIntArray() : new int[0];
    }

    private static long[] longs(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        return value instanceof LongArrayTag array ? array.getAsLongArray() : new long[0];
    }

    private static BlockPos intArrayPos(CompoundTag tag, String key) {
        int[] values = ints(tag, key);
        return values.length >= 3 ? new BlockPos(values[0], values[1], values[2]) : null;
    }

    private static BlockPos compoundPos(CompoundTag tag) {
        return new BlockPos(number(tag, "x", 0), number(tag, "y", 0), number(tag, "z", 0));
    }

    private static int checkedVolume(int width, int height, int length, String label) throws IOException {
        if (width <= 0 || height <= 0 || length <= 0) throw new IOException("Invalid schematic dimensions: " + label);
        long volume = (long) width * height * length;
        if (volume > MAX_BLOCKS) throw new IOException("Schematic exceeds safety limit of " + MAX_BLOCKS + " blocks: " + label);
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
