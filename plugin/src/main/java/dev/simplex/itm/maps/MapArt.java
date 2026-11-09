package dev.simplex.itm.maps;

/**
 * Pre-rendered map tiles of an image.
 *
 * @param tilesX  width in maps
 * @param tilesY  height in maps
 * @param frames  [frame][tile][128*128] map colour indices, tiles row-major (left→right, top→bottom)
 * @param delays  frame durations in ms
 * @param dirty   [frame][tile] = {x, y, w, h} region that differs from the previous frame (frame 0 vs last frame),
 *                or null when the tile did not change
 */
public record MapArt(int tilesX, int tilesY, byte[][][] frames, int[] delays, int[][][] dirty) {

    public int tiles() { return tilesX * tilesY; }

    public boolean animated() { return frames.length > 1; }

    public int frameCount() { return frames.length; }

    public long totalDuration() {
        long t = 0;
        for (int d : delays) t += d;
        return t;
    }

    /** Smallest rectangle {x,y,w,h} that differs between two tiles, null if identical. */
    public static int[] diff(byte[] a, byte[] b) {
        int minX = 128, minY = 128, maxX = -1, maxY = -1;
        for (int y = 0; y < 128; y++) {
            int row = y << 7;
            for (int x = 0; x < 128; x++) {
                if (a[row + x] != b[row + x]) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return null;
        return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }
}
