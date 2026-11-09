package dev.simplex.itm.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Small fluent item builder for menu icons. */
public final class Items {

    private Items() {}

    public static ItemStack icon(Material material, Component name, List<Component> lore) {
        return icon(new ItemStack(material), name, lore, null);
    }

    public static ItemStack icon(ItemStack base, Component name, List<Component> lore, Consumer<ItemMeta> extra) {
        ItemStack item = base.clone();
        item.setAmount(1);
        item.editMeta(meta -> {
            meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            if (lore != null && !lore.isEmpty()) {
                List<Component> l = new ArrayList<>();
                for (Component c : lore) l.add(c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                meta.lore(l);
            }
            meta.addItemFlags(ItemFlag.values());
            if (extra != null) extra.accept(meta);
        });
        return item;
    }

    public static ItemStack glow(ItemStack item) {
        item.editMeta(m -> m.setEnchantmentGlintOverride(true));
        return item;
    }
}
