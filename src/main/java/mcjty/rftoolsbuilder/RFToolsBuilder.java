package mcjty.rftoolsbuilder;

import mcjty.rftoolsbuilder.constructor.ConstructorBootstrap;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Complete Quantum Tools entry point. The legacy Builder/Quarry systems and the
 * Constructor subsystem are registered under the original rftoolsbuilder id so
 * existing worlds keep their registry identities.
 */
@Mod(RFToolsBuilder.MOD_ID)
public final class RFToolsBuilder {
    public static final String MOD_ID = "rftoolsbuilder";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final DeferredBlock<BuilderBlock> BUILDER = BLOCKS.registerBlock(
            "builder", BuilderBlock::new,
            properties -> properties.strength(3.5f, 8.0f).sound(SoundType.METAL));

    public static final DeferredItem<BlockItem> BUILDER_ITEM = ITEMS.registerItem(
            "builder", properties -> new BlockItem(BUILDER.get(), properties));

    public static final DeferredItem<ShapeCardItem> SHAPE_CARD_DEF = ITEMS.registerItem(
            "shape_card_def", ShapeCardItem::new, properties -> properties.stacksTo(1));

    public static final DeferredItem<QuarryCardItem> SHAPE_CARD_QUARRY = quarryCard("shape_card_quarry", QuarryMode.NORMAL);
    public static final DeferredItem<QuarryCardItem> SHAPE_CARD_QUARRY_CLEAR = quarryCard("shape_card_quarry_clear", QuarryMode.CLEAR);
    public static final DeferredItem<QuarryCardItem> SHAPE_CARD_QUARRY_FORTUNE = quarryCard("shape_card_quarry_fortune", QuarryMode.FORTUNE);
    public static final DeferredItem<QuarryCardItem> SHAPE_CARD_QUARRY_CLEAR_FORTUNE = quarryCard("shape_card_quarry_clear_fortune", QuarryMode.CLEAR_FORTUNE);
    public static final DeferredItem<QuarryCardItem> SHAPE_CARD_QUARRY_SILK = quarryCard("shape_card_quarry_silk", QuarryMode.SILK);
    public static final DeferredItem<QuarryCardItem> SHAPE_CARD_QUARRY_CLEAR_SILK = quarryCard("shape_card_quarry_clear_silk", QuarryMode.CLEAR_SILK);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BuilderBlockEntity>> BUILDER_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("builder", () -> new BlockEntityType<>(BuilderBlockEntity::new, false, BUILDER.get()));

    public static final DeferredHolder<MenuType<?>, MenuType<BuilderMenu>> BUILDER_MENU =
            MENUS.register("builder", () -> IMenuTypeExtension.create(BuilderMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<QuarryFilterMenu>> QUARRY_FILTER_MENU =
            MENUS.register("quarry_filter", () -> IMenuTypeExtension.create(QuarryFilterMenu::new));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.rftoolsbuilder"))
                    .icon(() -> BUILDER_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(BUILDER_ITEM.get());
                        output.accept(SHAPE_CARD_DEF.get());
                        output.accept(SHAPE_CARD_QUARRY.get());
                        output.accept(SHAPE_CARD_QUARRY_CLEAR.get());
                        output.accept(SHAPE_CARD_QUARRY_FORTUNE.get());
                        output.accept(SHAPE_CARD_QUARRY_CLEAR_FORTUNE.get());
                        output.accept(SHAPE_CARD_QUARRY_SILK.get());
                        output.accept(SHAPE_CARD_QUARRY_CLEAR_SILK.get());
                    })
                    .build());

    private static DeferredItem<QuarryCardItem> quarryCard(String name, QuarryMode mode) {
        return ITEMS.registerItem(name, properties -> new QuarryCardItem(properties, mode), properties -> properties.stacksTo(1));
    }

    public RFToolsBuilder(IEventBus modBus, ModContainer container) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        MENUS.register(modBus);
        TABS.register(modBus);
        modBus.addListener(this::registerCapabilities);
        modBus.addListener(this::registerPayloads);

        // Constructor is part of the same mod and must be generated from source
        // in the same build, never injected into the compiled JAR afterwards.
        ConstructorBootstrap.init(modBus);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(FilterTagPayload.TYPE, FilterTagPayload.STREAM_CODEC, FilterTagPayload::handle);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, BUILDER_BLOCK_ENTITY.get(),
                (builder, side) -> builder.energyStorage());
    }
}
