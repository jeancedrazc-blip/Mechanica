package mcjty.rftoolsbuilder.extractor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import mcjty.rftoolsbuilder.extractor.ExtractorBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Renders the player's sample as an actual vanilla block suspended inside the cage. */
public final class ExtractorBlockEntityRenderer implements BlockEntityRenderer<ExtractorBlockEntity, ExtractorRenderState> {
    private static final BlockDisplayContext DISPLAY_CONTEXT = BlockDisplayContext.create();
    private final BlockModelResolver blockResolver;

    public ExtractorBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        blockResolver = context.blockModelResolver();
    }

    @Override public ExtractorRenderState createRenderState() { return new ExtractorRenderState(); }

    @Override
    public void extractRenderState(ExtractorBlockEntity blockEntity, ExtractorRenderState state, float partialTick,
                                   Vec3 cameraPosition, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTick, cameraPosition, crumblingOverlay);
        BlockState sampleState = blockEntity.sampleState();
        state.hasSample = sampleState != null;
        state.running = blockEntity.status() == ExtractorBlockEntity.STATUS_RUNNING;
        state.effectTime = partialTick + (blockEntity.getLevel() == null ? 0 : blockEntity.getLevel().getGameTime());
        state.progress = blockEntity.processTicks() <= 0 ? 0.0f
                : blockEntity.progress() / (float) blockEntity.processTicks();
        if (sampleState != null) blockResolver.update(state.sample, sampleState, DISPLAY_CONTEXT);
    }

    @Override
    public void submit(ExtractorRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState cameraRenderState) {
        if (!state.hasSample) return;
        poseStack.pushPose();
        float bob = state.running ? (float) Math.sin(state.effectTime * 0.16f) * 0.025f : 0.0f;
        poseStack.translate(0.5, 0.5 + bob, 0.5);
        if (state.running) poseStack.mulPose(Axis.YP.rotationDegrees(state.effectTime * 1.35f));
        float pulse = state.running ? 0.56f + 0.015f * (float) Math.sin(state.effectTime * 0.22f) : 0.56f;
        poseStack.scale(pulse, pulse, pulse);
        poseStack.translate(-0.5, -0.5, -0.5);
        state.sample.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }
}
