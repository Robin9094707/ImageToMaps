package dev.simplex.itm.ui;

import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.util.Lang;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Renders {@link Menu}s for Java (chest GUI / dialogs) and Bedrock (native forms). */
public final class UiService implements Listener {

    private static final int PAGE_SIZE = 45;
    private final SimplexPlugin plugin;

    public UiService(SimplexPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, Menu menu) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            boolean bedrock = plugin.settings().bedrockForms() && plugin.platform().isBedrock(player) && plugin.platform().formsAvailable();
            try {
                if (bedrock && BedrockForms.open(plugin, player, menu)) {
                    plugin.sfx().play(player, "menu-open");
                    return;
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Bedrock form failed, falling back to Java UI: " + t.getMessage());
            }
            switch (menu) {
                case ChoiceMenu c -> openChest(player, c, 0);
                case InputMenu i -> openDialog(player, i);
                case ConfirmMenu c -> openConfirmDialog(player, c);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------------
    // Java: chest GUI
    // ------------------------------------------------------------------------------------------------------

    static final class ChestHolder implements InventoryHolder {
        final ChoiceMenu menu;
        final int page;
        final ChoiceMenu.Option[] slots = new ChoiceMenu.Option[54];
        Inventory inventory;

        ChestHolder(ChoiceMenu menu, int page) {
            this.menu = menu;
            this.page = page;
        }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private void openChest(Player player, ChoiceMenu menu, int page) {
        List<ChoiceMenu.Option> opts = menu.options();
        int pages = Math.max(1, (opts.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        int contentRows = Math.max(1, Math.min(5, (Math.min(PAGE_SIZE, opts.size() - page * PAGE_SIZE) + 8) / 9));
        boolean paged = pages > 1;
        int rows = paged ? 6 : Math.min(6, Math.max(menu.rows(), contentRows + 1));
        if (paged) contentRows = 5;
        ChestHolder holder = new ChestHolder(menu, page);
        Component title = pages > 1 ? menu.title().append(Component.text(" (" + (page + 1) + "/" + pages + ")", NamedTextColor.DARK_GRAY)) : menu.title();
        Inventory inv = Bukkit.createInventory(holder, rows * 9, title);
        holder.inventory = inv;

        int start = page * PAGE_SIZE;
        int maxContent = (rows - 1) * 9;
        for (int i = 0; i < maxContent && start + i < opts.size(); i++) {
            ChoiceMenu.Option o = opts.get(start + i);
            inv.setItem(i, o.icon());
            holder.slots[i] = o;
        }
        int bottom = (rows - 1) * 9;
        ItemStack filler = dev.simplex.itm.util.Items.icon(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), null);
        for (int i = bottom; i < rows * 9; i++) inv.setItem(i, filler);

        Lang lang = plugin.lang();
        if (menu.back() != null) {
            set(holder, bottom, new ChoiceMenu.Option(lang.item(player, "menu.back"), List.of(),
                    dev.simplex.itm.util.Items.icon(Material.ARROW, lang.item(player, "menu.back"), null), null, p -> {
                plugin.sfx().play(p, "back");
                menu.back().accept(p);
            }));
        }
        if (page > 0) {
            int target = page - 1;
            set(holder, bottom + 3, new ChoiceMenu.Option(Component.empty(), List.of(),
                    dev.simplex.itm.util.Items.icon(Material.SPECTRAL_ARROW, lang.item(player, "menu.previous"), null), null, p -> {
                plugin.sfx().play(p, "page");
                openChest(p, menu, target);
            }));
        }
        if (!(menu.body().equals(Component.empty()))) {
            List<Component> lore = new ArrayList<>();
            for (String line : Lang.plain(menu.body()).split("\n")) lore.add(Lang.parse("<gray>" + escape(line)));
            inv.setItem(bottom + 4, dev.simplex.itm.util.Items.icon(Material.BOOK, menu.title(), lore));
        }
        if (page < pages - 1) {
            int target = page + 1;
            set(holder, bottom + 5, new ChoiceMenu.Option(Component.empty(), List.of(),
                    dev.simplex.itm.util.Items.icon(Material.SPECTRAL_ARROW, lang.item(player, "menu.next"), null), null, p -> {
                plugin.sfx().play(p, "page");
                openChest(p, menu, target);
            }));
        }
        // toolbar: slots 1,2 and 6,7 of the bottom row
        int[] toolSlots = {bottom + 1, bottom + 2, bottom + 6, bottom + 7};
        for (int i = 0; i < menu.toolbar().size() && i < toolSlots.length; i++) set(holder, toolSlots[i], menu.toolbar().get(i));
        set(holder, bottom + 8, new ChoiceMenu.Option(Component.empty(), List.of(),
                dev.simplex.itm.util.Items.icon(Material.BARRIER, lang.item(player, "menu.close"), null), null, p -> {
            plugin.sfx().play(p, "back");
            p.closeInventory();
        }));

        player.openInventory(inv);
        plugin.sfx().play(player, page == 0 ? "menu-open" : "page");
    }

    private static String escape(String s) {
        return s.replace("<", "\\<");
    }

    private void set(ChestHolder holder, int slot, ChoiceMenu.Option option) {
        holder.inventory.setItem(slot, option.icon());
        holder.slots[slot] = option;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof ChestHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= holder.slots.length) return;
        ChoiceMenu.Option option = holder.slots[slot];
        if (option == null || option.action() == null) return;
        plugin.sfx().play(player, "click");
        Bukkit.getScheduler().runTask(plugin, () -> option.action().accept(player));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof ChestHolder) event.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------------------
    // Java: Paper dialogs (native input screens since Minecraft 1.21.6)
    // ------------------------------------------------------------------------------------------------------

    private ClickCallback.Options callbackOptions() {
        return ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(15)).build();
    }

    private void openDialog(Player player, InputMenu menu) {
        Lang lang = plugin.lang();
        List<DialogInput> inputs = new ArrayList<>();
        for (InputMenu.Field field : menu.fields()) {
            switch (field) {
                case InputMenu.Text t -> inputs.add(DialogInput.text(t.key(), t.label())
                        .initial(t.initial() == null ? "" : t.initial())
                        .maxLength(Math.max(1, t.maxLength()))
                        .width(300)
                        .build());
                case InputMenu.Toggle t -> inputs.add(DialogInput.bool(t.key(), t.label()).initial(t.initial()).build());
                case InputMenu.Dropdown d -> {
                    List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                    for (int i = 0; i < d.ids().size(); i++) {
                        entries.add(SingleOptionDialogInput.OptionEntry.create(d.ids().get(i), d.labels().get(i), i == d.initial()));
                    }
                    if (entries.isEmpty()) entries.add(SingleOptionDialogInput.OptionEntry.create("none", Component.text("-"), true));
                    inputs.add(DialogInput.singleOption(d.key(), d.label(), entries).width(300).build());
                }
                case InputMenu.Slider s -> inputs.add(DialogInput.numberRange(s.key(), s.label(), s.min(), s.max())
                        .step(s.step()).initial(s.initial()).width(300).build());
            }
        }
        Component submit = menu.submitLabel() != null ? menu.submitLabel() : lang.get(player, "menu.submit");
        Component cancel = menu.cancelLabel() != null ? menu.cancelLabel() : lang.get(player, "menu.cancel");

        ActionButton ok = ActionButton.create(submit, null, 150, DialogAction.customClick((view, audience) -> {
            InputMenu.Values values = new InputMenu.Values();
            for (InputMenu.Field field : menu.fields()) {
                switch (field) {
                    case InputMenu.Text t -> values.put(t.key(), view.getText(t.key()));
                    case InputMenu.Toggle t -> values.put(t.key(), view.getBoolean(t.key()));
                    case InputMenu.Dropdown d -> values.put(d.key(), view.getText(d.key()));
                    case InputMenu.Slider s -> values.put(s.key(), view.getFloat(s.key()));
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.sfx().play(player, "click");
                menu.submit().accept(player, values);
            });
        }, callbackOptions()));
        ActionButton no = ActionButton.create(cancel, null, 150, DialogAction.customClick((view, audience) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.sfx().play(player, "back");
                    menu.cancel().accept(player);
                }), callbackOptions()));

        List<DialogBody> body = new ArrayList<>();
        if (!menu.body().equals(Component.empty())) body.add(DialogBody.plainMessage(menu.body(), 300));
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(menu.title()).canCloseWithEscape(true).body(body).inputs(inputs).build())
                .type(DialogType.confirmation(ok, no)));
        player.closeInventory();
        player.showDialog(dialog);
        plugin.sfx().play(player, "menu-open");
    }

    private void openConfirmDialog(Player player, ConfirmMenu menu) {
        ActionButton yes = ActionButton.create(menu.yes(), null, 150, DialogAction.customClick((v, a) ->
                Bukkit.getScheduler().runTask(plugin, () -> menu.onYes().accept(player)), callbackOptions()));
        ActionButton no = ActionButton.create(menu.no(), null, 150, DialogAction.customClick((v, a) ->
                Bukkit.getScheduler().runTask(plugin, () -> menu.onNo().accept(player)), callbackOptions()));
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(menu.title()).canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(menu.body(), 300))).build())
                .type(DialogType.confirmation(yes, no)));
        player.closeInventory();
        player.showDialog(dialog);
        plugin.sfx().play(player, "menu-open");
    }

}
