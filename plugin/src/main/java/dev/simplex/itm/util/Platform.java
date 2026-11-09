package dev.simplex.itm.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Detects Bedrock players (Floodgate preferred, Geyser API as fallback) and sends native Bedrock forms. */
public final class Platform {

    private final boolean floodgate;
    private final boolean geyser;

    public Platform() {
        this.floodgate = present("org.geysermc.floodgate.api.FloodgateApi") && Bukkit.getPluginManager().getPlugin("floodgate") != null;
        this.geyser = present("org.geysermc.geyser.api.GeyserApi");
    }

    private static boolean present(String cls) {
        try {
            Class.forName(cls);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean floodgate() { return floodgate; }

    public boolean geyser() { return geyser; }

    public boolean formsAvailable() { return floodgate || geyser; }

    public boolean isBedrock(Player player) {
        return player != null && isBedrock(player.getUniqueId());
    }

    public boolean isBedrock(UUID uuid) {
        try {
            if (floodgate && FloodgateHook.isBedrock(uuid)) return true;
            if (geyser && GeyserHook.isBedrock(uuid)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** @param form a Cumulus {@code FormBuilder} (typed as Object so this class loads without Cumulus). */
    public boolean sendForm(Player player, Object form) {
        try {
            if (floodgate && FloodgateHook.send(player.getUniqueId(), form)) return true;
            if (geyser) return GeyserHook.send(player.getUniqueId(), form);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static final class FloodgateHook {
        static boolean isBedrock(UUID uuid) {
            return org.geysermc.floodgate.api.FloodgateApi.getInstance().isFloodgatePlayer(uuid);
        }

        static boolean send(UUID uuid, Object form) {
            return org.geysermc.floodgate.api.FloodgateApi.getInstance().sendForm(uuid, (org.geysermc.cumulus.form.util.FormBuilder<?, ?, ?>) form);
        }
    }

    private static final class GeyserHook {
        static boolean isBedrock(UUID uuid) {
            var api = org.geysermc.geyser.api.GeyserApi.api();
            return api != null && api.isBedrockPlayer(uuid);
        }

        static boolean send(UUID uuid, Object form) {
            var api = org.geysermc.geyser.api.GeyserApi.api();
            return api != null && api.sendForm(uuid, (org.geysermc.cumulus.form.util.FormBuilder<?, ?, ?>) form);
        }
    }
}
