package dev.simplex.itm.content;

import dev.simplex.common.SlotLayout;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A custom block definition ({@code blocks/<id>.yml}). */
public final class BlockDef {

    public String id;
    public Localized name;
    public Localized description;
    public SlotLayout.Kind kind = SlotLayout.Kind.SOLID;
    /** keys: all, top, bottom, side, north, south, east, west, particle → file in textures/ */
    public final Map<String, String> textures = new LinkedHashMap<>();
    /** Optional 3D model (id of a folder in models/) – transparent blocks only (furniture-like blocks). */
    public String model = "";
    public float hardness = 1.5f;
    public ToolType tool = ToolType.PICKAXE;
    public boolean requiresTool = false;
    public SoundPreset sound = SoundPreset.STONE;
    /** Optional custom sound files (name of an .ogg in sounds/ without extension) per event: place, break. */
    public final Map<String, String> customSounds = new LinkedHashMap<>();
    /** self | none | material id */
    public String drop = "self";
    public RecipeDef recipe;
    public String category = "building";
    public File file;

    // runtime
    public transient int slot = -1;
    public transient int itemSlot = -1;

    public String itemKey() { return "block:" + id; }

    /** Texture file for a face (up, down, north, south, east, west). */
    public String texture(String face) {
        String t = textures.get(face);
        if (t == null) {
            t = switch (face) {
                case "up" -> textures.get("top");
                case "down" -> textures.getOrDefault("bottom", textures.get("top"));
                default -> textures.get("side");
            };
        }
        if (t == null) t = textures.get("all");
        if (t == null && !textures.isEmpty()) t = textures.values().iterator().next();
        return t;
    }

    public String particleTexture() {
        String p = textures.get("particle");
        return p != null ? p : texture("north");
    }

    public static BlockDef load(File file) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        BlockDef d = new BlockDef();
        d.file = file;
        String base = file.getName().replace(".yml", "");
        d.id = cfg.getString("id", base).toLowerCase(Locale.ROOT);
        d.name = Localized.read(cfg, "name", d.id);
        d.description = Localized.read(cfg, "description", "");
        d.kind = "transparent".equalsIgnoreCase(cfg.getString("type", "solid")) ? SlotLayout.Kind.TRANSPARENT : SlotLayout.Kind.SOLID;
        ConfigurationSection tex = cfg.getConfigurationSection("textures");
        if (tex != null) for (String k : tex.getKeys(false)) d.textures.put(k, tex.getString(k));
        else if (cfg.isString("texture")) d.textures.put("all", cfg.getString("texture"));
        d.model = cfg.getString("model", "");
        d.hardness = (float) cfg.getDouble("hardness", 1.5);
        d.tool = ToolType.parse(cfg.getString("tool", "pickaxe"));
        d.requiresTool = cfg.getBoolean("requires-tool", false);
        d.sound = SoundPreset.parse(cfg.getString("sound", "stone"));
        ConfigurationSection cs = cfg.getConfigurationSection("custom-sounds");
        if (cs != null) for (String k : cs.getKeys(false)) {
            String v = cs.getString(k, "");
            if (!v.isBlank()) d.customSounds.put(k, v);
        }
        d.drop = cfg.getString("drop", "self");
        d.recipe = RecipeDef.read(cfg.getConfigurationSection("recipe"));
        d.category = cfg.getString("category", "building");
        return d;
    }

    public void save() throws java.io.IOException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("id", id);
        name.write(cfg, "name");
        description.write(cfg, "description");
        cfg.set("type", kind == SlotLayout.Kind.TRANSPARENT ? "transparent" : "solid");
        textures.forEach((k, v) -> cfg.set("textures." + k, v));
        if (model != null && !model.isBlank()) cfg.set("model", model);
        cfg.set("hardness", hardness);
        cfg.set("tool", tool.id());
        cfg.set("requires-tool", requiresTool);
        cfg.set("sound", sound.id());
        customSounds.forEach((k, v) -> cfg.set("custom-sounds." + k, v));
        cfg.set("drop", drop);
        cfg.set("category", category);
        if (recipe != null) recipe.write(cfg, "recipe");
        file.getParentFile().mkdirs();
        cfg.save(file);
    }
}
