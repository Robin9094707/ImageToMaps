package dev.simplex.itm.content.blocks;

import dev.simplex.common.SlotLayout;
import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.content.BlockDef;
import dev.simplex.itm.content.ContentManager;
import dev.simplex.itm.util.Sfx;
import io.papermc.paper.event.player.PlayerPickBlockEvent;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.Keyed;

import java.util.List;

/** Placing, breaking and protecting custom blocks. */
public final class BlockListener implements Listener {

    private final SimplexPlugin plugin;
    private final ContentManager content;
    private final BreakSpeed breakSpeed;

    public BlockListener(SimplexPlugin plugin) {
        this.plugin = plugin;
        this.content = plugin.content();
        this.breakSpeed = new BreakSpeed(plugin);
    }

    // ------------------------------------------------------------------------------------------------------
    // placing
    // ------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() == null) return;
        ItemStack item = event.getItem();
        BlockDef def = content.blockOf(item);
        if (def == null) return;
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null || plugin.placement().active(player)) return;
        if (!player.isSneaking() && clicked.getType().isInteractable() && content.blockAt(clicked) == null) return; // open chests etc.
        event.setCancelled(true);
        if (!content.blocksEnabled() || !player.hasPermission("simplex.blocks.place")) return;

        Block target = clicked.isReplaceable() && content.blockAt(clicked) == null ? clicked : clicked.getRelative(event.getBlockFace());
        if (!target.isReplaceable() || target.getY() < target.getWorld().getMinHeight() || target.getY() >= target.getWorld().getMaxHeight()) return;
        for (var e : target.getWorld().getNearbyEntities(org.bukkit.util.BoundingBox.of(target), en -> en instanceof LivingEntity)) {
            if (e instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) continue;
            return; // something stands there
        }
        BlockState replaced = target.getState();
        BlockData data = content.state(def);
        target.setBlockData(data, false);
        BlockPlaceEvent place = new BlockPlaceEvent(target, replaced, clicked, item, player, true, event.getHand());
        Bukkit.getPluginManager().callEvent(place);
        if (place.isCancelled() || !place.canBuild()) {
            replaced.update(true, false);
            return;
        }
        plugin.blockIndex().add(target, def.slot);
        if (player.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
        player.swingHand(event.getHand());
        playPlace(target, def);
    }

    private void playPlace(Block block, BlockDef def) {
        String custom = def.customSounds.get("place");
        String sound = custom != null ? "minecraft:simplex." + custom : def.sound.place();
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().playSound(Sfx.sound(sound, 1f, 0.8f), loc.getX(), loc.getY(), loc.getZ());
    }

    /** Java clients only – Bedrock clients play the sound from the generated pack themselves. */
    private void playBreak(Block block, BlockDef def, BlockData data) {
        String custom = def.customSounds.get("break");
        String sound = custom != null ? "minecraft:simplex." + custom : def.sound.breakSound();
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);
        Sound s = Sfx.sound(sound, 1f, 0.8f);
        for (Player p : block.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) > 256 || plugin.platform().isBedrock(p)) continue;
            p.playSound(s, loc.getX(), loc.getY(), loc.getZ());
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // breaking
    // ------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        BlockDef def = content.blockAt(event.getBlock());
        if (def != null) breakSpeed.apply(event.getPlayer(), event.getBlock(), def);
        else breakSpeed.clear(event.getPlayer());
    }

    @EventHandler
    public void onAbort(BlockDamageAbortEvent event) {
        breakSpeed.clear(event.getPlayer());
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        breakSpeed.clear(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        breakSpeed.clear(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockDef def = content.blockAt(block);
        if (def == null) return;
        Player player = event.getPlayer();
        breakSpeed.clear(player);
        event.setDropItems(false);
        event.setExpToDrop(0);
        BlockData data = block.getBlockData();
        plugin.blockIndex().remove(block);
        playBreak(block, def, data);
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        boolean correct = def.tool.reference().isPreferredTool(tool);
        if (def.requiresTool && !correct) return;
        dropFor(block, def, tool);
    }

    private void dropFor(Block block, BlockDef def, ItemStack tool) {
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);
        if ("none".equalsIgnoreCase(def.drop)) return;
        if ("self".equalsIgnoreCase(def.drop)) {
            block.getWorld().dropItemNaturally(loc, content.item(def, 1));
            return;
        }
        BlockDef other = content.block(def.drop.replace("simplex:", ""));
        if (other != null) {
            block.getWorld().dropItemNaturally(loc, content.item(other, 1));
            return;
        }
        Material m = Material.matchMaterial(def.drop);
        if (m != null && m.isItem()) block.getWorld().dropItemNaturally(loc, new ItemStack(m));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        explode(event.blockList(), event.getYield());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        explode(event.blockList(), event.getYield());
    }

    private void explode(List<Block> blocks, float yield) {
        blocks.removeIf(b -> {
            BlockDef def = content.blockAt(b);
            if (def == null) return false;
            plugin.blockIndex().remove(b);
            BlockData data = b.getBlockData();
            b.setType(Material.AIR, false);
            b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, data);
            if (Math.random() < yield) dropFor(b, def, null);
            return true;
        });
    }

    // ------------------------------------------------------------------------------------------------------
    // protection: keep the reserved states stable
    // ------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        Material m = event.getBlock().getType();
        if ((m == Material.BROWN_MUSHROOM_BLOCK || m == Material.RED_MUSHROOM_BLOCK || m == Material.MUSHROOM_STEM || m == Material.CHORUS_PLANT)
                && content.isReserved(event.getBlock().getBlockData())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        for (Block b : event.getBlocks()) if (content.blockAt(b) != null) { event.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) if (content.blockAt(b) != null) { event.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (content.blockAt(event.getBlock()) != null) event.setCancelled(true); // endermen, ravagers …
    }

    /** Vanilla mushroom / chorus blocks placed by players must never look like a custom block. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanillaPlace(BlockPlaceEvent event) {
        Block b = event.getBlockPlaced();
        Material m = b.getType();
        if (m != Material.BROWN_MUSHROOM_BLOCK && m != Material.RED_MUSHROOM_BLOCK && m != Material.MUSHROOM_STEM && m != Material.CHORUS_PLANT) return;
        if (content.blockOf(event.getItemInHand()) != null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (b.getType() != m || !content.isReserved(b.getBlockData())) return;
            MultipleFacing mf = (MultipleFacing) b.getBlockData();
            for (BlockFace f : mf.getAllowedFaces()) mf.setFace(f, m != Material.CHORUS_PLANT);
            b.setBlockData(mf, false);
        });
    }

    // ------------------------------------------------------------------------------------------------------
    // misc
    // ------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPick(PlayerPickBlockEvent event) {
        BlockDef def = content.blockAt(event.getBlock());
        if (def == null) return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        ItemStack wanted = content.item(def, 1);
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (wanted.isSimilar(inv.getItem(i))) {
                inv.setHeldItemSlot(i);
                return;
            }
        }
        if (p.getGameMode() == GameMode.CREATIVE) {
            int slot = inv.firstEmpty();
            if (slot >= 0 && slot < 9) inv.setHeldItemSlot(slot);
            inv.setItemInMainHand(wanted);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.blockIndex().onChunkLoad(event.getChunk());
    }

    /** Custom items are paper underneath – keep them out of vanilla recipes. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(PrepareItemCraftEvent event) {
        Recipe r = event.getRecipe();
        if (r instanceof Keyed k && k.getKey().getNamespace().equals(plugin.getName().toLowerCase(java.util.Locale.ROOT))) return;
        for (ItemStack i : event.getInventory().getMatrix()) {
            if (content.identify(i) != null) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!content.recipeKeys().isEmpty()) p.discoverRecipes(content.recipeKeys());
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshInventory(p), 20L);
    }

    public void refreshInventory(Player p) {
        if (!p.isOnline()) return;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            ItemStack fresh = content.refresh(it);
            if (fresh != null) inv.setItem(i, fresh.getType().isAir() ? null : fresh);
        }
    }

    public static boolean isHost(Material m) {
        return SlotLayout.isHostBlock(m.getKey().getKey());
    }
}
