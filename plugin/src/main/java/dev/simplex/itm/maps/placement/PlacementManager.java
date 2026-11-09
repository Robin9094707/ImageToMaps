package dev.simplex.itm.maps.placement;

import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.maps.ArtRenderer;
import dev.simplex.itm.maps.FitMode;
import dev.simplex.itm.maps.FrameStyle;
import dev.simplex.itm.maps.MapDisplay;
import dev.simplex.itm.util.Lang;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Guided placement: while active, the player sees a live particle outline of the image on the wall / floor /
 * ceiling they look at – green when it fits, red cells where support blocks are missing or something is in the
 * way. Works identically for Bedrock players (particles + action bar + boss bar).
 *
 * <p>Controls: right click = place, left click = frame style, sneak + scroll = size, swap hands (F) = fit mode,
 * sneak + right click = cancel. Existing displays can be re-targeted ("move") the same way.
 */
public final class PlacementManager implements Listener {

    public static final class Session {
        final UUID player;
        String image;
        int tilesX, tilesY;
        final double aspect;
        FitMode fit;
        boolean dither;
        FrameStyle style;
        PlacementPlan plan;
        Boolean lastValid;
        long lastClick;
        MapDisplay moving;
        BossBar bar;

        Session(UUID player, String image, int tilesX, int tilesY, double aspect, FitMode fit, boolean dither, FrameStyle style) {
            this.player = player;
            this.image = image;
            this.tilesX = tilesX;
            this.tilesY = tilesY;
            this.aspect = aspect;
            this.fit = fit;
            this.dither = dither;
            this.style = style;
        }
    }

    private static final Particle.DustOptions GREEN = new Particle.DustOptions(Color.fromRGB(0x45F27A), 0.9f);
    private static final Particle.DustOptions GREEN_SOFT = new Particle.DustOptions(Color.fromRGB(0x9DFFB8), 0.55f);
    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.fromRGB(0xFF3B3B), 0.9f);
    private static final Particle.DustOptions ORANGE = new Particle.DustOptions(Color.fromRGB(0xFFA43B), 0.9f);
    private static final Particle.DustOptions WHITE = new Particle.DustOptions(Color.fromRGB(0xFFFFFF), 0.7f);

    private final SimplexPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private int tick;

    public PlacementManager(SimplexPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean active(Player p) { return sessions.containsKey(p.getUniqueId()); }

    /** Starts guided placement. width/height <= 0 = derive from the image (aspect ratio preserved). */
    public boolean start(Player player, String image, int width, int height) {
        Path path = plugin.images().resolve(image);
        if (path == null) {
            plugin.lang().send(player, "image.not-found", Lang.ph("image", image));
            plugin.sfx().play(player, "error");
            return false;
        }
        int[] px;
        try {
            px = ArtRenderer.size(path);
        } catch (Exception e) {
            plugin.lang().send(player, "image.invalid", Lang.ph("error", e.getMessage()));
            plugin.sfx().play(player, "error");
            return false;
        }
        double aspect = (double) px[1] / px[0];
        int max = plugin.getConfig().getInt("placement.max-size", 16);
        if (width <= 0 && height <= 0) {
            width = Math.max(1, Math.min(plugin.getConfig().getInt("placement.default-width", 3), max));
            height = Math.max(1, (int) Math.round(width * aspect));
        } else if (height <= 0) {
            height = Math.max(1, (int) Math.round(width * aspect));
        } else if (width <= 0) {
            width = Math.max(1, (int) Math.round(height / aspect));
        }
        width = Math.min(width, max);
        height = Math.min(height, max);
        stop(player, false);
        Session s = new Session(player.getUniqueId(), path.getFileName().toString(), width, height, aspect,
                FitMode.parse(plugin.getConfig().getString("placement.default-fit", "FIT"), FitMode.FIT),
                plugin.getConfig().getBoolean("placement.default-dither", true),
                FrameStyle.parse(plugin.getConfig().getString("placement.default-style", "INVISIBLE"), FrameStyle.INVISIBLE));
        s.bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        player.showBossBar(s.bar);
        sessions.put(player.getUniqueId(), s);
        plugin.sfx().play(player, "place-start");
        plugin.lang().send(player, plugin.platform().isBedrock(player) ? "placement.started-bedrock" : "placement.started",
                Lang.ph("image", s.image), Lang.ph("w", width), Lang.ph("h", height));
        return true;
    }

    /** Re-place an existing display somewhere else (keeps its maps). */
    public void startMove(Player player, MapDisplay display) {
        if (start(player, display.image, display.tilesX, display.tilesY)) {
            Session s = sessions.get(player.getUniqueId());
            s.moving = display;
            s.fit = display.fit;
            s.dither = display.dither;
            s.style = display.style;
        }
    }

    public void stop(Player player, boolean announce) {
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) return;
        if (s.bar != null) player.hideBossBar(s.bar);
        player.sendActionBar(Component.empty());
        if (announce) {
            plugin.sfx().play(player, "place-cancel");
            plugin.lang().send(player, "placement.cancelled");
        }
    }

    public void stopAll() {
        for (UUID u : sessions.keySet().toArray(UUID[]::new)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) stop(p, false);
        }
        sessions.clear();
    }

    public void resize(Player player, int w, int h) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return;
        int max = plugin.getConfig().getInt("placement.max-size", 16);
        s.tilesX = Math.max(1, Math.min(max, w));
        s.tilesY = Math.max(1, Math.min(max, h));
        s.lastValid = null;
    }

    // ------------------------------------------------------------------------------------------------------

    public void tick() {
        tick++;
        if (sessions.isEmpty() || tick % 3 != 0) return;
        for (Session s : sessions.values()) {
            Player player = Bukkit.getPlayer(s.player);
            if (player == null) continue;
            update(player, s);
        }
    }

    private void update(Player player, Session s) {
        Lang lang = plugin.lang();
        RayTraceResult hit = player.rayTraceBlocks(plugin.getConfig().getDouble("placement.range", 48), FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) {
            s.plan = null;
            player.sendActionBar(lang.get(player, "placement.no-target"));
            updateBar(player, s, false);
            return;
        }
        s.plan = PlacementPlan.compute(hit.getHitBlock(), hit.getHitBlockFace(),
                PlacementPlan.cardinal(player.getLocation().getYaw()), s.tilesX, s.tilesY);
        boolean valid = s.plan.valid();
        if (s.lastValid == null || s.lastValid != valid) {
            plugin.sfx().play(player, valid ? "place-valid" : "place-invalid");
            s.lastValid = valid;
        }
        draw(player, s.plan);
        if (valid) {
            player.sendActionBar(lang.get(player, plugin.platform().isBedrock(player) ? "placement.valid-bedrock" : "placement.valid"));
        } else {
            player.sendActionBar(lang.get(player, "placement.invalid",
                    Lang.ph("missing", s.plan.count(PlacementPlan.Problem.NO_SUPPORT)),
                    Lang.ph("blocked", s.plan.count(PlacementPlan.Problem.BLOCKED) + s.plan.count(PlacementPlan.Problem.OCCUPIED))));
        }
        updateBar(player, s, valid);
    }

    private void updateBar(Player player, Session s, boolean valid) {
        Lang lang = plugin.lang();
        s.bar.name(lang.get(player, "placement.bossbar",
                Lang.ph("image", s.image), Lang.ph("w", s.tilesX), Lang.ph("h", s.tilesY),
                Lang.comp("style", lang.get(player, s.style.langKey())),
                Lang.comp("fit", lang.get(player, "fit-mode." + s.fit.name().toLowerCase()))));
        s.bar.color(s.plan == null ? BossBar.Color.WHITE : valid ? BossBar.Color.GREEN : BossBar.Color.RED);
    }

    /** Particle outline on the support surface, only visible to the placing player. */
    private void draw(Player player, PlacementPlan plan) {
        Vector n = new Vector(plan.face().getModX(), plan.face().getModY(), plan.face().getModZ());
        Vector r = new Vector(plan.right()[0], plan.right()[1], plan.right()[2]);
        Vector d = new Vector(plan.down()[0], plan.down()[1], plan.down()[2]);
        boolean valid = plan.valid();
        // corner of the anchor cell (top-left), on the support face, slightly lifted
        Vector anchorCenter = new Vector(plan.anchor()[0] + 0.5, plan.anchor()[1] + 0.5, plan.anchor()[2] + 0.5);
        Vector base = anchorCenter.clone().subtract(n.clone().multiply(0.5 - 0.06));
        Vector topLeft = base.clone().subtract(r.clone().multiply(0.5)).subtract(d.clone().multiply(0.5));
        Vector topRight = topLeft.clone().add(r.clone().multiply(plan.tilesX()));
        Vector bottomLeft = topLeft.clone().add(d.clone().multiply(plan.tilesY()));
        Vector bottomRight = topRight.clone().add(d.clone().multiply(plan.tilesY()));
        Particle.DustOptions edge = valid ? GREEN : RED;
        double step = plan.tilesX() * plan.tilesY() > 64 ? 0.5 : 0.25;
        line(player, topLeft, topRight, edge, step);
        line(player, topRight, bottomRight, edge, step);
        line(player, bottomRight, bottomLeft, edge, step);
        line(player, bottomLeft, topLeft, edge, step);
        // "up" arrow so it is obvious where the top of the image is (important on floors)
        Vector mid = topLeft.clone().add(r.clone().multiply(plan.tilesX() / 2.0));
        line(player, mid.clone().add(d.clone().multiply(0.6)), mid.clone().add(d.clone().multiply(0.05)), WHITE, 0.15);

        for (PlacementPlan.Cell c : plan.cells()) {
            Vector cellCenter = new Vector(c.x() + 0.5, c.y() + 0.5, c.z() + 0.5).subtract(n.clone().multiply(0.5 - 0.06));
            Vector a = cellCenter.clone().subtract(r.clone().multiply(0.42)).subtract(d.clone().multiply(0.42));
            Vector b = cellCenter.clone().add(r.clone().multiply(0.42)).add(d.clone().multiply(0.42));
            Vector a2 = cellCenter.clone().add(r.clone().multiply(0.42)).subtract(d.clone().multiply(0.42));
            Vector b2 = cellCenter.clone().subtract(r.clone().multiply(0.42)).add(d.clone().multiply(0.42));
            switch (c.problem()) {
                case NONE -> spawn(player, cellCenter, GREEN_SOFT);
                case NO_SUPPORT -> {
                    line(player, a, b, RED, 0.12);
                    line(player, a2, b2, RED, 0.12);
                }
                case BLOCKED, OCCUPIED -> {
                    line(player, a, b, ORANGE, 0.15);
                    line(player, a2, b2, ORANGE, 0.15);
                }
            }
        }
    }

    private void line(Player player, Vector from, Vector to, Particle.DustOptions dust, double step) {
        Vector diff = to.clone().subtract(from);
        double len = diff.length();
        if (len < 1e-4) return;
        int points = Math.max(1, (int) Math.ceil(len / step));
        for (int i = 0; i <= points; i++) {
            spawn(player, from.clone().add(diff.clone().multiply((double) i / points)), dust);
        }
    }

    private void spawn(Player player, Vector v, Particle.DustOptions dust) {
        player.spawnParticle(Particle.DUST, v.getX(), v.getY(), v.getZ(), 1, 0, 0, 0, 0, dust);
    }

    // ------------------------------------------------------------------------------------------------------
    // input
    // ------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null || event.getAction() == Action.PHYSICAL) return;
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        if (now - s.lastClick < 200) return; // Bedrock sends several packets per tap
        s.lastClick = now;
        Action a = event.getAction();
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            s.style = s.style.next();
            plugin.sfx().play(player, "style");
            plugin.lang().actionBar(player, "placement.style-changed", Lang.comp("style", plugin.lang().get(player, s.style.langKey())));
            return;
        }
        if (player.isSneaking()) {
            stop(player, true);
            return;
        }
        confirm(player, s);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null) return;
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND && System.currentTimeMillis() - s.lastClick > 200) {
            s.lastClick = System.currentTimeMillis();
            if (event.getPlayer().isSneaking()) stop(event.getPlayer(), true);
            else confirm(event.getPlayer(), s);
        }
    }

    public void confirm(Player player, Session s) {
        if (s.plan == null) {
            plugin.sfx().play(player, "error");
            plugin.lang().actionBar(player, "placement.no-target");
            return;
        }
        update(player, s);
        if (s.plan == null || !s.plan.valid()) {
            plugin.sfx().play(player, "error");
            plugin.lang().send(player, "placement.cannot-place",
                    Lang.ph("missing", s.plan == null ? 0 : s.plan.count(PlacementPlan.Problem.NO_SUPPORT)),
                    Lang.ph("blocked", s.plan == null ? 0 : s.plan.count(PlacementPlan.Problem.BLOCKED) + s.plan.count(PlacementPlan.Problem.OCCUPIED)));
            return;
        }
        PlacementPlan plan = s.plan;
        stop(player, false);
        if (s.moving != null && plugin.displays().get(s.moving.id) != null) {
            plugin.displays().remove(s.moving);
        }
        MapDisplay d = plugin.displays().create(player, plan, s.image, s.fit, s.dither, s.style);
        plugin.sfx().play(player, "place-confirm");
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.sfx().play(player, "place-done"), 6L);
        plugin.lang().send(player, "placement.placed", Lang.ph("id", d.id), Lang.ph("image", d.image),
                Lang.ph("w", d.tilesX), Lang.ph("h", d.tilesY));
        if (plugin.platform().isBedrock(player) && s.style.invisible) plugin.lang().send(player, "placement.bedrock-invisible-note");
    }

    @EventHandler(ignoreCancelled = true)
    public void onScroll(PlayerItemHeldEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null || !event.getPlayer().isSneaking()) return;
        int prev = event.getPreviousSlot(), next = event.getNewSlot();
        int delta = (next - prev + 9) % 9;
        boolean up = delta >= 1 && delta <= 4;
        event.setCancelled(true);
        int max = plugin.getConfig().getInt("placement.max-size", 16);
        int w = Math.max(1, Math.min(max, s.tilesX + (up ? -1 : 1)));
        int h = Math.max(1, Math.min(max, (int) Math.round(w * s.aspect)));
        resize(event.getPlayer(), w, h);
        plugin.sfx().play(event.getPlayer(), "resize");
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null) return;
        event.setCancelled(true);
        s.fit = s.fit.next();
        plugin.sfx().play(event.getPlayer(), "style");
        plugin.lang().actionBar(event.getPlayer(), "placement.fit-changed",
                Lang.comp("fit", plugin.lang().get(event.getPlayer(), "fit-mode." + s.fit.name().toLowerCase())));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        stop(event.getPlayer(), true);
    }

    public Session session(Player p) { return sessions.get(p.getUniqueId()); }

    public void configure(Player p, FitMode fit, FrameStyle style, boolean dither) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        s.fit = fit;
        s.style = style;
        s.dither = dither;
    }
}
