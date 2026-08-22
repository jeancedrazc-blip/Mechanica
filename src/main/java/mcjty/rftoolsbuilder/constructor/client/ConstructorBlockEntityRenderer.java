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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class ConstructorBlockEntityRenderer implements BlockEntityRenderer<ConstructorBlockEntity, ConstructorRenderState> {
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final BlockDisplayContext DISPLAY_CONTEXT = BlockDisplayContext.create();

    private static final double PIVOT_X = 0.5;
    private static final double PIVOT_Y = 0.96875;
    private static final double PIVOT_Z = 0.5;
    private static final double MUZZLE_DISTANCE = 1.50;

    private final BlockModelResolver blockResolver;
    private final ItemModelResolver itemResolver;

    public ConstructorBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.blockResolver = context.blockModelResolver();
        this.itemResolver = context.itemModelResolver();
    }

    @Override
    public ConstructorRenderState createRenderState() {
        return new ConstructorRenderState();
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

        blockResolver.update(state.turret, ConstructorBootstrap.CONSTRUCTOR_TURRET_VISUAL.get().defaultBlockState(), DISPLAY_CONTEXT);
        blockResolver.update(state.barrel, ConstructorBootstrap.CONSTRUCTOR_BARREL_VISUAL.get().defaultBlockState(), DISPLAY_CONTEXT);
        blockResolver.update(state.energyChannel, ConstructorBootstrap.CONSTRUCTOR_ENERGY_VISUAL.get().defaultBlockState(), DISPLAY_CONTEXT);
        blockResolver.update(state.beam, ConstructorBootstrap.CONSTRUCTOR_BEAM_VISUAL.get().defaultBlockState(), DISPLAY_CONTEXT);
        blockResolver.update(state.ring, ConstructorBootstrap.CONSTRUCTOR_RING_VISUAL.get().defaultBlockState(), DISPLAY_CONTEXT);
        blockResolver.update(state.targetFrame, ConstructorBootstrap.CONSTRUCTOR_TARGET_FRAME_VISUAL.get().defaultBlockState(), DISPLAY_CONTEXT);

        BlockPos origin = blockEntity.getBlockPos();
        BlockPos target = blockEntity.targetPos();
        BlockState targetState = blockEntity.targetState();
        boolean entityTarget = blockEntity.targetIsEntity();
        state.status = blockEntity.status();
        state.hasTarget = target != null && (targetState != null || entityTarget);
        state.projectileVisible = false;
        state.projectileProgress = 0.0f;
        state.projectileIsItem = false;
        state.recoil = 0.0f;
        state.entityProjectile.clear();

        Direction facing = blockEntity.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        float desiredYaw = homeYaw(facing);
        float desiredPitch = 0.0f;

        double time = partialTick;
        if (blockEntity.getLevel() != null) time += blockEntity.getLevel().getGameTime();
        state.effectTime = (float) time;

        if (!state.hasTarget && (state.status == ConstructorStatus.IDLE
                || state.status == ConstructorStatus.COMPLETE
                || state.status == ConstructorStatus.PAUSED)) {
            desiredYaw += (float) Math.sin(time * 0.035) * 1.6f;
            desiredPitch = (float) Math.sin(time * 0.045) * 0.7f;
        }

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

            state.targetX = target.getX() - origin.getX() + 0.5;
            state.targetY = target.getY() - origin.getY() + 0.5;
            state.targetZ = target.getZ() - origin.getZ() + 0.5;

            double dx = state.targetX - PIVOT_X;
            double dy = state.targetY - PIVOT_Y;
            double dz = state.targetZ - PIVOT_Z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            desiredYaw = (float) Math.toDegrees(Math.atan2(dx, dz));
            desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(1.0e-5, horizontal)));
        }

        state.targetYaw = desiredYaw;
        state.targetPitch = desiredPitch;

        if (!state.initialized) {
            state.displayYaw = desiredYaw;
            state.displayPitch = desiredPitch;
            state.initialized = true;
        } else {
            float yawFactor = state.status == ConstructorStatus.AIMING ? 0.34f : 0.18f;
            float pitchFactor = state.status == ConstructorStatus.AIMING ? 0.30f : 0.16f;
            state.displayYaw = approachAngle(state.displayYaw, desiredYaw, yawFactor);
            state.displayPitch += (desiredPitch - state.displayPitch) * pitchFactor;
        }

        if (state.status == ConstructorStatus.CHARGING) {
            state.energyPulse = 1.02f + (float) ((Math.sin(time * 1.75) + 1.0) * 0.045);
        } else if (state.status == ConstructorStatus.FIRING) {
            state.energyPulse = 1.10f;
        } else if (state.status == ConstructorStatus.WAITING_ENERGY) {
            state.energyPulse = 0.94f + (float) ((Math.sin(time * 0.35) + 1.0) * 0.025);
        } else {
            state.energyPulse = 1.0f;
        }

        if (state.hasTarget && (state.status == ConstructorStatus.CHARGING
                || state.status == ConstructorStatus.FIRING)) {
            state.projectileVisible = targetState != null || state.projectileIsItem;
            if (state.status == ConstructorStatus.FIRING) {
                state.projectileProgress = clamp01((blockEntity.shotProgress() + partialTick)
                        / (float) blockEntity.flightTicks());
                state.recoil = 0.145f * (1.0f - smoothStep(state.projectileProgress));
            } else {
                // Show the locked path while the shot is charging. This is only
                // reached after the server has validated energy, material,
                // chunk, range and block support, so the preview cannot promise
                // a placement that the authoritative machine already rejected.
                state.projectileProgress = 0.0f;
            }
        }
    }

    @Override
    public void submit(ConstructorRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraState) {
        submitTurret(state, poseStack, collector);
        submitBarrel(state, poseStack, collector);
        submitEnergy(state, poseStack, collector);
        if (state.projectileVisible && state.hasTarget) submitConstructionEffect(state, poseStack, collector);
    }

    private static void submitTurret(ConstructorRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        poseStack.pushPose();
        rotateAroundPivot(poseStack, state.displayYaw, 0.0f);
        state.turret.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    private static void submitBarrel(ConstructorRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        poseStack.pushPose();
        rotateAroundPivot(poseStack, state.displayYaw, state.displayPitch);
        poseStack.translate(0.0, 0.0, -state.recoil);
        state.barrel.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    private static void submitEnergy(ConstructorRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        boolean active = isEnergyActive(state.status);
        float pulse = active ? state.energyPulse : 1.0f;
        int light = active ? FULL_BRIGHT : state.lightCoords;

        poseStack.pushPose();
        rotateAroundPivot(poseStack, state.displayYaw, state.displayPitch);
        poseStack.translate(0.0, 0.0, -state.recoil);
        poseStack.translate(PIVOT_X, PIVOT_Y, PIVOT_Z);
        poseStack.scale(pulse, pulse, 1.0f);
        poseStack.translate(-PIVOT_X, -PIVOT_Y, -PIVOT_Z);
        state.energyChannel.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    private static void submitConstructionEffect(ConstructorRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        double dx = state.targetX - PIVOT_X;
        double dy = state.targetY - PIVOT_Y;
        double dz = state.targetZ - PIVOT_Z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-5) return;

        double beamLength = Math.max(0.05, length - MUZZLE_DISTANCE);
        submitBeam(state, poseStack, collector, beamLength);
        submitTargetFrame(state, poseStack, collector);
        if (state.status == ConstructorStatus.FIRING) {
            submitMaterializingTarget(state, poseStack, collector);
        }
    }

    private static void submitBeam(ConstructorRenderState state, PoseStack poseStack,
                                   SubmitNodeCollector collector, double beamLength) {
        poseStack.pushPose();
        poseStack.translate(PIVOT_X, PIVOT_Y, PIVOT_Z);
        poseStack.mulPose(Axis.YP.rotationDegrees(state.targetYaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(state.targetPitch));
        poseStack.translate(-0.5, -0.5, MUZZLE_DISTANCE);

        float flicker = 0.88f + 0.12f * (float) Math.sin(state.effectTime * 2.7f);
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.0);
        poseStack.scale(flicker, flicker, (float) beamLength);
        poseStack.translate(-0.5, -0.5, 0.0);
        state.beam.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();

        // Three square energy pulses travel along the beam. Their low-poly
        // geometry keeps the effect firmly inside Minecraft's vanilla style.
        for (int i = 0; i < 3; i++) {
            float travel = positiveModulo(state.effectTime * 0.085f + i / 3.0f, 1.0f);
            float ringScale = 0.56f + 0.12f * (float) Math.sin((state.effectTime + i * 4.0f) * 0.45f);
            poseStack.pushPose();
            poseStack.translate(0.5, 0.5, beamLength * travel);
            poseStack.mulPose(Axis.ZP.rotationDegrees(state.effectTime * 7.0f + i * 30.0f));
            poseStack.scale(ringScale, ringScale, ringScale);
            poseStack.translate(-0.5, -0.5, -0.5);
            state.ring.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    private static void submitTargetFrame(ConstructorRenderState state, PoseStack poseStack,
                                          SubmitNodeCollector collector) {
        float pulse = 1.0f + 0.018f * (float) Math.sin(state.effectTime * 0.65f);
        poseStack.pushPose();
        poseStack.translate(state.targetX, state.targetY, state.targetZ);
        poseStack.scale(pulse, pulse, pulse);
        poseStack.translate(-0.5, -0.5, -0.5);
        state.targetFrame.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    private static void submitMaterializingTarget(ConstructorRenderState state, PoseStack poseStack,
                                                   SubmitNodeCollector collector) {
        float growth = smoothStep(clamp01((state.projectileProgress - 0.10f) / 0.82f));
        float scale = 0.06f + 0.94f * growth;

        poseStack.pushPose();
        poseStack.translate(state.targetX, state.targetY, state.targetZ);

        if (state.projectileIsItem) {
            float itemScale = scale * 0.82f;
            poseStack.scale(itemScale, itemScale, itemScale);
            state.entityProjectile.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        } else {
            poseStack.scale(scale, scale, scale);
            poseStack.translate(-0.5, -0.5, -0.5);
            state.projectile.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        }
        poseStack.popPose();
    }

    private static void rotateAroundPivot(PoseStack poseStack, float yaw, float pitch) {
        poseStack.translate(PIVOT_X, PIVOT_Y, PIVOT_Z);
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        poseStack.translate(-PIVOT_X, -PIVOT_Y, -PIVOT_Z);
    }

    private static float approachAngle(float current, float target, float factor) {
        float delta = wrapDegrees(target - current);
        return current + delta * factor;
    }

    private static float wrapDegrees(float value) {
        value %= 360.0f;
        if (value <= -180.0f) value += 360.0f;
        if (value > 180.0f) value -= 360.0f;
        return value;
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

    private static boolean isEnergyActive(ConstructorStatus status) {
        return switch (status) {
            case READY, AIMING, CHARGING, FIRING, WAITING_ENERGY, WAITING_MATERIAL, WAITING_CHUNK -> true;
            default -> false;
        };
    }

    private static float clamp01(float value) { return Math.max(0.0f, Math.min(1.0f, value)); }
    private static float smoothStep(float value) { float t = clamp01(value); return t * t * (3.0f - 2.0f * t); }
    private static float positiveModulo(float value, float modulus) {
        float result = value % modulus;
        return result < 0.0f ? result + modulus : result;
    }
}
