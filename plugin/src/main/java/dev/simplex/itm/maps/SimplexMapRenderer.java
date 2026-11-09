package dev.simplex.itm.maps;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Non-contextual renderer: draws its pixel buffer only when it changed. Bukkit tracks the dirty area itself, so
 * clients only receive what actually changed. Used for static images and as fallback / initial state for GIFs.
 */
public final class SimplexMapRenderer extends MapRenderer {

    private volatile byte[] pixels;
    private volatile int version;
    private int drawnVersion = -1;

    public SimplexMapRenderer() {
        super(false);
    }

    public void set(byte[] pixels) {
        this.pixels = pixels;
        this.version++;
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        byte[] px = pixels;
        if (px == null || drawnVersion == version) return;
        drawnVersion = version;
        for (int y = 0; y < 128; y++) {
            int row = y << 7;
            for (int x = 0; x < 128; x++) canvas.setPixel(x, y, px[row + x]);
        }
    }
}
