package dev.simplex.itm.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal parser for Java Edition block/item models (Blockbench "Java Block/Item" export). */
public final class JavaModel {

    public record Face(String dir, float[] uv, String texture, int rotation) {}

    public record Element(float[] from, float[] to, float[] rotOrigin, String rotAxis, float rotAngle, List<Face> faces) {}

    public final JsonObject raw;
    public final Map<String, String> textures = new LinkedHashMap<>();
    public final List<Element> elements = new ArrayList<>();

    private JavaModel(JsonObject raw) {
        this.raw = raw;
    }

    public static JavaModel parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        JavaModel m = new JavaModel(o);
        if (o.has("textures")) {
            for (var e : o.getAsJsonObject("textures").entrySet()) m.textures.put(e.getKey(), e.getValue().getAsString());
        }
        if (o.has("elements")) {
            for (JsonElement el : o.getAsJsonArray("elements")) {
                JsonObject e = el.getAsJsonObject();
                float[] from = floats(e.getAsJsonArray("from"));
                float[] to = floats(e.getAsJsonArray("to"));
                float[] rotOrigin = {8, 8, 8};
                String axis = null;
                float angle = 0;
                if (e.has("rotation")) {
                    JsonObject r = e.getAsJsonObject("rotation");
                    if (r.has("origin")) rotOrigin = floats(r.getAsJsonArray("origin"));
                    axis = r.has("axis") ? r.get("axis").getAsString() : null;
                    angle = r.has("angle") ? r.get("angle").getAsFloat() : 0;
                }
                List<Face> faces = new ArrayList<>();
                if (e.has("faces")) {
                    for (var f : e.getAsJsonObject("faces").entrySet()) {
                        JsonObject fo = f.getValue().getAsJsonObject();
                        float[] uv = fo.has("uv") ? floats(fo.getAsJsonArray("uv")) : null;
                        String tex = fo.has("texture") ? fo.get("texture").getAsString() : "#missing";
                        int rot = fo.has("rotation") ? fo.get("rotation").getAsInt() : 0;
                        faces.add(new Face(f.getKey(), uv, tex.startsWith("#") ? tex.substring(1) : tex, rot));
                    }
                }
                m.elements.add(new Element(from, to, rotOrigin, axis, angle, faces));
            }
        }
        return m;
    }

    private static float[] floats(JsonArray a) {
        float[] f = new float[a.size()];
        for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsFloat();
        return f;
    }

    /** Follows {@code #ref} chains to the final texture path, or null. */
    public String resolve(String key) {
        String v = key;
        for (int i = 0; i < 8 && v != null; i++) {
            String next = textures.get(v.startsWith("#") ? v.substring(1) : v);
            if (next == null) return v.startsWith("#") ? null : v;
            v = next;
            if (!v.startsWith("#")) return v;
        }
        return v;
    }

    /** File base name of a texture path like {@code minecraft:block/chair_wood} → {@code chair_wood}. */
    public static String baseName(String path) {
        if (path == null) return null;
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'));
        return path.substring(i + 1);
    }

    /** Bounds in block units {minX,minY,minZ,maxX,maxY,maxZ}. */
    public double[] bounds() {
        if (elements.isEmpty()) return new double[]{0, 0, 0, 1, 1, 1};
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Element e : elements) {
            for (int i = 0; i < 3; i++) {
                b[i] = Math.min(b[i], Math.min(e.from[i], e.to[i]) / 16.0);
                b[i + 3] = Math.max(b[i + 3], Math.max(e.from[i], e.to[i]) / 16.0);
            }
        }
        return b;
    }

    /** Default UVs Minecraft uses when a face has none. */
    public static float[] defaultUv(String dir, float[] f, float[] t) {
        return switch (dir) {
            case "north" -> new float[]{16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
            case "south" -> new float[]{f[0], 16 - t[1], t[0], 16 - f[1]};
            case "east" -> new float[]{16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]};
            case "west" -> new float[]{f[2], 16 - t[1], t[2], 16 - f[1]};
            case "up" -> new float[]{f[0], f[2], t[0], t[2]};
            default -> new float[]{f[0], 16 - t[2], t[0], 16 - f[2]}; // down
        };
    }
}
