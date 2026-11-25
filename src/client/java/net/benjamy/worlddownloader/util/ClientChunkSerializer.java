package net.benjamy.worlddownloader.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettesFactory;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Map;

/**
 * Serialisiert einen Client-Chunk ins Vanilla-Chunk-Format (vgl. net.minecraft.world.chunk.SerializedChunk).
 * Blöcke und Biome laufen über die Vanilla-Codecs, damit Palette, Bit-Packing und IDs exakt stimmen.
 */
@Environment(EnvType.CLIENT)
public class ClientChunkSerializer {

    public static NbtCompound serialize(ClientWorld world, WorldChunk chunk) {
        NbtCompound nbt = NbtHelper.putDataVersion(new NbtCompound());
        ChunkPos chunkPos = chunk.getPos();

        nbt.putInt("xPos", chunkPos.x);
        nbt.putInt("yPos", chunk.getBottomSectionCoord());
        nbt.putInt("zPos", chunkPos.z);
        nbt.putLong("LastUpdate", world.getTime());
        nbt.putLong("InhabitedTime", chunk.getInhabitedTime());
        nbt.putString("Status", "minecraft:full");
        // Kein Licht und keine Heightmaps speichern: Der Client hat davon nur einen Teil.
        // Fehlen sie, berechnet Minecraft beides beim Laden selbst neu (isLightOn=false, populateHeightmaps).
        nbt.putBoolean("isLightOn", false);

        PalettesFactory palettes = world.getPalettesFactory();
        var blockCodec = palettes.blockStatesContainerCodec();
        var biomeCodec = palettes.biomeContainerCodec();

        NbtList sections = new NbtList();
        ChunkSection[] sectionArray = chunk.getSectionArray();
        int minY = chunk.getBottomSectionCoord();

        for (int i = 0; i < sectionArray.length; i++) {
            ChunkSection section = sectionArray[i];
            if (section == null) continue;

            NbtCompound sectionNbt = new NbtCompound();
            sectionNbt.putByte("Y", (byte) (minY + i));
            // Immer beide Container schreiben — auch reine Luft-Sections haben Biome.
            sectionNbt.put("block_states", blockCodec.encodeStart(NbtOps.INSTANCE, section.getBlockStateContainer()).getOrThrow());
            sectionNbt.put("biomes", biomeCodec.encodeStart(NbtOps.INSTANCE, section.getBiomeContainer()).getOrThrow());
            sections.add(sectionNbt);
        }
        nbt.put("sections", sections);

        NbtList blockEntities = new NbtList();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            try {
                // Wie Vanilla (getPackedBlockEntityNbt): id + x/y/z + Daten
                NbtCompound beNbt = entry.getValue().createNbtWithIdentifyingData(world.getRegistryManager());
                blockEntities.add(ContainerTracker.applySavedItems(world, pos, beNbt));
            } catch (Exception e) {
                System.out.println("[WorldDownloader] Failed to serialize block entity at " + pos + ": " + e.getMessage());
            }
        }
        nbt.put("block_entities", blockEntities);

        NbtCompound structures = new NbtCompound();
        structures.put("starts", new NbtCompound());
        structures.put("References", new NbtCompound());
        nbt.put("structures", structures);

        return nbt;
    }
}
