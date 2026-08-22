package mcjty.rftoolsbuilder.constructor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.ArrayList;
import java.util.List;

/** Transactional material bridge across all six adjacent item capabilities. */
final class ConstructorMaterialAccess {
    record Result(boolean success, ItemStack placementStack, ItemStack missingStack) {
        static Result ok(ItemStack placement) { return new Result(true, placement == null ? ItemStack.EMPTY : placement.copy(), ItemStack.EMPTY); }
        static Result missing(ItemStack stack) { return new Result(false, ItemStack.EMPTY, stack == null ? ItemStack.EMPTY : stack.copy()); }
    }

    private ConstructorMaterialAccess() {}

    static Result simulate(ServerLevel level, BlockPos machinePos, ConstructorRequirement requirement) {
        return execute(level, machinePos, requirement, false);
    }

    static Result consume(ServerLevel level, BlockPos machinePos, ConstructorRequirement requirement) {
        return execute(level, machinePos, requirement, true);
    }

    /** Non-mutating snapshot used by written material tablets. */
    static int countAvailable(ServerLevel level, BlockPos machinePos, Item item) {
        if (item == null) return 0;
        long total = 0;
        for (ResourceHandler<ItemResource> handler : adjacentHandlers(level, machinePos)) {
            for (int slot = 0; slot < handler.size(); slot++) {
                ItemResource resource = handler.getResource(slot);
                if (resource == null || resource.isEmpty()) continue;
                ItemStack candidate = resource.toStack();
                if (!candidate.is(item)) continue;
                total += Math.max(0, handler.getAmountAsInt(slot));
                if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    private static Result execute(ServerLevel level, BlockPos machinePos, ConstructorRequirement requirement, boolean commit) {
        if (requirement == null || requirement.isInvalid()) return Result.missing(ItemStack.EMPTY);
        if (requirement.isEmpty()) return Result.ok(ItemStack.EMPTY);

        List<ResourceHandler<ItemResource>> handlers = adjacentHandlers(level, machinePos);
        if (handlers.isEmpty()) return Result.missing(requirement.requirements().get(0).stack());

        try (Transaction transaction = Transaction.openRoot()) {
            ItemStack placement = ItemStack.EMPTY;
            for (ConstructorRequirement.StackRequirement required : requirement.requirements()) {
                if (required.stack().isEmpty()) continue;
                if (required.use() == ConstructorRequirement.Use.CONSUME) {
                    int remaining = required.stack().getCount();
                    for (ResourceHandler<ItemResource> handler : handlers) {
                        for (int slot = 0; slot < handler.size() && remaining > 0; slot++) {
                            ItemResource resource = handler.getResource(slot);
                            if (resource == null || resource.isEmpty()) continue;
                            ItemStack candidate = resource.toStack();
                            if (!required.matches(candidate)) continue;
                            int available = handler.getAmountAsInt(slot);
                            if (available <= 0) continue;
                            int wanted = Math.min(remaining, available);
                            int extracted = handler.extract(slot, resource, wanted, transaction);
                            if (extracted <= 0) continue;
                            if (placement.isEmpty()) placement = resource.toStack(1);
                            remaining -= extracted;
                        }
                        if (remaining == 0) break;
                    }
                    if (remaining > 0) return Result.missing(required.stack());
                } else {
                    boolean damaged = damageOne(handlers, required, transaction);
                    if (!damaged) return Result.missing(required.stack());
                    if (placement.isEmpty()) placement = required.stack().copyWithCount(1);
                }
            }
            if (commit) transaction.commit();
            return Result.ok(placement);
        }
    }

    private static boolean damageOne(List<ResourceHandler<ItemResource>> handlers,
                                     ConstructorRequirement.StackRequirement required,
                                     Transaction transaction) {
        for (ResourceHandler<ItemResource> handler : handlers) {
            for (int slot = 0; slot < handler.size(); slot++) {
                ItemResource resource = handler.getResource(slot);
                if (resource == null || resource.isEmpty()) continue;
                ItemStack candidate = resource.toStack();
                if (!required.matches(candidate) || !candidate.isDamageableItem()) continue;
                if (handler.extract(slot, resource, 1, transaction) != 1) continue;

                ItemStack damaged = resource.toStack();
                damaged.setDamageValue(damaged.getDamageValue() + 1);
                if (damaged.getDamageValue() >= damaged.getMaxDamage()) return true;

                ItemResource damagedResource = ItemResource.of(damaged);
                if (handler.insert(damagedResource, 1, transaction) == 1) return true;
                for (ResourceHandler<ItemResource> other : handlers) {
                    if (other == handler) continue;
                    if (other.insert(damagedResource, 1, transaction) == 1) return true;
                }
                return false;
            }
        }
        return false;
    }

    private static List<ResourceHandler<ItemResource>> adjacentHandlers(ServerLevel level, BlockPos machinePos) {
        ArrayList<ResourceHandler<ItemResource>> result = new ArrayList<>(6);
        for (Direction direction : Direction.values()) {
            ResourceHandler<ItemResource> handler = level.getCapability(
                    Capabilities.Item.BLOCK,
                    machinePos.relative(direction),
                    direction.getOpposite()
            );
            if (handler != null && !result.contains(handler)) result.add(handler);
        }
        return result;
    }
}
