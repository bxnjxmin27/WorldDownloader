package net.benjamy.worlddownloader.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.RegionFile;
import net.minecraft.world.storage.StorageKey;
import org.lwjgl.glfw.GLFW;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * End-to-End-Test: Welt erstellen → Kiste/Rüstungsständer/verzaubertes Schwert → aufnehmen (I) → Kiste öffnen
 * → exportieren (J) → Dateien mit Vanillas RegionFile lesen → exportierte Welt im Spiel laden und prüfen.
 */
public class WorldDownloaderGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        BlockPos feet;
        BlockPos chestPos;
        BlockPos standPos;
        Set<String> clientBiomesInColumn = new HashSet<>();
        Path exportFolder;
        Set<Path> autoExportBefore;
        Path downloadsDir;

        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksDownload();
            feet = ctx.computeOnClient(c -> c.player.getBlockPos());
            chestPos = feet.south(2);
            standPos = feet.east(3);
            int x = feet.getX(), y = feet.getY(), z = feet.getZ();

            sp.getServer().runCommand("gamerule spawn_mobs false");
            sp.getServer().runCommand("fill " + (x - 1) + " " + y + " " + (z - 1) + " " + (x + 4) + " " + (y + 2) + " " + (z + 3) + " air");
            sp.getServer().runCommand("setblock " + chestPos.getX() + " " + y + " " + chestPos.getZ()
                    + " chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:5},{Slot:1b,id:\"minecraft:diamond_pickaxe\",count:1,components:{\"minecraft:enchantments\":{\"minecraft:efficiency\":5}}},{Slot:26b,id:\"minecraft:emerald\",count:3}]}");
            sp.getServer().runCommand("summon armor_stand " + standPos.getX() + " " + y + " " + standPos.getZ()
                    + " {CustomName:\"Testy\",CustomNameVisible:1b,equipment:{head:{id:\"minecraft:diamond_helmet\",count:1,components:{\"minecraft:enchantments\":{\"minecraft:unbreaking\":3}}}}}");
            // Spieler: verzauberte Rüstung + Offhand
            sp.getServer().runCommand("item replace entity @a armor.chest with diamond_chestplate[enchantments={\"minecraft:protection\":4}]");
            sp.getServer().runCommand("item replace entity @a armor.feet with netherite_boots[enchantments={\"minecraft:feather_falling\":4},custom_name=\"Bens Boots\"]");
            sp.getServer().runCommand("item replace entity @a weapon.offhand with shield");
            sp.getServer().runCommand("give @a diamond_sword[enchantments={\"minecraft:sharpness\":5}]");
            // Blick auf die Kiste (Süden = yaw 0)
            sp.getServer().runCommand("tp @a " + (x + 0.5) + " " + y + " " + (z + 0.5) + " 0 40");
            ctx.waitTicks(10);

            // Biome der ganzen Spalte merken (Client-Sicht)
            ctx.runOnClient(c -> {
                for (int sy = c.world.getBottomY(); sy < c.world.getTopYInclusive(); sy += 16) {
                    clientBiomesInColumn.add(c.world.getBiome(new BlockPos(x, sy + 8, z)).getKey().orElseThrow().getValue().toString());
                }
            });

            ctx.getInput().pressKey(GLFW.GLFW_KEY_I); // Aufnahme starten
            ctx.waitTicks(2);
            ctx.getInput().pressKey(options -> options.useKey); // Kiste öffnen
            ctx.waitForScreen(GenericContainerScreen.class);
            // warten, bis der Server den Inhalt geschickt hat
            ctx.waitFor(c -> c.player.currentScreenHandler.getSlot(0).hasStack(), 100);
            ctx.setScreen(() -> null);
            ctx.waitTicks(2);

            Path downloads = ctx.computeOnClient(c -> c.runDirectory.toPath().resolve("downloaded_worlds"));
            Set<Path> before = listDirs(downloads);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_J); // exportieren
            ctx.waitFor(c -> listDirs(downloads).size() > before.size(), 200);
            exportFolder = listDirs(downloads).stream().filter(p -> !before.contains(p)).findFirst().orElseThrow();
            // auf Hintergrund-Thread warten: Entity-Region ist das Letzte, was geschrieben wird
            ctx.waitFor(c -> Files.isDirectory(exportFolder.resolve("entities")), 400);
            ctx.waitTicks(20);

            // Nach dem Export etwas ändern — der Auto-Export beim Verlassen muss den NEUEN Stand haben
            sp.getServer().runCommand("setblock " + (x + 1) + " " + (y + 1) + " " + z + " gold_block");
            ctx.waitTicks(5);
            autoExportBefore = listDirs(downloads);
            downloadsDir = downloads;
        }

        // Auto-Export beim Verlassen (Singleplayer, Standard = an)
        final Set<Path> beforeAuto = autoExportBefore;
        final Path dl = downloadsDir;
        ctx.waitFor(c -> listDirs(dl).size() > beforeAuto.size(), 200);
        Path autoFolder = listDirs(dl).stream().filter(p -> !beforeAuto.contains(p)).findFirst().orElseThrow();
        ctx.waitFor(c -> Files.isDirectory(autoFolder.resolve("region")) && Files.exists(autoFolder.resolve("level.dat")), 400);
        ctx.waitTicks(20);
        NbtCompound autoChunk = readChunk(autoFolder.resolve("region"), new ChunkPos(feet.east()));
        check(autoChunk != null && autoChunk.toString().contains("minecraft:gold_block"),
                "Auto-Export beim Verlassen enthält den später gesetzten Goldblock nicht");
        System.out.println("[WD-TEST] Auto-Export beim Verlassen OK (enthält späteren Goldblock)");

        // ---------- Stufe 1: Dateien direkt prüfen ----------
        ChunkPos chunkPos = new ChunkPos(feet);
        NbtCompound chunk = readChunk(exportFolder.resolve("region"), chunkPos);
        check(chunk != null, "Chunk des Spielers fehlt im Export");

        NbtList sections = chunk.getListOrEmpty("sections");
        check(!sections.isEmpty(), "Keine Sections im Chunk");
        Set<String> exportedBiomes = new HashSet<>();
        for (NbtElement e : sections) {
            NbtCompound sec = (NbtCompound) e;
            check(sec.contains("biomes"), "Section Y=" + sec.getByte("Y", (byte) 0) + " hat keine Biome");
            for (NbtElement b : sec.getCompoundOrEmpty("biomes").getListOrEmpty("palette")) {
                exportedBiomes.add(b.asString().orElse(""));
            }
        }
        check(exportedBiomes.containsAll(clientBiomesInColumn),
                "Biome stimmen nicht: Client " + clientBiomesInColumn + " vs. Export " + exportedBiomes);

        NbtCompound chestChunk = readChunk(exportFolder.resolve("region"), new ChunkPos(chestPos));
        check(chestChunk != null, "Chunk der Kiste fehlt im Export");
        boolean chestHasItems = chestChunk.getListOrEmpty("block_entities").stream()
                .map(e -> (NbtCompound) e)
                .anyMatch(be -> be.getInt("x", 0) == chestPos.getX() && be.getInt("z", 0) == chestPos.getZ()
                        && be.getListOrEmpty("Items").size() == 3);
        check(chestHasItems, "Kisteninhalt fehlt im Chunk: " + chestChunk.getListOrEmpty("block_entities"));

        check(Files.isRegularFile(exportFolder.resolve("level.dat")), "level.dat fehlt");
        System.out.println("[WD-TEST] Stufe 1 OK — Biome " + exportedBiomes + ", Kiste gespeichert");

        // ---------- Stufe 2: exportierte Welt im Spiel laden ----------
        String saveName = "wd_gametest_" + System.currentTimeMillis();
        Path saves = ctx.computeOnClient(c -> c.runDirectory.toPath().resolve("saves"));
        copyDir(exportFolder, saves.resolve(saveName));

        ctx.runOnClient(c -> c.createIntegratedServerLoader().start(saveName, () -> {}));
        for (int i = 0; i < 60; i++) {
            if (ctx.computeOnClient(c -> c.world != null && c.player != null && c.getServer() != null)) break;
            String screen = ctx.computeOnClient(c -> c.currentScreen == null ? "none" : c.currentScreen.getClass().getName()
                    + " / " + c.currentScreen.getTitle().getString());
            System.out.println("[WD-TEST] waiting for world, screen = " + screen);
            if (i == 10) ctx.takeScreenshot("wd-load-wait");
            ctx.waitTicks(20);
        }
        check(ctx.computeOnClient(c -> c.world != null && c.getServer() != null), "Exportierte Welt hat nicht geladen");
        ctx.waitTicks(40);

        var server = ctx.computeOnClient(c -> c.getServer());
        var stage2 = server.submit(() -> {
            ServerWorld world = server.getWorld(World.OVERWORLD);

            // Kiste
            check(world.getBlockEntity(chestPos) instanceof ChestBlockEntity, "Kiste nicht geladen");
            ChestBlockEntity chest = (ChestBlockEntity) world.getBlockEntity(chestPos);
            check(chest.getStack(0).isOf(Items.DIAMOND) && chest.getStack(0).getCount() == 5, "Kiste Slot 0 falsch: " + chest.getStack(0));
            var enchReg = server.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
            check(chest.getStack(1).isOf(Items.DIAMOND_PICKAXE)
                    && chest.getStack(1).getEnchantments().getLevel(enchReg.getOrThrow(Enchantments.EFFICIENCY)) == 5,
                    "Verzauberte Spitzhacke in der Kiste falsch: " + chest.getStack(1));
            check(chest.getStack(26).isOf(Items.EMERALD) && chest.getStack(26).getCount() == 3, "Kiste Slot 26 falsch: " + chest.getStack(26));

            // Biome in der Höhe
            for (int sy = world.getBottomY(); sy < world.getTopYInclusive(); sy += 16) {
                String id = world.getBiome(new BlockPos(feet.getX(), sy + 8, feet.getZ())).getKey().orElseThrow().getValue().toString();
                check(clientBiomesInColumn.contains(id), "Biom bei Y=" + (sy + 8) + " ist " + id + ", erwartet " + clientBiomesInColumn);
            }

            // Rüstungsständer mit Namen
            ArmorStandEntity stand = null;
            for (var entity : world.iterateEntities()) {
                if (entity instanceof ArmorStandEntity a && a.getCustomName() != null
                        && a.getCustomName().getString().equals("Testy")) stand = a;
            }
            check(stand != null, "Rüstungsständer 'Testy' fehlt");
            ItemStack helmet = stand.getEquippedStack(EquipmentSlot.HEAD);
            check(helmet.isOf(Items.DIAMOND_HELMET)
                    && helmet.getEnchantments().getLevel(enchReg.getOrThrow(Enchantments.UNBREAKING)) == 3,
                    "Helm des Rüstungsständers falsch: " + helmet);

            // Spielerinventar mit Verzauberung
            var enchantments = server.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
            var sharpness = enchantments.getOrThrow(Enchantments.SHARPNESS);
            boolean sword = false;
            var inv = server.getPlayerManager().getPlayerList().getFirst().getInventory();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (s.isOf(Items.DIAMOND_SWORD) && s.getEnchantments().getLevel(sharpness) == 5) sword = true;
            }
            check(sword, "Verzaubertes Schwert fehlt im Inventar");

            var player = server.getPlayerManager().getPlayerList().getFirst();
            ItemStack chestplate = player.getEquippedStack(EquipmentSlot.CHEST);
            check(chestplate.isOf(Items.DIAMOND_CHESTPLATE)
                    && chestplate.getEnchantments().getLevel(enchReg.getOrThrow(Enchantments.PROTECTION)) == 4,
                    "Brustplatte falsch: " + chestplate);
            ItemStack boots = player.getEquippedStack(EquipmentSlot.FEET);
            check(boots.isOf(Items.NETHERITE_BOOTS)
                    && boots.getEnchantments().getLevel(enchReg.getOrThrow(Enchantments.FEATHER_FALLING)) == 4
                    && boots.getName().getString().equals("Bens Boots"),
                    "Stiefel falsch: " + boots + " / " + boots.getName().getString());
            check(player.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.SHIELD), "Schild in der Offhand fehlt");
        });
        // Im Gametest tickt der Server nur, während der Test wartet
        ctx.waitFor(c -> stage2.isDone(), 200);
        stage2.join();
        System.out.println("[WD-TEST] Stufe 2 OK — Kiste, Biome, Entity mit verzaubertem Helm, Spieler mit verzauberter Rüstung/Offhand/Schwert");

        // Welt so verlassen wie Fabrics TestSingleplayerContext#close
        ctx.runOnClient(c -> {
            c.world.disconnect(Text.translatable("menu.savingLevel"));
            c.disconnect(new MessageScreen(Text.translatable("menu.savingLevel")), false);
        });
        ctx.waitFor(c -> c.world == null && c.getServer() == null, 1200);
        ctx.setScreen(TitleScreen::new);
    }

    // -------------------------------------------------------------------------

    private static NbtCompound readChunk(Path regionDir, ChunkPos pos) {
        Path file = regionDir.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
        try (RegionFile region = new RegionFile(new StorageKey("test", World.OVERWORLD, "chunk"), file, regionDir, false);
             DataInputStream in = region.getChunkInputStream(pos)) {
            return in == null ? null : NbtIo.readCompound(in, NbtSizeTracker.ofUnlimitedBytes());
        } catch (IOException e) {
            throw new AssertionError("Region-Datei nicht lesbar: " + file, e);
        }
    }

    private static Set<Path> listDirs(Path dir) {
        if (!Files.isDirectory(dir)) return Set.of();
        try (Stream<Path> s = Files.list(dir)) {
            return new HashSet<>(s.filter(Files::isDirectory).toList());
        } catch (IOException e) {
            return Set.of();
        }
    }

    private static void copyDir(Path src, Path dest) {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path t = dest.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("[WD-TEST] " + message);
    }
}
