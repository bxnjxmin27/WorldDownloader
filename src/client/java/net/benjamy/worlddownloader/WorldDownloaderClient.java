package net.benjamy.worlddownloader;

import net.benjamy.worlddownloader.config.ModConfig;
import net.benjamy.worlddownloader.util.ChunkListener;
import net.benjamy.worlddownloader.util.ContainerTracker;
import net.benjamy.worlddownloader.util.EntityTracker;
import net.benjamy.worlddownloader.util.WorldExporter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Environment(EnvType.CLIENT)
public class WorldDownloaderClient implements ClientModInitializer {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");

    private static KeyBinding recordKey;
    private static KeyBinding exportKey;
    private static KeyBinding clearKey;

    /** Pfad zum Server-Resource-Pack (wird gesetzt, wenn der Server eins schickt) */
    public static volatile Path resourcePackLocation;

    private static volatile boolean recording = false;
    private static volatile boolean pendingDisconnect = false;

    public static boolean isRecording() {
        return recording;
    }

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = new KeyBinding.Category(Identifier.of("worlddownloader", "category"));
        recordKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.worlddownloader.record", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_I, category));
        exportKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.worlddownloader.export", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
        clearKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.worlddownloader.clear", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Direkter Wechsel (z. B. Server-Transfer), bevor der Tick den alten Disconnect verarbeitet hat:
            // alten Stand exportieren, OHNE die neue Welt zu snapshotten.
            if (pendingDisconnect) {
                pendingDisconnect = false;
                onDisconnected(client, false);
            }
            ModConfig config = ModConfig.getInstance();
            boolean singleplayer = client.isInSingleplayer();
            if (singleplayer ? config.isAutoStartSingleplayer() : config.isAutoStartServer()) {
                startRecording(client);
            }
        });

        // Achtung: DISCONNECT läuft auf dem Netzwerk-Thread, oft BEVOR der Client die Welt wegwirft.
        // client.execute() geht hier nicht — MinecraftClient#disconnect ruft cancelTasks() auf und
        // würde den Auftrag löschen. Deshalb nur ein Flag setzen; der Tick-Handler exportiert, sobald
        // die Welt weg ist (der letzte Snapshot ist dann im MinecraftClientMixin passiert).
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (recording) pendingDisconnect = true;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingDisconnect && client.world == null) {
                pendingDisconnect = false;
                onDisconnected(client, true);
            }
            while (recordKey.wasPressed()) {
                if (recording) stopRecording(client); else startRecording(client);
            }
            while (exportKey.wasPressed()) {
                export(client);
            }
            while (clearKey.wasPressed()) {
                clearAll();
                notify(client, Text.translatable("worlddownloader.msg.cleared"), Formatting.YELLOW);
            }
        });
    }

    // -------------------------------------------------------------------------

    private static void onDisconnected(MinecraftClient client, boolean mayCaptureWorld) {
        if (!recording) return;
        boolean singleplayer = WorldExporter.lastStateWasSingleplayer();
        ModConfig config = ModConfig.getInstance();
        if (singleplayer ? config.isAutoExportSingleplayer() : config.isAutoExportServer()) {
            export(client, mayCaptureWorld);
            recording = false;
        } else if (singleplayer) {
            recording = false;
            clearAll(); // Singleplayer ohne Auto-Export: verwerfen
        } else {
            recording = false;
            System.out.println("[WorldDownloader] Recording stopped. Press J to export manually.");
        }
    }

    private static void startRecording(MinecraftClient client) {
        recording = true;
        int loaded = ChunkListener.captureLoadedChunks(client);
        System.out.println("[WorldDownloader] Recording started (" + loaded + " chunks already loaded).");
        notify(client, Text.translatable("worlddownloader.msg.recording_started"), Formatting.GREEN);
    }

    private static void stopRecording(MinecraftClient client) {
        snapshot(client); // letzten Stand sichern, bevor wir aufhören
        recording = false;
        System.out.println("[WorldDownloader] Recording stopped.");
        notify(client, Text.translatable("worlddownloader.msg.recording_stopped", ChunkListener.getChunkCount()), Formatting.YELLOW);
    }

    /**
     * Erfasst den aktuellen Stand aller geladenen Chunks und Entities.
     * Wird vor Dimensionswechsel, Disconnect, Stop und Export aufgerufen — Momente, in denen
     * der Client seine Chunks ohne einzelnes Entladen wegwirft.
     */
    public static void snapshot(MinecraftClient client) {
        if (!recording || client.world == null || !client.isOnThread()) return;
        ChunkListener.captureLoadedChunks(client);
        EntityTracker.captureAll(client.world);
        WorldExporter.capturePlayerState(client, levelName(client));
    }

    private static void clearAll() {
        ChunkListener.clear();
        EntityTracker.clear();
        ContainerTracker.clear();
    }

    /**
     * Gemeinsamer Export für J und Auto-Export.
     * Auf dem Game-Thread: Snapshot, level.dat, Spielerdaten. Im Hintergrund: Region-Dateien.
     */
    private static void export(MinecraftClient client) {
        export(client, true);
    }

    private static void export(MinecraftClient client, boolean captureWorld) {
        if (captureWorld) {
            snapshot(client);
            if (client.world != null) WorldExporter.capturePlayerState(client, levelName(client));
        }
        int chunkCount = ChunkListener.getChunkCount();
        if (chunkCount == 0) {
            notify(client, Text.translatable("worlddownloader.msg.nothing"), Formatting.RED);
            return;
        }

        String levelName = WorldExporter.lastLevelName();
        String stamp = LocalDateTime.now().format(TIMESTAMP);
        Path folder = uniqueFolder(client.runDirectory.toPath().resolve("downloaded_worlds"),
                sanitize(levelName) + "_" + stamp);

        var chunks = ChunkListener.drain();
        var entities = EntityTracker.drain();
        int entityCount = entities.values().stream().mapToInt(java.util.Map::size).sum();

        WorldExporter.createLoadableWorld(folder, levelName + " (" + stamp.replace('_', ' ') + ")");
        if (ModConfig.getInstance().isExportWithTexturePack()) {
            exportResourcePack(folder);
        }

        notify(client, Text.translatable("worlddownloader.msg.export_started", chunkCount), Formatting.AQUA);
        System.out.println("[WorldDownloader] Exporting " + chunkCount + " chunks and " + entityCount + " entities to " + folder);

        Thread thread = new Thread(() -> {
            try {
                ChunkListener.writeRegions(folder, chunks);
                EntityTracker.writeEntities(folder, entities);
                System.out.println("[WorldDownloader] World exported to " + folder);
                client.execute(() -> notify(client,
                        Text.translatable("worlddownloader.msg.export_done", folder.getFileName().toString()), Formatting.GREEN));
            } catch (Exception e) {
                System.out.println("[WorldDownloader] Failed to export world: " + e.getMessage());
                e.printStackTrace();
                client.execute(() -> notify(client,
                        Text.translatable("worlddownloader.msg.export_failed", String.valueOf(e.getMessage())), Formatting.RED));
            }
        }, "WorldDownloader-Export");
        thread.setDaemon(true);
        thread.start();
    }

    private static String levelName(MinecraftClient client) {
        if (client.getCurrentServerEntry() != null) return client.getCurrentServerEntry().name;
        if (client.getServer() != null) return client.getServer().getSaveProperties().getLevelName();
        return "Downloaded World";
    }

    private static String sanitize(String name) {
        String s = name.replaceAll("[^A-Za-z0-9 ._-]", "_").trim();
        return s.isEmpty() ? "World" : s;
    }

    private static Path uniqueFolder(Path parent, String name) {
        Path p = parent.resolve(name);
        for (int i = 2; Files.exists(p); i++) p = parent.resolve(name + "_" + i);
        return p;
    }

    /** Vanilla lädt <welt>/resources.zip automatisch als Welt-Resourcepack. */
    private static void exportResourcePack(Path worldFolder) {
        Path source = resourcePackLocation;
        if (source == null || !Files.isRegularFile(source)) return;
        try {
            Files.copy(source, worldFolder.resolve("resources.zip"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[WorldDownloader] Failed to export resource pack: " + e.getMessage());
        }
    }

    private static void notify(MinecraftClient client, Text text, Formatting color) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[WorldDownloader] ").formatted(Formatting.GRAY)
                    .append(text.copy().formatted(color)), true);
        }
    }
}
