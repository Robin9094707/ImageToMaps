package dev.simplex.itm.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Deterministic in-memory zip: same content → same bytes → same SHA-1 (no needless client re-downloads). */
public final class ZipBuilder {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Map<String, byte[]> entries = new TreeMap<>();

    public void put(String path, byte[] data) { entries.put(path, data); }

    public void put(String path, String text) { entries.put(path, text.getBytes(StandardCharsets.UTF_8)); }

    public void json(String path, JsonElement json) { put(path, GSON.toJson(json)); }

    public void png(String path, BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        entries.put(path, out.toByteArray());
    }

    public boolean has(String path) { return entries.containsKey(path); }

    public int size() { return entries.size(); }

    public byte[] build() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setLevel(9);
            for (var e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(315532800000L); // 1980-01-01, fixed
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
