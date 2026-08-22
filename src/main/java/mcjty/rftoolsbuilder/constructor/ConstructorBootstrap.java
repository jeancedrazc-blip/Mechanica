package mcjty.rftoolsbuilder.constructor;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ConstructorBootstrap {
    public static final String MOD_ID = "rftoolsbuilder";
    private static final ResourceKey<CreativeModeTab> MAIN_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            Identifier.fromNamespaceAndPath(MOD_ID, "main")
    );

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);
    private static boolean initialized;

    public static final DeferredBlock<ConstructorBlock> CONSTRUCTOR = BLOCKS.registerBlock(
            "constructor",
            ConstructorBlock::new,
            props -> props.strength(5.0f, 12.0f)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> state.hasProperty(BlockStateProperties.LIT)
                            && state.getValue(BlockStateProperties.LIT) ? 13 : 0)
    );

    public static final DeferredBlock<Block> CONSTRUCTOR_TURRET_VISUAL = BLOCKS.registerBlock(
            "constructor_turret_visual",
            Block::new,
            props -> props.noCollision().noOcclusion().strength(-1.0f, 3_600_000.0f)
    );
    public static final DeferredBlock<Block> CONSTRUCTOR_BARREL_VISUAL = BLOCKS.registerBlock(
            "constructor_barrel_visual",
            Block::new,
            props -> props.noCollision().noOcclusion().strength(-1.0f, 3_600_000.0f)
    );
    public static final DeferredBlock<Block> CONSTRUCTOR_ENERGY_VISUAL = BLOCKS.registerBlock(
            "constructor_energy_visual",
            Block::new,
            props -> props.noCollision().noOcclusion().strength(-1.0f, 3_600_000.0f).lightLevel(state -> 12)
    );
    public static final DeferredBlock<Block> CONSTRUCTOR_BEAM_VISUAL = BLOCKS.registerBlock(
            "constructor_beam_visual",
            Block::new,
            props -> props.noCollision().noOcclusion().strength(-1.0f, 3_600_000.0f).lightLevel(state -> 15)
    );
    public static final DeferredBlock<Block> CONSTRUCTOR_RING_VISUAL = BLOCKS.registerBlock(
            "constructor_ring_visual",
            Block::new,
            props -> props.noCollision().noOcclusion().strength(-1.0f, 3_600_000.0f).lightLevel(state -> 15)
    );
    public static final DeferredBlock<Block> CONSTRUCTOR_TARGET_FRAME_VISUAL = BLOCKS.registerBlock(
            "constructor_target_frame_visual",
            Block::new,
            props -> props.noCollision().noOcclusion().strength(-1.0f, 3_600_000.0f).lightLevel(state -> 12)
    );

    public static final DeferredBlock<SchematicTableBlock> SCHEMATIC_TABLE = BLOCKS.registerBlock(
            "schematic_table",
            SchematicTableBlock::new,
            props -> props.strength(4.5f, 10.0f).sound(SoundType.METAL).noOcclusion()
    );

    public static final DeferredItem<BlockItem> CONSTRUCTOR_ITEM = ITEMS.registerSimpleBlockItem(CONSTRUCTOR);
    public static final DeferredItem<BlockItem> SCHEMATIC_TABLE_ITEM = ITEMS.registerSimpleBlockItem(SCHEMATIC_TABLE);
    public static final DeferredItem<SchematicCreatorCardItem> SCHEMATIC_CREATOR_CARD = ITEMS.registerItem("schematic_creator_card", SchematicCreatorCardItem::new);
    public static final DeferredItem<SchematicCardItem> SCHEMATIC_CARD = ITEMS.registerItem("schematic_card", SchematicCardItem::new);
    public static final DeferredItem<MaterialListTabletItem> MATERIAL_LIST_TABLET = ITEMS.registerItem("material_list_tablet", MaterialListTabletItem::new);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ConstructorBlockEntity>> CONSTRUCTOR_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("constructor", () -> new BlockEntityType<>(ConstructorBlockEntity::new, false, CONSTRUCTOR.get()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SchematicTableBlockEntity>> SCHEMATIC_TABLE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("schematic_table", () -> new BlockEntityType<>(SchematicTableBlockEntity::new, false, SCHEMATIC_TABLE.get()));

    public static final DeferredHolder<MenuType<?>, MenuType<ConstructorMenu>> CONSTRUCTOR_MENU =
            MENUS.register("constructor", () -> IMenuTypeExtension.create(ConstructorMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<SchematicTableMenu>> SCHEMATIC_TABLE_MENU =
            MENUS.register("schematic_table", () -> IMenuTypeExtension.create(SchematicTableMenu::new));

    private ConstructorBootstrap() {}

    public static synchronized void init(IEventBus modBus) {
        if (initialized) return;
        initialized = true;
        ConstructorCompatibilityBootstrap.registerDefaults();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        MENUS.register(modBus);
        modBus.addListener(ConstructorBootstrap::registerCapabilities);
        modBus.addListener(ConstructorBootstrap::addCreativeTabContents);
        modBus.addListener(ConstructorNetworking::registerPayloads);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.Energy.BLOCK,
                CONSTRUCTOR_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.energyStorage()
        );
    }

    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(MAIN_TAB)) {
            event.accept(CONSTRUCTOR_ITEM.get());
            event.accept(SCHEMATIC_TABLE_ITEM.get());
            event.accept(SCHEMATIC_CREATOR_CARD.get());
            event.accept(SCHEMATIC_CARD.get());
            event.accept(MATERIAL_LIST_TABLET.get());
        }
    }
}
