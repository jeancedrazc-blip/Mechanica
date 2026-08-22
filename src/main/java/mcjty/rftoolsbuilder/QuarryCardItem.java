package mcjty.rftoolsbuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Quarry mode card plus its persistent whitelist/blacklist filter. */
public class QuarryCardItem extends Item {
    public static final int MAX_FILTER_ENTRIES = 18;
    private static final String P = "QuantumFilter";
    private static final String LEGACY_BLACK = "QuantumFilterBlacklist";
    private static final String DAMAGE = "QuantumFilterDamage";
    private static final String NBT = "QuantumFilterNbt";
    private static final String MOD = "QuantumFilterMod";
    private static final String ITEM_COUNT = "QuantumFilterItemCount";
    private static final String TAG_COUNT = "QuantumFilterTagCount";
    private static final String ITEM_PREFIX = "QuantumFilterItem";
    private static final String TAG_PREFIX = "QuantumFilterTag";
    private static final String ITEM_BLACK_PREFIX = "QuantumFilterItemBlack";
    private static final String TAG_BLACK_PREFIX = "QuantumFilterTagBlack";

    private final QuarryMode mode;

    public QuarryCardItem(Properties properties, QuarryMode mode) {
        super(properties.stacksTo(1));
        this.mode = mode;
    }

    public QuarryMode mode() { return mode; }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            int cardSlot = hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : 40;
            serverPlayer.openMenu(new SimpleMenuProvider(
                    (id, inventory, p) -> new QuarryFilterMenu(id, inventory, cardSlot),
                    Component.translatable("gui.rftoolsbuilder.filter.title")),
                    data -> data.writeVarInt(cardSlot));
        }
        return InteractionResult.SUCCESS;
    }

    private static CompoundTag root(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static void saveRoot(ItemStack stack, CompoundTag root) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    public static boolean damageMode(ItemStack stack) { return root(stack).getBooleanOr(DAMAGE, false); }
    public static boolean nbtMode(ItemStack stack) { return root(stack).getBooleanOr(NBT, false); }
    public static boolean modMode(ItemStack stack) { return root(stack).getBooleanOr(MOD, false); }
    public static int itemCount(ItemStack stack) { return Math.max(0, Math.min(MAX_FILTER_ENTRIES, root(stack).getIntOr(ITEM_COUNT, 0))); }
    public static int tagCount(ItemStack stack) { return Math.max(0, Math.min(MAX_FILTER_ENTRIES, root(stack).getIntOr(TAG_COUNT, 0))); }
    public static int entryCount(ItemStack stack) { return Math.min(MAX_FILTER_ENTRIES, itemCount(stack) + tagCount(stack)); }

    public static void toggle(ItemStack stack, int setting) {
        String key = switch (setting) {
            case 1 -> DAMAGE;
            case 2 -> NBT;
            case 3 -> MOD;
            default -> null;
        };
        if (key == null) return;
        CompoundTag r = root(stack);
        r.putBoolean(key, !r.getBooleanOr(key, false));
        saveRoot(stack, r);
    }

    public static String getTag(ItemStack stack, int index) {
        CompoundTag r = root(stack);
        int count = Math.max(0, Math.min(MAX_FILTER_ENTRIES, r.getIntOr(TAG_COUNT, 0)));
        if (index < 0 || index >= count) return "";
        return r.getString(TAG_PREFIX + index).orElse("");
    }

    public static ItemStack getFilterItem(ItemStack stack, int index, HolderLookup.Provider registries) {
        CompoundTag r = root(stack);
        int count = Math.max(0, Math.min(MAX_FILTER_ENTRIES, r.getIntOr(ITEM_COUNT, 0)));
        if (index < 0 || index >= count) return ItemStack.EMPTY;
        CompoundTag tag = r.getCompound(ITEM_PREFIX + index).orElse(null);
        if (tag == null) return ItemStack.EMPTY;
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, registries, tag);
        return input.read("Stack", ItemStack.CODEC).orElse(ItemStack.EMPTY);
    }

    public static boolean entryBlacklist(ItemStack card, int combinedIndex) {
        int tags = tagCount(card);
        if (combinedIndex < 0 || combinedIndex >= entryCount(card)) return false;
        CompoundTag r = root(card);
        boolean legacy = r.getBooleanOr(LEGACY_BLACK, false);
        return combinedIndex < tags
                ? r.getBooleanOr(TAG_BLACK_PREFIX + combinedIndex, legacy)
                : r.getBooleanOr(ITEM_BLACK_PREFIX + (combinedIndex - tags), legacy);
    }

    public static void toggleEntryRule(ItemStack card, int combinedIndex) {
        int tags = tagCount(card);
        if (combinedIndex < 0 || combinedIndex >= entryCount(card)) return;
        boolean next = !entryBlacklist(card, combinedIndex);
        CompoundTag r = root(card);
        if (combinedIndex < tags) r.putBoolean(TAG_BLACK_PREFIX + combinedIndex, next);
        else r.putBoolean(ITEM_BLACK_PREFIX + (combinedIndex - tags), next);
        saveRoot(card, r);
    }

    public static int blacklistCount(ItemStack card) {
        int c = 0;
        for (int i = 0; i < entryCount(card); i++) if (entryBlacklist(card, i)) c++;
        return c;
    }

    public static int whitelistCount(ItemStack card) { return entryCount(card) - blacklistCount(card); }

    public static List<String> getTags(ItemStack stack) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < tagCount(stack); i++) {
            String value = getTag(stack, i);
            if (!value.isBlank()) result.add(value);
        }
        return result;
    }

    public static boolean addFilterItem(ItemStack card, ItemStack source, HolderLookup.Provider registries) {
        return addFilterItem(card, source, registries, false);
    }

    private static boolean addFilterItem(ItemStack card, ItemStack source, HolderLookup.Provider registries, boolean blacklist) {
        if (source.isEmpty() || source.getItem() instanceof QuarryCardItem || entryCount(card) >= MAX_FILTER_ENTRIES) return false;
        int count = itemCount(card);
        ItemStack normalized = source.copyWithCount(1);
        for (int i = 0; i < count; i++) {
            if (ItemStack.isSameItemSameComponents(getFilterItem(card, i, registries), normalized)) return false;
        }
        CompoundTag r = root(card);
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        output.store("Stack", ItemStack.CODEC, normalized);
        r.put(ITEM_PREFIX + count, output.buildResult());
        r.putBoolean(ITEM_BLACK_PREFIX + count, blacklist);
        r.putInt(ITEM_COUNT, count + 1);
        saveRoot(card, r);
        return true;
    }

    public static boolean addFilterTag(ItemStack card, String raw) { return addFilterTag(card, raw, false); }

    private static boolean addFilterTag(ItemStack card, String raw, boolean blacklist) {
        String value = raw == null ? "" : raw.trim().toLowerCase();
        if (value.startsWith("#")) value = value.substring(1);
        if (value.isBlank() || !value.contains(":")) return false;
        Identifier id;
        try { id = Identifier.parse(value); }
        catch (RuntimeException ignored) { return false; }
        value = id.toString();
        if (entryCount(card) >= MAX_FILTER_ENTRIES) return false;
        int count = tagCount(card);
        for (int i = 0; i < count; i++) if (value.equals(getTag(card, i))) return false;
        CompoundTag r = root(card);
        r.putString(TAG_PREFIX + count, value);
        r.putBoolean(TAG_BLACK_PREFIX + count, blacklist);
        r.putInt(TAG_COUNT, count + 1);
        saveRoot(card, r);
        return true;
    }

    public static int addTagsFromItem(ItemStack card, ItemStack source) { return addTagsFromItem(card, source, false); }

    private static int addTagsFromItem(ItemStack card, ItemStack source, boolean blacklist) {
        if (source.isEmpty()) return 0;
        int[] added = {0};
        source.typeHolder().tags().forEach(tag -> {
            if (addFilterTag(card, tag.location().toString(), blacklist)) added[0]++;
        });
        return added[0];
    }

    public static void removeEntry(ItemStack card, int combinedIndex, HolderLookup.Provider registries) {
        int tags = tagCount(card);
        if (combinedIndex < 0 || combinedIndex >= entryCount(card)) return;
        CompoundTag r = root(card);
        boolean legacy = r.getBooleanOr(LEGACY_BLACK, false);
        if (combinedIndex < tags) {
            for (int i = combinedIndex; i < tags - 1; i++) {
                String next = r.getString(TAG_PREFIX + (i + 1)).orElse("");
                if (next.isBlank()) r.remove(TAG_PREFIX + i); else r.putString(TAG_PREFIX + i, next);
                r.putBoolean(TAG_BLACK_PREFIX + i, r.getBooleanOr(TAG_BLACK_PREFIX + (i + 1), legacy));
            }
            r.remove(TAG_PREFIX + (tags - 1));
            r.remove(TAG_BLACK_PREFIX + (tags - 1));
            r.putInt(TAG_COUNT, tags - 1);
        } else {
            int index = combinedIndex - tags;
            int items = itemCount(card);
            for (int i = index; i < items - 1; i++) {
                CompoundTag next = r.getCompound(ITEM_PREFIX + (i + 1)).orElse(null);
                if (next != null) r.put(ITEM_PREFIX + i, next.copy()); else r.remove(ITEM_PREFIX + i);
                r.putBoolean(ITEM_BLACK_PREFIX + i, r.getBooleanOr(ITEM_BLACK_PREFIX + (i + 1), legacy));
            }
            r.remove(ITEM_PREFIX + (items - 1));
            r.remove(ITEM_BLACK_PREFIX + (items - 1));
            r.putInt(ITEM_COUNT, items - 1);
        }
        saveRoot(card, r);
    }

    public static void expandEntryToTags(ItemStack card, int combinedIndex, HolderLookup.Provider registries) {
        int itemIndex = combinedIndex - tagCount(card);
        if (itemIndex < 0 || itemIndex >= itemCount(card)) return;
        boolean blacklist = entryBlacklist(card, combinedIndex);
        ItemStack source = getFilterItem(card, itemIndex, registries);
        if (source.isEmpty()) return;
        removeEntry(card, combinedIndex, registries);
        addTagsFromItem(card, source, blacklist);
    }

    public static void clearFilter(ItemStack card) {
        CompoundTag r = root(card);
        int items = Math.max(0, r.getIntOr(ITEM_COUNT, 0));
        int tags = Math.max(0, r.getIntOr(TAG_COUNT, 0));
        for (int i = 0; i < items; i++) { r.remove(ITEM_PREFIX + i); r.remove(ITEM_BLACK_PREFIX + i); }
        for (int i = 0; i < tags; i++) { r.remove(TAG_PREFIX + i); r.remove(TAG_BLACK_PREFIX + i); }
        r.remove(LEGACY_BLACK);
        r.putInt(ITEM_COUNT, 0);
        r.putInt(TAG_COUNT, 0);
        saveRoot(card, r);
    }

    private static boolean matchesItemRule(ItemStack card, ItemStack target, ItemStack filter) {
        if (filter.isEmpty()) return false;
        if (modMode(card)) {
            Identifier targetId = BuiltInRegistries.ITEM.getKey(target.getItem());
            Identifier filterId = BuiltInRegistries.ITEM.getKey(filter.getItem());
            if (targetId == null || filterId == null || !targetId.getNamespace().equals(filterId.getNamespace())) return false;
        } else if (target.getItem() != filter.getItem()) return false;
        if (damageMode(card) && target.getDamageValue() != filter.getDamageValue()) return false;
        if (nbtMode(card) && !ItemStack.isSameItemSameComponents(target, filter)) return false;
        return true;
    }

    public static boolean allowsBlock(ItemStack card, BlockState state, HolderLookup.Provider registries) {
        int items = itemCount(card);
        int tags = tagCount(card);
        if (items + tags == 0) return true;
        boolean hasWhitelist = whitelistCount(card) > 0;
        boolean matchedBlacklist = false;
        boolean matchedWhitelist = false;
        ItemStack target = new ItemStack(state.getBlock().asItem());

        for (int i = 0; i < tags; i++) {
            boolean match = false;
            try {
                Identifier id = Identifier.parse(getTag(card, i));
                match = state.is(TagKey.create(Registries.BLOCK, id))
                        || (!target.isEmpty() && target.is(TagKey.create(Registries.ITEM, id)));
            } catch (RuntimeException ignored) {}
            if (match) {
                if (entryBlacklist(card, i)) matchedBlacklist = true;
                else matchedWhitelist = true;
            }
        }
        for (int i = 0; i < items; i++) {
            ItemStack filter = getFilterItem(card, i, registries);
            if (matchesItemRule(card, target, filter)) {
                if (entryBlacklist(card, tags + i)) matchedBlacklist = true;
                else matchedWhitelist = true;
            }
        }
        if (matchedBlacklist) return false;
        if (matchedWhitelist) return true;
        return !hasWhitelist;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> text, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, text, flag);
        text.accept(Component.literal("Quarry: " + mode.name().toLowerCase().replace('_', ' ')).withStyle(ChatFormatting.AQUA));
        int entries = entryCount(stack);
        text.accept(Component.literal("Filter: " + entries + "/" + MAX_FILTER_ENTRIES + "  W:" + whitelistCount(stack) + " B:" + blacklistCount(stack))
                .withStyle(ChatFormatting.GRAY));
        if (damageMode(stack)) text.accept(Component.literal("Match damage").withStyle(ChatFormatting.DARK_GRAY));
        if (nbtMode(stack)) text.accept(Component.literal("Match components/NBT").withStyle(ChatFormatting.DARK_GRAY));
        if (modMode(stack)) text.accept(Component.literal("Match mod namespace").withStyle(ChatFormatting.DARK_GRAY));
        text.accept(Component.translatable("gui.rftoolsbuilder.filter.open_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
