package net.benjamy.worlddownloader.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtString;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.WorldProperties;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.entity.boss.dragon.EnderDragonFight;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Schreibt level.dat und die Spielerdaten, damit der Export als normale Einzelspielerwelt startet.
 * Format nach net.minecraft.world.level.LevelProperties#updateProperties bzw. ServerPlayerEntity#writeCustomData.
 */
@Environment(EnvType.CLIENT)
public class WorldExporter {

    /**
     * Zuletzt erfasster Stand von Welt und Spieler. Wird bei jedem Snapshot auf dem Game-Thread aktualisiert,
     * damit ein Auto-Export nach dem Disconnect (Spieler und Welt sind dann schon weg) noch alles hat.
     */
    private record State(String levelName, boolean singleplayer, long time, long dayTime,
                         WorldProperties.SpawnPoint spawn, NbtCompound player, String playerUuid,
                         FeatureSet features) {}

    private static volatile State state;

    public static void capturePlayerState(MinecraftClient client, String levelName) {
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null) return;

        // Yaw muss in [-180, 180] liegen, sonst verwirft SpawnPoint.CODEC den ganzen Spawn
        // und Minecraft setzt dich auf 0,0.
        WorldProperties.SpawnPoint spawn = new WorldProperties.SpawnPoint(
                GlobalPos.create(world.getRegistryKey(), safeStandingPos(world, player)),
                MathHelper.wrapDegrees(player.getYaw()), 0f);

        state = new State(levelName, client.isInSingleplayer(), world.getTime(), world.getTimeOfDay(),
                spawn, playerNbt(world, player), player.getUuidAsString(), world.getEnabledFeatures());
    }

    public static String lastLevelName() {
        State s = state;
        return s != null ? s.levelName() : "Downloaded World";
    }

    public static boolean lastStateWasSingleplayer() {
        State s = state;
        return s != null && s.singleplayer();
    }

    public static void createLoadableWorld(Path worldFolder, String displayName) {
        try {
            Files.createDirectories(worldFolder);
            writeLevelDat(worldFolder, displayName);
            writePlayerData(worldFolder);
        } catch (Exception e) {
            System.out.println("[WorldDownloader] Failed to create world files: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void writeLevelDat(Path worldFolder, String levelName) throws IOException {
        State st = state;

        NbtCompound data = NbtHelper.putDataVersion(new NbtCompound());
        data.putString("LevelName", levelName);
        data.putInt("version", 19133);
        data.putLong("LastPlayed", System.currentTimeMillis());
        data.putLong("Time", st != null ? st.time() : 0L);
        data.putLong("DayTime", st != null ? st.dayTime() : 6000L);
        data.putInt("GameType", 1); // Creative
        data.putBoolean("hardcore", false);
        data.putBoolean("allowCommands", true);
        data.putByte("Difficulty", (byte) 2);
        data.putBoolean("DifficultyLocked", false);
        data.putBoolean("initialized", true);
        data.putBoolean("WasModded", true);

        data.put("spawn", WorldProperties.SpawnPoint.CODEC, st != null ? st.spawn() : WorldProperties.SpawnPoint.DEFAULT);
        data.put("DragonFight", EnderDragonFight.Data.CODEC, EnderDragonFight.Data.DEFAULT);

        NbtCompound version = new NbtCompound();
        var gameVersion = SharedConstants.getGameVersion();
        version.putString("Name", gameVersion.name());
        version.putInt("Id", gameVersion.dataVersion().id());
        version.putBoolean("Snapshot", !gameVersion.stable());
        version.putString("Series", gameVersion.dataVersion().series());
        data.put("Version", version);

        // Neue (nicht heruntergeladene) Chunks in der Oberwelt werden leer generiert.
        // Nether und End MÜSSEN die Vanilla-Generatoren behalten, sonst gilt die Welt als
        // "experimentell" (DimensionOptionsRegistryHolder#isVanilla) und Minecraft warnt beim Öffnen.
        NbtCompound worldGen = new NbtCompound();
        // Seed des Servers kennt der Client nicht — nur Nether/End-Neugenerierung hängt davon ab.
        worldGen.putLong("seed", 0L);
        worldGen.putBoolean("generate_features", false);
        worldGen.putBoolean("bonus_chest", false);
        NbtCompound dimensions = new NbtCompound();
        dimensions.put("minecraft:overworld", voidDimension("minecraft:overworld"));
        dimensions.put("minecraft:the_nether", noiseDimension("minecraft:the_nether", "minecraft:nether",
                biomeSource("minecraft:multi_noise", "minecraft:nether")));
        dimensions.put("minecraft:the_end", noiseDimension("minecraft:the_end", "minecraft:end",
                biomeSource("minecraft:the_end", null)));
        worldGen.put("dimensions", dimensions);
        data.put("WorldGenSettings", worldGen);

        NbtCompound dataPacks = new NbtCompound();
        NbtList enabled = new NbtList();
        enabled.add(NbtString.of("vanilla"));
        dataPacks.put("Enabled", enabled);
        dataPacks.put("Disabled", new NbtList());
        data.put("DataPacks", dataPacks);
        // Ohne enabled_features hält Minecraft die Welt für "experimentell" und warnt beim Öffnen.
        // Wir übernehmen die Features der Server-Welt (meist nur minecraft:vanilla).
        data.put("enabled_features", FeatureFlags.CODEC,
                st != null ? st.features() : FeatureFlags.DEFAULT_ENABLED_FEATURES);
        // game_rules werden bewusst weggelassen → Minecraft nimmt die Standardwerte

        NbtCompound root = new NbtCompound();
        root.put("Data", data);
        NbtIo.writeCompressed(root, worldFolder.resolve("level.dat"));
    }

    private static NbtCompound noiseDimension(String type, String settings, NbtCompound biomeSource) {
        NbtCompound generator = new NbtCompound();
        generator.putString("type", "minecraft:noise");
        generator.putString("settings", settings);
        generator.put("biome_source", biomeSource);

        NbtCompound dim = new NbtCompound();
        dim.putString("type", type);
        dim.put("generator", generator);
        return dim;
    }

    private static NbtCompound biomeSource(String type, String preset) {
        NbtCompound source = new NbtCompound();
        source.putString("type", type);
        if (preset != null) source.putString("preset", preset);
        return source;
    }

    private static NbtCompound voidDimension(String type) {
        NbtCompound settings = new NbtCompound();
        settings.put("layers", new NbtList());
        settings.putString("biome", "minecraft:the_void");
        settings.putBoolean("features", false);
        settings.putBoolean("lakes", false);
        settings.put("structure_overrides", new NbtList());

        NbtCompound generator = new NbtCompound();
        generator.putString("type", "minecraft:flat");
        generator.put("settings", settings);

        NbtCompound dim = new NbtCompound();
        dim.putString("type", type);
        dim.put("generator", generator);
        return dim;
    }

    /**
     * Spielerdaten über Vanillas eigene Speicherlogik (Inventar inkl. Verzauberungen/Komponenten,
     * Rüstung, Offhand, XP, Hunger, Effekte …) plus die Felder, die sonst nur der Server schreibt.
     */
    private static NbtCompound playerNbt(ClientWorld world, ClientPlayerEntity player) {
        NbtWriteView view = NbtWriteView.create(ErrorReporter.EMPTY, world.getRegistryManager());
        player.writeData(view);
        NbtCompound nbt = view.getNbt();

        nbt.putInt("playerGameType", 1); // Creative
        nbt.putString("Dimension", world.getRegistryKey().getValue().toString());
        nbt.remove("RootVehicle");

        BlockPos stand = safeStandingPos(world, player);
        NbtList pos = new NbtList();
        pos.add(NbtDouble.of(player.getX()));
        pos.add(NbtDouble.of(stand.getY()));
        pos.add(NbtDouble.of(player.getZ()));
        nbt.put("Pos", pos);

        NbtList motion = new NbtList();
        motion.add(NbtDouble.of(0));
        motion.add(NbtDouble.of(0));
        motion.add(NbtDouble.of(0));
        nbt.put("Motion", motion);
        nbt.putDouble("fall_distance", 0.0);
        return nbt;
    }

    private static void writePlayerData(Path worldFolder) throws IOException {
        State st = state;
        if (st == null) return;

        NbtCompound nbt = st.player().copy();
        NbtList ender = ContainerTracker.getEnderItems();
        if (ender != null) nbt.put("EnderItems", ender.copy());

        Path dir = worldFolder.resolve("playerdata");
        Files.createDirectories(dir);
        NbtIo.writeCompressed(nbt, dir.resolve(st.playerUuid() + ".dat"));
    }

    /** Wo der Spieler steht — oder, falls er fliegt/fällt, die Oberfläche darunter. */
    private static BlockPos safeStandingPos(ClientWorld world, ClientPlayerEntity player) {
        BlockPos pos = player.getBlockPos();
        if (player.isOnGround()) return pos;
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, pos.getX(), pos.getZ());
        return top > world.getBottomY() ? new BlockPos(pos.getX(), top, pos.getZ()) : pos;
    }
}
