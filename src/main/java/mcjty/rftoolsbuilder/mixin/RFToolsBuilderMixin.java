package mcjty.rftoolsbuilder.mixin;

import mcjty.rftoolsbuilder.constructor.ConstructorBootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "mcjty.rftoolsbuilder.RFToolsBuilder", remap = false)
public abstract class RFToolsBuilderMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void quantumtools$registerConstructor(IEventBus modBus, ModContainer container, CallbackInfo ci) {
        ConstructorBootstrap.init(modBus);
    }
}
