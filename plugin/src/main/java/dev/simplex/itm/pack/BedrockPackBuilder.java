package dev.simplex.itm.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.simplex.common.SlotLayout;
import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.content.BlockDef;
import dev.simplex.itm.content.ModelDef;
import dev.simplex.itm.util.Texts;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Generates the Bedrock resource pack that the Geyser extension streams to Bedrock players on join:
 * textures for every block slot face, icons for every item slot, block geometry (glass / furniture blocks),
 * attachables for 3D models, native block sounds, custom sound definitions and translations.
 */
public final class BedrockPackBuilder {

    public record Output(byte[] data, int version) {}

    private final SimplexPlugin plugin;

    public BedrockPackBuilder(SimplexPlugin plugin) {
        this.plugin = plugin;
    }

    public Output build(UUID header, UUID module, int lastVersion, String lastContentHash, java.util.function.Consumer<String> hashOut) throws IOException {
        ZipBuilder zip = new ZipBuilder();
        var content = plugin.content();
        boolean blocks = content.blocksEnabled();

        JsonObject terrainData = new JsonObject();
        JsonObject itemData = new JsonObject();
        JsonObject blocksJson = new JsonObject();
        JsonArray fmt = new JsonArray();
        fmt.add(1);
        fmt.add(1);
        fmt.add(0);
        blocksJson.add("format_version", fmt);
        JsonObject blockSounds = new JsonObject();
        Set<String> copied = new HashSet<>();
        Map<Integer, BlockDef> bySlot = new HashMap<>();
        if (blocks) for (BlockDef b : content.blocks()) bySlot.put(b.slot, b);

        // ---- block slots ----
        for (SlotLayout.BlockSlot slot : SlotLayout.blockSlots()) {
            BlockDef def = bySlot.get(slot.index());
            ModelDef model = def == null || def.model == null || def.model.isBlank() ? null : content.model(def.model);
            String atlasPath = null;
            if (slot.kind() == SlotLayout.Kind.TRANSPARENT) {
                if (model != null && model.modelJson().isFile()) {
                    JavaModel jm = JavaModel.parse(Files.readString(model.modelJson().toPath(), StandardCharsets.UTF_8));
                    ModelConverter.Result r = ModelConverter.convert(jm, loadTextures(model), 1.0, slot.geometryIdentifier(), false);
                    zip.json("models/blocks/" + slot.bedrockName() + ".geo.json", r.geometryFile());
                    atlasPath = "textures/simplex/blocks/" + slot.bedrockName() + "_atlas";
                    zip.png(atlasPath + ".png", r.atlas());
                } else {
                    zip.json("models/blocks/" + slot.bedrockName() + ".geo.json", ModelConverter.unitCube(slot.geometryIdentifier()));
                }
            }
            for (String face : SlotLayout.FACES) {
                String path;
                if (atlasPath != null) path = atlasPath;
                else if (def != null) path = copyTexture(zip, def.texture(face), copied);
                else path = slot.kind() == SlotLayout.Kind.TRANSPARENT ? "textures/blocks/glass" : "textures/blocks/mushroom_block_skin_brown";
                if (path == null) path = "textures/blocks/stone";
                JsonObject t = new JsonObject();
                t.addProperty("textures", path);
                terrainData.add(slot.textureKey(face), t);
            }
            if (def != null) {
                JsonObject b = new JsonObject();
                boolean custom = def.customSounds.containsKey("break") || def.customSounds.containsKey("place");
                b.addProperty("sound", custom ? slot.bedrockName() : def.sound.bedrock);
                blocksJson.add(slot.bedrockIdentifier(), b);
                if (custom) blockSounds.add(slot.bedrockName(), customBlockSounds(def));
            }
        }

        // ---- item slots ----
        Map<Integer, String> iconPaths = new HashMap<>();
        if (blocks) {
            for (BlockDef b : content.blocks()) {
                if (b.itemSlot < 0) continue;
                BufferedImage icon;
                ModelDef model = b.model == null || b.model.isBlank() ? null : content.model(b.model);
                if (model != null && model.modelJson().isFile()) {
                    icon = modelIcon(model);
                } else {
                    icon = ModelConverter.isoIcon(readTexture(b.texture("up")), readTexture(b.texture("south")), readTexture(b.texture("east")));
                }
                String path = "textures/simplex/items/i" + b.itemSlot;
                zip.png(path + ".png", icon);
                iconPaths.put(b.itemSlot, path);
            }
        }
        JsonObject animations = new JsonObject();
        if (content.modelsEnabled()) {
            for (ModelDef m : content.models()) {
                if (m.itemSlot < 0 || !m.modelJson().isFile()) continue;
                String path = "textures/simplex/items/i" + m.itemSlot;
                zip.png(path + ".png", modelIcon(m));
                iconPaths.put(m.itemSlot, path);
                JavaModel jm = JavaModel.parse(Files.readString(m.modelJson().toPath(), StandardCharsets.UTF_8));
                ModelConverter.Result r = ModelConverter.convert(jm, loadTextures(m), m.scale, SlotLayout.itemGeometry(m.itemSlot), true);
                zip.json("models/entity/simplex_i" + m.itemSlot + ".geo.json", r.geometryFile());
                String atlas = "textures/simplex/models/" + m.id;
                zip.png(atlas + ".png", r.atlas());
                zip.json("attachables/simplex_i" + m.itemSlot + ".json", attachable(m.itemSlot, atlas));
            }
        }
        for (int i = 0; i < SlotLayout.ITEM_SLOTS; i++) {
            JsonObject t = new JsonObject();
            t.addProperty("textures", iconPaths.getOrDefault(i, "textures/items/paper"));
            itemData.add(SlotLayout.itemIconKey(i), t);
        }
        zip.json("animations/simplex.animation.json", handAnimation());

        // ---- atlases ----
        JsonObject terrain = new JsonObject();
        terrain.addProperty("resource_pack_name", "simplex");
        terrain.addProperty("texture_name", "atlas.terrain");
        terrain.addProperty("padding", 8);
        terrain.addProperty("num_mip_levels", 4);
        terrain.add("texture_data", terrainData);
        zip.json("textures/terrain_texture.json", terrain);
        JsonObject items = new JsonObject();
        items.addProperty("resource_pack_name", "simplex");
        items.addProperty("texture_name", "atlas.items");
        items.add("texture_data", itemData);
        zip.json("textures/item_texture.json", items);
        zip.json("blocks.json", blocksJson);

        // ---- sounds ----
        JsonObject definitions = new JsonObject();
        for (String s : content.soundFiles()) {
            String id = Texts.toId(s);
            zip.put("sounds/simplex/" + id + ".ogg", Files.readAllBytes(new File(content.soundsFolder(), s + ".ogg").toPath()));
            JsonObject def = new JsonObject();
            def.addProperty("category", "block");
            JsonArray list = new JsonArray();
            JsonObject snd = new JsonObject();
            snd.addProperty("name", "sounds/simplex/" + id);
            snd.addProperty("volume", 1.0);
            list.add(snd);
            def.add("sounds", list);
            definitions.add("simplex." + id, def);
        }
        if (!definitions.isEmpty()) {
            JsonObject sd = new JsonObject();
            sd.addProperty("format_version", "1.14.0");
            sd.add("sound_definitions", definitions);
            zip.json("sounds/sound_definitions.json", sd);
        }
        if (!blockSounds.isEmpty()) {
            JsonObject sounds = new JsonObject();
            sounds.add("block_sounds", blockSounds);
            zip.json("sounds.json", sounds);
        }

        // ---- texts ----
        Translations tr = Translations.collect(plugin);
        JsonArray languages = new JsonArray();
        for (String l : List.of("en_US", "en_GB", "de_DE")) languages.add(l);
        zip.json("texts/languages.json", languages);
        zip.put("texts/en_US.lang", tr.bedrockLang(plugin, "en"));
        zip.put("texts/en_GB.lang", tr.bedrockLang(plugin, "en"));
        zip.put("texts/de_DE.lang", tr.bedrockLang(plugin, "de"));

        JavaPackBuilder.overlay(zip, plugin.getDataFolder().toPath().resolve("resourcepack/bedrock"));

        // ---- icon + manifest (version only bumps when the content changed) ----
        BufferedImage packIcon = packIcon();
        if (packIcon != null) zip.png("pack_icon.png", packIcon);
        String hash = hash(zip.build());
        int version = hash.equals(lastContentHash) ? Math.max(1, lastVersion) : lastVersion + 1;
        hashOut.accept(hash);
        zip.json("manifest.json", manifest(header, module, version));
        return new Output(zip.build(), version);
    }

    private JsonObject manifest(UUID header, UUID module, int version) {
        JsonObject m = new JsonObject();
        m.addProperty("format_version", 2);
        JsonObject h = new JsonObject();
        h.addProperty("name", "Simplex Custom Content");
        h.addProperty("description", "Generated by SimplexImageToMap");
        h.addProperty("uuid", header.toString());
        h.add("version", ModelConverter.arr(1, 0, version));
        h.add("min_engine_version", ModelConverter.arr(1, 21, 0));
        m.add("header", h);
        JsonObject mod = new JsonObject();
        mod.addProperty("type", "resources");
        mod.addProperty("uuid", module.toString());
        mod.add("version", ModelConverter.arr(1, 0, version));
        JsonArray modules = new JsonArray();
        modules.add(mod);
        m.add("modules", modules);
        return m;
    }

    private static JsonObject customBlockSounds(BlockDef def) {
        String type = def.sound.bedrock;
        JsonObject events = new JsonObject();
        events.addProperty("default", "");
        events.add("break", ev(def.customSounds.containsKey("break") ? "simplex." + Texts.toId(def.customSounds.get("break")) : "dig." + type, 1.0));
        events.add("place", ev(def.customSounds.containsKey("place") ? "simplex." + Texts.toId(def.customSounds.get("place")) : "dig." + type, 1.0));
        events.add("hit", ev("hit." + type, 0.3));
        events.add("step", ev("step." + type, 0.3));
        JsonObject o = new JsonObject();
        o.addProperty("volume", 1.0);
        o.addProperty("pitch", 1.0);
        o.add("events", events);
        return o;
    }

    private static JsonObject ev(String sound, double volume) {
        JsonObject e = new JsonObject();
        e.addProperty("sound", sound);
        e.addProperty("volume", volume);
        return e;
    }

    private static JsonObject attachable(int slot, String texture) {
        JsonObject desc = new JsonObject();
        desc.addProperty("identifier", SlotLayout.bedrockItemIdentifier(slot));
        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_alphatest");
        materials.addProperty("enchanted", "entity_alphatest_glint");
        desc.add("materials", materials);
        JsonObject textures = new JsonObject();
        textures.addProperty("default", texture);
        textures.addProperty("enchanted", "textures/misc/enchanted_item_glint");
        desc.add("textures", textures);
        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", SlotLayout.itemGeometry(slot));
        desc.add("geometry", geometry);
        JsonObject scripts = new JsonObject();
        JsonArray pre = new JsonArray();
        pre.add("v.is_head = c.item_slot == 'head';");
        pre.add("v.is_first_person = c.is_first_person;");
        scripts.add("pre_animation", pre);
        JsonArray animate = new JsonArray();
        JsonObject hand = new JsonObject();
        hand.addProperty("simplex_hand", "!v.is_head && !v.is_first_person");
        animate.add(hand);
        JsonObject fp = new JsonObject();
        fp.addProperty("simplex_first_person", "!v.is_head && v.is_first_person");
        animate.add(fp);
        scripts.add("animate", animate);
        desc.add("scripts", scripts);
        JsonObject anims = new JsonObject();
        anims.addProperty("simplex_hand", "animation.simplex.hand");
        anims.addProperty("simplex_first_person", "animation.simplex.first_person");
        desc.add("animations", anims);
        JsonArray controllers = new JsonArray();
        controllers.add("controller.render.item_default");
        desc.add("render_controllers", controllers);
        JsonObject att = new JsonObject();
        att.add("description", desc);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.10.0");
        root.add("minecraft:attachable", att);
        return root;
    }

    private static JsonObject handAnimation() {
        JsonObject anims = new JsonObject();
        anims.add("animation.simplex.hand", boneAnim(0.32, 0, -19.5, -3));
        anims.add("animation.simplex.first_person", boneAnim(0.25, 2, -20, -6));
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.8.0");
        root.add("animations", anims);
        return root;
    }

    private static JsonObject boneAnim(double scale, double x, double y, double z) {
        JsonObject bone = new JsonObject();
        bone.add("scale", ModelConverter.arr(scale, scale, scale));
        bone.add("position", ModelConverter.arr(x, y, z));
        JsonObject bones = new JsonObject();
        bones.add("simplex_item", bone);
        JsonObject a = new JsonObject();
        a.addProperty("loop", true);
        a.add("bones", bones);
        return a;
    }

    // ---- textures ----

    private String copyTexture(ZipBuilder zip, String file, Set<String> copied) throws IOException {
        if (file == null) return null;
        String id = Texts.toId(Texts.baseName(file));
        String path = "textures/simplex/blocks/" + id;
        if (copied.add(path)) {
            BufferedImage img = readTexture(file);
            if (img == null) return null;
            if (img.getHeight() > img.getWidth()) img = img.getSubimage(0, 0, img.getWidth(), img.getWidth()); // animated strip → first frame
            zip.png(path + ".png", img);
        }
        return path;
    }

    private BufferedImage readTexture(String file) {
        if (file == null) return null;
        try {
            File f = new File(plugin.content().texturesFolder(), file);
            return f.isFile() ? ImageIO.read(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, BufferedImage> loadTextures(ModelDef m) throws IOException {
        Map<String, BufferedImage> map = new HashMap<>();
        File[] pngs = m.folder.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        if (pngs != null) for (File f : pngs) {
            if (f.getName().equalsIgnoreCase("icon.png")) continue;
            BufferedImage img = ImageIO.read(f);
            if (img != null) map.put(f.getName().substring(0, f.getName().length() - 4), img);
        }
        return map;
    }

    private BufferedImage modelIcon(ModelDef m) throws IOException {
        File icon = new File(m.folder, "icon.png");
        if (icon.isFile()) {
            BufferedImage img = ImageIO.read(icon);
            if (img != null) return img;
        }
        JavaModel jm = JavaModel.parse(Files.readString(m.modelJson().toPath(), StandardCharsets.UTF_8));
        return ModelConverter.frontIcon(jm, loadTextures(m));
    }

    private BufferedImage packIcon() {
        try {
            File custom = new File(plugin.getDataFolder(), "resourcepack/pack.png");
            if (custom.isFile()) return ImageIO.read(custom);
            for (BlockDef b : plugin.content().blocks()) {
                return ModelConverter.isoIcon(readTexture(b.texture("up")), readTexture(b.texture("south")), readTexture(b.texture("east")));
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    static String hash(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
