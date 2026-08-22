package mcjty.rftoolsbuilder.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "mcjty.rftoolsbuilder.BuilderBlockEntity", remap = false)
public abstract class BuilderSpeedMixin {
    private static final int WORK_INTERVAL_TICKS = 4;

    @Inject(method = "work", at = @At("HEAD"), cancellable = true, remap = false)
    private void quantumtools$limitMiningRate(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level != null && Math.floorMod(level.getGameTime(), WORK_INTERVAL_TICKS) != 0L) {
            ci.cancel();
        }
    }
}
