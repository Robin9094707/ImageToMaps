package dev.simplex.itm.maps;

import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.maps.placement.PlacementPlan;
import dev.simplex.itm.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Owns every placed image: persistence, map creation, (re-)rendering and the animation loop.
 *
 * <p>Update strategy:
 * <ul>
 *   <li>Static images: drawn once into the map canvas – Bukkit only syncs dirty areas, so it costs nothing.</li>
 *   <li>GIFs: frames are pre-quantised, the changed rectangle between frames is pre-computed, and only that
 *       rectangle is sent to nearby viewers at the GIF's real frame rate (partial map packets). Viewers out of
 *       range receive nothing. Bedrock players get a configurable frame-rate cap, Java players full speed.
 *       Periodic key frames heal any desync.</li>
 * </ul>
 */
public final class DisplayManager implements Listener {

    private final SimplexPlugin plugin;
    private final NamespacedKey keyDisplay;
    private final Map<String, MapDisplay> displays = new LinkedHashMap<>();
    private final Map<UUID, String> frameIndex = new HashMap<>();
    private final MapPacketSender sender;
    private final ArtRenderer renderer;
    private final File file;
    private int nextId = 1;

    public DisplayManager(SimplexPlugin plugin) {
        this.plugin = plugin;
        this.keyDisplay = new NamespacedKey(plugin, "display");
        this.sender = new MapPacketSender(plugin.getLogger());
        this.renderer = new ArtRenderer(plugin.getDataFolder().toPath().resolve("cache").resolve("maps"),
                plugin.getConfig().getInt("animation.max-frames", 400));
        this.file = new File(plugin.getDataFolder(), "data/displays.yml");
    }

    public MapPacketSender sender() { return sender; }

    public Collection<MapDisplay> all() { return displays.values(); }

    public MapDisplay get(String id) { return displays.get(id); }

    public NamespacedKey key() { return keyDisplay; }

    // ------------------------------------------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------------------------------------------

    public void load() {
        displays.clear();
        frameIndex.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        nextId = cfg.getInt("next-id", 1);
        ConfigurationSection sec = cfg.getConfigurationSection("displays");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                if (s == null) continue;
                MapDisplay d = MapDisplay.load(id, s);
                displays.put(id, d);
                for (UUID u : d.frames) if (u != null) frameIndex.put(u, id);
            }
        }
        for (MapDisplay d : displays.values()) {
            attachMaps(d);
            render(d, null);
        }
        plugin.getLogger().info("Loaded " + displays.size() + " image displays.");
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("next-id", nextId);
        for (MapDisplay d : displays.values()) d.save(cfg.createSection("displays." + d.id));
        try {
            file.getParentFile().mkdirs();
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save displays: " + e.getMessage());
        }
    }

    /** Connects the stored map ids with fresh renderers (re-creates lost maps). */
    private void attachMaps(MapDisplay d) {
        World world = d.bukkitWorld();
        if (world == null) {
            d.error = "world not loaded";
            return;
        }
        if (d.mapIds.length != d.tiles()) {
            d.mapIds = new int[d.tiles()];
            java.util.Arrays.fill(d.mapIds, -1);
        }
        d.renderers = new SimplexMapRenderer[d.tiles()];
        for (int i = 0; i < d.tiles(); i++) {
            MapView view = d.mapIds[i] >= 0 ? Bukkit.getMap(d.mapIds[i]) : null;
            if (view == null) {
                view = Bukkit.createMap(world);
                d.mapIds[i] = view.getId();
            }
            d.renderers[i] = prepare(view);
        }
    }

    private SimplexMapRenderer prepare(MapView view) {
        for (var r : new ArrayList<>(view.getRenderers())) view.removeRenderer(r);
        view.setScale(MapView.Scale.CLOSEST);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        SimplexMapRenderer r = new SimplexMapRenderer();
        view.addRenderer(r);
        return r;
    }

    // ------------------------------------------------------------------------------------------------------
    // rendering
    // ------------------------------------------------------------------------------------------------------

    public boolean usesImage(String image) {
        for (MapDisplay d : displays.values()) if (d.image.equalsIgnoreCase(image)) return true;
        return false;
    }

    /** Re-renders all displays showing the image (e.g. after the file was replaced). */
    public int refreshImage(String image, Consumer<Boolean> done) {
        int count = 0;
        for (MapDisplay d : displays.values()) {
            if (d.image.equalsIgnoreCase(image)) {
                count++;
                render(d, done);
            }
        }
        return count;
    }

    public CompletableFuture<MapArt> render(MapDisplay d, Consumer<Boolean> done) {
        Path path = plugin.images().resolve(d.image);
        if (path == null) {
            d.error = "image missing";
            if (done != null) done.accept(false);
            return CompletableFuture.completedFuture(null);
        }
        d.loading = true;
        return CompletableFuture.supplyAsync(() -> {
            try {
                return renderer.render(path, d.tilesX, d.tilesY, d.fit, d.dither);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, plugin.workers()).whenComplete((art, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            d.loading = false;
            if (err != null || art == null) {
                d.error = err == null ? "unknown" : err.getCause() != null ? err.getCause().getMessage() : err.getMessage();
                plugin.getLogger().warning("Could not render image '" + d.image + "' for display " + d.id + ": " + d.error);
                if (done != null) done.accept(false);
                return;
            }
            apply(d, art);
            if (done != null) done.accept(true);
        }));
    }

    private void apply(MapDisplay d, MapArt art) {
        d.error = null;
        d.art = art;
        d.frame = 0;
        d.nextFrameAt = System.currentTimeMillis() + art.delays()[0];
        if (d.renderers == null) attachMaps(d);
        if (d.renderers == null) return;
        for (int t = 0; t < d.tiles() && t < art.tiles(); t++) d.renderers[t].set(art.frames()[0][t]);
        // instant update for everyone nearby (otherwise vanilla would take up to 10 ticks)
        d.viewerFrame.clear();
        if (sender.fast()) {
            for (Player p : viewers(d)) sendFull(d, p, 0);
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // creation / removal
    // ------------------------------------------------------------------------------------------------------

    public MapDisplay create(Player creator, PlacementPlan plan, String image, FitMode fit, boolean dither, FrameStyle style) {
        MapDisplay d = new MapDisplay();
        d.id = "d" + (nextId++);
        d.image = image;
        d.tilesX = plan.tilesX();
        d.tilesY = plan.tilesY();
        d.fit = fit;
        d.dither = dither;
        d.style = style;
        d.world = plan.world().getName();
        d.x = plan.anchor()[0];
        d.y = plan.anchor()[1];
        d.z = plan.anchor()[2];
        d.face = plan.face();
        d.right = plan.right();
        d.down = plan.down();
        d.rotation = plan.rotation();
        d.owner = creator == null ? "console" : creator.getName();
        d.created = System.currentTimeMillis();
        attachMaps(d);
        spawnFrames(d);
        displays.put(d.id, d);
        save();
        render(d, null);
        return d;
    }

    private void spawnFrames(MapDisplay d) {
        World world = d.bukkitWorld();
        if (world == null) return;
        d.frames = new UUID[d.tiles()];
        for (int row = 0; row < d.tilesY; row++) {
            for (int col = 0; col < d.tilesX; col++) {
                int index = row * d.tilesX + col;
                int[] p = d.tilePos(col, row);
                Location loc = new Location(world, p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
                MapView view = Bukkit.getMap(d.mapIds[index]);
                ItemStack map = new ItemStack(Material.FILLED_MAP);
                map.editMeta(MapMeta.class, m -> m.setMapView(view));
                Class<? extends ItemFrame> cls = d.style.glow ? GlowItemFrame.class : ItemFrame.class;
                try {
                    ItemFrame frame = world.spawn(loc, cls, f -> {
                        f.setFacingDirection(d.face, true);
                        f.setItem(map, false);
                        f.setRotation(Rotation.values()[Math.floorMod(d.rotation, 4)]);
                        f.setVisible(!d.style.invisible);
                        f.setFixed(true);
                        f.setInvulnerable(true);
                        f.setPersistent(true);
                        f.setSilent(true);
                        f.getPersistentDataContainer().set(keyDisplay, PersistentDataType.STRING, d.id);
                    });
                    d.frames[index] = frame.getUniqueId();
                    frameIndex.put(frame.getUniqueId(), d.id);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Could not place item frame at " + p[0] + "," + p[1] + "," + p[2] + ": " + e.getMessage());
                }
            }
        }
    }

    private void removeFrames(MapDisplay d) {
        for (UUID u : d.frames) {
            if (u == null) continue;
            frameIndex.remove(u);
            Entity e = Bukkit.getEntity(u);
            if (e != null) e.remove();
        }
        // also sweep loaded frames carrying our tag (frames in unloaded chunks are removed when they load)
        World w = d.bukkitWorld();
        if (w != null) {
            for (ItemFrame f : w.getEntitiesByClass(ItemFrame.class)) {
                if (d.id.equals(f.getPersistentDataContainer().get(keyDisplay, PersistentDataType.STRING))) f.remove();
            }
        }
    }

    public void remove(MapDisplay d) {
        removeFrames(d);
        displays.remove(d.id);
        save();
    }

    public void restyle(MapDisplay d, FrameStyle style) {
        removeFrames(d);
        d.style = style;
        spawnFrames(d);
        save();
    }

    public void changeImage(MapDisplay d, String image, FitMode fit, boolean dither, Consumer<Boolean> done) {
        d.image = image;
        d.fit = fit;
        d.dither = dither;
        save();
        render(d, done);
    }

    /** The display the player looks at (or the nearest one within 6 blocks). */
    public MapDisplay target(Player player) {
        Entity e = player.getTargetEntity(10);
        if (e instanceof ItemFrame f) {
            String id = f.getPersistentDataContainer().get(keyDisplay, PersistentDataType.STRING);
            if (id != null && displays.containsKey(id)) return displays.get(id);
        }
        return displays.values().stream()
                .filter(d -> player.getWorld().getName().equals(d.world))
                .filter(d -> d.center().distanceSquared(player.getLocation()) < 36 + d.tiles())
                .min(Comparator.comparingDouble(d -> d.center().distanceSquared(player.getLocation())))
                .orElse(null);
    }

    public MapDisplay byFrame(Entity entity) {
        if (!(entity instanceof ItemFrame f)) return null;
        String id = f.getPersistentDataContainer().get(keyDisplay, PersistentDataType.STRING);
        return id == null ? null : displays.get(id);
    }

    // ------------------------------------------------------------------------------------------------------
    // animation loop
    // ------------------------------------------------------------------------------------------------------

    public void tick() {
        long now = System.currentTimeMillis();
        int bedrockFps = Math.max(1, plugin.getConfig().getInt("animation.bedrock-max-fps", 10));
        int javaFps = Math.max(1, plugin.getConfig().getInt("animation.java-max-fps", 20));
        long keyframeMs = plugin.getConfig().getLong("animation.keyframe-seconds", 8) * 1000L;
        for (MapDisplay d : displays.values()) {
            MapArt art = d.art;
            if (art == null || !art.animated() || d.paused) continue;
            if (now < d.nextFrameAt) continue;
            // advance (skip frames if the server lagged)
            int guard = 0;
            while (now >= d.nextFrameAt && guard++ < art.frameCount()) {
                d.frame = (d.frame + 1) % art.frameCount();
                d.nextFrameAt += Math.max(20, art.delays()[d.frame]);
            }
            if (now - d.nextFrameAt > 2000) d.nextFrameAt = now + art.delays()[d.frame];

            List<Player> viewers = viewers(d);
            if (!sender.fast()) {
                // fallback: let Bukkit sync the canvas (≈2 fps in frames) + push full maps at a low rate
                for (int t = 0; t < d.tiles(); t++) d.renderers[t].set(art.frames()[d.frame][t]);
                continue;
            }
            boolean keyframe = now - d.lastKeyframe > keyframeMs;
            if (keyframe) d.lastKeyframe = now;
            d.viewerFrame.keySet().retainAll(viewers.stream().map(Player::getUniqueId).toList());
            for (Player p : viewers) {
                UUID u = p.getUniqueId();
                boolean bedrock = plugin.platform().isBedrock(p);
                long minGap = 1000L / (bedrock ? bedrockFps : javaFps);
                Long last = d.viewerLastSend.get(u);
                Integer shown = d.viewerFrame.get(u);
                if (shown != null && !keyframe && last != null && now - last < minGap) continue;
                if (shown == null || keyframe) {
                    sendFull(d, p, d.frame);
                } else if (shown == (d.frame - 1 + art.frameCount()) % art.frameCount()) {
                    for (int t = 0; t < d.tiles(); t++) {
                        int[] rect = art.dirty()[d.frame][t];
                        if (rect != null) sender.sendRegion(p, d.mapIds[t], art.frames()[d.frame][t], rect);
                    }
                } else if (shown != d.frame) {
                    for (int t = 0; t < d.tiles(); t++) {
                        int[] rect = MapArt.diff(art.frames()[shown][t], art.frames()[d.frame][t]);
                        if (rect != null) sender.sendRegion(p, d.mapIds[t], art.frames()[d.frame][t], rect);
                    }
                }
                d.viewerFrame.put(u, d.frame);
                d.viewerLastSend.put(u, now);
            }
        }
    }

    private void sendFull(MapDisplay d, Player p, int frame) {
        MapArt art = d.art;
        if (art == null) return;
        for (int t = 0; t < d.tiles() && t < art.tiles(); t++) {
            sender.sendRegion(p, d.mapIds[t], art.frames()[frame][t], MapPacketSender.FULL);
        }
        d.viewerFrame.put(p.getUniqueId(), frame);
    }

    private List<Player> viewers(MapDisplay d) {
        World w = d.bukkitWorld();
        if (w == null) return List.of();
        double range = plugin.getConfig().getDouble("animation.view-distance", 64);
        double r2 = (range + Math.max(d.tilesX, d.tilesY)) * (range + Math.max(d.tilesX, d.tilesY));
        Location c = d.center();
        List<Player> out = new ArrayList<>();
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(c) <= r2) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------------------------------------------
    // protection & self healing
    // ------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onHangingBreak(HangingBreakEvent event) {
        if (isOurs(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        if (isOurs(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (isOurs(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID u = event.getPlayer().getUniqueId();
        for (MapDisplay d : displays.values()) {
            d.viewerFrame.remove(u);
            d.viewerLastSend.remove(u);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (!(e instanceof ItemFrame f)) continue;
            String id = f.getPersistentDataContainer().get(keyDisplay, PersistentDataType.STRING);
            if (id != null && !displays.containsKey(id)) f.remove(); // orphan of a removed display
        }
    }

    private boolean isOurs(Entity e) {
        return e instanceof ItemFrame && (frameIndex.containsKey(e.getUniqueId())
                || e.getPersistentDataContainer().has(keyDisplay, PersistentDataType.STRING));
    }

    public String describe(Player viewer, MapDisplay d) {
        Lang lang = plugin.lang();
        String status = d.loading ? lang.raw(lang.code(viewer), "display.status-loading")
                : d.error != null ? lang.raw(lang.code(viewer), "display.status-error").replace("<error>", d.error)
                : d.art != null && d.art.animated() ? lang.raw(lang.code(viewer), "display.status-animated").replace("<frames>", String.valueOf(d.art.frameCount()))
                : lang.raw(lang.code(viewer), "display.status-ok");
        return status;
    }
}
