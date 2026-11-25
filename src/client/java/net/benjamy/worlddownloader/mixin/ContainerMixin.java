package net.benjamy.worlddownloader.mixin;

import net.benjamy.worlddownloader.WorldDownloaderClient;
import net.benjamy.worlddownloader.util.ContainerTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(ClientPlayNetworkHandler.class)
public class ContainerMixin {

    /** Server öffnet ein Container-Fenster → merken, welcher Block angeschaut wird. */
    @Inject(method = "onOpenScreen", at = @At("TAIL"))
    private void worlddownloader$onOpenScreen(OpenScreenS2CPacket packet, CallbackInfo ci) {
        if (!WorldDownloaderClient.isRecording()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            ContainerTracker.onContainerOpened(packet.getSyncId(), client.world, hit.getBlockPos());
        }
    }
}
