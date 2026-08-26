package mcjty.rftoolsbuilder.extractor.client;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

public final class ExtractorRenderState extends BlockEntityRenderState {
    public final BlockModelRenderState sample = new BlockModelRenderState();
    public boolean hasSample;
    public boolean running;
    public float effectTime;
    public float progress;
}
