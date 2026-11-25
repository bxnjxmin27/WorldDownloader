package net.benjamy.worlddownloader.util;

import net.benjamy.worlddownloader.config.ModConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.StackWithSlot;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Merkt sich Container-Inhalte (Kisten, Fässer, Öfen …), sobald der Spieler sie öffnet.
 * Der Server schickt Inhalte nur beim Öffnen — deshalb wird beim Schließen aus den Container-Slots gelesen.
 */
@Environment(EnvType.CLIENT)
public class ContainerTracker {

    /** syncId → Position des geöffneten Blocks */
    private static final Map<Integer, GlobalPos> openContainers = new ConcurrentHashMap<>();
    /** Position → gespeicherte Items (Vanilla-Format: Liste aus {Slot, id, count, components}) */
    private static final Map<GlobalPos, NbtList> savedItems = new ConcurrentHashMap<>();
    /** Inhalt der Enderkiste — gehört dem Spieler, nicht dem Block */
    private static volatile NbtList enderItems;

    public static void onContainerOpened(int syncId, ClientWorld world, BlockPos pos) {
        if (!ModConfig.getInstance().isSaveContainers() || world == null || pos == null) return;
        openContainers.put(syncId, GlobalPos.create(world.getRegistryKey(), pos.toImmutable()));
    }

    public static void onContainerClosed(ScreenHandler handler, ClientWorld world) {
        GlobalPos target = openContainers.remove(handler.syncId);
        if (target == null || world == null || world.getRegistryKey() != target.dimension()) return;

        // Nur Container-Slots, nicht das Spielerinventar. slot.getIndex() = Index im Container-Inventar.
        List<StackWithSlot> stacks = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            stacks.add(new StackWithSlot(slot.getIndex(), slot.getStack().copy()));
        }

        BlockPos pos = target.pos();
        BlockState state = world.getBlockState(pos);

        if (state.getBlock() instanceof EnderChestBlock) {
            enderItems = encode(world, stacks, 0, 27);
        } else if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE
                && stacks.size() >= 54) {
            // Doppelkiste: Vanilla-DoubleInventory = (RIGHT-Hälfte: Slots 0–26, LEFT-Hälfte: Slots 27–53)
            BlockPos other = pos.offset(ChestBlock.getFacing(state));
            boolean isRight = state.get(ChestBlock.CHEST_TYPE) == ChestType.RIGHT;
            BlockPos rightPos = isRight ? pos : other;
            BlockPos leftPos = isRight ? other : pos;
            save(world, rightPos, encode(world, stacks, 0, 27));
            save(world, leftPos, encode(world, stacks, 27, 54));
        } else {
            save(world, pos, encode(world, stacks, 0, Integer.MAX_VALUE));
        }
    }

    private static NbtList encode(ClientWorld world, List<StackWithSlot> stacks, int from, int to) {
        var ops = world.getRegistryManager().getOps(NbtOps.INSTANCE);
        NbtList list = new NbtList();
        for (StackWithSlot s : stacks) {
            if (s.stack().isEmpty() || s.slot() < from || s.slot() >= to) continue;
            StackWithSlot shifted = new StackWithSlot(s.slot() - from, s.stack());
            StackWithSlot.CODEC.encodeStart(ops, shifted).result().ifPresent(list::add);
        }
        return list;
    }

    private static void save(ClientWorld world, BlockPos pos, NbtList items) {
        savedItems.put(GlobalPos.create(world.getRegistryKey(), pos.toImmutable()), items);
        // Chunk sofort neu erfassen, damit die Items im Cache landen
        WorldChunk chunk = world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        ChunkListener.capture(world, chunk);
    }

    /** Wird beim Serialisieren jedes Block-Entities aufgerufen und ergänzt gespeicherte Items. */
    public static NbtCompound applySavedItems(World world, BlockPos pos, NbtCompound blockEntityNbt) {
        NbtList items = savedItems.get(GlobalPos.create(world.getRegistryKey(), pos));
        if (items != null) {
            blockEntityNbt.put("Items", items.copy());
        }
        return blockEntityNbt;
    }

    public static NbtList getEnderItems() {
        return enderItems;
    }

    public static int getTotalSavedContainers() {
        return savedItems.size();
    }

    public static void clear() {
        openContainers.clear();
        savedItems.clear();
        enderItems = null;
    }
}
