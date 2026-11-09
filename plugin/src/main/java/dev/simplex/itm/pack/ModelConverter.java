package dev.simplex.itm.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Converts Java models to Bedrock geometry. Every face's texture region is baked (cropped, flipped, rotated)
 * into one atlas, so models using several textures, mirrored UVs or rotated faces convert reliably.
 */
public final class ModelConverter {

    public record Result(JsonObject geometryFile, BufferedImage atlas) {}

    private record Tile(Element element, String dir, BufferedImage image) {}

    private record Element(JavaModel.Element source, float[] from, float[] to, float[] origin, List<Tile> tiles) {}

    private ModelConverter() {}

    /**
     * @param textures texture images keyed by the model's texture base name
     * @param scale    uniform scale around the bottom centre (8, 0, 8)
     * @param bindToHead true for item attachables (armor stand head), false for block geometry
     */
    public static Result convert(JavaModel model, Map<String, BufferedImage> textures, double scale, String identifier, boolean bindToHead) {
        List<Element> elements = new ArrayList<>();
        List<Tile> tiles = new ArrayList<>();
        for (JavaModel.Element e : model.elements) {
            float[] f = scaled(e.from(), scale), t = scaled(e.to(), scale), o = scaled(e.rotOrigin(), scale);
            Element el = new Element(e, f, t, o, new ArrayList<>());
            for (JavaModel.Face face : e.faces()) {
                String path = model.resolve(face.texture());
                BufferedImage tex = textures.get(JavaModel.baseName(path));
                if (tex == null && !textures.isEmpty()) tex = textures.values().iterator().next();
                if (tex == null) continue;
                float[] uv = face.uv() != null ? face.uv() : JavaModel.defaultUv(face.dir(), e.from(), e.to());
                BufferedImage region = region(tex, uv, face.rotation());
                if (region == null) continue;
                Tile tile = new Tile(el, face.dir(), region);
                el.tiles().add(tile);
                tiles.add(tile);
            }
            elements.add(el);
        }

        // ---- pack the atlas (shelf packing) ----
        int area = 0, widest = 1;
        for (Tile t : tiles) {
            area += (t.image.getWidth() + 1) * (t.image.getHeight() + 1);
            widest = Math.max(widest, t.image.getWidth());
        }
        int width = 16;
        while (width < widest || width * width < area * 1.3) width *= 2;
        List<Tile> sorted = new ArrayList<>(tiles);
        sorted.sort(Comparator.comparingInt((Tile t) -> t.image.getHeight()).reversed());
        java.util.Map<Tile, int[]> pos = new java.util.IdentityHashMap<>();
        int x = 0, y = 0, rowH = 0;
        for (Tile t : sorted) {
            if (x + t.image.getWidth() > width) {
                x = 0;
                y += rowH;
                rowH = 0;
            }
            pos.put(t, new int[]{x, y});
            x += t.image.getWidth();
            rowH = Math.max(rowH, t.image.getHeight());
        }
        int height = 16;
        while (height < y + rowH) height *= 2;
        BufferedImage atlas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        for (Tile t : tiles) {
            int[] p = pos.get(t);
            g.drawImage(t.image, p[0], p[1], null);
        }
        g.dispose();

        // ---- geometry ----
        JsonArray cubes = new JsonArray();
        for (Element el : elements) {
            if (el.tiles().isEmpty()) continue;
            JsonObject cube = new JsonObject();
            float[] f = el.from(), t = el.to();
            cube.add("origin", arr(8 - Math.max(f[0], t[0]), Math.min(f[1], t[1]), Math.min(f[2], t[2]) - 8));
            cube.add("size", arr(Math.abs(t[0] - f[0]), Math.abs(t[1] - f[1]), Math.abs(t[2] - f[2])));
            JavaModel.Element src = el.source();
            if (src.rotAxis() != null && src.rotAngle() != 0) {
                cube.add("pivot", arr(8 - el.origin()[0], el.origin()[1], el.origin()[2] - 8));
                float a = src.rotAngle();
                cube.add("rotation", switch (src.rotAxis()) {
                    case "x" -> arr(-a, 0, 0);
                    case "y" -> arr(0, -a, 0);
                    default -> arr(0, 0, a);
                });
            }
            JsonObject uv = new JsonObject();
            for (Tile tile : el.tiles()) {
                int[] p = pos.get(tile);
                int w = tile.image.getWidth(), h = tile.image.getHeight();
                JsonObject faceUv = new JsonObject();
                if (tile.dir.equals("up") || tile.dir.equals("down")) {
                    faceUv.add("uv", arr(p[0] + w, p[1] + h));
                    faceUv.add("uv_size", arr(-w, -h));
                } else {
                    faceUv.add("uv", arr(p[0], p[1]));
                    faceUv.add("uv_size", arr(w, h));
                }
                if (!bindToHead) faceUv.addProperty("material_instance", tile.dir);
                uv.add(tile.dir, faceUv);
            }
            cube.add("uv", uv);
            cubes.add(cube);
        }
        JsonObject bone = new JsonObject();
        bone.addProperty("name", bindToHead ? "simplex_item" : "simplex_block");
        bone.add("pivot", bindToHead ? arr(0, 24, 0) : arr(0, 0, 0));
        if (bindToHead) bone.addProperty("binding", "c.item_slot == 'head' ? 'head' : q.item_slot_to_bone_name(c.item_slot)");
        bone.add("cubes", cubes);
        JsonArray bones = new JsonArray();
        bones.add(bone);

        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        description.addProperty("texture_width", width);
        description.addProperty("texture_height", height);
        description.addProperty("visible_bounds_width", 4);
        description.addProperty("visible_bounds_height", 4);
        description.add("visible_bounds_offset", arr(0, 1, 0));
        JsonObject geo = new JsonObject();
        geo.add("description", description);
        geo.add("bones", bones);
        JsonArray geos = new JsonArray();
        geos.add(geo);
        JsonObject file = new JsonObject();
        file.addProperty("format_version", "1.16.0");
        file.add("minecraft:geometry", geos);
        return new Result(file, atlas);
    }

    /** A plain unit cube for transparent block slots without a custom model. */
    public static JsonObject unitCube(String identifier) {
        JsonObject uv = new JsonObject();
        for (String face : new String[]{"north", "south", "east", "west", "up", "down"}) {
            JsonObject f = new JsonObject();
            if (face.equals("up") || face.equals("down")) {
                f.add("uv", arr(16, 16));
                f.add("uv_size", arr(-16, -16));
            } else {
                f.add("uv", arr(0, 0));
                f.add("uv_size", arr(16, 16));
            }
            f.addProperty("material_instance", face);
            uv.add(face, f);
        }
        JsonObject cube = new JsonObject();
        cube.add("origin", arr(-8, 0, -8));
        cube.add("size", arr(16, 16, 16));
        cube.add("uv", uv);
        JsonArray cubes = new JsonArray();
        cubes.add(cube);
        JsonObject bone = new JsonObject();
        bone.addProperty("name", "simplex_block");
        bone.add("pivot", arr(0, 0, 0));
        bone.add("cubes", cubes);
        JsonArray bones = new JsonArray();
        bones.add(bone);
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        description.addProperty("texture_width", 16);
        description.addProperty("texture_height", 16);
        JsonObject geo = new JsonObject();
        geo.add("description", description);
        geo.add("bones", bones);
        JsonArray geos = new JsonArray();
        geos.add(geo);
        JsonObject file = new JsonObject();
        file.addProperty("format_version", "1.16.0");
        file.add("minecraft:geometry", geos);
        return file;
    }

    private static float[] scaled(float[] v, double s) {
        if (s == 1.0) return v.clone();
        return new float[]{(float) ((v[0] - 8) * s + 8), (float) (v[1] * s), (float) ((v[2] - 8) * s + 8)};
    }

    private static BufferedImage region(BufferedImage tex, float[] uv, int rotation) {
        int tw = tex.getWidth();
        int th = tex.getHeight() > tex.getWidth() ? tex.getWidth() : tex.getHeight(); // first frame of animated strips
        double sx = tw / 16.0, sy = th / 16.0;
        double x1 = uv[0] * sx, y1 = uv[1] * sy, x2 = uv[2] * sx, y2 = uv[3] * sy;
        boolean flipX = x2 < x1, flipY = y2 < y1;
        int ix = (int) Math.floor(Math.min(x1, x2)), iy = (int) Math.floor(Math.min(y1, y2));
        int iw = Math.max(1, (int) Math.round(Math.abs(x2 - x1))), ih = Math.max(1, (int) Math.round(Math.abs(y2 - y1)));
        ix = Math.max(0, Math.min(tw - 1, ix));
        iy = Math.max(0, Math.min(th - 1, iy));
        iw = Math.min(iw, tw - ix);
        ih = Math.min(ih, th - iy);
        if (iw <= 0 || ih <= 0) return null;
        BufferedImage crop = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < ih; y++) {
            for (int x = 0; x < iw; x++) {
                int sxp = flipX ? ix + iw - 1 - x : ix + x;
                int syp = flipY ? iy + ih - 1 - y : iy + y;
                crop.setRGB(x, y, tex.getRGB(sxp, syp));
            }
        }
        int steps = Math.floorMod(rotation / 90, 4);
        for (int i = 0; i < steps; i++) crop = rotateCw(crop);
        return crop;
    }

    private static BufferedImage rotateCw(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getHeight(), src.getWidth(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++)
            for (int x = 0; x < src.getWidth(); x++)
                dst.setRGB(src.getHeight() - 1 - y, x, src.getRGB(x, y));
        return dst;
    }

    static JsonArray arr(double... v) {
        JsonArray a = new JsonArray();
        for (double d : v) {
            if (d == Math.rint(d)) a.add((long) d);
            else a.add(Math.round(d * 10000) / 10000.0);
        }
        return a;
    }

    /** Simple front view (south faces, back to front) used as Bedrock inventory icon for 3D models. */
    public static BufferedImage frontIcon(JavaModel model, Map<String, BufferedImage> textures) {
        int size = 64;
        double[] b = model.bounds();
        double w = Math.max(b[3] - b[0], b[4] - b[1]);
        if (w <= 0) w = 1;
        double scale = size / (w * 16.0) * 0.92;
        double ox = (size - (b[3] - b[0]) * 16 * scale) / 2 - b[0] * 16 * scale;
        double oy = (size - (b[4] - b[1]) * 16 * scale) / 2 + b[4] * 16 * scale;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        List<JavaModel.Element> els = new ArrayList<>(model.elements);
        els.sort(Comparator.comparingDouble(e -> Math.max(e.from()[2], e.to()[2])));
        for (JavaModel.Element e : els) {
            for (String dir : new String[]{"up", "south"}) {
                JavaModel.Face face = e.faces().stream().filter(f -> f.dir().equals(dir)).findFirst().orElse(null);
                if (face == null) continue;
                BufferedImage tex = textures.get(JavaModel.baseName(model.resolve(face.texture())));
                if (tex == null && !textures.isEmpty()) tex = textures.values().iterator().next();
                if (tex == null) continue;
                BufferedImage region = region(tex, face.uv() != null ? face.uv() : JavaModel.defaultUv(dir, e.from(), e.to()), face.rotation());
                if (region == null) continue;
                double x1 = ox + Math.min(e.from()[0], e.to()[0]) * scale, x2 = ox + Math.max(e.from()[0], e.to()[0]) * scale;
                double top = Math.max(e.from()[1], e.to()[1]), bottom = Math.min(e.from()[1], e.to()[1]);
                double depth = Math.abs(e.to()[2] - e.from()[2]);
                if (dir.equals("up")) {
                    double y2 = oy - top * scale;
                    double y1 = y2 - depth * scale * 0.35;
                    g.drawImage(region, (int) x1, (int) y1, (int) Math.max(1, x2 - x1), (int) Math.max(1, y2 - y1), null);
                } else {
                    double y1 = oy - top * scale, y2 = oy - bottom * scale;
                    g.drawImage(region, (int) x1, (int) y1, (int) Math.max(1, x2 - x1), (int) Math.max(1, y2 - y1), null);
                }
            }
        }
        g.dispose();
        return out;
    }

    /** Isometric inventory / form icon from top and side textures. */
    public static BufferedImage isoIcon(BufferedImage top, BufferedImage left, BufferedImage right) {
        int s = 64;
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        drawFace(g, frame(top), new AffineTransform(1, 0.5, -1, 0.5, 32, 0), 0f);
        drawFace(g, frame(left), new AffineTransform(1, 0.5, 0, 1, 0, 16), 0.22f);
        drawFace(g, frame(right), new AffineTransform(1, -0.5, 0, 1, 32, 32), 0.38f);
        g.dispose();
        return out;
    }

    private static BufferedImage frame(BufferedImage img) {
        if (img == null) {
            BufferedImage b = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) b.setRGB(x, y, ((x / 8 + y / 8) % 2 == 0) ? 0xFFF800F8 : 0xFF000000);
            return b;
        }
        if (img.getHeight() > img.getWidth()) return img.getSubimage(0, 0, img.getWidth(), img.getWidth());
        return img;
    }

    private static void drawFace(Graphics2D g, BufferedImage tex, AffineTransform base, float shade) {
        BufferedImage scaled = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = scaled.createGraphics();
        sg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        sg.drawImage(tex, 0, 0, 32, 32, null);
        if (shade > 0) {
            sg.setComposite(java.awt.AlphaComposite.SrcAtop);
            sg.setColor(new java.awt.Color(0, 0, 0, (int) (shade * 255)));
            sg.fillRect(0, 0, 32, 32);
        }
        sg.dispose();
        AffineTransform old = g.getTransform();
        g.transform(base);
        g.drawImage(scaled, 0, 0, null);
        g.setTransform(old);
    }
}
