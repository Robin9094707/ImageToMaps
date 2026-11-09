package dev.simplex.itm.maps;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Turns image files (PNG, JPG, BMP, static or animated GIF) into {@link MapArt}, with a disk cache. */
public final class ArtRenderer {

    private static final int CACHE_VERSION = 3;

    private final Path cacheDir;
    private final int maxFrames;

    public ArtRenderer(Path cacheDir, int maxFrames) {
        this.cacheDir = cacheDir;
        this.maxFrames = maxFrames;
    }

    public static boolean isAnimated(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    /** Reads the pixel size of an image without fully decoding it (falls back to decoding). */
    public static int[] size(Path file) throws IOException {
        try (var in = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(in);
            if (readers.hasNext()) {
                var r = readers.next();
                try {
                    r.setInput(in);
                    return new int[]{r.getWidth(0), r.getHeight(0)};
                } finally {
                    r.dispose();
                }
            }
        }
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) throw new IOException("Unsupported image: " + file.getFileName());
        return new int[]{img.getWidth(), img.getHeight()};
    }

    public MapArt render(Path file, int tilesX, int tilesY, FitMode fit, boolean dither) throws IOException {
        String key = cacheKey(file, tilesX, tilesY, fit, dither);
        Path cached = cacheDir.resolve(key + ".bin.gz");
        if (Files.isRegularFile(cached)) {
            try {
                return read(cached);
            } catch (Exception ignored) {
                Files.deleteIfExists(cached);
            }
        }
        List<BufferedImage> sources;
        List<Integer> delays;
        if (isAnimated(file)) {
            GifDecoder.Result r = GifDecoder.decode(file, maxFrames);
            sources = r.frames();
            delays = r.delaysMs();
        } else {
            BufferedImage img = ImageIO.read(file.toFile());
            if (img == null) throw new IOException("Unsupported image format: " + file.getFileName());
            sources = List.of(img);
            delays = List.of(0);
        }

        int w = tilesX * 128, h = tilesY * 128;
        ColorMatcher matcher = ColorMatcher.get();
        List<byte[][]> frames = new ArrayList<>();
        List<Integer> frameDelays = new ArrayList<>();
        byte[] full = new byte[w * h];
        int[] argb = new int[w * h];
        byte[][] last = null;
        for (int f = 0; f < sources.size(); f++) {
            BufferedImage scaled = fit(sources.get(f), w, h, fit);
            scaled.getRGB(0, 0, w, h, argb, 0, w);
            matcher.quantize(argb, w, h, dither, full);
            byte[][] tiles = split(full, tilesX, tilesY);
            if (last != null && same(last, tiles)) {
                // merge identical consecutive frames – saves memory and bandwidth
                frameDelays.set(frameDelays.size() - 1, frameDelays.getLast() + delays.get(f));
                continue;
            }
            frames.add(tiles);
            frameDelays.add(delays.get(f));
            last = tiles;
        }
        byte[][][] arr = frames.toArray(new byte[0][][]);
        int[] d = frameDelays.stream().mapToInt(Integer::intValue).toArray();
        MapArt art = new MapArt(tilesX, tilesY, arr, d, dirty(arr));
        try {
            write(cached, art);
        } catch (IOException ignored) {
        }
        return art;
    }

    private static int[][][] dirty(byte[][][] frames) {
        int[][][] dirty = new int[frames.length][][];
        for (int f = 0; f < frames.length; f++) {
            byte[][] prev = frames[(f - 1 + frames.length) % frames.length];
            dirty[f] = new int[frames[f].length][];
            for (int t = 0; t < frames[f].length; t++) dirty[f][t] = MapArt.diff(prev[t], frames[f][t]);
        }
        return dirty;
    }

    private static boolean same(byte[][] a, byte[][] b) {
        for (int i = 0; i < a.length; i++) if (!java.util.Arrays.equals(a[i], b[i])) return false;
        return true;
    }

    private static byte[][] split(byte[] full, int tilesX, int tilesY) {
        int w = tilesX * 128;
        byte[][] tiles = new byte[tilesX * tilesY][128 * 128];
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                byte[] tile = tiles[ty * tilesX + tx];
                for (int y = 0; y < 128; y++) {
                    System.arraycopy(full, (ty * 128 + y) * w + tx * 128, tile, y * 128, 128);
                }
            }
        }
        return tiles;
    }

    /** High quality resize into a transparent canvas of exactly {@code w×h} pixels. */
    public static BufferedImage fit(BufferedImage src, int w, int h, FitMode mode) {
        int sw = src.getWidth(), sh = src.getHeight();
        int dw = w, dh = h, dx = 0, dy = 0;
        if (mode != FitMode.STRETCH) {
            double scale = mode == FitMode.FIT ? Math.min((double) w / sw, (double) h / sh) : Math.max((double) w / sw, (double) h / sh);
            dw = Math.max(1, (int) Math.round(sw * scale));
            dh = Math.max(1, (int) Math.round(sh * scale));
            dx = (w - dw) / 2;
            dy = (h - dh) / 2;
        }
        BufferedImage scaled = progressiveScale(toArgb(src), dw, dh);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(scaled, dx, dy, null);
        g.dispose();
        return out;
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage b = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = b.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return b;
    }

    /** Halves repeatedly before the final step – avoids the aliasing of a single bilinear downscale. */
    private static BufferedImage progressiveScale(BufferedImage img, int tw, int th) {
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage cur = img;
        while (w / 2 >= tw && h / 2 >= th) {
            w /= 2;
            h /= 2;
            cur = scale(cur, w, h, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        if (w != tw || h != th) cur = scale(cur, tw, th, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return cur;
    }

    private static BufferedImage scale(BufferedImage src, int w, int h, Object interpolation) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    // ---- cache ------------------------------------------------------------------------------------------

    private static String cacheKey(Path file, int tx, int ty, FitMode fit, boolean dither) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            md.update((CACHE_VERSION + ":" + tx + "x" + ty + ":" + fit + ":" + dither).getBytes());
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void write(Path file, MapArt art) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp); DataOutputStream out = new DataOutputStream(new GZIPOutputStream(os, 1 << 16))) {
            out.writeInt(CACHE_VERSION);
            out.writeInt(art.tilesX());
            out.writeInt(art.tilesY());
            out.writeInt(art.frames().length);
            for (int f = 0; f < art.frames().length; f++) {
                out.writeInt(art.delays()[f]);
                for (byte[] tile : art.frames()[f]) out.write(tile);
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static MapArt read(Path file) throws IOException {
        try (InputStream is = Files.newInputStream(file); DataInputStream in = new DataInputStream(new GZIPInputStream(is, 1 << 16))) {
            if (in.readInt() != CACHE_VERSION) throw new IOException("old cache");
            int tx = in.readInt(), ty = in.readInt(), n = in.readInt();
            byte[][][] frames = new byte[n][tx * ty][128 * 128];
            int[] delays = new int[n];
            for (int f = 0; f < n; f++) {
                delays[f] = in.readInt();
                for (int t = 0; t < tx * ty; t++) in.readFully(frames[f][t]);
            }
            return new MapArt(tx, ty, frames, delays, dirty(frames));
        }
    }
}
