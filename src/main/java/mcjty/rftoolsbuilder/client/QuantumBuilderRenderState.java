package mcjty.rftoolsbuilder.client;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

public final class QuantumBuilderRenderState extends BlockEntityRenderState {
    public boolean visible;
    public Direction facing = Direction.SOUTH;
    public int chunkX;
    public int chunkZ;
    public int progressPermille;
    public int status;
}
