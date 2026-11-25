package net.benjamy.worlddownloader.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speichert Entities mit Vanillas eigener Speicherlogik (Entity#saveData) — also im aktuellen Format
 * inklusive Ausrüstung, Namen, Varianten, Item-Frames, Gemälden, Rüstungsständern und Passagieren.
 * Entities werden pro Dimension und UUID gehalten; ein neuer Stand überschreibt den alten.
 */
@Environment(EnvType.CLIENT)
public class EntityTracker {

    public record Captured(ChunkPos chunk, NbtCompound nbt) {}

    private static volatile Map<RegistryKey<World>, Map<UUID, Captured>> cache = new ConcurrentHashMap<>();

    public static void captureAll(ClientWorld world) {
        if (world == null) return;
        for (Entity entity : world.getEntities()) {
            capture(world, entity);
        }
    }

    /** Erfasst die Entities eines einzelnen Chunks (z. B. kurz bevor er entladen wird). */
    public static void captureChunk(ClientWorld world, ChunkPos pos) {
        if (world == null) return;
        for (Entity entity : world.getEntities()) {
            if (entity.getChunkPos().equals(pos)) {
                capture(world, entity);
            }
        }
    }

    private static void capture(ClientWorld world, Entity entity) {
        // Spieler gehören nicht in die Entity-Dateien (Vanilla speichert sie dort auch nicht)
        if (entity instanceof PlayerEntity) return;
        try {
            NbtWriteView view = NbtWriteView.create(ErrorReporter.EMPTY, world.getRegistryManager());
            // saveData() lässt Reiter aus — die werden vom Fahrzeug als "Passengers" mitgespeichert
            if (!entity.saveData(view)) return;
            cache.computeIfAbsent(world.getRegistryKey(), k -> new ConcurrentHashMap<>())
                    .put(entity.getUuid(), new Captured(entity.getChunkPos(), view.getNbt()));
        } catch (Throwable t) {
            System.out.println("[WorldDownloader] Failed to save entity " + entity + ": " + t.getMessage());
        }
    }

    public static Map<RegistryKey<World>, Map<UUID, Captured>> drain() {
        var old = cache;
        cache = new ConcurrentHashMap<>();
        return old;
    }

    /** Schreibt entities/r.X.Z.mca pro Dimension im Vanilla-Format (vgl. EntityChunkDataAccess). */
    public static void writeEntities(Path worldFolder, Map<RegistryKey<World>, Map<UUID, Captured>> entities) throws IOException {
        for (var dim : entities.entrySet()) {
            Map<ChunkPos, NbtList> byChunk = new HashMap<>();
            for (Captured c : dim.getValue().values()) {
                byChunk.computeIfAbsent(c.chunk(), k -> new NbtList()).add(c.nbt());
            }

            Map<ChunkPos, NbtCompound> chunks = new HashMap<>();
            byChunk.forEach((pos, list) -> {
                NbtCompound chunk = NbtHelper.putDataVersion(new NbtCompound());
                chunk.put("Entities", list);
                chunk.put("Position", ChunkPos.CODEC, pos);
                chunks.put(pos, chunk);
            });

            Path dir = DimensionType.getSaveDirectory(dim.getKey(), worldFolder).resolve("entities");
            ChunkListener.writeGroupedByRegion(dir, chunks);
        }
    }

    public static int getTotalTrackedEntities() {
        return cache.values().stream().mapToInt(Map::size).sum();
    }

    public static void clear() {
        cache = new ConcurrentHashMap<>();
    }
}
