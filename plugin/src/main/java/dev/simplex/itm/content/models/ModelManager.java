package dev.simplex.itm.content.models;

import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.content.ModelDef;
import dev.simplex.itm.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Places 3D models in the world so that Java <b>and</b> Bedrock players see them:
 * <ul>
 *   <li>Java: an {@link ItemDisplay} with the model's item (smooth, any rotation and scale).</li>
 *   <li>Bedrock: an invisible marker armor stand wearing the item – Geyser maps it to a custom item and the
 *       generated attachable renders the converted 3D geometry. It is only shown to Bedrock players.</li>
 *   <li>Both: an {@link Interaction} hit box (sit / break) and an optional barrier for collision.</li>
 * </ul>
 * While holding a model item a live ghost preview follows the crosshair (Java: glowing hologram, Bedrock: particles).
 */
public final class ModelManager implements Listener {

    public static final class Placed {
        public String group, model, world;
        public int x, y, z;
        public float yaw;
        public UUID display, stand, interaction;
        public boolean barrier;
        public String owner = "";
    }

    private final SimplexPlugin plugin;
    private final NamespacedKey keyGroup, keySeat;
    private final File file;
    private final Map<String, Placed> placed = new LinkedHashMap<>();
    private final Map<UUID, String> entityToGroup = new HashMap<>();
    private final Map<UUID, ItemDisplay> ghosts = new HashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public ModelManager(SimplexPlugin plugin) {
        this.plugin = plugin;
        this.keyGroup = new NamespacedKey(plugin, "model_group");
        this.keySeat = new NamespacedKey(plugin, "seat");
        this.file = new File(plugin.getDataFolder(), "data/placed-models.yml");
    }

    public java.util.Collection<Placed> all() { return placed.values(); }

    // ------------------------------------------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------------------------------------------

    public void load() {
        placed.clear();
        entityToGroup.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String g : cfg.getKeys(false)) {
            ConfigurationSection s = cfg.getConfigurationSection(g);
            if (s == null) continue;
            Placed p = new Placed();
            p.group = g;
            p.model = s.getString("model");
            p.world = s.getString("world");
            p.x = s.getInt("x");
            p.y = s.getInt("y");
            p.z = s.getInt("z");
            p.yaw = (float) s.getDouble("yaw");
            p.display = uuid(s.getString("display"));
            p.stand = uuid(s.getString("stand"));
            p.interaction = uuid(s.getString("interaction"));
            p.barrier = s.getBoolean("barrier");
            p.owner = s.getString("owner", "");
            placed.put(g, p);
            index(p);
        }
    }

    private static UUID uuid(String s) {
        try { return s == null ? null : UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private void index(Placed p) {
        for (UUID u : new UUID[]{p.display, p.stand, p.interaction}) if (u != null) entityToGroup.put(u, p.group);
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Placed p : placed.values()) {
            String g = p.group + ".";
            cfg.set(g + "model", p.model);
            cfg.set(g + "world", p.world);
            cfg.set(g + "x", p.x);
            cfg.set(g + "y", p.y);
            cfg.set(g + "z", p.z);
            cfg.set(g + "yaw", p.yaw);
            cfg.set(g + "display", str(p.display));
            cfg.set(g + "stand", str(p.stand));
            cfg.set(g + "interaction", str(p.interaction));
            cfg.set(g + "barrier", p.barrier);
            cfg.set(g + "owner", p.owner);
        }
        try {
            file.getParentFile().mkdirs();
            cfg.save(file);
        } catch (IOException ignored) {
        }
    }

    private static String str(UUID u) { return u == null ? null : u.toString(); }

    // ------------------------------------------------------------------------------------------------------
    // placing / removing
    // ------------------------------------------------------------------------------------------------------

    public Placed place(ModelDef def, Block block, float yaw, Player owner) {
        World world = block.getWorld();
        String group = UUID.randomUUID().toString().substring(0, 13);
        Placed p = new Placed();
        p.group = group;
        p.model = def.id;
        p.world = world.getName();
        p.x = block.getX();
        p.y = block.getY();
        p.z = block.getZ();
        p.yaw = yaw;
        p.owner = owner == null ? "" : owner.getName();

        ItemStack item = plugin.content().item(def, 1);
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        center.setYaw(yaw);
        center.setPitch(0);
        ItemDisplay display = world.spawn(center, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(transformation(def));
            d.setViewRange((float) (plugin.getConfig().getDouble("models.view-range", 96) / 64.0));
            d.setPersistent(true);
            if (def.glowing) d.setBrightness(new Display.Brightness(15, 15));
            tag(d, group);
        });
        p.display = display.getUniqueId();

        Location standLoc = block.getLocation().add(0.5 + def.bedrockOffset[0], def.bedrockOffset[1], 0.5 + def.bedrockOffset[2]);
        standLoc.setYaw(yaw + def.bedrockYawOffset);
        ArmorStand stand = world.spawn(standLoc, ArmorStand.class, s -> {
            s.setVisibleByDefault(false);
            s.setInvisible(true);
            s.setMarker(true);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setPersistent(true);
            s.setSilent(true);
            s.setDisabledSlots(EquipmentSlot.values());
            s.getEquipment().setHelmet(item);
            tag(s, group);
        });
        p.stand = stand.getUniqueId();
        for (Player pl : Bukkit.getOnlinePlayers()) if (plugin.platform().isBedrock(pl)) pl.showEntity(plugin, stand);

        double[] b = def.bounds;
        float width = (float) Math.max(0.3, Math.min(3, Math.max(b[3] - b[0], b[5] - b[2]) * def.scale));
        float height = (float) Math.max(0.2, Math.min(4, (b[4] - Math.max(0, b[1])) * def.scale));
        Interaction hit = world.spawn(block.getLocation().add(0.5, Math.max(0, b[1]) * def.scale, 0.5), Interaction.class, i -> {
            i.setInteractionWidth(width);
            i.setInteractionHeight(height);
            i.setResponsive(true);
            i.setPersistent(true);
            tag(i, group);
        });
        p.interaction = hit.getUniqueId();

        if (def.collision && block.getType().isAir()) {
            block.setType(Material.BARRIER, false);
            p.barrier = true;
        }
        placed.put(group, p);
        index(p);
        save();
        return p;
    }

    private void tag(Entity e, String group) {
        e.getPersistentDataContainer().set(keyGroup, PersistentDataType.STRING, group);
    }

    public static Transformation transformation(ModelDef def) {
        float s = (float) def.scale;
        // ItemDisplay "NONE" renders the model centred on the entity; scale around the model's bottom
        float ty = (s - 1f) * 0.5f;
        return new Transformation(new Vector3f(0, ty, 0), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f());
    }

    public void remove(Placed p, boolean drop) {
        placed.remove(p.group);
        World world = Bukkit.getWorld(p.world);
        for (UUID u : new UUID[]{p.display, p.stand, p.interaction}) {
            if (u == null) continue;
            entityToGroup.remove(u);
            Entity e = Bukkit.getEntity(u);
            if (e != null) e.remove();
        }
        if (world != null) {
            Block b = world.getBlockAt(p.x, p.y, p.z);
            if (p.barrier && b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
            ModelDef def = plugin.content().model(p.model);
            if (drop && def != null) world.dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), plugin.content().item(def, 1));
            world.spawnParticle(Particle.CLOUD, b.getLocation().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3, 0.02);
        }
        save();
    }

    public void removeAll(String modelId) {
        for (Placed p : new ArrayList<>(placed.values())) {
            if (!p.model.equals(modelId)) continue;
            World w = Bukkit.getWorld(p.world);
            if (w == null) continue;
            w.getChunkAtAsync(p.x >> 4, p.z >> 4).thenAccept(c -> remove(p, false));
        }
    }

    /** Applies changed definitions (scale, texture, glow …) to every placed copy – live. */
    public void refreshModel(ModelDef def) {
        ItemStack item = plugin.content().item(def, 1);
        for (Placed p : placed.values()) {
            if (!p.model.equals(def.id)) continue;
            if (Bukkit.getEntity(p.display) instanceof ItemDisplay d) {
                d.setItemStack(item);
                d.setTransformation(transformation(def));
                d.setBrightness(def.glowing ? new Display.Brightness(15, 15) : null);
            }
            if (Bukkit.getEntity(p.stand) instanceof ArmorStand s) s.getEquipment().setHelmet(item);
        }
    }

    public Placed byEntity(Entity e) {
        String g = entityToGroup.get(e.getUniqueId());
        if (g == null) g = e.getPersistentDataContainer().get(keyGroup, PersistentDataType.STRING);
        return g == null ? null : placed.get(g);
    }

    public Placed byBarrier(Block b) {
        if (b.getType() != Material.BARRIER) return null;
        for (Placed p : placed.values()) {
            if (p.barrier && p.x == b.getX() && p.y == b.getY() && p.z == b.getZ() && p.world.equals(b.getWorld().getName())) return p;
        }
        return null;
    }

    public Placed nearest(Player player, double range) {
        Placed best = null;
        double bestD = range * range;
        for (Placed p : placed.values()) {
            if (!p.world.equals(player.getWorld().getName())) continue;
            double d = player.getLocation().distanceSquared(new Location(player.getWorld(), p.x + 0.5, p.y, p.z + 0.5));
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------------
    // interaction
    // ------------------------------------------------------------------------------------------------------

    private boolean debounce(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastUse.get(p.getUniqueId());
        if (last != null && now - last < 250) return true;
        lastUse.put(p.getUniqueId(), now);
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlaceItem(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        if (plugin.placement().active(event.getPlayer())) return;
        ModelDef def = plugin.content().modelOf(event.getItem());
        Block clicked = event.getClickedBlock();
        if (clicked != null) {
            Placed seat = byBarrier(clicked);
            if (seat != null && def == null) {
                event.setCancelled(true);
                use(event.getPlayer(), seat);
                return;
            }
        }
        if (def == null || clicked == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (debounce(player) || !plugin.content().modelsEnabled() || !player.hasPermission("simplex.models.place")) return;
        Block target = clicked.isReplaceable() ? clicked : clicked.getRelative(event.getBlockFace());
        if (!target.isReplaceable() || occupied(target)) {
            plugin.sfx().play(player, "error");
            return;
        }
        // let protection plugins decide
        BlockBreakEvent probe = new BlockBreakEvent(target, player);
        Bukkit.getPluginManager().callEvent(probe);
        if (probe.isCancelled()) return;
        place(def, target, snapYaw(player, def), player);
        if (player.getGameMode() != GameMode.CREATIVE) event.getItem().setAmount(event.getItem().getAmount() - 1);
        player.swingMainHand();
        target.getWorld().playSound(target.getLocation().add(0.5, 0.5, 0.5), org.bukkit.Sound.BLOCK_WOOD_PLACE, 1f, 0.9f);
    }

    private boolean occupied(Block b) {
        for (Placed p : placed.values()) if (p.x == b.getX() && p.y == b.getY() && p.z == b.getZ() && p.world.equals(b.getWorld().getName())) return true;
        return false;
    }

    public static float snapYaw(Player player, ModelDef def) {
        float yaw = player.getLocation().getYaw() + 180f; // face the player
        float step = def.rotationStep;
        return Math.round(yaw / step) * step;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Placed p = byEntity(event.getRightClicked());
        if (p == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND || debounce(event.getPlayer())) return;
        use(event.getPlayer(), p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (byEntity(event.getRightClicked()) != null) event.setCancelled(true);
    }

    private void use(Player player, Placed p) {
        ModelDef def = plugin.content().model(p.model);
        if (def == null || !def.seat || player.isInsideVehicle()) return;
        World w = Bukkit.getWorld(p.world);
        if (w == null) return;
        Location seatLoc = new Location(w, p.x + 0.5, p.y + def.seatHeight * def.scale - 0.05, p.z + 0.5, p.yaw + 180f, 0);
        ArmorStand seat = w.spawn(seatLoc, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setSmall(true);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setPersistent(false);
            s.getPersistentDataContainer().set(keySeat, PersistentDataType.BYTE, (byte) 1);
        });
        seat.addPassenger(player);
        plugin.sfx().play(player, "sit");
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        Entity seat = event.getDismounted();
        if (!seat.getPersistentDataContainer().has(keySeat, PersistentDataType.BYTE)) return;
        Entity rider = event.getEntity();
        Bukkit.getScheduler().runTask(plugin, () -> {
            seat.remove();
            if (rider.isValid()) rider.teleport(rider.getLocation().add(0, 0.6, 0));
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent event) {
        Placed p = byEntity(event.getEntity());
        if (p == null) return;
        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player player) || !player.isSneaking() || debounce(player)) return;
        if (!player.hasPermission("simplex.models.break")) return;
        World w = Bukkit.getWorld(p.world);
        if (w == null) return;
        BlockBreakEvent probe = new BlockBreakEvent(w.getBlockAt(p.x, p.y, p.z), player);
        Bukkit.getPluginManager().callEvent(probe);
        if (probe.isCancelled()) return;
        remove(p, player.getGameMode() != GameMode.CREATIVE);
        plugin.sfx().play(player, "remove");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (byEntity(event.getEntity()) != null && !(event instanceof EntityDamageByEntityEvent)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBarrierBreak(BlockBreakEvent event) {
        Placed p = byBarrier(event.getBlock());
        if (p == null) return;
        event.setCancelled(true);
        if (event.getPlayer().isSneaking() && event.getPlayer().hasPermission("simplex.models.break")) {
            remove(p, event.getPlayer().getGameMode() != GameMode.CREATIVE);
            plugin.sfx().play(event.getPlayer(), "remove");
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Bedrock visibility
    // ------------------------------------------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!plugin.platform().isBedrock(player)) return;
        for (Placed p : placed.values()) {
            if (p.stand == null) continue;
            Entity e = Bukkit.getEntity(p.stand);
            if (e != null) player.showEntity(plugin, e);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<Player> bedrock = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.platform().isBedrock(p)) bedrock.add(p);
        for (Entity e : event.getEntities()) {
            if (e.getPersistentDataContainer().has(keySeat, PersistentDataType.BYTE)) {
                e.remove();
                continue;
            }
            String g = e.getPersistentDataContainer().get(keyGroup, PersistentDataType.STRING);
            if (g == null) continue;
            if (!placed.containsKey(g)) {
                e.remove(); // orphan
                continue;
            }
            if (e instanceof ArmorStand) for (Player p : bedrock) p.showEntity(plugin, e);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ItemDisplay ghost = ghosts.remove(event.getPlayer().getUniqueId());
        if (ghost != null) ghost.remove();
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------------------------------------------
    // ghost preview
    // ------------------------------------------------------------------------------------------------------

    public void tick() {
        if (!plugin.getConfig().getBoolean("models.ghost-preview", true)) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            ModelDef def = plugin.content().modelOf(player.getInventory().getItemInMainHand());
            ItemDisplay ghost = ghosts.get(player.getUniqueId());
            if (def == null || plugin.placement().active(player)) {
                if (ghost != null) {
                    ghost.remove();
                    ghosts.remove(player.getUniqueId());
                }
                continue;
            }
            RayTraceResult hit = player.rayTraceBlocks(6, FluidCollisionMode.NEVER);
            if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) {
                if (ghost != null) ghost.setGlowColorOverride(Color.GRAY);
                continue;
            }
            Block target = hit.getHitBlock().isReplaceable() ? hit.getHitBlock() : hit.getHitBlock().getRelative(hit.getHitBlockFace());
            boolean ok = target.isReplaceable() && !occupied(target);
            float yaw = snapYaw(player, def);
            Location loc = target.getLocation().add(0.5, 0.5, 0.5);
            loc.setYaw(yaw);
            loc.setPitch(0);
            if (plugin.platform().isBedrock(player)) {
                box(player, target, ok);
                continue;
            }
            if (ghost == null || !ghost.isValid()) {
                ghost = player.getWorld().spawn(loc, ItemDisplay.class, d -> {
                    d.setVisibleByDefault(false);
                    d.setPersistent(false);
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    d.setGlowing(true);
                    d.setTeleportDuration(2);
                    d.setBrightness(new Display.Brightness(15, 15));
                });
                player.showEntity(plugin, ghost);
                ghosts.put(player.getUniqueId(), ghost);
            } else if (!ghost.getWorld().equals(loc.getWorld()) || ghost.getLocation().distanceSquared(loc) > 0.0001 || ghost.getLocation().getYaw() != yaw) {
                ghost.teleport(loc);
            }
            ItemStack item = plugin.content().item(def, 1);
            if (!item.isSimilar(ghost.getItemStack())) ghost.setItemStack(item);
            ghost.setTransformation(transformation(def));
            ghost.setGlowColorOverride(ok ? Color.fromRGB(0x45F27A) : Color.fromRGB(0xFF3B3B));
        }
    }

    private void box(Player player, Block b, boolean ok) {
        Particle.DustOptions dust = new Particle.DustOptions(ok ? Color.fromRGB(0x45F27A) : Color.fromRGB(0xFF3B3B), 0.7f);
        double x = b.getX(), y = b.getY(), z = b.getZ();
        for (double t = 0; t <= 1.0001; t += 0.25) {
            for (double[] e : new double[][]{{t, 0, 0}, {t, 0, 1}, {0, 0, t}, {1, 0, t}, {t, 1, 0}, {t, 1, 1}, {0, 1, t}, {1, 1, t}, {0, t, 0}, {1, t, 0}, {0, t, 1}, {1, t, 1}}) {
                player.spawnParticle(Particle.DUST, x + e[0], y + e[1], z + e[2], 1, 0, 0, 0, 0, dust);
            }
        }
    }

    public void shutdown() {
        for (ItemDisplay d : ghosts.values()) d.remove();
        ghosts.clear();
    }

    public String describe(Player viewer, Placed p) {
        ModelDef def = plugin.content().model(p.model);
        String name = def == null ? p.model : def.name.get(plugin.lang().code(viewer));
        return name + " @ " + p.world + " " + p.x + " " + p.y + " " + p.z;
    }

    static String lang(SimplexPlugin plugin, Player p, String key) {
        return Lang.plain(plugin.lang().get(p, key));
    }
}
