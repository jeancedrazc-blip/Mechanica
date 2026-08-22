package mcjty.rftoolsbuilder.client;

import mcjty.rftoolsbuilder.RFToolsBuilder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Client bootstrap for the reconstructed Builder/Miner subsystem. */
@EventBusSubscriber(modid = RFToolsBuilder.MOD_ID, value = Dist.CLIENT)
public final class RFToolsBuilderClient {
    private RFToolsBuilderClient() {}

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(RFToolsBuilder.BUILDER_MENU.get(), BuilderScreen::new);
        event.register(RFToolsBuilder.QUARRY_FILTER_MENU.get(), QuarryFilterScreen::new);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(RFToolsBuilder.BUILDER_BLOCK_ENTITY.get(), QuantumBuilderBlockEntityRenderer::new);
    }
}
