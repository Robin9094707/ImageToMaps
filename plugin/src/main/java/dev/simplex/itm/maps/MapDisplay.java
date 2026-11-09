package dev.simplex.itm.maps;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A placed image: a rectangle of item frames holding maps. */
public final class MapDisplay {

    public String id;
    public String image;
    public int tilesX, tilesY;
    public FitMode fit = FitMode.FIT;
    public boolean dither = true;
    public FrameStyle style = FrameStyle.INVISIBLE;
    public String world;
    /** Block position of the top-left frame (as seen by the viewer). */
    public int x, y, z;
    public BlockFace face = BlockFace.NORTH;
    public int[] right = {1, 0, 0};
    public int[] down = {0, -1, 0};
    public int rotation;
    public int[] mapIds = new int[0];
    public UUID[] frames = new UUID[0];
    public String owner = "";
    public long created;
    public boolean paused;

    // ---- runtime state ----
    public transient MapArt art;
    public transient SimplexMapRenderer[] renderers;
    public transient int frame;
    public transient long nextFrameAt;
    public transient boolean loading;
    public transient String error;
    public transient final Map<UUID, Integer> viewerFrame = new HashMap<>();
    public transient final Map<UUID, Long> viewerLastSend = new HashMap<>();
    public transient long lastKeyframe;

    public int tiles() { return tilesX * tilesY; }

    public World bukkitWorld() { return Bukkit.getWorld(world); }

    /** Frame block position of tile {@code col,row}. */
    public int[] tilePos(int col, int row) {
        return new int[]{x + right[0] * col + down[0] * row, y + right[1] * col + down[1] * row, z + right[2] * col + down[2] * row};
    }

    public Location center() {
        World w = bukkitWorld();
        double cx = x + 0.5 + right[0] * (tilesX - 1) / 2.0 + down[0] * (tilesY - 1) / 2.0;
        double cy = y + 0.5 + right[1] * (tilesX - 1) / 2.0 + down[1] * (tilesY - 1) / 2.0;
        double cz = z + 0.5 + right[2] * (tilesX - 1) / 2.0 + down[2] * (tilesY - 1) / 2.0;
        return new Location(w, cx, cy, cz);
    }

    public Vector normal() { return face.getDirection(); }

    public void save(ConfigurationSection s) {
        s.set("image", image);
        s.set("tiles-x", tilesX);
        s.set("tiles-y", tilesY);
        s.set("fit", fit.name());
        s.set("dither", dither);
        s.set("style", style.name());
        s.set("world", world);
        s.set("pos", List.of(x, y, z));
        s.set("face", face.name());
        s.set("right", List.of(right[0], right[1], right[2]));
        s.set("down", List.of(down[0], down[1], down[2]));
        s.set("rotation", rotation);
        List<Integer> ids = new ArrayList<>();
        for (int id : mapIds) ids.add(id);
        s.set("maps", ids);
        List<String> f = new ArrayList<>();
        for (UUID u : frames) f.add(u == null ? "" : u.toString());
        s.set("frames", f);
        s.set("owner", owner);
        s.set("created", created);
        s.set("paused", paused);
    }

    public static MapDisplay load(String id, ConfigurationSection s) {
        MapDisplay d = new MapDisplay();
        d.id = id;
        d.image = s.getString("image");
        d.tilesX = s.getInt("tiles-x", 1);
        d.tilesY = s.getInt("tiles-y", 1);
        d.fit = FitMode.parse(s.getString("fit", "FIT"), FitMode.FIT);
        d.dither = s.getBoolean("dither", true);
        d.style = FrameStyle.parse(s.getString("style", "INVISIBLE"), FrameStyle.INVISIBLE);
        d.world = s.getString("world");
        List<Integer> pos = s.getIntegerList("pos");
        if (pos.size() == 3) { d.x = pos.get(0); d.y = pos.get(1); d.z = pos.get(2); }
        try { d.face = BlockFace.valueOf(s.getString("face", "NORTH")); } catch (Exception ignored) { }
        d.right = ints(s.getIntegerList("right"), d.right);
        d.down = ints(s.getIntegerList("down"), d.down);
        d.rotation = s.getInt("rotation", 0);
        List<Integer> maps = s.getIntegerList("maps");
        d.mapIds = maps.stream().mapToInt(Integer::intValue).toArray();
        List<String> frames = s.getStringList("frames");
        d.frames = new UUID[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            try { d.frames[i] = frames.get(i).isEmpty() ? null : UUID.fromString(frames.get(i)); } catch (Exception ignored) { }
        }
        d.owner = s.getString("owner", "");
        d.created = s.getLong("created", 0);
        d.paused = s.getBoolean("paused", false);
        return d;
    }

    private static int[] ints(List<Integer> list, int[] def) {
        if (list.size() != 3) return def;
        return new int[]{list.get(0), list.get(1), list.get(2)};
    }
}
