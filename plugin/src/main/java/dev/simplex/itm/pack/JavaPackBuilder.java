package dev.simplex.itm.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplex.common.SlotLayout;
import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.content.BlockDef;
import dev.simplex.itm.content.ModelDef;
import dev.simplex.itm.util.Texts;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Generates the Java resource pack: re-textured mushroom / chorus states, block + item models, item model
 * definitions for every item slot, textures, custom sounds and English / German translations.
 */
public final class JavaPackBuilder {

    private final SimplexPlugin plugin;

    public JavaPackBuilder(SimplexPlugin plugin) {
        this.plugin = plugin;
    }

    public byte[] build() throws IOException {
        ZipBuilder zip = new ZipBuilder();
        JsonObject pack = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("description", plugin.getConfig().getString("resource-pack.description", "§bSimplex §7Custom Content"));
        int format = plugin.getConfig().getInt("resource-pack.java-pack-format", 75);
        meta.addProperty("pack_format", format);
        JsonObject supported = new JsonObject();
        supported.addProperty("min_inclusive", 34);
        supported.addProperty("max_inclusive", 999);
        meta.add("supported_formats", supported);
        meta.addProperty("min_format", 34);
        meta.addProperty("max_format", 999);
        pack.add("pack", meta);
        zip.json("pack.mcmeta", pack);
        Path logo = plugin.getDataFolder().toPath().resolve("resourcepack/pack.png");
        if (Files.isRegularFile(logo)) zip.put("pack.png", Files.readAllBytes(logo));

        var content = plugin.content();
        boolean blocks = content.blocksEnabled();
        Map<Integer, BlockDef> bySlot = new HashMap<>();
        if (blocks) for (BlockDef b : content.blocks()) bySlot.put(b.slot, b);

        // ---- textures used by blocks ----
        Set<String> copied = new HashSet<>();
        if (blocks) {
            for (BlockDef b : content.blocks()) {
                for (String face : SlotLayout.FACES) copyTexture(zip, b.texture(face), copied);
                copyTexture(zip, b.particleTexture(), copied);
            }
        }

        // ---- block models ----
        if (blocks) {
            for (BlockDef b : content.blocks()) {
                ModelDef model = b.model == null || b.model.isBlank() ? null : content.model(b.model);
                if (model != null && model.modelJson().isFile()) {
                    zip.json("assets/simplex/models/block/" + b.id + ".json", rewriteModel(model, "block", zip, copied));
                    continue;
                }
                JsonObject m = new JsonObject();
                m.addProperty("parent", "minecraft:block/cube");
                JsonObject tex = new JsonObject();
                for (String face : SlotLayout.FACES) tex.addProperty(face, texRef(b.texture(face)));
                tex.addProperty("particle", texRef(b.particleTexture()));
                m.add("textures", tex);
                zip.json("assets/simplex/models/block/" + b.id + ".json", m);
            }
        }

        // ---- blockstates of the host blocks ----
        for (String host : List.of("brown_mushroom_block", "red_mushroom_block", "mushroom_stem")) {
            JsonObject variants = new JsonObject();
            for (int mask = 0; mask < 64; mask++) {
                SlotLayout.BlockSlot slot = SlotLayout.find(host, mask);
                JsonObject v = new JsonObject();
                BlockDef def = slot == null ? null : bySlot.get(slot.index());
                if (def != null) {
                    v.addProperty("model", "simplex:block/" + def.id);
                } else {
                    String model = "simplex:block/vanilla/" + host + "_" + mask;
                    zip.json("assets/simplex/models/block/vanilla/" + host + "_" + mask + ".json", vanillaMushroom(host, mask));
                    v.addProperty("model", model);
                }
                variants.add(SlotLayout.variantKey(mask), v);
            }
            JsonObject state = new JsonObject();
            state.add("variants", variants);
            zip.json("assets/minecraft/blockstates/" + host + ".json", state);
        }
        zip.json("assets/minecraft/blockstates/chorus_plant.json", chorusBlockstate(bySlot));

        // ---- items: one item model definition per slot ----
        Map<Integer, String> itemModels = new HashMap<>();
        if (blocks) for (BlockDef b : content.blocks()) if (b.itemSlot >= 0) itemModels.put(b.itemSlot, "simplex:block/" + b.id);
        if (content.modelsEnabled()) {
            for (ModelDef m : content.models()) {
                if (m.itemSlot < 0 || !m.modelJson().isFile()) continue;
                zip.json("assets/simplex/models/item/" + m.id + ".json", rewriteModel(m, "item", zip, copied));
                itemModels.put(m.itemSlot, "simplex:item/" + m.id);
            }
        }
        for (var e : itemModels.entrySet()) {
            JsonObject def = new JsonObject();
            JsonObject model = new JsonObject();
            model.addProperty("type", "minecraft:model");
            model.addProperty("model", e.getValue());
            def.add("model", model);
            zip.json("assets/simplex/items/" + SlotLayout.itemModelPath(e.getKey()) + ".json", def);
        }

        // ---- custom sounds: event "minecraft:simplex.<name>" (Geyser forwards it as "simplex.<name>") ----
        JsonObject sounds = new JsonObject();
        for (String s : content.soundFiles()) {
            String id = Texts.toId(s);
            zip.put("assets/simplex/sounds/" + id + ".ogg", Files.readAllBytes(new File(content.soundsFolder(), s + ".ogg").toPath()));
            JsonObject ev = new JsonObject();
            JsonArray list = new JsonArray();
            JsonObject snd = new JsonObject();
            snd.addProperty("name", "simplex:" + id);
            list.add(snd);
            ev.add("sounds", list);
            ev.addProperty("subtitle", "simplex.sound." + id);
            sounds.add("simplex." + id, ev);
        }
        if (!sounds.isEmpty()) zip.json("assets/minecraft/sounds.json", sounds);

        // ---- translations ----
        Translations tr = Translations.collect(plugin);
        for (String locale : List.of("en_us", "en_gb")) zip.json("assets/simplex/lang/" + locale + ".json", tr.json("en"));
        for (String locale : List.of("de_de", "de_at", "de_ch")) zip.json("assets/simplex/lang/" + locale + ".json", tr.json("de"));

        // ---- user overlay (resourcepack/java/**) ----
        overlay(zip, plugin.getDataFolder().toPath().resolve("resourcepack/java"));
        return zip.build();
    }

    static String texRef(String file) {
        return file == null ? "minecraft:block/stone" : "simplex:block/" + Texts.toId(Texts.baseName(file));
    }

    private void copyTexture(ZipBuilder zip, String file, Set<String> copied) throws IOException {
        if (file == null || !copied.add(file)) return;
        Path p = plugin.content().texturesFolder().toPath().resolve(file);
        if (!Files.isRegularFile(p)) return;
        String id = Texts.toId(Texts.baseName(file));
        zip.put("assets/simplex/textures/block/" + id + ".png", Files.readAllBytes(p));
        Path mcmeta = p.resolveSibling(p.getFileName() + ".mcmeta");
        if (Files.isRegularFile(mcmeta)) zip.put("assets/simplex/textures/block/" + id + ".png.mcmeta", Files.readAllBytes(mcmeta));
    }

    /** Copies a Blockbench model and points its textures to {@code simplex:<kind>/<model>/<texture>}. */
    private JsonObject rewriteModel(ModelDef m, String kind, ZipBuilder zip, Set<String> copied) throws IOException {
        JsonObject json = JsonParser.parseString(Files.readString(m.modelJson().toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        if (json.has("textures")) {
            JsonObject tex = json.getAsJsonObject("textures");
            for (String key : tex.keySet().toArray(String[]::new)) {
                String v = tex.get(key).getAsString();
                if (v.startsWith("#")) continue;
                String base = JavaModel.baseName(v);
                File png = new File(m.folder, base + ".png");
                String id = Texts.toId(base);
                String target = "assets/simplex/textures/model/" + m.id + "/" + id + ".png";
                if (png.isFile() && copied.add(target)) zip.put(target, Files.readAllBytes(png.toPath()));
                tex.addProperty(key, "simplex:model/" + m.id + "/" + id);
            }
        }
        json.remove("parent");
        if (kind.equals("block")) json.addProperty("ambientocclusion", false);
        return json;
    }

    private static JsonObject vanillaMushroom(String host, int mask) {
        JsonObject m = new JsonObject();
        m.addProperty("parent", "minecraft:block/cube");
        JsonObject tex = new JsonObject();
        String skin = "minecraft:block/" + host;
        String inside = "minecraft:block/mushroom_block_inside";
        for (String face : SlotLayout.FACES) tex.addProperty(face, SlotLayout.has(mask, face) ? skin : inside);
        tex.addProperty("particle", skin);
        m.add("textures", tex);
        return m;
    }

    /**
     * Vanilla chorus plant multipart, but every vanilla part only applies to non-reserved states. Reserved states
     * get exactly one part: the custom block model (or the plain vanilla core when the slot is unused).
     */
    private static JsonObject chorusBlockstate(Map<Integer, BlockDef> bySlot) {
        JsonArray parts = new JsonArray();
        String[][] sides = {{"north", "0", "0"}, {"east", "0", "90"}, {"south", "0", "180"}, {"west", "0", "270"}, {"up", "270", "0"}, {"down", "90", "0"}};
        for (String[] s : sides) {
            parts.add(part(apply("minecraft:block/chorus_plant_side", s[1], s[2]), vanillaOnly(s[0], "true")));
        }
        String[][] nosides = {{"north", "0", "0"}, {"east", "0", "90"}, {"south", "0", "180"}, {"west", "0", "270"}, {"up", "270", "0"}, {"down", "90", "0"}};
        for (String[] s : nosides) {
            JsonArray choices = new JsonArray();
            String[] names = {"chorus_plant_noside", "chorus_plant_noside1", "chorus_plant_noside2", "chorus_plant_noside3"};
            for (String n : names) choices.add(apply("minecraft:block/" + n, s[1], s[2]));
            JsonObject p = new JsonObject();
            p.add("apply", choices);
            p.add("when", vanillaOnly(s[0], "false"));
            parts.add(p);
        }
        for (SlotLayout.BlockSlot slot : SlotLayout.blockSlots()) {
            if (slot.kind() != SlotLayout.Kind.TRANSPARENT) continue;
            BlockDef def = bySlot.get(slot.index());
            JsonObject when = new JsonObject();
            for (String face : List.of("down", "east", "north", "south", "up", "west")) when.addProperty(face, String.valueOf(SlotLayout.has(slot.mask(), face)));
            parts.add(part(apply(def != null ? "simplex:block/" + def.id : "minecraft:block/chorus_plant", "0", "0"), when));
        }
        JsonObject state = new JsonObject();
        state.add("multipart", parts);
        return state;
    }

    private static JsonObject part(JsonObject apply, JsonObject when) {
        JsonObject p = new JsonObject();
        p.add("apply", apply);
        p.add("when", when);
        return p;
    }

    private static JsonObject apply(String model, String x, String y) {
        JsonObject a = new JsonObject();
        a.addProperty("model", model);
        if (!x.equals("0")) a.addProperty("x", Integer.parseInt(x));
        if (!y.equals("0")) a.addProperty("y", Integer.parseInt(y));
        if (!x.equals("0") || !y.equals("0")) a.addProperty("uvlock", true);
        return a;
    }

    /** "face=value AND state is not reserved" = OR of (face=value, down=true) and (face=value, ≤1 horizontal). */
    private static JsonObject vanillaOnly(String face, String value) {
        JsonArray or = new JsonArray();
        String[] horizontal = {"east", "north", "south", "west"};
        // down=true is never reserved
        if (!(face.equals("down") && value.equals("false"))) {
            JsonObject c = new JsonObject();
            c.addProperty(face, value);
            c.addProperty("down", "true");
            if (!(face.equals("down"))) or.add(c);
            else or.add(single(face, value));
        }
        // at most one horizontal connection (any down)
        for (int only = -1; only < 4; only++) {
            JsonObject c = new JsonObject();
            boolean contradiction = false;
            for (int i = 0; i < 4; i++) {
                String v = String.valueOf(i == only);
                if (horizontal[i].equals(face) && !v.equals(value)) contradiction = true;
                c.addProperty(horizontal[i], v);
            }
            if (contradiction) continue;
            if (!java.util.Arrays.asList(horizontal).contains(face)) c.addProperty(face, value);
            or.add(c);
        }
        JsonObject when = new JsonObject();
        when.add("OR", or);
        return when;
    }

    private static JsonObject single(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    static void overlay(ZipBuilder zip, Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                zip.put(dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
    }
}
