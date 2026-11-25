package net.benjamy.worlddownloader.mixin;

import net.benjamy.worlddownloader.WorldDownloaderClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bei Dimensionswechsel, Disconnect und Reconfiguration wirft der Client die alte Welt weg,
 * ohne ihre Chunks einzeln zu entladen. Vorher den aktuellen Stand sichern.
 */
@Environment(EnvType.CLIENT)
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "joinWorld", at = @At("HEAD"))
    private void worlddownloader$beforeWorldChange(ClientWorld world, CallbackInfo ci) {
        WorldDownloaderClient.snapshot((MinecraftClient) (Object) this);
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;ZZ)V", at = @At("HEAD"))
    private void worlddownloader$beforeDisconnect(Screen screen, boolean transferring, boolean stopSounds, CallbackInfo ci) {
        WorldDownloaderClient.snapshot((MinecraftClient) (Object) this);
    }

    @Inject(method = "enterReconfiguration", at = @At("HEAD"))
    private void worlddownloader$beforeReconfiguration(Screen screen, CallbackInfo ci) {
        WorldDownloaderClient.snapshot((MinecraftClient) (Object) this);
    }
}
