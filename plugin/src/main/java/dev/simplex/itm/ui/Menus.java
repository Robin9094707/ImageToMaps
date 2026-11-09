package dev.simplex.itm.ui;

import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.maps.ArtRenderer;
import dev.simplex.itm.maps.FitMode;
import dev.simplex.itm.maps.FrameStyle;
import dev.simplex.itm.maps.MapDisplay;
import dev.simplex.itm.util.Items;
import dev.simplex.itm.util.Lang;
import dev.simplex.itm.util.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** All menus of the plugin, written once for Java (chest / dialog) and Bedrock (forms). */
public final class Menus {

    private final SimplexPlugin plugin;

    public Menus(SimplexPlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() { return plugin.lang(); }

    private void open(Player p, Menu m) { plugin.ui().open(p, m); }

    private ChoiceMenu.Option option(Player p, String key, Material icon, String bedrockImage, Consumer<Player> action) {
        return new ChoiceMenu.Option(lang().get(p, key + ".name"), lang().lore(p, key + ".lore"),
                Items.icon(icon, lang().item(p, key + ".name"), lang().lore(p, key + ".lore")), bedrockImage, action);
    }

    private ChoiceMenu.Option option(Component label, List<Component> lore, ItemStack icon, String image, Consumer<Player> action) {
        return new ChoiceMenu.Option(label, lore, Items.icon(icon, label, lore, null), image, action);
    }

    private boolean admin(Player p) { return p.hasPermission("simplex.admin"); }

    // ------------------------------------------------------------------------------------------------------
    // main
    // ------------------------------------------------------------------------------------------------------

    public void main(Player p) {
        ChoiceMenu m = new ChoiceMenu(lang().get(p, "menu.main.title")).body(lang().get(p, "menu.main.body")).rows(3);
        if (p.hasPermission("simplex.images")) {
            m.option(option(p, "menu.main.images", Material.PAINTING, "textures/items/painting", this::images));
            m.option(option(p, "menu.main.displays", Material.ITEM_FRAME, "textures/items/item_frame", this::displays));
        }
        if (p.hasPermission("simplex.images.create")) {
            m.option(option(p, "menu.main.place-nearest", Material.SPYGLASS, "textures/items/spyglass", pl -> {
                MapDisplay d = plugin.displays().target(pl);
                if (d == null) {
                    plugin.sfx().play(pl, "error");
                    lang().send(pl, "display.not-found");
                } else {
                    display(pl, d);
                }
            }));
        }
        if (admin(p)) {
            m.option(option(p, "menu.main.settings", Material.COMPARATOR, "textures/items/comparator", this::settings));
            m.option(option(p, "menu.main.reload", Material.REPEATER, "textures/items/repeater", pl -> plugin.command().reload(pl)));
        }
        m.option(option(p, "menu.main.help", Material.KNOWLEDGE_BOOK, "textures/items/book_enchanted", pl -> {
            pl.closeInventory();
            plugin.command().help(pl);
        }));
        open(p, m);
    }

    // ------------------------------------------------------------------------------------------------------
    // images & displays
    // ------------------------------------------------------------------------------------------------------

    public void images(Player p) {
        ChoiceMenu m = new ChoiceMenu(lang().get(p, "menu.images.title"))
                .body(lang().get(p, "menu.images.body", Lang.ph("folder", "plugins/" + plugin.getName() + "/images/")))
                .back(this::main);
        for (String name : plugin.images().list()) {
            boolean gif = name.toLowerCase(Locale.ROOT).endsWith(".gif");
            String size = "";
            try {
                int[] s = ArtRenderer.size(plugin.images().resolve(name));
                size = s[0] + "×" + s[1];
            } catch (Exception ignored) {
            }
            long used = plugin.displays().all().stream().filter(d -> d.image.equalsIgnoreCase(name)).count();
            List<Component> lore = lang().lore(p, "menu.images.entry-lore", Lang.ph("size", size), Lang.ph("used", used),
                    Lang.ph("type", gif ? "GIF" : Texts.extension(name).toUpperCase(Locale.ROOT)));
            ItemStack icon = new ItemStack(gif ? Material.FILLED_MAP : Material.MAP);
            if (gif) Items.glow(icon);
            m.option(option(Component.text(name), lore, icon, gif ? "textures/items/map_filled" : "textures/items/map_empty", pl -> image(pl, name)));
        }
        if (admin(p) || p.hasPermission("simplex.images.create")) {
            m.tool(option(p, "menu.images.download", Material.HOPPER, "textures/blocks/hopper_top", this::download));
            m.tool(option(p, "menu.images.refresh-all", Material.CLOCK, "textures/items/clock_item", pl -> {
                int n = 0;
                for (String img : plugin.images().list()) n += plugin.displays().refreshImage(img, null);
                plugin.sfx().play(pl, "refresh");
                lang().send(pl, "image.refreshed-all", Lang.ph("count", n));
            }));
        }
        open(p, m);
    }

    public void image(Player p, String name) {
        ChoiceMenu m = new ChoiceMenu(Component.text(name)).back(this::images).rows(2);
        if (p.hasPermission("simplex.images.create")) {
            m.option(option(p, "menu.image.place", Material.ITEM_FRAME, "textures/items/item_frame", pl -> {
                pl.closeInventory();
                plugin.placement().start(pl, name, 0, 0);
            }));
            m.option(option(p, "menu.image.place-custom", Material.GLOW_ITEM_FRAME, "textures/items/glow_item_frame", pl -> placeCustom(pl, name)));
        }
        m.option(option(p, "menu.image.refresh", Material.CLOCK, "textures/items/clock_item", pl -> {
            int n = plugin.displays().refreshImage(name, ok -> {
                if (pl.isOnline()) lang().send(pl, ok ? "image.refreshed" : "image.refresh-failed", Lang.ph("image", name));
            });
            plugin.sfx().play(pl, "refresh");
            if (n == 0) lang().send(pl, "image.not-used", Lang.ph("image", name));
        }));
        if (p.hasPermission("simplex.images.create")) {
            long used = plugin.displays().all().stream().filter(d -> d.image.equalsIgnoreCase(name)).count();
            if (used > 0) {
                m.option(option(p, "menu.image.remove-displays", Material.SHEARS, "textures/items/shears", pl -> open(pl, new ConfirmMenu(
                        lang().get(pl, "menu.image.remove-displays.name"),
                        lang().get(pl, "menu.image.remove-displays-confirm", Lang.ph("image", name), Lang.ph("count", used)),
                        lang().get(pl, "menu.yes"), lang().get(pl, "menu.no"),
                        x -> {
                            int n = plugin.displays().removeAll(name);
                            plugin.sfx().play(x, "remove");
                            lang().send(x, "image.displays-removed", Lang.ph("image", name), Lang.ph("count", n));
                            image(x, name);
                        }, x -> image(x, name)))));
            }
            m.option(option(p, "menu.image.delete", Material.LAVA_BUCKET, "textures/items/bucket_lava", pl -> open(pl, new ConfirmMenu(
                    lang().get(pl, "menu.image.delete.name"),
                    lang().get(pl, "menu.image.delete-confirm", Lang.ph("image", name), Lang.ph("count", used)),
                    lang().get(pl, "menu.yes"), lang().get(pl, "menu.no"),
                    x -> {
                        plugin.command().deleteImage(x, name);
                        images(x);
                    }, x -> image(x, name)))));
        }
        open(p, m);
    }

    private void placeCustom(Player p, String name) {
        int[] size = {0, 0};
        try {
            size = ArtRenderer.size(plugin.images().resolve(name));
        } catch (Exception ignored) {
        }
        int suggestedW = Math.max(1, Math.min(8, Math.round(size[0] / 128f)));
        int max = plugin.getConfig().getInt("placement.max-size", 16);
        InputMenu m = new InputMenu(lang().get(p, "menu.place.title"))
                .body(lang().get(p, "menu.place.body", Lang.ph("image", name), Lang.ph("size", size[0] + "×" + size[1])))
                .field(new InputMenu.Slider("width", lang().get(p, "menu.place.width"), 1, max, 1, suggestedW))
                .field(new InputMenu.Slider("height", lang().get(p, "menu.place.height"), 0, max, 1, 0))
                .field(dropdownEnum(p, "fit", "menu.place.fit", FitMode.values(), FitMode.FIT, "fit-mode."))
                .field(dropdownEnum(p, "style", "menu.place.style", FrameStyle.values(), FrameStyle.INVISIBLE, "frame-style."))
                .field(new InputMenu.Toggle("dither", lang().get(p, "menu.place.dither"), true))
                .onSubmit((pl, v) -> {
                    int w = Math.round(v.number("width", 1));
                    int h = Math.round(v.number("height", 0));
                    if (plugin.placement().start(pl, name, w, h)) {
                        var s = plugin.placement().session(pl);
                        if (s != null) plugin.placement().configure(pl, FitMode.parse(v.text("fit"), FitMode.FIT),
                                FrameStyle.parse(v.text("style"), FrameStyle.INVISIBLE), v.bool("dither"));
                    }
                })
                .onCancel(pl -> image(pl, name));
        open(p, m);
    }

    private <E extends Enum<E>> InputMenu.Dropdown dropdownEnum(Player p, String key, String labelKey, E[] values, E selected, String langPrefix) {
        List<String> ids = new ArrayList<>();
        List<Component> labels = new ArrayList<>();
        int initial = 0;
        for (int i = 0; i < values.length; i++) {
            ids.add(values[i].name());
            labels.add(lang().get(p, langPrefix + values[i].name().toLowerCase(Locale.ROOT).replace('_', '-')));
            if (values[i] == selected) initial = i;
        }
        return new InputMenu.Dropdown(key, lang().get(p, labelKey), ids, labels, initial);
    }

    private void download(Player p) {
        InputMenu m = new InputMenu(lang().get(p, "menu.download.title"))
                .body(lang().get(p, "menu.download.body"))
                .field(new InputMenu.Text("url", lang().get(p, "menu.download.url"), "", "https://…", 512))
                .field(new InputMenu.Text("name", lang().get(p, "menu.download.name"), "", "my_image", 40))
                .onSubmit((pl, v) -> downloadImage(pl, v.text("url"), v.text("name")))
                .onCancel(this::images);
        open(p, m);
    }

    public void downloadImage(Player p, String url, String name) {
        if (url.isBlank()) return;
        if (name.isBlank()) name = Texts.toId(Texts.baseName(URI_PATH(url)));
        lang().send(p, "image.downloading", Lang.ph("url", url));
        String finalName = name;
        plugin.images().download(url, finalName).whenComplete((file, err) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (err != null) {
                plugin.sfx().play(p, "error");
                lang().send(p, "image.download-failed", Lang.ph("error", err.getCause() != null ? err.getCause().getMessage() : err.getMessage()));
                return;
            }
            plugin.sfx().play(p, "success");
            lang().send(p, "image.downloaded", Lang.ph("image", file));
            if (plugin.displays().usesImage(file)) plugin.displays().refreshImage(file, null);
        }));
    }

    private static String URI_PATH(String url) {
        try {
            String path = java.net.URI.create(url).getPath();
            return path == null || path.isBlank() ? "image" : path;
        } catch (Exception e) {
            return "image";
        }
    }

    public void displays(Player p) {
        ChoiceMenu m = new ChoiceMenu(lang().get(p, "menu.displays.title")).body(lang().get(p, "menu.displays.body")).back(this::main);
        List<MapDisplay> list = new ArrayList<>(plugin.displays().all());
        Location here = p.getLocation();
        list.sort(Comparator.comparingDouble(d -> d.world.equals(here.getWorld().getName()) ? d.center().distanceSquared(here) : Double.MAX_VALUE));
        for (MapDisplay d : list) {
            String dist = d.world.equals(here.getWorld().getName()) ? String.valueOf((int) Math.sqrt(d.center().distanceSquared(here))) : "∞";
            List<Component> lore = lang().lore(p, "menu.displays.entry-lore", Lang.ph("image", d.image), Lang.ph("w", d.tilesX), Lang.ph("h", d.tilesY),
                    Lang.ph("world", d.world), Lang.ph("distance", dist), Lang.ph("status", plugin.displays().describe(p, d)));
            ItemStack icon = new ItemStack(d.style.glow ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME);
            m.option(option(Component.text(d.id + " · " + d.image), lore, icon, "textures/items/item_frame", pl -> display(pl, d)));
        }
        if (p.hasPermission("simplex.images.create")) {
            m.tool(option(p, "menu.displays.remove-nearest", Material.SHEARS, "textures/items/shears", pl -> {
                MapDisplay d = plugin.displays().target(pl);
                if (d == null) {
                    plugin.sfx().play(pl, "error");
                    lang().send(pl, "display.not-found");
                    return;
                }
                display(pl, d);
            }));
        }
        open(p, m);
    }

    public void display(Player p, MapDisplay d) {
        ChoiceMenu m = new ChoiceMenu(Component.text(d.id + " · " + d.image)).body(Component.text(plugin.displays().describe(p, d))).back(this::displays).rows(3);
        m.option(option(p, "menu.display.teleport", Material.ENDER_PEARL, "textures/items/ender_pearl", pl -> {
            Location c = d.center();
            if (c.getWorld() == null) return;
            Location target = c.clone().add(d.normal().multiply(Math.max(2, Math.max(d.tilesX, d.tilesY) * 0.8)));
            target.setDirection(c.toVector().subtract(target.toVector()));
            pl.teleportAsync(target);
        }));
        m.option(option(p, "menu.display.refresh", Material.CLOCK, "textures/items/clock_item", pl -> {
            plugin.displays().render(d, ok -> lang().send(pl, ok ? "image.refreshed" : "image.refresh-failed", Lang.ph("image", d.image)));
            plugin.sfx().play(pl, "refresh");
        }));
        if (p.hasPermission("simplex.images.create")) {
            m.option(option(p, "menu.display.edit", Material.WRITABLE_BOOK, "textures/items/book_writable", pl -> editDisplay(pl, d)));
            m.option(option(p, "menu.display.move", Material.PISTON, "textures/blocks/piston_top_normal", pl -> {
                pl.closeInventory();
                plugin.placement().startMove(pl, d);
            }));
            if (d.art != null && d.art.animated()) {
                m.option(option(p, d.paused ? "menu.display.resume" : "menu.display.pause", d.paused ? Material.LIME_DYE : Material.GRAY_DYE, "textures/items/clock_item", pl -> {
                    d.paused = !d.paused;
                    plugin.displays().save();
                    display(pl, d);
                }));
            }
            m.option(option(p, "menu.display.remove", Material.TNT, "textures/blocks/tnt_side", pl -> open(pl, new ConfirmMenu(
                    lang().get(pl, "menu.display.remove.name"), lang().get(pl, "menu.display.remove-confirm", Lang.ph("id", d.id)),
                    lang().get(pl, "menu.yes"), lang().get(pl, "menu.no"),
                    x -> {
                        plugin.displays().remove(d);
                        plugin.sfx().play(x, "remove");
                        lang().send(x, "display.removed", Lang.ph("id", d.id));
                        displays(x);
                    }, x -> display(x, d)))));
        }
        open(p, m);
    }

    private void editDisplay(Player p, MapDisplay d) {
        List<String> images = plugin.images().list();
        List<Component> labels = new ArrayList<>();
        for (String i : images) labels.add(Component.text(i));
        InputMenu m = new InputMenu(lang().get(p, "menu.display.edit.name"))
                .field(new InputMenu.Dropdown("image", lang().get(p, "menu.display.image"), images, labels, Math.max(0, images.indexOf(d.image))))
                .field(dropdownEnum(p, "fit", "menu.place.fit", FitMode.values(), d.fit, "fit-mode."))
                .field(dropdownEnum(p, "style", "menu.place.style", FrameStyle.values(), d.style, "frame-style."))
                .field(new InputMenu.Toggle("dither", lang().get(p, "menu.place.dither"), d.dither))
                .onSubmit((pl, v) -> {
                    String img = plugin.images().canonicalName(v.text("image"));
                    FrameStyle style = FrameStyle.parse(v.text("style"), d.style);
                    if (style != d.style) plugin.displays().restyle(d, style);
                    plugin.displays().changeImage(d, img == null ? d.image : img, FitMode.parse(v.text("fit"), d.fit), v.bool("dither"),
                            ok -> lang().send(pl, ok ? "image.refreshed" : "image.refresh-failed", Lang.ph("image", d.image)));
                    plugin.sfx().play(pl, "success");
                })
                .onCancel(pl -> display(pl, d));
        open(p, m);
    }

    // ------------------------------------------------------------------------------------------------------
    // settings
    // ------------------------------------------------------------------------------------------------------

    public void settings(Player p) {
        var c = plugin.getConfig();
        InputMenu m = new InputMenu(lang().get(p, "menu.settings.title")).body(lang().get(p, "menu.settings.body"))
                .field(new InputMenu.Toggle("auto_refresh", lang().get(p, "menu.settings.auto-refresh"), c.getBoolean("images.auto-refresh", true)))
                .field(new InputMenu.Toggle("sneak_edit", lang().get(p, "menu.settings.sneak-edit"), c.getBoolean("displays.sneak-hit-opens-menu", true)))
                .field(dropdownEnum(p, "style", "menu.settings.default-style", FrameStyle.values(),
                        FrameStyle.parse(c.getString("placement.default-style", "INVISIBLE"), FrameStyle.INVISIBLE), "frame-style."))
                .field(dropdownEnum(p, "fit", "menu.settings.default-fit", FitMode.values(),
                        FitMode.parse(c.getString("placement.default-fit", "FIT"), FitMode.FIT), "fit-mode."))
                .field(new InputMenu.Toggle("dither", lang().get(p, "menu.settings.default-dither"), c.getBoolean("placement.default-dither", true)))
                .field(new InputMenu.Slider("max_size", lang().get(p, "menu.settings.max-size"), 2, 32, 1, c.getInt("placement.max-size", 16)))
                .field(new InputMenu.Slider("java_fps", lang().get(p, "menu.settings.java-fps"), 1, 20, 1, c.getInt("animation.java-max-fps", 20)))
                .field(new InputMenu.Slider("bedrock_fps", lang().get(p, "menu.settings.bedrock-fps"), 1, 20, 1, c.getInt("animation.bedrock-max-fps", 10)))
                .field(new InputMenu.Slider("view", lang().get(p, "menu.settings.view-distance"), 16, 160, 8, (float) c.getDouble("animation.view-distance", 64)))
                .onSubmit((pl, v) -> {
                    c.set("images.auto-refresh", v.bool("auto_refresh"));
                    c.set("displays.sneak-hit-opens-menu", v.bool("sneak_edit"));
                    c.set("placement.default-style", v.text("style"));
                    c.set("placement.default-fit", v.text("fit"));
                    c.set("placement.default-dither", v.bool("dither"));
                    c.set("placement.max-size", Math.round(v.number("max_size", 16)));
                    c.set("animation.java-max-fps", Math.round(v.number("java_fps", 20)));
                    c.set("animation.bedrock-max-fps", Math.round(v.number("bedrock_fps", 10)));
                    c.set("animation.view-distance", Math.round(v.number("view", 64)));
                    plugin.saveConfig();
                    plugin.sfx().play(pl, "success");
                    lang().send(pl, "settings.saved");
                })
                .onCancel(this::main);
        open(p, m);
    }

    public Path imagePath(String name) { return plugin.images().resolve(name); }
}
