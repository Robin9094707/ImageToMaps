package dev.simplex.itm.content;

import dev.simplex.common.SlotLayout;
import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.pack.JavaModel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registry for custom blocks and 3D models, their items, block states and recipes. */
public final class ContentManager {

    private final SimplexPlugin plugin;
    private final SlotStore slots;
    private final NamespacedKey keyContent;
    private final File blocksFolder, modelsFolder, texturesFolder, soundsFolder;
    private final Map<String, BlockDef> blocks = new LinkedHashMap<>();
    private final Map<String, ModelDef> models = new LinkedHashMap<>();
    private final BlockDef[] bySlot = new BlockDef[SlotLayout.blockSlots().size()];
    private final BlockData[] stateBySlot = new BlockData[SlotLayout.blockSlots().size()];
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();

    public ContentManager(SimplexPlugin plugin) {
        this.plugin = plugin;
        File data = plugin.getDataFolder();
        this.slots = new SlotStore(new File(data, "data/slots.yml"));
        this.keyContent = new NamespacedKey(plugin, "content");
        this.blocksFolder = new File(data, "blocks");
        this.modelsFolder = new File(data, "models");
        this.texturesFolder = new File(data, "textures");
        this.soundsFolder = new File(data, "sounds");
        for (File f : new File[]{blocksFolder, modelsFolder, texturesFolder, soundsFolder}) f.mkdirs();
        for (SlotLayout.BlockSlot s : SlotLayout.blockSlots()) stateBySlot[s.index()] = Bukkit.createBlockData(s.javaState());
    }

    public boolean blocksEnabled() { return plugin.getConfig().getBoolean("custom-blocks.enabled", true); }

    public boolean modelsEnabled() { return plugin.getConfig().getBoolean("models.enabled", true); }

    public SlotStore slots() { return slots; }
    public NamespacedKey key() { return keyContent; }
    public File texturesFolder() { return texturesFolder; }
    public File soundsFolder() { return soundsFolder; }
    public File modelsFolder() { return modelsFolder; }
    public File blocksFolder() { return blocksFolder; }

    public Collection<BlockDef> blocks() { return blocks.values(); }
    public Collection<ModelDef> models() { return models.values(); }
    public BlockDef block(String id) { return id == null ? null : blocks.get(id.toLowerCase(Locale.ROOT)); }
    public ModelDef model(String id) { return id == null ? null : models.get(id.toLowerCase(Locale.ROOT)); }

    // ------------------------------------------------------------------------------------------------------

    public void load() {
        slots.load();
        blocks.clear();
        models.clear();
        java.util.Arrays.fill(bySlot, null);
        File[] blockFiles = blocksFolder.listFiles((d, n) -> n.endsWith(".yml"));
        if (blockFiles != null) {
            java.util.Arrays.sort(blockFiles);
            for (File f : blockFiles) {
                try {
                    BlockDef def = BlockDef.load(f);
                    if (blocks.containsKey(def.id)) {
                        plugin.getLogger().warning("Duplicate block id '" + def.id + "' in " + f.getName());
                        continue;
                    }
                    register(def);
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not load block " + f.getName() + ": " + e.getMessage());
                }
            }
        }
        File[] modelDirs = modelsFolder.listFiles(f -> f.isDirectory() && new File(f, "model.yml").isFile());
        if (modelDirs != null) {
            java.util.Arrays.sort(modelDirs);
            for (File dir : modelDirs) {
                try {
                    registerModel(ModelDef.load(dir));
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not load model " + dir.getName() + ": " + e.getMessage());
                }
            }
        }
        slots.save();
        registerRecipes();
        plugin.getLogger().info("Loaded " + blocks.size() + " custom blocks (" + slots.freeBlockSlots(SlotLayout.Kind.SOLID) + " solid / "
                + slots.freeBlockSlots(SlotLayout.Kind.TRANSPARENT) + " transparent slots free) and " + models.size() + " 3D models.");
    }

    private boolean register(BlockDef def) {
        int slot = slots.assignBlock(def.id, def.kind);
        if (slot < 0) {
            plugin.getLogger().warning("No free " + def.kind + " block slot left for '" + def.id + "'.");
            return false;
        }
        def.slot = slot;
        def.itemSlot = slots.assignItem(def.itemKey());
        blocks.put(def.id, def);
        bySlot[slot] = def;
        return true;
    }

    private void registerModel(ModelDef def) throws IOException {
        File json = def.modelJson();
        if (json.isFile()) def.bounds = JavaModel.parse(Files.readString(json.toPath(), StandardCharsets.UTF_8)).bounds();
        def.itemSlot = slots.assignItem(def.itemKey());
        models.put(def.id, def);
    }

    /** Adds or replaces a block definition at runtime and saves it. */
    public boolean saveBlock(BlockDef def) throws IOException {
        if (def.file == null) def.file = new File(blocksFolder, def.id + ".yml");
        BlockDef old = blocks.remove(def.id);
        if (old != null && old.slot >= 0) bySlot[old.slot] = null;
        if (!register(def)) {
            if (old != null) register(old);
            return false;
        }
        def.save();
        slots.save();
        registerRecipes();
        return true;
    }

    public void saveModel(ModelDef def) throws IOException {
        def.save();
        models.remove(def.id);
        registerModel(def);
        slots.save();
        registerRecipes();
    }

    /** Removes a block definition. Placed blocks are replaced by {@code replacement} (handled by BlockCleanup). */
    public BlockDef deleteBlock(String id, Material replacement) {
        BlockDef def = blocks.remove(id);
        if (def == null) return null;
        bySlot[def.slot] = null;
        slots.retireBlock(id, replacement.name());
        slots.releaseItem(def.itemKey());
        slots.save();
        if (def.file != null && def.file.exists()) {
            File trash = new File(plugin.getDataFolder(), "trash/" + def.file.getName());
            trash.getParentFile().mkdirs();
            def.file.renameTo(trash);
        }
        registerRecipes();
        return def;
    }

    public ModelDef deleteModel(String id) {
        ModelDef def = models.remove(id);
        if (def == null) return null;
        slots.releaseItem(def.itemKey());
        slots.save();
        File trash = new File(plugin.getDataFolder(), "trash/models/" + def.folder.getName());
        trash.getParentFile().mkdirs();
        def.folder.renameTo(trash);
        registerRecipes();
        return def;
    }

    // ------------------------------------------------------------------------------------------------------
    // block states
    // ------------------------------------------------------------------------------------------------------

    public BlockData state(BlockDef def) { return stateBySlot[def.slot].clone(); }

    public SlotLayout.BlockSlot slotOf(BlockData data) {
        if (!(data instanceof MultipleFacing mf)) return null;
        String name = data.getMaterial().getKey().getKey();
        if (!SlotLayout.isHostBlock(name)) return null;
        int mask = SlotLayout.mask(mf.hasFace(BlockFace.DOWN), mf.hasFace(BlockFace.EAST), mf.hasFace(BlockFace.NORTH),
                mf.hasFace(BlockFace.SOUTH), mf.hasFace(BlockFace.UP), mf.hasFace(BlockFace.WEST));
        return SlotLayout.find(name, mask);
    }

    /** The custom block at this position, or null. */
    public BlockDef blockAt(Block block) {
        Material m = block.getType();
        if (m != Material.BROWN_MUSHROOM_BLOCK && m != Material.RED_MUSHROOM_BLOCK && m != Material.MUSHROOM_STEM && m != Material.CHORUS_PLANT) return null;
        SlotLayout.BlockSlot slot = slotOf(block.getBlockData());
        return slot == null ? null : bySlot[slot.index()];
    }

    public boolean isReserved(BlockData data) {
        return slotOf(data) != null;
    }

    // ------------------------------------------------------------------------------------------------------
    // items
    // ------------------------------------------------------------------------------------------------------

    public ItemStack item(BlockDef def, int amount) {
        return buildItem(def.itemKey(), def.itemSlot, "simplex.block." + def.id, def.name, def.description, amount, "block");
    }

    public ItemStack item(ModelDef def, int amount) {
        return buildItem(def.itemKey(), def.itemSlot, "simplex.model." + def.id, def.name, def.description, amount, "model");
    }

    private ItemStack buildItem(String key, int itemSlot, String langKey, Localized name, Localized desc, int amount, String kind) {
        ItemStack item = new ItemStack(Material.PAPER, Math.max(1, amount));
        item.editMeta(meta -> {
            meta.itemName(Component.translatable(langKey, name.en()));
            List<Component> lore = new ArrayList<>();
            if (desc != null && desc.en() != null && !desc.en().isBlank()) {
                lore.add(Component.translatable(langKey + ".desc", desc.en()).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.translatable("simplex.tag." + kind, kind.equals("block") ? "Simplex Block" : "Simplex Model")
                    .color(NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            if (itemSlot >= 0) meta.setItemModel(NamespacedKey.fromString(SlotLayout.itemModel(itemSlot)));
            meta.getPersistentDataContainer().set(keyContent, PersistentDataType.STRING, key);
        });
        return item;
    }

    /** "block:id" / "model:id" or null. */
    public String identify(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keyContent, PersistentDataType.STRING);
    }

    public BlockDef blockOf(ItemStack item) {
        String k = identify(item);
        return k != null && k.startsWith("block:") ? blocks.get(k.substring(6)) : null;
    }

    public ModelDef modelOf(ItemStack item) {
        String k = identify(item);
        return k != null && k.startsWith("model:") ? models.get(k.substring(6)) : null;
    }

    /** Brings an item up to date (new slot / names) or converts items of deleted content. Returns null to keep as is. */
    public ItemStack refresh(ItemStack item) {
        String k = identify(item);
        if (k == null) return null;
        if (k.startsWith("block:")) {
            BlockDef def = blocks.get(k.substring(6));
            if (def == null) return new ItemStack(Material.DIRT, item.getAmount());
            ItemStack fresh = item(def, item.getAmount());
            return fresh.isSimilar(item) ? null : fresh;
        }
        if (k.startsWith("model:")) {
            ModelDef def = models.get(k.substring(6));
            if (def == null) return new ItemStack(Material.AIR);
            ItemStack fresh = item(def, item.getAmount());
            return fresh.isSimilar(item) ? null : fresh;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------
    // recipes
    // ------------------------------------------------------------------------------------------------------

    public List<NamespacedKey> recipeKeys() { return recipeKeys; }

    public void registerRecipes() {
        for (NamespacedKey k : recipeKeys) Bukkit.removeRecipe(k, false);
        recipeKeys.clear();
        if (blocksEnabled()) for (BlockDef b : blocks.values()) if (b.recipe != null) addRecipe("block_" + b.id, item(b, b.recipe.amount()), b.recipe);
        if (modelsEnabled()) for (ModelDef m : models.values()) if (m.recipe != null) addRecipe("model_" + m.id, item(m, m.recipe.amount()), m.recipe);
        try {
            Bukkit.updateRecipes();
        } catch (Throwable ignored) {
        }
    }

    private void addRecipe(String name, ItemStack result, RecipeDef def) {
        NamespacedKey key = new NamespacedKey(plugin, name);
        try {
            ShapedRecipe r = new ShapedRecipe(key, result);
            r.shape(def.shape().toArray(String[]::new));
            for (var e : def.ingredients().entrySet()) {
                RecipeChoice choice = choice(e.getValue());
                if (choice == null) throw new IllegalArgumentException("unknown ingredient " + e.getValue());
                r.setIngredient(e.getKey(), choice);
            }
            r.setCategory(org.bukkit.inventory.recipe.CraftingBookCategory.BUILDING);
            Bukkit.addRecipe(r, false);
            recipeKeys.add(key);
        } catch (Exception ex) {
            plugin.getLogger().warning("Invalid recipe for " + name + ": " + ex.getMessage());
        }
    }

    public RecipeChoice choice(String id) {
        if (id == null) return null;
        String s = id.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("simplex:")) {
            String rest = s.substring(8);
            if (rest.startsWith("model/")) {
                ModelDef m = models.get(rest.substring(6));
                return m == null ? null : new RecipeChoice.ExactChoice(item(m, 1));
            }
            if (rest.startsWith("block/")) rest = rest.substring(6);
            BlockDef b = blocks.get(rest);
            return b == null ? null : new RecipeChoice.ExactChoice(item(b, 1));
        }
        Material m = Material.matchMaterial(s);
        return m == null || !m.isItem() ? null : new RecipeChoice.MaterialChoice(m);
    }

    /** Ingredient id for an item placed into the recipe editor. */
    public String ingredientId(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        String k = identify(item);
        if (k != null) return k.startsWith("block:") ? "simplex:" + k.substring(6) : "simplex:model/" + k.substring(6);
        return item.getType().getKey().toString();
    }

    public ItemStack ingredientIcon(String id) {
        RecipeChoice c = choice(id);
        if (c instanceof RecipeChoice.ExactChoice e) return e.getItemStack();
        if (c instanceof RecipeChoice.MaterialChoice m) return new ItemStack(m.getChoices().getFirst());
        return new ItemStack(Material.BARRIER);
    }

    public List<String> textureFiles() {
        List<String> out = new ArrayList<>();
        File[] files = texturesFolder.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (files != null) for (File f : files) out.add(f.getName());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public List<String> soundFiles() {
        List<String> out = new ArrayList<>();
        File[] files = soundsFolder.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".ogg"));
        if (files != null) for (File f : files) out.add(f.getName().substring(0, f.getName().length() - 4));
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
