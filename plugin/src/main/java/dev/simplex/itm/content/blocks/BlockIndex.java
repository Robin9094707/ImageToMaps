package dev.simplex.itm.content.blocks;

import dev.simplex.itm.SimplexPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Remembers where custom blocks are: positions are stored in each chunk's persistent data, and the set of chunks
 * that ever contained custom blocks is kept in {@code data/block-chunks.yml}. This makes deleting a block type
 * "live": every placed copy (also in unloaded chunks) is replaced, e.g. with dirt.
 */
public final class BlockIndex {

    private final SimplexPlugin plugin;
    private final NamespacedKey key;
    private final File file;
    private final Map<String, Set<Long>> chunks = new HashMap<>();
    private boolean dirty;

    public BlockIndex(SimplexPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "blocks");
        this.file = new File(plugin.getDataFolder(), "data/block-chunks.yml");
    }

    public void load() {
        chunks.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String world : cfg.getKeys(false)) {
            Set<Long> set = new HashSet<>();
            for (Object o : cfg.getList(world, List.of())) {
                try { set.add(Long.parseLong(String.valueOf(o))); } catch (NumberFormatException ignored) { }
            }
            chunks.put(world, set);
        }
    }

    public void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        YamlConfiguration cfg = new YamlConfiguration();
        chunks.forEach((w, set) -> cfg.set(w, set.stream().map(String::valueOf).toList()));
        try {
            file.getParentFile().mkdirs();
            cfg.save(file);
        } catch (IOException ignored) {
        }
    }

    private static long entry(int slot, Block b) {
        int local = (b.getX() & 15) | ((b.getZ() & 15) << 4) | ((b.getY() + 2048) << 8);
        return ((long) slot << 32) | (local & 0xFFFFFFFFL);
    }

    public void add(Block block, int slot) {
        Chunk chunk = block.getChunk();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        long[] arr = pdc.getOrDefault(key, PersistentDataType.LONG_ARRAY, new long[0]);
        long e = entry(slot, block);
        long pos = e & 0xFFFFFFFFL;
        List<Long> list = new ArrayList<>(arr.length + 1);
        for (long l : arr) if ((l & 0xFFFFFFFFL) != pos) list.add(l);
        list.add(e);
        pdc.set(key, PersistentDataType.LONG_ARRAY, list.stream().mapToLong(Long::longValue).toArray());
        if (chunks.computeIfAbsent(block.getWorld().getName(), w -> new HashSet<>()).add(chunk.getChunkKey())) dirty = true;
    }

    public void remove(Block block) {
        Chunk chunk = block.getChunk();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        long[] arr = pdc.get(key, PersistentDataType.LONG_ARRAY);
        if (arr == null) return;
        long pos = entry(0, block) & 0xFFFFFFFFL;
        long[] next = java.util.Arrays.stream(arr).filter(l -> (l & 0xFFFFFFFFL) != pos).toArray();
        if (next.length == 0) pdc.remove(key);
        else pdc.set(key, PersistentDataType.LONG_ARRAY, next);
    }

    /** Replaces all blocks of a retired slot in one chunk. Returns how many were replaced. */
    public int purge(Chunk chunk, int slot, Material replacement) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        long[] arr = pdc.get(key, PersistentDataType.LONG_ARRAY);
        if (arr == null) return 0;
        int replaced = 0;
        List<Long> keep = new ArrayList<>();
        for (long l : arr) {
            int s = (int) (l >>> 32);
            if (s != slot) {
                keep.add(l);
                continue;
            }
            int local = (int) l;
            int x = (chunk.getX() << 4) | (local & 15);
            int z = (chunk.getZ() << 4) | ((local >> 4) & 15);
            int y = (local >>> 8) - 2048;
            Block b = chunk.getWorld().getBlockAt(x, y, z);
            var current = plugin.content().slotOf(b.getBlockData());
            if (current != null && current.index() == slot) {
                b.setType(replacement, false);
                replaced++;
            }
        }
        if (keep.isEmpty()) pdc.remove(key);
        else pdc.set(key, PersistentDataType.LONG_ARRAY, keep.stream().mapToLong(Long::longValue).toArray());
        return replaced;
    }

    /** Live cleanup of a deleted block type in every chunk that ever contained custom blocks. */
    public CompletableFuture<Integer> purgeEverywhere(int slot, Material replacement) {
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (var e : chunks.entrySet()) {
            World world = Bukkit.getWorld(e.getKey());
            if (world == null) continue;
            for (long ck : e.getValue()) {
                int cx = (int) ck, cz = (int) (ck >> 32);
                futures.add(world.getChunkAtAsync(cx, cz, false).thenApply(c -> c == null ? 0 : purge(c, slot, replacement)));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(v -> futures.stream().mapToInt(CompletableFuture::join).sum());
    }

    /** Safety net for chunks we did not know about: called on chunk load. */
    public void onChunkLoad(Chunk chunk) {
        var retired = plugin.content().slots().retiredBlocks();
        if (retired.isEmpty()) return;
        if (!chunk.getPersistentDataContainer().has(key, PersistentDataType.LONG_ARRAY)) return;
        for (var e : retired.entrySet()) {
            Material m = Material.matchMaterial(e.getValue());
            purge(chunk, e.getKey(), m == null ? Material.DIRT : m);
        }
    }
}
