package mcjty.rftoolsbuilder.constructor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import mcjty.rftoolsbuilder.constructor.ConstructorNetworking;

public final class MaterialListTabletClient {
    private MaterialListTabletClient() {}

    public static void open(ItemStack tablet, InteractionHand hand) {
        Minecraft.getInstance().setScreen(new MaterialListTabletScreen(tablet, hand));
        ClientPacketDistributor.sendToServer(new ConstructorNetworking.RequestTabletMaterials(
                hand == InteractionHand.MAIN_HAND ? 0 : 1));
    }

    public static void receive(String schematicName, int total, String materials, String status) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen instanceof MaterialListTabletScreen screen) {
                screen.applyServerData(schematicName, total, materials, status);
            }
        });
    }
}
