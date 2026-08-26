package mcjty.rftoolsbuilder.constructor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Orange selection tool. First click marks corner one; second click marks the
 * opposite corner and writes a vanilla structure NBT into the schematics folder.
 */
public final class SchematicCreatorCardItem extends Item {
    private static final String HAS_FIRST = "MechanicaCreatorHasFirst";
    private static final String FIRST = "MechanicaCreatorFirst";
    private static final int MAX_BLOCKS = 262_144;

    public SchematicCreatorCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        ItemStack stack = context.getItemInHand();
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel server) || context.getPlayer() == null) return InteractionResult.PASS;

        if (context.getPlayer().isShiftKeyDown()) {
            clearFirst(stack);
            context.getPlayer().displayClientMessage(
                    Component.translatable("message.rftoolsbuilder.schematic_creator.cancelled"), true);
            return InteractionResult.SUCCESS;
        }

        CompoundTag tag = root(stack);
        BlockPos clicked = context.getClickedPos();
        if (!tag.getBooleanOr(HAS_FIRST, false)) {
            tag.putBoolean(HAS_FIRST, true);
            tag.putLong(FIRST, clicked.asLong());
            saveRoot(stack, tag);
            context.getPlayer().displayClientMessage(
                    Component.translatable("message.rftoolsbuilder.schematic_creator.first", clicked.toShortString()), true);
            return InteractionResult.SUCCESS;
        }

        BlockPos first = BlockPos.of(tag.getLongOr(FIRST, clicked.asLong()));
        int sx = Math.abs(clicked.getX() - first.getX()) + 1;
        int sy = Math.abs(clicked.getY() - first.getY()) + 1;
        int sz = Math.abs(clicked.getZ() - first.getZ()) + 1;
        long volume = (long) sx * sy * sz;
        if (volume > MAX_BLOCKS) {
            context.getPlayer().sendSystemMessage(
                    Component.translatable("message.rftoolsbuilder.schematic_creator.too_large", volume, MAX_BLOCKS));
            return InteractionResult.SUCCESS;
        }

        try {
            String fileName = writeStructure(server, first, clicked);
            clearFirst(stack);
            context.getPlayer().sendSystemMessage(
                    Component.translatable("message.rftoolsbuilder.schematic_creator.saved", fileName, sx, sy, sz));
        } catch (IOException | RuntimeException exception) {
            String message = exception.getMessage();
            context.getPlayer().sendSystemMessage(Component.translatable(
                    "message.rftoolsbuilder.schematic_creator.failed",
                    message == null || message.isBlank() ? exception.getClass().getSimpleName() : message));
        }
        return InteractionResult.SUCCESS;
    }

    private static String writeStructure(ServerLevel level, BlockPos a, BlockPos b) throws IOException {
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());

        CompoundTag root = new CompoundTag();
        root.put("size", intList(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1));
        ListTag palette = new ListTag();
        ListTag blocks = new ListTag();
        root.put("palette", palette);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());

        Map<BlockState, Integer> paletteIds = new LinkedHashMap<>();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    Integer stateId = paletteIds.get(state);
                    if (stateId == null) {
                        stateId = paletteIds.size();
                        paletteIds.put(state, stateId);
                        palette.add(writeBlockState(state));
                    }

                    CompoundTag block = new CompoundTag();
                    block.put("pos", intList(x - minX, y - minY, z - minZ));
                    block.putInt("state", stateId);
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        block.put("nbt", blockEntity.saveWithFullMetadata(level.registryAccess()));
                    }
                    blocks.add(block);
                }
            }
        }

        Path directory = SchematicFolderIndex.directory();
        Files.createDirectories(directory);
        String fileName = "mechanica_" + Long.toUnsignedString(System.currentTimeMillis()) + ".nbt";
        Path target = directory.resolve(fileName).normalize();
        if (!target.startsWith(directory)) throw new IOException("Invalid schematic path");
        try (OutputStream stream = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            NbtIo.writeCompressed(root, stream);
        }
        return fileName;
    }

    private static CompoundTag writeBlockState(BlockState state) {
        CompoundTag tag = new CompoundTag();
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        tag.putString("Name", id == null ? "minecraft:air" : id.toString());
        if (!state.getValues().isEmpty()) {
            CompoundTag properties = new CompoundTag();
            for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
                properties.putString(entry.getKey().getName(), propertyName(entry.getKey(), entry.getValue()));
            }
            tag.put("Properties", properties);
        }
        return tag;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String propertyName(Property property, Comparable value) {
        return property.getName(value);
    }

    private static ListTag intList(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }

    private static CompoundTag root(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static void saveRoot(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void clearFirst(ItemStack stack) {
        CompoundTag tag = root(stack);
        tag.remove(HAS_FIRST);
        tag.remove(FIRST);
        saveRoot(stack, tag);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> text, TooltipFlag flag) {
        CompoundTag tag = root(stack);
        if (tag.getBooleanOr(HAS_FIRST, false)) {
            text.accept(Component.translatable("tooltip.rftoolsbuilder.schematic_creator_card.selected",
                    BlockPos.of(tag.getLongOr(FIRST, BlockPos.ZERO.asLong())).toShortString()));
            text.accept(Component.translatable("tooltip.rftoolsbuilder.schematic_creator_card.second"));
        } else {
            text.accept(Component.translatable("tooltip.rftoolsbuilder.schematic_creator_card.first"));
        }
        text.accept(Component.translatable("tooltip.rftoolsbuilder.schematic_creator_card.cancel"));
        text.accept(Component.translatable("tooltip.rftoolsbuilder.schematic_creator_card.folder"));
    }
}
