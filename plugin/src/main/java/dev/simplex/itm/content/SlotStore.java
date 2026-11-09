package dev.simplex.itm.content;

import dev.simplex.common.SlotLayout;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Persistent assignment of content to slots. Assignments never move on their own, so placed blocks and items
 * keep their look across restarts. Deleted blocks keep their slot "retired" until all placed copies are gone.
 */
public final class SlotStore {

    private final File file;
    private final Map<String, Integer> blocks = new LinkedHashMap<>();
    private final Map<String, Integer> items = new LinkedHashMap<>();
    /** retired block slot -> replacement material name */
    private final Map<Integer, String> retiredBlocks = new HashMap<>();
    private final Set<Integer> retiredItems = new HashSet<>();

    public SlotStore(File file) {
        this.file = file;
    }

    public void load() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        blocks.clear();
        items.clear();
        retiredBlocks.clear();
        retiredItems.clear();
        read(cfg.getConfigurationSection("blocks"), blocks);
        read(cfg.getConfigurationSection("items"), items);
        ConfigurationSection r = cfg.getConfigurationSection("retired-blocks");
        if (r != null) for (String k : r.getKeys(false)) retiredBlocks.put(Integer.parseInt(k), r.getString(k, "DIRT"));
        retiredItems.addAll(cfg.getIntegerList("retired-items"));
    }

    private static void read(ConfigurationSection s, Map<String, Integer> into) {
        if (s == null) return;
        for (String k : s.getKeys(false)) into.put(k, s.getInt(k));
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.options().setHeader(java.util.List.of("Slot assignments – do not edit unless you know what you are doing."));
        blocks.forEach((k, v) -> cfg.set("blocks." + k, v));
        items.forEach((k, v) -> cfg.set("items." + k, v));
        retiredBlocks.forEach((k, v) -> cfg.set("retired-blocks." + k, v));
        cfg.set("retired-items", new java.util.ArrayList<>(retiredItems));
        try {
            file.getParentFile().mkdirs();
            cfg.save(file);
        } catch (IOException ignored) {
        }
    }

    public Integer blockSlot(String id) { return blocks.get(id); }

    public Integer itemSlot(String key) { return items.get(key); }

    /** Returns the block slot for the id, assigning the first free slot of the right kind if needed (-1 = full). */
    public int assignBlock(String id, SlotLayout.Kind kind) {
        Integer existing = blocks.get(id);
        if (existing != null) {
            SlotLayout.BlockSlot slot = SlotLayout.blockSlot(existing);
            if (slot != null && slot.kind() == kind) return existing;
            retiredBlocks.put(existing, "DIRT");
            blocks.remove(id);
        }
        Set<Integer> used = new HashSet<>(blocks.values());
        used.addAll(retiredBlocks.keySet());
        for (SlotLayout.BlockSlot s : SlotLayout.blockSlots()) {
            if (s.kind() == kind && !used.contains(s.index())) {
                blocks.put(id, s.index());
                return s.index();
            }
        }
        return -1;
    }

    public int assignItem(String key) {
        Integer existing = items.get(key);
        if (existing != null) return existing;
        Set<Integer> used = new HashSet<>(items.values());
        used.addAll(retiredItems);
        for (int i = 0; i < SlotLayout.ITEM_SLOTS; i++) {
            if (!used.contains(i)) {
                items.put(key, i);
                return i;
            }
        }
        return -1;
    }

    public void retireBlock(String id, String replacement) {
        Integer slot = blocks.remove(id);
        if (slot != null) retiredBlocks.put(slot, replacement);
    }

    public void releaseItem(String key) {
        items.remove(key);
    }

    public Map<Integer, String> retiredBlocks() { return retiredBlocks; }

    public void freeRetired(int slot) { retiredBlocks.remove(slot); }

    public int freeBlockSlots(SlotLayout.Kind kind) {
        Set<Integer> used = new HashSet<>(blocks.values());
        used.addAll(retiredBlocks.keySet());
        int n = 0;
        for (SlotLayout.BlockSlot s : SlotLayout.blockSlots()) if (s.kind() == kind && !used.contains(s.index())) n++;
        return n;
    }
}
