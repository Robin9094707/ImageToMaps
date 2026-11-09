package dev.simplex.itm.util;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/** Configurable feedback sounds (menus, placement, success / error). */
public final class Sfx {

    public record Spec(String sound, float volume, float pitch) {}

    private static final Map<String, Spec> DEFAULTS = Map.ofEntries(
            Map.entry("menu-open", new Spec("minecraft:block.amethyst_block.chime", 0.6f, 1.4f)),
            Map.entry("click", new Spec("minecraft:ui.button.click", 0.4f, 1.2f)),
            Map.entry("page", new Spec("minecraft:item.book.page_turn", 0.8f, 1.1f)),
            Map.entry("back", new Spec("minecraft:ui.button.click", 0.4f, 0.8f)),
            Map.entry("success", new Spec("minecraft:entity.player.levelup", 0.5f, 1.6f)),
            Map.entry("error", new Spec("minecraft:entity.villager.no", 0.7f, 1.0f)),
            Map.entry("place-start", new Spec("minecraft:block.beacon.activate", 0.5f, 1.8f)),
            Map.entry("place-valid", new Spec("minecraft:block.note_block.pling", 0.35f, 1.7f)),
            Map.entry("place-invalid", new Spec("minecraft:block.note_block.bass", 0.45f, 0.7f)),
            Map.entry("place-confirm", new Spec("minecraft:entity.item_frame.place", 1.0f, 1.0f)),
            Map.entry("place-done", new Spec("minecraft:block.amethyst_block.resonate", 0.9f, 1.3f)),
            Map.entry("place-cancel", new Spec("minecraft:block.beacon.deactivate", 0.5f, 1.6f)),
            Map.entry("style", new Spec("minecraft:entity.item_frame.rotate_item", 0.8f, 1.2f)),
            Map.entry("resize", new Spec("minecraft:block.note_block.hat", 0.6f, 1.5f)),
            Map.entry("refresh", new Spec("minecraft:block.enchantment_table.use", 0.6f, 1.4f)),
            Map.entry("remove", new Spec("minecraft:entity.item_frame.break", 1.0f, 0.9f)),
            Map.entry("pack", new Spec("minecraft:block.respawn_anchor.charge", 0.5f, 1.5f)),
            Map.entry("sit", new Spec("minecraft:block.wool.place", 0.7f, 1.1f))
    );

    private final Map<String, Spec> specs = new HashMap<>(DEFAULTS);

    public void load(ConfigurationSection section) {
        specs.clear();
        specs.putAll(DEFAULTS);
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;
            String sound = s.getString("sound", "");
            if (sound.isBlank() || sound.equalsIgnoreCase("none")) {
                specs.put(key, null);
                continue;
            }
            specs.put(key, new Spec(sound, (float) s.getDouble("volume", 1), (float) s.getDouble("pitch", 1)));
        }
    }

    public void play(Player player, String key) {
        Spec spec = specs.get(key);
        if (spec == null || player == null) return;
        player.playSound(sound(spec.sound(), spec.volume(), spec.pitch()), Sound.Emitter.self());
    }

    public void playAt(Player player, Location loc, String key) {
        Spec spec = specs.get(key);
        if (spec == null || player == null) return;
        player.playSound(sound(spec.sound(), spec.volume(), spec.pitch()), loc.getX(), loc.getY(), loc.getZ());
    }

    public static Sound sound(String key, float volume, float pitch) {
        Key k;
        try {
            k = Key.key(key);
        } catch (Exception e) {
            k = Key.key("minecraft:ui.button.click");
        }
        return Sound.sound(k, Sound.Source.MASTER, volume, pitch);
    }
}
