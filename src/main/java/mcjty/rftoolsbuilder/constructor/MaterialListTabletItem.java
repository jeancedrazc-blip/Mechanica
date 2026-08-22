package mcjty.rftoolsbuilder.constructor;

import mcjty.rftoolsbuilder.constructor.client.MaterialListTabletClient;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;

/** Portable, scrollable bill of materials written by the Constructor. */
public final class MaterialListTabletItem extends Item {
    public MaterialListTabletItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            MaterialListTabletClient.open(player.getItemInHand(hand), hand);
        } else if (level instanceof ServerLevel server) {
            refreshFromLinkedConstructor(server, player.getItemInHand(hand));
            player.getInventory().setChanged();
        }
        return InteractionResult.SUCCESS;
    }

    static String refreshFromLinkedConstructor(ServerLevel level, ItemStack tablet) {
        CustomData data = tablet.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "BLANK";
        var tag = data.copyTag();
        String linkedDimension = tag.getString("QTConstructorDimension").orElse("");
        if (linkedDimension.isBlank() || tag.getLongOr("QTConstructorPos", Long.MIN_VALUE) == Long.MIN_VALUE) return "UNLINKED";
        if (!linkedDimension.equals(level.dimension().identifier().toString())) return "OTHER DIMENSION";
        long packed = tag.getLongOr("QTConstructorPos", Long.MIN_VALUE);
        if (packed == Long.MIN_VALUE) return "UNLINKED";
        BlockPos pos = BlockPos.of(packed);
        if (!level.hasChunkAt(pos)) return "CONSTRUCTOR OFFLINE";
        if (level.getBlockEntity(pos) instanceof ConstructorBlockEntity constructor) {
            return constructor.refreshTabletFromCurrentSchematic(tablet);
        }
        return "CONSTRUCTOR NOT FOUND";
    }

    static String refreshFromLinkedConstructor(ServerLevel level, ItemStack tablet, BlockPos playerPos) {
        CustomData data = tablet.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "BLANK";
        var tag = data.copyTag();
        if (tag.getString("QTConstructorDimension").orElse("").isBlank()
                || tag.getLongOr("QTConstructorPos", Long.MIN_VALUE) == Long.MIN_VALUE) {
            BlockPos nearest = findNearestConstructor(level, playerPos);
            if (nearest == null) return "UNLINKED";
            tag.putString("QTConstructorDimension", level.dimension().identifier().toString());
            tag.putLong("QTConstructorPos", nearest.asLong());
            tablet.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        return refreshFromLinkedConstructor(level, tablet);
    }

    private static BlockPos findNearestConstructor(ServerLevel level, BlockPos center) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = -8; y <= 8; y++) {
            for (int x = -16; x <= 16; x++) {
                for (int z = -16; z <= 16; z++) {
                    cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    double distance = x * x + y * y + z * z;
                    if (distance >= bestDistance || !level.hasChunkAt(cursor)) continue;
                    if (level.getBlockEntity(cursor) instanceof ConstructorBlockEntity) {
                        bestDistance = distance;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    public static boolean isWritten(ItemStack stack) {
        return stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
    }
}
