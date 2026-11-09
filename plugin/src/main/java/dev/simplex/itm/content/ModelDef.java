package dev.simplex.itm.content;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * A 3D model ({@code models/<id>/model.yml} + {@code model.json} exported from Blockbench as "Java Block/Item"
 * + its PNG textures). Placed models are shown with item displays on Java and armor-stand attachables on Bedrock.
 */
public final class ModelDef {

    public String id;
    public Localized name;
    public Localized description;
    public File folder;
    public String modelFile = "model.json";
    public double scale = 1.0;
    public boolean collision = true;
    public boolean seat = false;
    public double seatHeight = 0.45;
    public int rotationStep = 45;
    public boolean glowing = false;
    public float bedrockYawOffset = 180f;
    public double[] bedrockOffset = {0, 0, 0};
    public RecipeDef recipe;
    public String category = "furniture";

    // runtime
    public transient int itemSlot = -1;
    /** model bounds in block units relative to the block origin: minX,minY,minZ,maxX,maxY,maxZ */
    public transient double[] bounds = {0, 0, 0, 1, 1, 1};

    public String itemKey() { return "model:" + id; }

    public File modelJson() { return new File(folder, modelFile); }

    public static ModelDef load(File folder) {
        File yml = new File(folder, "model.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(yml);
        ModelDef d = new ModelDef();
        d.folder = folder;
        d.id = cfg.getString("id", folder.getName()).toLowerCase(Locale.ROOT);
        d.name = Localized.read(cfg, "name", d.id);
        d.description = Localized.read(cfg, "description", "");
        d.modelFile = cfg.getString("model", "model.json");
        d.scale = cfg.getDouble("scale", 1.0);
        d.collision = cfg.getBoolean("collision", true);
        d.seat = cfg.getBoolean("seat", false);
        d.seatHeight = cfg.getDouble("seat-height", 0.45);
        d.rotationStep = Math.max(1, cfg.getInt("rotation-step", 45));
        d.glowing = cfg.getBoolean("full-bright", false);
        d.bedrockYawOffset = (float) cfg.getDouble("bedrock.yaw-offset", 180);
        List<Double> off = cfg.getDoubleList("bedrock.offset");
        if (off.size() == 3) d.bedrockOffset = new double[]{off.get(0), off.get(1), off.get(2)};
        d.recipe = RecipeDef.read(cfg.getConfigurationSection("recipe"));
        d.category = cfg.getString("category", "furniture");
        return d;
    }

    public void save() throws java.io.IOException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("id", id);
        name.write(cfg, "name");
        description.write(cfg, "description");
        cfg.set("model", modelFile);
        cfg.set("scale", scale);
        cfg.set("collision", collision);
        cfg.set("seat", seat);
        cfg.set("seat-height", seatHeight);
        cfg.set("rotation-step", rotationStep);
        cfg.set("full-bright", glowing);
        cfg.set("bedrock.yaw-offset", bedrockYawOffset);
        cfg.set("bedrock.offset", List.of(bedrockOffset[0], bedrockOffset[1], bedrockOffset[2]));
        cfg.set("category", category);
        if (recipe != null) recipe.write(cfg, "recipe");
        cfg.save(new File(folder, "model.yml"));
    }
}
