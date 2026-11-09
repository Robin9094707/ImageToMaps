package dev.simplex.itm.ui;

import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.content.RecipeDef;
import dev.simplex.itm.util.Items;
import dev.simplex.itm.util.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.function.BiConsumer;

/**
 * Chest based 3×3 recipe editor. Works for Java and Bedrock players (Geyser shows it as a chest).
 * Put ingredients into the grid, adjust the amount and press save.
 */
public final class RecipeEditor implements Listener {

    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int RESULT = 24, MINUS = 32, PLUS = 34, AMOUNT = 33, SAVE = 41, CLEAR = 39, REMOVE = 43, CANCEL = 44;

    private final SimplexPlugin plugin;

    public RecipeEditor(SimplexPlugin plugin) {
        this.plugin = plugin;
    }

    static final class Holder implements InventoryHolder {
        Inventory inv;
        ItemStack result;
        int amount;
        BiConsumer<Player, RecipeDef> onSave;
        boolean closing;

        @Override
        public Inventory getInventory() { return inv; }
    }

    public void open(Player player, ItemStack result, RecipeDef current, BiConsumer<Player, RecipeDef> onSave) {
        Holder h = new Holder();
        h.result = result.clone();
        h.amount = current == null ? 1 : current.amount();
        h.onSave = onSave;
        Lang lang = plugin.lang();
        Inventory inv = Bukkit.createInventory(h, 45, lang.get(player, "recipe.title"));
        h.inv = inv;
        ItemStack pane = Items.icon(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), null);
        for (int i = 0; i < 45; i++) inv.setItem(i, pane);
        for (int s : GRID) inv.setItem(s, null);
        if (current != null) {
            for (int r = 0; r < current.shape().size() && r < 3; r++) {
                String row = current.shape().get(r);
                for (int c = 0; c < row.length() && c < 3; c++) {
                    String id = current.ingredients().get(row.charAt(c));
                    if (id != null) inv.setItem(GRID[r * 3 + c], plugin.content().ingredientIcon(id));
                }
            }
        }
        redraw(player, h);
        player.openInventory(inv);
        plugin.sfx().play(player, "menu-open");
    }

    private void redraw(Player player, Holder h) {
        Lang lang = plugin.lang();
        ItemStack res = h.result.clone();
        res.setAmount(Math.max(1, Math.min(64, h.amount)));
        h.inv.setItem(RESULT, res);
        h.inv.setItem(MINUS, Items.icon(Material.RED_STAINED_GLASS_PANE, lang.item(player, "recipe.less"), null));
        h.inv.setItem(AMOUNT, Items.icon(Material.PAPER, lang.item(player, "recipe.amount", Lang.ph("amount", h.amount)), null));
        h.inv.setItem(PLUS, Items.icon(Material.LIME_STAINED_GLASS_PANE, lang.item(player, "recipe.more"), null));
        h.inv.setItem(SAVE, Items.icon(Material.EMERALD_BLOCK, lang.item(player, "recipe.save"), lang.lore(player, "recipe.save-lore")));
        h.inv.setItem(CLEAR, Items.icon(Material.BUCKET, lang.item(player, "recipe.clear"), null));
        h.inv.setItem(REMOVE, Items.icon(Material.LAVA_BUCKET, lang.item(player, "recipe.remove"), null));
        h.inv.setItem(CANCEL, Items.icon(Material.BARRIER, lang.item(player, "menu.cancel"), null));
        h.inv.setItem(22, Items.icon(Material.CRAFTING_TABLE, lang.item(player, "recipe.hint"), lang.lore(player, "recipe.hint-lore")));
    }

    private static boolean isGrid(int slot) {
        for (int s : GRID) if (s == slot) return true;
        return false;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder h) || !(event.getWhoClicked() instanceof Player player)) return;
        int raw = event.getRawSlot();
        if (raw >= 45) {
            if (event.isShiftClick()) event.setCancelled(true);
            return; // own inventory: free
        }
        if (isGrid(raw)) {
            // ingredients are only "ghost" copies: never consume the player's items
            event.setCancelled(true);
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                ItemStack copy = cursor.clone();
                copy.setAmount(1);
                h.inv.setItem(raw, copy);
            } else {
                h.inv.setItem(raw, null);
            }
            plugin.sfx().play(player, "click");
            return;
        }
        event.setCancelled(true);
        switch (raw) {
            case MINUS -> { h.amount = Math.max(1, h.amount - (event.isShiftClick() ? 8 : 1)); redraw(player, h); plugin.sfx().play(player, "click"); }
            case PLUS -> { h.amount = Math.min(64, h.amount + (event.isShiftClick() ? 8 : 1)); redraw(player, h); plugin.sfx().play(player, "click"); }
            case CLEAR -> { for (int s : GRID) h.inv.setItem(s, null); plugin.sfx().play(player, "click"); }
            case CANCEL -> { plugin.sfx().play(player, "back"); player.closeInventory(); }
            case REMOVE -> {
                h.closing = true;
                player.closeInventory();
                h.onSave.accept(player, null);
            }
            case SAVE -> {
                String[] grid = new String[9];
                for (int i = 0; i < 9; i++) grid[i] = plugin.content().ingredientId(h.inv.getItem(GRID[i]));
                RecipeDef def = RecipeDef.fromGrid(grid, h.amount);
                if (def == null) {
                    plugin.sfx().play(player, "error");
                    return;
                }
                h.closing = true;
                player.closeInventory();
                h.onSave.accept(player, def);
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder)) return;
        for (int s : event.getRawSlots()) if (s < 45) { event.setCancelled(true); return; }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        // nothing to return: the grid only holds copies
    }
}
