package dev.simplex.itm.maps;

import org.bukkit.map.MapPalette;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Fast perceptual colour matching against the Minecraft map palette with optional Floyd–Steinberg dithering.
 * A 64×64×64 lookup table is computed once in parallel, so quantising a 4K image takes milliseconds.
 */
public final class ColorMatcher {

    private static volatile ColorMatcher instance;

    private final byte[] lut = new byte[64 * 64 * 64];
    private final int[] paletteRgb = new int[256];

    private ColorMatcher() {
        List<int[]> palette = new ArrayList<>();
        for (int i = 4; i < 256; i++) {
            try {
                @SuppressWarnings("deprecation")
                Color c = MapPalette.getColor((byte) i);
                if (c.getAlpha() < 255) continue;
                palette.add(new int[]{i, c.getRed(), c.getGreen(), c.getBlue()});
                paletteRgb[i] = c.getRGB();
            } catch (Throwable ignored) {
                // end of palette
            }
        }
        int[][] pal = palette.toArray(new int[0][]);
        IntStream.range(0, 64 * 64).parallel().forEach(rg -> {
            int r = ((rg >> 6) << 2) | 2;
            int g = ((rg & 63) << 2) | 2;
            for (int bi = 0; bi < 64; bi++) {
                int b = (bi << 2) | 2;
                int best = 4;
                double bestD = Double.MAX_VALUE;
                for (int[] p : pal) {
                    double d = distance(r, g, b, p[1], p[2], p[3]);
                    if (d < bestD) {
                        bestD = d;
                        best = p[0];
                    }
                }
                lut[(rg << 6) | bi] = (byte) best;
            }
        });
    }

    public static ColorMatcher get() {
        ColorMatcher m = instance;
        if (m == null) {
            synchronized (ColorMatcher.class) {
                m = instance;
                if (m == null) instance = m = new ColorMatcher();
            }
        }
        return m;
    }

    /** "Redmean" weighted euclidean distance – cheap and visually much better than plain RGB distance. */
    private static double distance(int r1, int g1, int b1, int r2, int g2, int b2) {
        double rm = (r1 + r2) / 2.0;
        int dr = r1 - r2, dg = g1 - g2, db = b1 - b2;
        return (2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db;
    }

    public byte match(int r, int g, int b) {
        return lut[((r >> 2) << 12) | ((g >> 2) << 6) | (b >> 2)];
    }

    public int rgb(byte index) {
        return paletteRgb[index & 0xFF];
    }

    /**
     * Quantises ARGB pixels into map colour indices.
     *
     * @param argb   pixels, row major
     * @param out    output indices (same length)
     * @param dither Floyd–Steinberg error diffusion (serpentine)
     */
    public void quantize(int[] argb, int width, int height, boolean dither, byte[] out) {
        if (!dither) {
            for (int i = 0; i < argb.length; i++) {
                int c = argb[i];
                if ((c >>> 24) < 128) {
                    out[i] = 0;
                } else {
                    out[i] = match((c >> 16) & 255, (c >> 8) & 255, c & 255);
                }
            }
            return;
        }
        float[] errR = new float[(width + 2) * 2], errG = new float[(width + 2) * 2], errB = new float[(width + 2) * 2];
        for (int y = 0; y < height; y++) {
            int cur = (y & 1) * (width + 2), next = ((y + 1) & 1) * (width + 2);
            for (int i = 0; i < width + 2; i++) {
                errR[next + i] = 0;
                errG[next + i] = 0;
                errB[next + i] = 0;
            }
            boolean ltr = (y & 1) == 0;
            for (int k = 0; k < width; k++) {
                int x = ltr ? k : width - 1 - k;
                int idx = y * width + x;
                int c = argb[idx];
                if ((c >>> 24) < 128) {
                    out[idx] = 0;
                    continue;
                }
                int e = x + 1;
                float r = clamp(((c >> 16) & 255) + errR[cur + e]);
                float g = clamp(((c >> 8) & 255) + errG[cur + e]);
                float b = clamp((c & 255) + errB[cur + e]);
                byte m = match((int) r, (int) g, (int) b);
                out[idx] = m;
                int p = paletteRgb[m & 0xFF];
                float dr = r - ((p >> 16) & 255), dg = g - ((p >> 8) & 255), db = b - (p & 255);
                int dir = ltr ? 1 : -1;
                spread(errR, errG, errB, cur + e + dir, dr, dg, db, 7 / 16f);
                spread(errR, errG, errB, next + e - dir, dr, dg, db, 3 / 16f);
                spread(errR, errG, errB, next + e, dr, dg, db, 5 / 16f);
                spread(errR, errG, errB, next + e + dir, dr, dg, db, 1 / 16f);
            }
        }
    }

    private static void spread(float[] r, float[] g, float[] b, int i, float dr, float dg, float db, float f) {
        r[i] += dr * f;
        g[i] += dg * f;
        b[i] += db * f;
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
