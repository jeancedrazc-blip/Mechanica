package mcjty.rftoolsbuilder.constructor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import mcjty.rftoolsbuilder.constructor.ConstructorBlockEntity;
import mcjty.rftoolsbuilder.constructor.ConstructorBootstrap;
import mcjty.rftoolsbuilder.constructor.ConstructorStatus;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Renders one lightweight, non-colliding construction drone. The server only
 * synchronizes its current leg and target; interpolation, shutters, beam and
 * voxel assembly are client-side visuals and never decide placement outcome.
 */
public final class ConstructorBlockEntityRenderer implements BlockEntityRenderer<ConstructorBlockEntity, ConstructorRenderState> {
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final BlockDisplayContext DISPLAY_CONTEXT = BlockDisplayContext.create();
    private static final double DOCK_X = 0.5;
    private static final double DOCK_Y = 1.0625;
    private static final double DOCK_Z = 0.5;
    private static final double WORK_Y_OFFSET = 1.35;
    private static final float TRAVEL_PORTION = 0.58f;

    private final BlockModelResolver blockResolver;
    private final ItemModelResolver itemResolver;

    public ConstructorBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        blockResolver = context.blockModelResolver();
        itemResolver = context.itemModelResolver();
    }

    @Override
    public ConstructorRenderState createRenderState() {
        return new ConstructorRenderState();
    }

    @Override
    public AABB getRenderBoundingBox(ConstructorBlockEntity blockEntity) {
        BlockPos origin = blockEntity.getBlockPos();
        double minX = origin.getX() - 1.0;
        double minY = origin.getY() - 1.0;
        double minZ = origin.getZ() - 1.0;
        double maxX = origin.getX() + 2.0;
        double maxY = origin.getY() + 3.0;
        double maxZ = origin.getZ() + 2.0;
        BlockPos[] movingPoints = {blockEntity.targetPos(), blockEntity.dronePos(), blockEntity.flightStartPos()};
        for (BlockPos point : movingPoints) {
            if (point == null) continue;
            minX = Math.min(minX, point.getX() - 1.5);
            minY = Math.min(minY, point.getY() - 1.0);
            minZ = Math.min(minZ, point.getZ() - 1.5);
            maxX = Math.max(maxX, point.getX() + 2.5);
            maxY = Math.max(maxY, point.getY() + 3.0);
            maxZ = Math.max(maxZ, point.getZ() + 2.5);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public int getViewDistance() {
        return ConstructorBlockEntity.MAX_TARGET_DISTANCE;
    }

    @Override
    public void extractRenderState(
            ConstructorBlockEntity blockEntity,
            ConstructorRenderState state,
            float partialTick,
            Vec3 cameraPosition,
            @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay
    ) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTick, cameraPosition, crumblingOverlay);

        blockResolver.update(state.drone, ConstructorBootstrap.CONSTRUCTOR_DRONE_VISUAL.get().defaultBlockState(), DISPLAY_CONTEXT);
        blockResolver.update(state.droneEnergy, ConstructorBootstrap.CONSTRUCTOR_DRONE_ENERGY_VISUAL.get().defaultBlockState(), DISPLAY_CONTEXT);
        blockResolver.update(state.droneLowEnergy, ConstructorBootstrap.CONSTRUCTOR_DRONE_LOW_VISUAL.get().defaultBlockState(), DISPLAY_CONTEXT);
        blockResolver.update(state.shutter, ConstructorBootstrap.CONSTRUCTOR_SHUTTER_VISUAL.get().defaultBlockState(), DISPLAY_CONTEXT);
        blockResolver.update(state.beam, ConstructorBootstrap.CONSTRUCTOR_BEAM_VISUAL.get().defaultBlockState(), DISPLAY_CONTEXT);
        blockResolver.update(state.ring, ConstructorBootstrap.CONSTRUCTOR_RING_VISUAL.get().defaultBlockState(), DISPLAY_CONTEXT);
        blockResolver.update(state.targetFrame, ConstructorBootstrap.CONSTRUCTOR_TARGET_FRAME_VISUAL.get().defaultBlockState(), DISPLAY_CONTEXT);

        BlockPos origin = blockEntity.getBlockPos();
        BlockPos target = blockEntity.targetPos();
        BlockState targetState = blockEntity.targetState();
        boolean entityTarget = blockEntity.targetIsEntity();
        state.status = blockEntity.status();
        state.hasTarget = target != null && (targetState != null || entityTarget);
        state.droneVisible = true;
        state.droneLow = blockEntity.droneLowEnergy();
        state.returning = blockEntity.droneReturning();
        state.recharging = blockEntity.droneRecharging();
        state.constructing = false;
        state.projectileVisible = false;
        state.projectileIsItem = false;
        state.projectileProgress = 0.0f;
        state.buildProgress = 0.0f;
        state.flightProgress = 0.0f;
        state.entityProjectile.clear();

        double time = partialTick;
        if (blockEntity.getLevel() != null) time += blockEntity.getLevel().getGameTime();
        state.effectTime = (float) time;

        Direction facing = blockEntity.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        state.droneYaw = homeYaw(facing);
        state.droneX = DOCK_X;
        state.droneY = DOCK_Y;
        state.droneZ = DOCK_Z;

        if (state.hasTarget) {
            if (targetState != null) {
                blockResolver.update(state.projectile, targetState, DISPLAY_CONTEXT);
            } else if (entityTarget) {
                ItemStack projectileItem = blockEntity.projectileItem();
                if (!projectileItem.isEmpty()) {
                    itemResolver.updateForTopItem(
                            state.entityProjectile,
                            projectileItem,
                            ItemDisplayContext.FIXED,
                            blockEntity.getLevel(),
                            null,
                            origin.hashCode()
                    );
                    state.projectileIsItem = true;
                }
            }
            state.targetX = relativeCenterX(target, origin);
            state.targetY = relativeCenterY(target, origin);
            state.targetZ = relativeCenterZ(target, origin);
        }

        if (state.returning) {
            float progress = clamp01((blockEntity.phaseTick() + partialTick) / (float) blockEntity.flightTicks());
            state.flightProgress = smoothStep(progress);
            BlockPos start = firstNonNull(blockEntity.flightStartPos(), blockEntity.dronePos(), origin);
            double sx = relativeCenterX(start, origin);
            double sy = workingY(start, origin);
            double sz = relativeCenterZ(start, origin);
            state.droneX = lerp(sx, DOCK_X, state.flightProgress);
            state.droneY = lerp(sy, DOCK_Y, state.flightProgress) + flightArc(progress);
            state.droneZ = lerp(sz, DOCK_Z, state.flightProgress);
            state.droneYaw = movementYaw(DOCK_X - sx, DOCK_Z - sz, state.droneYaw);
            state.shutterOpen = smoothStep(clamp01((progress - 0.58f) / 0.32f));
        } else if (state.status == ConstructorStatus.FIRING && state.hasTarget) {
            float raw = clamp01((blockEntity.shotProgress() + partialTick) / (float) blockEntity.flightTicks());
            state.flightProgress = smoothStep(clamp01(raw / TRAVEL_PORTION));
            state.buildProgress = smoothStep(clamp01((raw - TRAVEL_PORTION) / (1.0f - TRAVEL_PORTION)));
            BlockPos start = firstNonNull(blockEntity.flightStartPos(), origin);
            boolean launchedBefore = blockEntity.droneDeployed();
            double sx = launchedBefore ? relativeCenterX(start, origin) : DOCK_X;
            double sy = launchedBefore ? workingY(start, origin) : DOCK_Y;
            double sz = launchedBefore ? relativeCenterZ(start, origin) : DOCK_Z;
            double tx = relativeCenterX(target, origin);
            double ty = workingY(target, origin);
            double tz = relativeCenterZ(target, origin);
            state.droneX = lerp(sx, tx, state.flightProgress);
            state.droneY = lerp(sy, ty, state.flightProgress) + flightArc(clamp01(raw / TRAVEL_PORTION));
            state.droneZ = lerp(sz, tz, state.flightProgress);
            state.droneYaw = movementYaw(tx - sx, tz - sz, state.droneYaw);
            state.constructing = raw >= TRAVEL_PORTION;
            state.projectileProgress = state.buildProgress;
            state.projectileVisible = state.constructing && (targetState != null || state.projectileIsItem);
            state.shutterOpen = launchedBefore ? 0.0f : 1.0f - smoothStep(clamp01((raw - 0.12f) / 0.28f));
        } else if (blockEntity.droneDeployed() && blockEntity.dronePos() != null) {
            BlockPos parked = blockEntity.dronePos();
            state.droneX = relativeCenterX(parked, origin);
            state.droneY = workingY(parked, origin) + Math.sin(time * 0.14) * 0.025;
            state.droneZ = relativeCenterZ(parked, origin);
            state.shutterOpen = 0.0f;
        } else {
            state.shutterOpen = state.recharging ? 0.72f : 0.36f;
        }

        if (state.status == ConstructorStatus.CHARGING || state.recharging) {
            state.energyPulse = 1.03f + (float) ((Math.sin(time * 1.35) + 1.0) * 0.035);
        } else if (state.constructing) {
            state.energyPulse = 1.10f;
        } else {
            state.energyPulse = 1.0f;
        }
    }

    @Override
    public void submit(ConstructorRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraState) {
        submitShutters(state, poseStack, collector);
        if (state.droneVisible) submitDrone(state, poseStack, collector);
        if (state.constructing && state.hasTarget) submitConstructionEffect(state, poseStack, collector);
    }

    private static void submitShutters(ConstructorRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        float open = smoothStep(state.shutterOpen);
        for (int i = 0; i < 4; i++) {
            poseStack.pushPose();
            poseStack.translate(0.5, 0.0, 0.5);
            poseStack.mulPose(Axis.YP.rotationDegrees(i * 90.0f));
            poseStack.translate(0.0, open * 0.08, -open * 0.24);
            poseStack.mulPose(Axis.XP.rotationDegrees(-open * 52.0f));
            poseStack.translate(-0.5, 0.0, -0.5);
            state.shutter.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
    }

    private static void submitDrone(ConstructorRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        poseStack.pushPose();
        poseStack.translate(state.droneX, state.droneY, state.droneZ);
        poseStack.mulPose(Axis.YP.rotationDegrees(state.droneYaw));
        poseStack.translate(-0.5, 0.0, -0.5);
        state.drone.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);

        poseStack.translate(0.5, 0.25, 0.5);
        poseStack.scale(state.energyPulse, state.energyPulse, state.energyPulse);
        poseStack.translate(-0.5, -0.25, -0.5);
        if (state.droneLow) {
            state.droneLowEnergy.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        } else {
            state.droneEnergy.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        }
        poseStack.popPose();
    }

    private static void submitConstructionEffect(ConstructorRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        double sourceX = state.droneX;
        double sourceY = state.droneY + 0.015;
        double sourceZ = state.droneZ;
        submitBeamBetween(state, poseStack, collector, sourceX, sourceY, sourceZ,
                state.targetX, state.targetY, state.targetZ);
        submitTargetFrame(state, poseStack, collector);
        if (state.projectileVisible) submitMaterializingTarget(state, poseStack, collector);
    }

    private static void submitBeamBetween(ConstructorRenderState state, PoseStack poseStack,
                                          SubmitNodeCollector collector,
                                          double sourceX, double sourceY, double sourceZ,
                                          double targetX, double targetY, double targetZ) {
        double dx = targetX - sourceX;
        double dy = targetY - sourceY;
        double dz = targetZ - sourceZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-5) return;
        float yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(1.0e-5, horizontal)));

        poseStack.pushPose();
        poseStack.translate(sourceX, sourceY, sourceZ);
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        poseStack.translate(-0.5, -0.5, 0.0);
        float flicker = 0.74f + 0.10f * (float) Math.sin(state.effectTime * 2.7f);
        poseStack.translate(0.5, 0.5, 0.0);
        poseStack.scale(flicker, flicker, (float) length);
        poseStack.translate(-0.5, -0.5, 0.0);
        state.beam.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();

        for (int i = 0; i < 3; i++) {
            float travel = positiveModulo(state.effectTime * 0.075f + i / 3.0f, 1.0f);
            poseStack.pushPose();
            poseStack.translate(sourceX + dx * travel, sourceY + dy * travel, sourceZ + dz * travel);
            poseStack.mulPose(Axis.YP.rotationDegrees(state.effectTime * 5.0f + i * 30.0f));
            poseStack.scale(0.34f, 0.34f, 0.34f);
            poseStack.translate(-0.5, -0.5, -0.5);
            state.ring.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
    }

    private static void submitTargetFrame(ConstructorRenderState state, PoseStack poseStack,
                                          SubmitNodeCollector collector) {
        float pulse = 1.0f + 0.025f * (float) Math.sin(state.effectTime * 0.8f);
        poseStack.pushPose();
        poseStack.translate(state.targetX, state.targetY, state.targetZ);
        poseStack.scale(pulse, pulse, pulse);
        poseStack.translate(-0.5, -0.5, -0.5);
        state.targetFrame.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();

        // A square scan plane climbs through the target while the block fills
        // from bottom to top, making the assembly order readable at a glance.
        poseStack.pushPose();
        poseStack.translate(state.targetX, state.targetY - 0.5 + state.buildProgress, state.targetZ);
        poseStack.mulPose(Axis.YP.rotationDegrees(45.0f));
        poseStack.scale(0.92f, 0.055f, 0.92f);
        poseStack.translate(-0.5, -0.5, -0.5);
        state.ring.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    private static void submitMaterializingTarget(ConstructorRenderState state, PoseStack poseStack,
                                                   SubmitNodeCollector collector) {
        float growth = Math.max(0.04f, state.buildProgress);
        poseStack.pushPose();
        if (state.projectileIsItem) {
            poseStack.translate(state.targetX, state.targetY, state.targetZ);
            float itemScale = growth * 0.82f;
            poseStack.scale(itemScale, itemScale, itemScale);
            state.entityProjectile.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        } else {
            poseStack.translate(state.targetX - 0.5, state.targetY - 0.5, state.targetZ - 0.5);
            poseStack.scale(1.0f, growth, 1.0f);
            state.projectile.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        }
        poseStack.popPose();
    }

    private static BlockPos firstNonNull(BlockPos first, BlockPos second) {
        return first != null ? first : second;
    }

    private static BlockPos firstNonNull(BlockPos first, BlockPos second, BlockPos third) {
        return first != null ? first : second != null ? second : third;
    }

    private static double relativeCenterX(BlockPos point, BlockPos origin) {
        return point.getX() - origin.getX() + 0.5;
    }

    private static double relativeCenterY(BlockPos point, BlockPos origin) {
        return point.getY() - origin.getY() + 0.5;
    }

    private static double relativeCenterZ(BlockPos point, BlockPos origin) {
        return point.getZ() - origin.getZ() + 0.5;
    }

    private static double workingY(BlockPos point, BlockPos origin) {
        return point.getY() - origin.getY() + WORK_Y_OFFSET;
    }

    private static float movementYaw(double dx, double dz, float fallback) {
        return dx * dx + dz * dz < 1.0e-6 ? fallback : (float) Math.toDegrees(Math.atan2(dx, dz));
    }

    private static float homeYaw(Direction facing) {
        return switch (facing) {
            case SOUTH -> 0.0f;
            case EAST -> 90.0f;
            case NORTH -> 180.0f;
            case WEST -> -90.0f;
            default -> 0.0f;
        };
    }

    private static double flightArc(float progress) {
        return Math.sin(clamp01(progress) * Math.PI) * 0.22;
    }

    private static double lerp(double start, double end, float progress) {
        return start + (end - start) * progress;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float smoothStep(float value) {
        float t = clamp01(value);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float positiveModulo(float value, float modulus) {
        float result = value % modulus;
        return result < 0.0f ? result + modulus : result;
    }
}
