package mcjty.rftoolsbuilder.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import mcjty.rftoolsbuilder.BuilderBlockEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Small emissive front-panel hologram used by the Builder while a quarry card is loaded. */
public final class QuantumBuilderBlockEntityRenderer implements BlockEntityRenderer<BuilderBlockEntity, QuantumBuilderRenderState> {
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final int CYAN = 0xFF36E6F7;
    private static final int WHITE = 0xFFF0F0FF;
    private static final int MUTED = 0xFF8ED0C1;
    private static final int SCREEN_BG = 0x72081414;
    private final Font font;

    public QuantumBuilderBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    @Override public QuantumBuilderRenderState createRenderState() { return new QuantumBuilderRenderState(); }

    @Override
    public void extractRenderState(BuilderBlockEntity blockEntity, QuantumBuilderRenderState state, float partialTicks,
                                   Vec3 cameraPosition, @Nullable ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        state.visible = blockEntity.hologramVisible();
        state.facing = blockEntity.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        BlockPos target = blockEntity.hologramTarget();
        state.chunkX = target.getX() >> 4;
        state.chunkZ = target.getZ() >> 4;
        state.progressPermille = blockEntity.hologramProgressPermille();
        state.status = blockEntity.hologramStatus();
    }

    @Override
    public void submit(QuantumBuilderRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState) {
        if (!state.visible) return;
        poseStack.pushPose();
        placeOnFront(poseStack, state.facing);
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f));
        poseStack.scale(0.0125f, 0.0125f, 0.0125f);
        String title = "  QUANTUM SCAN";
        String chunk = "CHUNK " + state.chunkX + " / " + state.chunkZ;
        String progress = statusName(state.status) + "  " + String.format(Locale.ROOT, "%.1f%%", state.progressPermille / 10.0f);
        drawLine(collector, poseStack, title, -13.0f, CYAN);
        drawLine(collector, poseStack, chunk, -3.0f, WHITE);
        drawLine(collector, poseStack, progress, 7.0f, state.status == BuilderBlockEntity.STATUS_RUNNING ? CYAN : MUTED);
        poseStack.popPose();
    }

    private void placeOnFront(PoseStack poseStack, Direction facing) {
        switch (facing) {
            case NORTH -> {
                poseStack.translate(0.5, 0.78, -0.12);
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0f));
            }
            case SOUTH -> poseStack.translate(0.5, 0.78, 1.12);
            case WEST -> {
                poseStack.translate(-0.12, 0.78, 0.5);
                poseStack.mulPose(Axis.YP.rotationDegrees(90.0f));
            }
            case EAST -> {
                poseStack.translate(1.12, 0.78, 0.5);
                poseStack.mulPose(Axis.YP.rotationDegrees(-90.0f));
            }
            default -> poseStack.translate(0.5, 0.78, 1.12);
        }
    }

    private void drawLine(SubmitNodeCollector collector, PoseStack poseStack, String text, float y, int color) {
        float width = font.width(text);
        collector.submitText(poseStack, -width / 2.0f, y, Component.literal(text).getVisualOrderText(), false,
                Font.DisplayMode.SEE_THROUGH, FULL_BRIGHT, color, SCREEN_BG, 0);
    }

    private static String statusName(int status) {
        return switch (status) {
            case BuilderBlockEntity.STATUS_RUNNING -> "MINING";
            case BuilderBlockEntity.STATUS_NO_ENERGY -> "NO FE";
            case BuilderBlockEntity.STATUS_OUTPUT_FULL -> "OUTPUT FULL";
            case BuilderBlockEntity.STATUS_DONE -> "COMPLETE";
            case BuilderBlockEntity.STATUS_NO_CARD -> "NO CARD";
            case BuilderBlockEntity.STATUS_PAUSED -> "PAUSED";
            default -> "STANDBY";
        };
    }
}
