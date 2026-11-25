package net.benjamy.worlddownloader.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionType;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DeflaterOutputStream;

/**
 * Hält die aufgezeichneten Chunks — getrennt pro Dimension — und schreibt sie als Anvil-Region-Dateien.
 */
@Environment(EnvType.CLIENT)
public class ChunkListener {

    private static final int SECTOR_BYTES = 4096;
    /** Vanilla RegionFile: Bit 128 im Compression-Byte = Chunk liegt in externer c.X.Z.mcc-Datei. */
    private static final int EXTERNAL_FLAG = 128;
    private static final int ZLIB = 2;

    private static volatile Map<RegistryKey<World>, Map<ChunkPos, NbtCompound>> cache = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Aufzeichnen
    // -------------------------------------------------------------------------

    /** Serialisiert den aktuellen Zustand eines Chunks und legt ihn in den Cache (überschreibt ältere Stände). */
    public static void capture(ClientWorld world, WorldChunk chunk) {
        if (world == null || chunk == null) return;
        try {
            NbtCompound nbt = ClientChunkSerializer.serialize(world, chunk);
            cache.computeIfAbsent(world.getRegistryKey(), k -> new ConcurrentHashMap<>()).put(chunk.getPos(), nbt);
        } catch (Exception e) {
            System.out.println("[WorldDownloader] Failed to capture chunk " + chunk.getPos() + ": " + e.getMessage());
        }
    }

    /** Erfasst alle aktuell im Client geladenen Chunks (z. B. beim Start, Stop, Export oder Dimensionswechsel). */
    public static int captureLoadedChunks(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || client.player == null) return 0;

        var chunkManager = world.getChunkManager();
        ChunkPos center = client.player.getChunkPos();
        int radius = Math.min(48, client.options.getViewDistance().getValue() + 16);
        int count = 0;

        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                WorldChunk chunk = chunkManager.getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    capture(world, chunk);
                    count++;
                }
            }
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    /**
     * Nimmt alle gecachten Chunks heraus und setzt den Cache zurück.
     * So kann die Aufnahme weiterlaufen, während der Export-Thread schreibt.
     */
    public static Map<RegistryKey<World>, Map<ChunkPos, NbtCompound>> drain() {
        Map<RegistryKey<World>, Map<ChunkPos, NbtCompound>> old = cache;
        cache = new ConcurrentHashMap<>();
        return old;
    }

    /** Schreibt die Chunks jeder Dimension in deren Vanilla-Ordner (region/, DIM-1/region/, DIM1/region/ …). */
    public static void writeRegions(Path worldFolder, Map<RegistryKey<World>, Map<ChunkPos, NbtCompound>> chunks) throws IOException {
        for (var dim : chunks.entrySet()) {
            Path regionDir = DimensionType.getSaveDirectory(dim.getKey(), worldFolder).resolve("region");
            writeGroupedByRegion(regionDir, dim.getValue());
        }
    }

    /** Gruppiert Chunks nach 32x32-Region und schreibt je eine r.X.Z.mca. */
    public static void writeGroupedByRegion(Path dir, Map<ChunkPos, NbtCompound> chunks) throws IOException {
        if (chunks.isEmpty()) return;
        Files.createDirectories(dir);

        Map<Long, Map<ChunkPos, NbtCompound>> regions = new HashMap<>();
        for (var entry : chunks.entrySet()) {
            ChunkPos pos = entry.getKey();
            long key = ChunkPos.toLong(pos.getRegionX(), pos.getRegionZ());
            regions.computeIfAbsent(key, k -> new HashMap<>()).put(pos, entry.getValue());
        }

        for (var region : regions.entrySet()) {
            int rx = ChunkPos.getPackedX(region.getKey());
            int rz = ChunkPos.getPackedZ(region.getKey());
            writeMcaFile(dir, dir.resolve("r." + rx + "." + rz + ".mca"), region.getValue());
        }
    }

    /**
     * Schreibt eine MCA-Datei im Anvil-Format (wie net.minecraft.world.storage.RegionFile).
     * Chunks, die größer als 255 Sektoren (~1 MB) sind, landen wie in Vanilla in einer externen c.X.Z.mcc.
     */
    private static void writeMcaFile(Path dir, Path path, Map<ChunkPos, NbtCompound> chunks) throws IOException {
        record Entry(int index, byte[] data, boolean external) {}
        List<Entry> entries = new ArrayList<>();

        for (var e : chunks.entrySet()) {
            ChunkPos pos = e.getKey();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(baos))) {
                NbtIo.writeCompound(e.getValue(), out);
            }
            byte[] data = baos.toByteArray();
            int index = Math.floorMod(pos.x, 32) + Math.floorMod(pos.z, 32) * 32;

            boolean external = sectorsFor(5 + data.length) > 255;
            if (external) {
                Files.write(dir.resolve("c." + pos.x + "." + pos.z + ".mcc"), data);
            }
            entries.add(new Entry(index, data, external));
        }

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.setLength(0);
            int[] locations = new int[1024];
            int sector = 2; // Sektor 0 = Locations, Sektor 1 = Timestamps
            int timestamp = (int) (System.currentTimeMillis() / 1000L);

            for (Entry e : entries) {
                int payload = e.external() ? 1 : e.data().length + 1;
                int sectors = sectorsFor(4 + payload);
                locations[e.index()] = (sector << 8) | sectors;

                raf.seek((long) sector * SECTOR_BYTES);
                raf.writeInt(payload);
                raf.writeByte(e.external() ? (ZLIB | EXTERNAL_FLAG) : ZLIB);
                if (!e.external()) raf.write(e.data());
                sector += sectors;
            }
            raf.setLength((long) sector * SECTOR_BYTES); // letzten Sektor auffüllen

            raf.seek(0);
            for (int loc : locations) raf.writeInt(loc);
            for (int loc : locations) raf.writeInt(loc != 0 ? timestamp : 0);
        }
    }

    private static int sectorsFor(int bytes) {
        return (bytes + SECTOR_BYTES - 1) / SECTOR_BYTES;
    }

    // -------------------------------------------------------------------------

    public static int getChunkCount() {
        return cache.values().stream().mapToInt(Map::size).sum();
    }

    public static void clear() {
        cache = new ConcurrentHashMap<>();
    }
}
