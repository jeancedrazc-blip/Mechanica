package mcjty.rftoolsbuilder.constructor;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public compatibility hook for schematic entity material requirements.
 * Built-in support is deliberately limited to entities that have a complete,
 * deterministic material cost. Unknown entities are INVALID until a
 * compatibility provider explicitly defines how they are paid for.
 */
public final class ConstructorEntityRequirementRegistry {
    @FunctionalInterface
    public interface EntityRequirementProvider {
        ConstructorRequirement get(Entity entity);
    }

    private static final Map<EntityType<?>, EntityRequirementProvider> ENTITY_TYPES = new ConcurrentHashMap<>();
    private static final List<EquipmentSlot> ARMOR_STAND_SLOTS = List.of(
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND,
            EquipmentSlot.FEET,
            EquipmentSlot.LEGS,
            EquipmentSlot.CHEST,
            EquipmentSlot.HEAD
    );

    private ConstructorEntityRequirementRegistry() {}

    public static void register(EntityType<?> type, EntityRequirementProvider provider) {
        if (type != null && provider != null) ENTITY_TYPES.put(type, provider);
    }

    public static ConstructorRequirement resolve(ServerLevel level, net.minecraft.nbt.CompoundTag entityData) {
        Entity entity = ConstructorEntityDataCompat.createDetached(level, entityData);
        return entity == null ? ConstructorRequirement.INVALID : resolve(entity);
    }

    public static ConstructorRequirement resolve(Entity entity) {
        if (entity == null || entity.getType().onlyOpCanSetNbt()) return ConstructorRequirement.INVALID;

        EntityRequirementProvider provider = ENTITY_TYPES.get(entity.getType());
        if (provider != null) {
            try {
                ConstructorRequirement result = provider.get(entity);
                return result == null ? ConstructorRequirement.INVALID : result;
            } catch (RuntimeException ignored) {
                return ConstructorRequirement.INVALID;
            }
        }

        if (entity instanceof ItemFrame frame) {
            ItemStack frameStack = new ItemStack(entity instanceof GlowItemFrame ? Items.GLOW_ITEM_FRAME : Items.ITEM_FRAME);
            ConstructorRequirement result = ConstructorRequirement.consume(frameStack, false);
            ItemStack displayed = frame.getItem();
            if (!displayed.isEmpty()) {
                result = result.union(ConstructorRequirement.consume(displayed.copyWithCount(1), true));
            }
            return result;
        }

        if (entity instanceof ArmorStand armorStand) {
            ArrayList<ConstructorRequirement.StackRequirement> requirements = new ArrayList<>();
            requirements.add(new ConstructorRequirement.StackRequirement(
                    new ItemStack(Items.ARMOR_STAND), ConstructorRequirement.Use.CONSUME, false));
            for (EquipmentSlot slot : ARMOR_STAND_SLOTS) {
                ItemStack stack = armorStand.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    requirements.add(new ConstructorRequirement.StackRequirement(
                            stack.copyWithCount(1), ConstructorRequirement.Use.CONSUME, true));
                }
            }
            return new ConstructorRequirement(requirements);
        }

        if (entity instanceof Painting) return ConstructorRequirement.consume(Items.PAINTING, 1);

        return ConstructorRequirement.INVALID;
    }
}
