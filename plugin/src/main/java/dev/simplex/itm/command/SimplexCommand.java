package dev.simplex.itm.command;

import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.maps.FrameStyle;
import dev.simplex.itm.maps.MapDisplay;
import dev.simplex.itm.util.Lang;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** {@code /simplex} (aliases {@code /sim}, {@code /itm}). Without arguments it opens the main menu. */
public final class SimplexCommand implements BasicCommand {

    private final SimplexPlugin plugin;

    public SimplexCommand(SimplexPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() { return plugin.lang(); }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Player player = source.getExecutor() instanceof Player p ? p : sender instanceof Player p ? p : null;
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (player == null) {
                help(sender);
                return;
            }
            plugin.menus().main(player);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "help" -> help(sender);
                case "place" -> place(sender, player, args);
                case "images" -> {
                    if (requirePlayer(sender, player) && perm(sender, "simplex.images")) plugin.menus().images(player);
                }
                case "download" -> {
                    if (!perm(sender, "simplex.images.create") || !requirePlayer(sender, player)) return;
                    if (args.length < 2) {
                        lang().send(sender, "usage.download");
                        return;
                    }
                    plugin.menus().downloadImage(player, args[1], args.length > 2 ? args[2] : "");
                }
                case "refresh" -> refresh(sender, args);
                case "display", "displays" -> display(sender, player, args);
                case "image" -> image(sender, args);
                case "reload" -> {
                    if (perm(sender, "simplex.admin")) reload(sender);
                }
                default -> help(sender);
            }
        } catch (NumberFormatException e) {
            lang().send(sender, "error.number", Lang.ph("value", e.getMessage()));
        }
    }

    private boolean perm(CommandSender s, String perm) {
        if (s.hasPermission(perm)) return true;
        lang().send(s, "error.no-permission");
        if (s instanceof Player p) plugin.sfx().play(p, "error");
        return false;
    }

    private boolean requirePlayer(CommandSender s, Player p) {
        if (p != null) return true;
        lang().send(s, "error.player-only");
        return false;
    }

    public void help(CommandSender sender) {
        sender.sendMessage(lang().get(sender, "help"));
    }

    // ------------------------------------------------------------------------------------------------------

    private void place(CommandSender sender, Player player, String[] args) {
        if (!perm(sender, "simplex.images.create") || !requirePlayer(sender, player)) return;
        if (args.length < 2) {
            lang().send(sender, "usage.place");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "cancel" -> plugin.placement().stop(player, true);
            case "confirm" -> {
                var s = plugin.placement().session(player);
                if (s != null) plugin.placement().confirm(player, s);
            }
            case "size" -> {
                if (args.length < 4) {
                    lang().send(sender, "usage.place");
                    return;
                }
                plugin.placement().resize(player, Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            }
            default -> {
                int w = args.length > 2 ? Integer.parseInt(args[2]) : 0;
                int h = args.length > 3 ? Integer.parseInt(args[3]) : 0;
                plugin.placement().start(player, args[1], w, h);
            }
        }
    }

    private void refresh(CommandSender sender, String[] args) {
        if (!perm(sender, "simplex.images.create")) return;
        if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
            int n = 0;
            for (String img : plugin.images().list()) n += plugin.displays().refreshImage(img, null);
            lang().send(sender, "image.refreshed-all", Lang.ph("count", n));
            return;
        }
        String name = plugin.images().canonicalName(args[1]);
        if (name == null) {
            lang().send(sender, "image.not-found", Lang.ph("image", args[1]));
            return;
        }
        int n = plugin.displays().refreshImage(name, ok -> lang().send(sender, ok ? "image.refreshed" : "image.refresh-failed", Lang.ph("image", name)));
        if (n == 0) lang().send(sender, "image.not-used", Lang.ph("image", name));
        if (sender instanceof Player p) plugin.sfx().play(p, "refresh");
    }

    private MapDisplay targetDisplay(CommandSender sender, Player player, String[] args, int idIndex) {
        MapDisplay d = null;
        if (args.length > idIndex) d = plugin.displays().get(args[idIndex]);
        else if (player != null) d = plugin.displays().target(player);
        if (d == null) lang().send(sender, "display.not-found");
        return d;
    }

    private void display(CommandSender sender, Player player, String[] args) {
        if (!perm(sender, "simplex.images")) return;
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list" -> {
                if (player != null) {
                    plugin.menus().displays(player);
                    return;
                }
                for (MapDisplay d : plugin.displays().all()) {
                    sender.sendMessage(d.id + " " + d.image + " " + d.tilesX + "x" + d.tilesY + " " + d.world + " " + d.x + "," + d.y + "," + d.z);
                }
            }
            case "create" -> {
                // /sim display create <image> <w> <h> <world> <x> <y> <z> <face> [style] – console & command blocks
                if (!perm(sender, "simplex.images.create")) return;
                if (args.length < 10) {
                    lang().send(sender, "usage.display-create");
                    return;
                }
                String image = plugin.images().canonicalName(args[2]);
                org.bukkit.World world = Bukkit.getWorld(args[5]);
                if (image == null || world == null) {
                    lang().send(sender, "image.not-found", Lang.ph("image", args[2]));
                    return;
                }
                org.bukkit.block.BlockFace face = org.bukkit.block.BlockFace.valueOf(args[9].toUpperCase(Locale.ROOT));
                org.bukkit.block.Block support = world.getBlockAt(Integer.parseInt(args[6]), Integer.parseInt(args[7]), Integer.parseInt(args[8]));
                var plan = dev.simplex.itm.maps.placement.PlacementPlan.compute(support, face,
                        face.getModY() != 0 ? org.bukkit.block.BlockFace.NORTH : face.getOppositeFace(), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                if (!plan.valid()) {
                    lang().send(sender, "placement.cannot-place",
                            Lang.ph("missing", plan.count(dev.simplex.itm.maps.placement.PlacementPlan.Problem.NO_SUPPORT)),
                            Lang.ph("blocked", plan.count(dev.simplex.itm.maps.placement.PlacementPlan.Problem.BLOCKED) + plan.count(dev.simplex.itm.maps.placement.PlacementPlan.Problem.OCCUPIED)));
                    return;
                }
                FrameStyle style = args.length > 10 ? FrameStyle.parse(args[10], FrameStyle.INVISIBLE) : FrameStyle.INVISIBLE;
                MapDisplay d = plugin.displays().create(player, plan, image, dev.simplex.itm.maps.FitMode.FIT, true, style);
                lang().send(sender, "placement.placed", Lang.ph("id", d.id), Lang.ph("image", d.image), Lang.ph("w", d.tilesX), Lang.ph("h", d.tilesY));
            }
            case "info" -> {
                MapDisplay d = targetDisplay(sender, player, args, 2);
                if (d != null) lang().send(sender, "display.info", Lang.ph("id", d.id), Lang.ph("image", d.image), Lang.ph("w", d.tilesX),
                        Lang.ph("h", d.tilesY), Lang.ph("style", d.style.name()), Lang.ph("fit", d.fit.name()), Lang.ph("status", plugin.displays().describe(player, d)));
            }
            case "remove" -> {
                if (!perm(sender, "simplex.images.create")) return;
                MapDisplay d = targetDisplay(sender, player, args, 2);
                if (d == null) return;
                plugin.displays().remove(d);
                lang().send(sender, "display.removed", Lang.ph("id", d.id));
                if (player != null) plugin.sfx().play(player, "remove");
            }
            case "style" -> {
                if (!perm(sender, "simplex.images.create")) return;
                if (args.length < 3) {
                    lang().send(sender, "usage.display");
                    return;
                }
                MapDisplay d = targetDisplay(sender, player, args, 3);
                if (d == null) return;
                plugin.displays().restyle(d, FrameStyle.parse(args[2], d.style));
                lang().send(sender, "display.restyled", Lang.ph("id", d.id));
            }
            case "move" -> {
                if (!perm(sender, "simplex.images.create") || !requirePlayer(sender, player)) return;
                MapDisplay d = targetDisplay(sender, player, args, 2);
                if (d != null) plugin.placement().startMove(player, d);
            }
            case "pause", "resume" -> {
                MapDisplay d = targetDisplay(sender, player, args, 2);
                if (d == null) return;
                d.paused = action.equals("pause");
                plugin.displays().save();
            }
            case "tp" -> {
                if (!requirePlayer(sender, player)) return;
                MapDisplay d = targetDisplay(sender, player, args, 2);
                if (d != null) player.teleportAsync(d.center().add(d.normal().multiply(2)));
            }
            default -> lang().send(sender, "usage.display");
        }
    }

    private void image(CommandSender sender, String[] args) {
        if (!perm(sender, "simplex.images.create")) return;
        if (args.length < 3 || !args[1].equalsIgnoreCase("delete")) {
            lang().send(sender, "usage.image");
            return;
        }
        deleteImage(sender, args[2]);
    }

    /** Removes all displays of the image and moves the file to {@code trash/}. */
    public void deleteImage(CommandSender sender, String name) {
        String file = plugin.images().canonicalName(name);
        if (file == null) {
            lang().send(sender, "image.not-found", Lang.ph("image", name));
            return;
        }
        int removed = plugin.displays().removeAll(file);
        boolean ok = plugin.images().delete(file);
        lang().send(sender, ok ? "image.deleted" : "image.delete-failed", Lang.ph("image", file), Lang.ph("count", removed));
        if (sender instanceof Player p) plugin.sfx().play(p, ok ? "remove" : "error");
    }

    public void reload(CommandSender sender) {
        plugin.reloadEverything();
        lang().send(sender, "reloaded");
        if (sender instanceof Player p) plugin.sfx().play(p, "success");
    }

    // ------------------------------------------------------------------------------------------------------

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender s = source.getSender();
        List<String> out = new ArrayList<>();
        if (args.length <= 1) {
            out.addAll(List.of("menu", "help", "images", "place", "refresh", "display"));
            if (s.hasPermission("simplex.images.create")) out.addAll(List.of("download", "image"));
            if (s.hasPermission("simplex.admin")) out.add("reload");
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "place" -> {
                    if (args.length == 2) {
                        out.addAll(plugin.images().list());
                        out.addAll(List.of("cancel", "confirm", "size"));
                    } else if (args.length <= 4) out.addAll(List.of("1", "2", "3", "4", "6", "8"));
                }
                case "refresh" -> {
                    if (args.length == 2) {
                        out.add("all");
                        out.addAll(plugin.images().list());
                    }
                }
                case "display", "displays" -> {
                    if (args.length == 2) out.addAll(List.of("list", "info", "create", "remove", "style", "move", "pause", "resume", "tp"));
                    else if (args.length == 3 && args[1].equalsIgnoreCase("create")) out.addAll(plugin.images().list());
                    else if (args.length == 3 && args[1].equalsIgnoreCase("style")) Stream.of(FrameStyle.values()).map(Enum::name).forEach(out::add);
                    else plugin.displays().all().forEach(d -> out.add(d.id));
                }
                case "image" -> {
                    if (args.length == 2) out.add("delete");
                    else if (args.length == 3) out.addAll(plugin.images().list());
                }
                default -> {
                }
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    @Override
    public String permission() {
        return "simplex.use";
    }
}
