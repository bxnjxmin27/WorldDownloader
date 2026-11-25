package net.benjamy.worlddownloader.mixin;

import net.benjamy.worlddownloader.util.ContainerTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(HandledScreen.class)
public class HandledScreenMixin<T extends ScreenHandler> {

    @Shadow @Final protected T handler;

    /** Fenster wird geschlossen → Inhalt der Container-Slots übernehmen. */
    @Inject(method = "removed", at = @At("HEAD"))
    private void worlddownloader$onRemoved(CallbackInfo ci) {
        if (handler != null) {
            ContainerTracker.onContainerClosed(handler, MinecraftClient.getInstance().world);
        }
    }
}
