package mcjty.rftoolsbuilder.constructor.client;

import mcjty.rftoolsbuilder.constructor.ConstructorStatus;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;

public final class ConstructorRenderState extends BlockEntityRenderState {
    public final BlockModelRenderState drone = new BlockModelRenderState();
    public final BlockModelRenderState droneEnergy = new BlockModelRenderState();
    public final BlockModelRenderState droneLowEnergy = new BlockModelRenderState();
    public final BlockModelRenderState shutter = new BlockModelRenderState();
    public final BlockModelRenderState beam = new BlockModelRenderState();
    public final BlockModelRenderState ring = new BlockModelRenderState();
    public final BlockModelRenderState targetFrame = new BlockModelRenderState();
    public final BlockModelRenderState projectile = new BlockModelRenderState();
    public final ItemStackRenderState entityProjectile = new ItemStackRenderState();

    public float energyPulse = 1.0f;
    public float projectileProgress;
    public float buildProgress;
    public float flightProgress;
    public float shutterOpen;
    public float droneYaw;
    public float effectTime;
    public double droneX;
    public double droneY;
    public double droneZ;
    public double targetX;
    public double targetY;
    public double targetZ;
    public boolean hasTarget;
    public boolean droneVisible;
    public boolean droneLow;
    public boolean constructing;
    public boolean returning;
    public boolean recharging;
    public boolean projectileVisible;
    public boolean projectileIsItem;
    public ConstructorStatus status = ConstructorStatus.IDLE;
}
