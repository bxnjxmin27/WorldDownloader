package net.benjamy.worlddownloader.mixin;

import net.benjamy.worlddownloader.WorldDownloaderClient;
import net.benjamy.worlddownloader.util.ChunkListener;
import net.benjamy.worlddownloader.util.EntityTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Consumer;

@Environment(EnvType.CLIENT)
@Mixin(ClientChunkManager.class)
public abstract class ClientChunkManagerMixin {

    @Shadow @Final ClientWorld world;

    /** Neuer Chunk vom Server → sofort erfassen. */
    @Inject(method = "loadChunkFromPacket", at = @At("RETURN"))
    private void worlddownloader$onLoad(int x, int z, PacketByteBuf buf, Map<Heightmap.Type, long[]> heightmaps,
                                        Consumer<ChunkData.BlockEntityVisitor> consumer,
                                        CallbackInfoReturnable<WorldChunk> cir) {
        if (!WorldDownloaderClient.isRecording()) return;
        ChunkListener.capture(this.world, cir.getReturnValue());
    }

    /**
     * Chunk wird entladen → letzten Stand sichern. So landen auch Blöcke, die nach dem Laden
     * geändert wurden (abgebaut, gebaut, Türen …), und die Entities des Chunks im Export.
     */
    @Inject(method = "unload", at = @At("HEAD"))
    private void worlddownloader$onUnload(ChunkPos pos, CallbackInfo ci) {
        if (!WorldDownloaderClient.isRecording()) return;
        WorldChunk chunk = ((ClientChunkManager) (Object) this).getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
        if (chunk == null) return;
        ChunkListener.capture(this.world, chunk);
        EntityTracker.captureChunk(this.world, pos);
    }
}
