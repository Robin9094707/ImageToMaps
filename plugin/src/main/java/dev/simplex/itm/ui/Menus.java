package dev.simplex.itm.ui;

import dev.simplex.common.SlotLayout;
import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.content.BlockDef;
import dev.simplex.itm.content.Localized;
import dev.simplex.itm.content.ModelDef;
import dev.simplex.itm.content.SoundPreset;
import dev.simplex.itm.content.ToolType;
import dev.simplex.itm.content.models.ModelManager;
import dev.simplex.itm.maps.ArtRenderer;
import dev.simplex.itm.maps.FitMode;
import dev.simplex.itm.maps.FrameStyle;
import dev.simplex.itm.maps.MapDisplay;
import dev.simplex.itm.util.Items;
import dev.simplex.itm.util.Lang;
import dev.simplex.itm.util.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
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
        if (plugin.content().blocksEnabled()) m.option(option(p, "menu.main.blocks", Material.BRICKS, "textures/blocks/brick", this::blocks));
        if (plugin.content().modelsEnabled()) m.option(option(p, "menu.main.models", Material.ARMOR_STAND, "textures/items/armor_stand", this::models));
        if (admin(p)) {
            m.option(option(p, "menu.main.pack", Material.ENDER_CHEST, "textures/blocks/ender_chest_front", this::pack));
            m.option(option(p, "menu.main.settings", Material.COMPARATOR, "textures/items/comparator", this::settings));
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
    // blocks
    // ------------------------------------------------------------------------------------------------------

    public void blocks(Player p) {
        ChoiceMenu m = new ChoiceMenu(lang().get(p, "menu.blocks.title")).body(lang().get(p, "menu.blocks.body")).back(this::main);
        String code = lang().code(p);
        for (BlockDef b : plugin.content().blocks()) {
            List<Component> lore = new ArrayList<>();
            if (!b.description.get(code).isBlank()) lore.add(Component.text(b.description.get(code), net.kyori.adventure.text.format.NamedTextColor.GRAY));
            lore.addAll(lang().lore(p, "menu.blocks.entry-lore", Lang.ph("id", b.id),
                    Lang.comp("type", lang().get(p, "block-type." + b.kind.name().toLowerCase(Locale.ROOT))), Lang.ph("hardness", b.hardness)));
            m.option(option(Component.text(b.name.get(code)), lore, plugin.content().item(b, 1), "textures/simplex/items/i" + b.itemSlot, pl -> block(pl, b)));
        }
        if (p.hasPermission("simplex.blocks.manage")) {
            m.tool(option(p, "menu.blocks.create", Material.NETHER_STAR, "textures/items/nether_star", pl -> editBlock(pl, null)));
        }
        open(p, m);
    }

    public void block(Player p, BlockDef b) {
        String code = lang().code(p);
        ChoiceMenu m = new ChoiceMenu(Component.text(b.name.get(code))).body(Component.text(b.description.get(code))).back(this::blocks).rows(3);
        if (p.hasPermission("simplex.blocks.give") || p.getGameMode() == GameMode.CREATIVE) {
            for (int amount : new int[]{1, 16, 64}) {
                ItemStack icon = plugin.content().item(b, amount);
                m.option(option(lang().get(p, "menu.block.give", Lang.ph("amount", amount)), List.of(), icon, "textures/simplex/items/i" + b.itemSlot, pl -> {
                    pl.getInventory().addItem(plugin.content().item(b, amount)).values().forEach(rest -> pl.getWorld().dropItem(pl.getLocation(), rest));
                    plugin.sfx().play(pl, "success");
                }));
            }
        }
        if (p.hasPermission("simplex.blocks.manage")) {
            m.option(option(p, "menu.block.edit", Material.WRITABLE_BOOK, "textures/items/book_writable", pl -> editBlock(pl, b)));
            m.option(option(p, "menu.block.recipe", Material.CRAFTING_TABLE, "textures/blocks/crafting_table_front", pl ->
                    plugin.recipeEditor().open(pl, plugin.content().item(b, 1), b.recipe, (x, recipe) -> {
                        b.recipe = recipe;
                        saveBlock(x, b, false);
                        block(x, b);
                    })));
            m.option(option(p, "menu.block.delete", Material.LAVA_BUCKET, "textures/items/bucket_lava", pl -> deleteBlock(pl, b)));
        }
        open(p, m);
    }

    private void deleteBlock(Player p, BlockDef b) {
        List<String> ids = List.of("DIRT", "AIR", "STONE", "COBBLESTONE", "OAK_PLANKS", "GLASS");
        List<Component> labels = new ArrayList<>();
        for (String id : ids) labels.add(Component.translatable(Material.valueOf(id).translationKey()));
        InputMenu m = new InputMenu(lang().get(p, "menu.block.delete.name"))
                .body(lang().get(p, "menu.block.delete-body", Lang.ph("id", b.id)))
                .field(new InputMenu.Dropdown("replacement", lang().get(p, "menu.block.replacement"), ids, labels, 0))
                .field(new InputMenu.Toggle("confirm", lang().get(p, "menu.block.delete-confirm"), false))
                .onSubmit((pl, v) -> {
                    if (!v.bool("confirm")) {
                        block(pl, b);
                        return;
                    }
                    plugin.command().deleteBlock(pl, b.id, Material.matchMaterial(v.text("replacement")));
                })
                .onCancel(pl -> block(pl, b));
        open(p, m);
    }

    /** Create (def == null) or edit a block. */
    public void editBlock(Player p, BlockDef existing) {
        List<String> textures = plugin.content().textureFiles();
        if (textures.isEmpty()) {
            lang().send(p, "block.no-textures", Lang.ph("folder", "plugins/" + plugin.getName() + "/textures/"));
            plugin.sfx().play(p, "error");
            return;
        }
        BlockDef d = existing;
        List<String> texIds = new ArrayList<>(textures);
        List<Component> texLabels = new ArrayList<>();
        for (String t : textures) texLabels.add(Component.text(t));
        List<String> optIds = new ArrayList<>();
        optIds.add("");
        optIds.addAll(textures);
        List<Component> optLabels = new ArrayList<>();
        optLabels.add(lang().get(p, "menu.block-editor.same"));
        optLabels.addAll(texLabels);

        List<String> modelIds = new ArrayList<>();
        modelIds.add("");
        List<Component> modelLabels = new ArrayList<>();
        modelLabels.add(lang().get(p, "menu.block-editor.no-model"));
        for (ModelDef md : plugin.content().models()) {
            modelIds.add(md.id);
            modelLabels.add(Component.text(md.id));
        }
        List<String> soundIds = new ArrayList<>();
        soundIds.add("");
        List<Component> soundLabels = new ArrayList<>();
        soundLabels.add(lang().get(p, "menu.block-editor.preset-sound"));
        for (String s : plugin.content().soundFiles()) {
            soundIds.add(s);
            soundLabels.add(Component.text(s + ".ogg"));
        }

        InputMenu m = new InputMenu(lang().get(p, d == null ? "menu.block-editor.create" : "menu.block-editor.edit"))
                .body(lang().get(p, "menu.block-editor.body"));
        if (d == null) m.field(new InputMenu.Text("id", lang().get(p, "menu.block-editor.id"), "", "marble_tiles", 40));
        m.field(new InputMenu.Text("name_en", lang().get(p, "menu.block-editor.name-en"), d == null ? "" : d.name.en(), "Marble Tiles", 64))
                .field(new InputMenu.Text("name_de", lang().get(p, "menu.block-editor.name-de"), d == null ? "" : d.name.de(), "Marmorfliesen", 64))
                .field(new InputMenu.Text("desc_en", lang().get(p, "menu.block-editor.desc-en"), d == null ? "" : d.description.en(), "", 128))
                .field(new InputMenu.Text("desc_de", lang().get(p, "menu.block-editor.desc-de"), d == null ? "" : d.description.de(), "", 128))
                .field(new InputMenu.Dropdown("type", lang().get(p, "menu.block-editor.type"), List.of("SOLID", "TRANSPARENT"),
                        List.of(lang().get(p, "block-type.solid"), lang().get(p, "block-type.transparent")), d != null && d.kind == SlotLayout.Kind.TRANSPARENT ? 1 : 0))
                .field(new InputMenu.Dropdown("texture", lang().get(p, "menu.block-editor.texture"), texIds, texLabels, d == null ? 0 : Math.max(0, texIds.indexOf(d.texture("north")))))
                .field(new InputMenu.Dropdown("top", lang().get(p, "menu.block-editor.top"), optIds, optLabels, d == null ? 0 : Math.max(0, optIds.indexOf(nn(d.textures.get("top"))))))
                .field(new InputMenu.Dropdown("bottom", lang().get(p, "menu.block-editor.bottom"), optIds, optLabels, d == null ? 0 : Math.max(0, optIds.indexOf(nn(d.textures.get("bottom"))))))
                .field(new InputMenu.Dropdown("model", lang().get(p, "menu.block-editor.model"), modelIds, modelLabels, d == null ? 0 : Math.max(0, modelIds.indexOf(nn(d.model)))))
                .field(new InputMenu.Slider("hardness", lang().get(p, "menu.block-editor.hardness"), 0, 50, 0.5f, d == null ? 1.5f : d.hardness))
                .field(dropdownEnum(p, "tool", "menu.block-editor.tool", ToolType.values(), d == null ? ToolType.PICKAXE : d.tool, "tool."))
                .field(new InputMenu.Toggle("requires_tool", lang().get(p, "menu.block-editor.requires-tool"), d != null && d.requiresTool))
                .field(dropdownEnum(p, "sound", "menu.block-editor.sound", SoundPreset.values(), d == null ? SoundPreset.STONE : d.sound, "sound-preset."))
                .field(new InputMenu.Dropdown("place_sound", lang().get(p, "menu.block-editor.place-sound"), soundIds, soundLabels, d == null ? 0 : Math.max(0, soundIds.indexOf(nn(d.customSounds.get("place"))))))
                .field(new InputMenu.Dropdown("break_sound", lang().get(p, "menu.block-editor.break-sound"), soundIds, soundLabels, d == null ? 0 : Math.max(0, soundIds.indexOf(nn(d.customSounds.get("break"))))))
                .field(new InputMenu.Toggle("drop_self", lang().get(p, "menu.block-editor.drop-self"), d == null || "self".equals(d.drop)))
                .onSubmit((pl, v) -> {
                    BlockDef def = d;
                    if (def == null) {
                        String id = Texts.toId(v.text("id").isBlank() ? v.text("name_en") : v.text("id"));
                        if (!Texts.validId(id) || plugin.content().block(id) != null) {
                            plugin.sfx().play(pl, "error");
                            lang().send(pl, "block.invalid-id", Lang.ph("id", id));
                            return;
                        }
                        def = new BlockDef();
                        def.id = id;
                        def.file = new File(plugin.content().blocksFolder(), id + ".yml");
                    }
                    String en = v.text("name_en").isBlank() ? def.id : v.text("name_en");
                    def.name = new Localized(en, v.text("name_de").isBlank() ? en : v.text("name_de"));
                    def.description = new Localized(v.text("desc_en"), v.text("desc_de").isBlank() ? v.text("desc_en") : v.text("desc_de"));
                    def.kind = "TRANSPARENT".equals(v.text("type")) ? SlotLayout.Kind.TRANSPARENT : SlotLayout.Kind.SOLID;
                    def.textures.clear();
                    def.textures.put("all", v.text("texture"));
                    if (!v.text("top").isBlank()) def.textures.put("top", v.text("top"));
                    if (!v.text("bottom").isBlank()) def.textures.put("bottom", v.text("bottom"));
                    def.model = def.kind == SlotLayout.Kind.TRANSPARENT ? v.text("model") : "";
                    def.hardness = v.number("hardness", 1.5f);
                    def.tool = ToolType.parse(v.text("tool"));
                    def.requiresTool = v.bool("requires_tool");
                    def.sound = SoundPreset.parse(v.text("sound"));
                    def.customSounds.clear();
                    if (!v.text("place_sound").isBlank()) def.customSounds.put("place", v.text("place_sound"));
                    if (!v.text("break_sound").isBlank()) def.customSounds.put("break", v.text("break_sound"));
                    def.drop = v.bool("drop_self") ? "self" : "none";
                    saveBlock(pl, def, d == null);
                })
                .onCancel(pl -> {
                    if (d == null) blocks(pl);
                    else block(pl, d);
                });
        open(p, m);
    }

    private static String nn(String s) { return s == null ? "" : s; }

    private void saveBlock(Player p, BlockDef def, boolean created) {
        try {
            if (!plugin.content().saveBlock(def)) {
                plugin.sfx().play(p, "error");
                lang().send(p, "block.no-slot");
                return;
            }
        } catch (IOException e) {
            plugin.sfx().play(p, "error");
            lang().send(p, "error.generic", Lang.ph("error", e.getMessage()));
            return;
        }
        plugin.packs().requestRebuild();
        plugin.sfx().play(p, "success");
        lang().send(p, created ? "block.created" : "block.saved", Lang.ph("id", def.id));
        if (created) {
            p.getInventory().addItem(plugin.content().item(def, 16));
            if (def.kind == SlotLayout.Kind.SOLID || def.kind == SlotLayout.Kind.TRANSPARENT) lang().send(p, "block.bedrock-reconnect");
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // models
    // ------------------------------------------------------------------------------------------------------

    public void models(Player p) {
        ChoiceMenu m = new ChoiceMenu(lang().get(p, "menu.models.title")).body(lang().get(p, "menu.models.body",
                Lang.ph("folder", "plugins/" + plugin.getName() + "/models/"))).back(this::main);
        String code = lang().code(p);
        for (ModelDef d : plugin.content().models()) {
            List<Component> lore = new ArrayList<>();
            if (!d.description.get(code).isBlank()) lore.add(Component.text(d.description.get(code), net.kyori.adventure.text.format.NamedTextColor.GRAY));
            long placed = plugin.models().all().stream().filter(x -> x.model.equals(d.id)).count();
            lore.addAll(lang().lore(p, "menu.models.entry-lore", Lang.ph("id", d.id), Lang.ph("placed", placed), Lang.ph("scale", d.scale)));
            m.option(option(Component.text(d.name.get(code)), lore, plugin.content().item(d, 1), "textures/simplex/items/i" + d.itemSlot, pl -> model(pl, d)));
        }
        if (p.hasPermission("simplex.models.manage")) {
            m.tool(option(p, "menu.models.import", Material.NETHER_STAR, "textures/items/nether_star", this::importModel));
            m.tool(option(p, "menu.models.remove-nearest", Material.SHEARS, "textures/items/shears", pl -> {
                ModelManager.Placed near = plugin.models().nearest(pl, 6);
                if (near == null) {
                    plugin.sfx().play(pl, "error");
                    lang().send(pl, "model.none-nearby");
                    return;
                }
                plugin.models().remove(near, false);
                plugin.sfx().play(pl, "remove");
                lang().send(pl, "model.removed", Lang.ph("model", near.model));
            }));
        }
        open(p, m);
    }

    public void model(Player p, ModelDef d) {
        String code = lang().code(p);
        ChoiceMenu m = new ChoiceMenu(Component.text(d.name.get(code))).body(Component.text(d.description.get(code))).back(this::models).rows(3);
        if (p.hasPermission("simplex.models.give") || p.getGameMode() == GameMode.CREATIVE) {
            for (int amount : new int[]{1, 8}) {
                m.option(option(lang().get(p, "menu.block.give", Lang.ph("amount", amount)), List.of(), plugin.content().item(d, amount), "textures/simplex/items/i" + d.itemSlot, pl -> {
                    pl.getInventory().addItem(plugin.content().item(d, amount));
                    plugin.sfx().play(pl, "success");
                }));
            }
        }
        if (p.hasPermission("simplex.models.manage")) {
            m.option(option(p, "menu.model.edit", Material.WRITABLE_BOOK, "textures/items/book_writable", pl -> editModel(pl, d, false)));
            m.option(option(p, "menu.block.recipe", Material.CRAFTING_TABLE, "textures/blocks/crafting_table_front", pl ->
                    plugin.recipeEditor().open(pl, plugin.content().item(d, 1), d.recipe, (x, recipe) -> {
                        d.recipe = recipe;
                        saveModel(x, d, false);
                        model(x, d);
                    })));
            m.option(option(p, "menu.model.delete", Material.LAVA_BUCKET, "textures/items/bucket_lava", pl -> open(pl, new ConfirmMenu(
                    lang().get(pl, "menu.model.delete.name"), lang().get(pl, "menu.model.delete-confirm", Lang.ph("id", d.id)),
                    lang().get(pl, "menu.yes"), lang().get(pl, "menu.no"),
                    x -> {
                        plugin.models().removeAll(d.id);
                        plugin.content().deleteModel(d.id);
                        plugin.packs().requestRebuild();
                        plugin.sfx().play(x, "remove");
                        lang().send(x, "model.deleted", Lang.ph("id", d.id));
                        models(x);
                    }, x -> model(x, d)))));
        }
        open(p, m);
    }

    private void importModel(Player p) {
        File[] dirs = plugin.content().modelsFolder().listFiles(f -> f.isDirectory() && !new File(f, "model.yml").isFile());
        List<String> ids = new ArrayList<>();
        List<Component> labels = new ArrayList<>();
        if (dirs != null) for (File f : dirs) {
            ids.add(f.getName());
            labels.add(Component.text(f.getName() + "/"));
        }
        if (ids.isEmpty()) {
            plugin.sfx().play(p, "error");
            lang().send(p, "model.no-uploads", Lang.ph("folder", "plugins/" + plugin.getName() + "/models/<name>/"));
            return;
        }
        InputMenu m = new InputMenu(lang().get(p, "menu.models.import.name"))
                .body(lang().get(p, "menu.model-editor.import-body"))
                .field(new InputMenu.Dropdown("folder", lang().get(p, "menu.model-editor.folder"), ids, labels, 0))
                .onSubmit((pl, v) -> {
                    File folder = new File(plugin.content().modelsFolder(), v.text("folder"));
                    File json = findModelJson(folder);
                    if (json == null) {
                        plugin.sfx().play(pl, "error");
                        lang().send(pl, "model.no-json", Lang.ph("folder", folder.getName()));
                        return;
                    }
                    ModelDef d = new ModelDef();
                    d.id = Texts.toId(folder.getName());
                    d.folder = folder;
                    d.modelFile = json.getName();
                    d.name = new Localized(folder.getName(), folder.getName());
                    d.description = new Localized("", "");
                    editModel(pl, d, true);
                })
                .onCancel(this::models);
        open(p, m);
    }

    private static File findModelJson(File folder) {
        File[] jsons = folder.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (jsons == null || jsons.length == 0) return null;
        for (File f : jsons) if (f.getName().equalsIgnoreCase("model.json")) return f;
        return jsons[0];
    }

    private void editModel(Player p, ModelDef d, boolean created) {
        InputMenu m = new InputMenu(lang().get(p, created ? "menu.model-editor.create" : "menu.model-editor.edit"))
                .body(lang().get(p, "menu.model-editor.body"))
                .field(new InputMenu.Text("name_en", lang().get(p, "menu.block-editor.name-en"), d.name.en(), "Oak Chair", 64))
                .field(new InputMenu.Text("name_de", lang().get(p, "menu.block-editor.name-de"), d.name.de(), "Eichenstuhl", 64))
                .field(new InputMenu.Text("desc_en", lang().get(p, "menu.block-editor.desc-en"), d.description.en(), "", 128))
                .field(new InputMenu.Text("desc_de", lang().get(p, "menu.block-editor.desc-de"), d.description.de(), "", 128))
                .field(new InputMenu.Slider("scale", lang().get(p, "menu.model-editor.scale"), 0.25f, 4f, 0.05f, (float) d.scale))
                .field(new InputMenu.Toggle("collision", lang().get(p, "menu.model-editor.collision"), d.collision))
                .field(new InputMenu.Toggle("seat", lang().get(p, "menu.model-editor.seat"), d.seat))
                .field(new InputMenu.Slider("seat_height", lang().get(p, "menu.model-editor.seat-height"), 0f, 1.5f, 0.05f, (float) d.seatHeight))
                .field(new InputMenu.Slider("rotation", lang().get(p, "menu.model-editor.rotation"), 15, 90, 15, d.rotationStep))
                .field(new InputMenu.Toggle("bright", lang().get(p, "menu.model-editor.bright"), d.glowing))
                .onSubmit((pl, v) -> {
                    String en = v.text("name_en").isBlank() ? d.id : v.text("name_en");
                    d.name = new Localized(en, v.text("name_de").isBlank() ? en : v.text("name_de"));
                    d.description = new Localized(v.text("desc_en"), v.text("desc_de").isBlank() ? v.text("desc_en") : v.text("desc_de"));
                    d.scale = Math.round(v.number("scale", 1) * 100) / 100.0;
                    d.collision = v.bool("collision");
                    d.seat = v.bool("seat");
                    d.seatHeight = Math.round(v.number("seat_height", 0.45f) * 100) / 100.0;
                    d.rotationStep = Math.round(v.number("rotation", 45));
                    d.glowing = v.bool("bright");
                    saveModel(pl, d, created);
                })
                .onCancel(pl -> {
                    if (created) models(pl);
                    else model(pl, d);
                });
        open(p, m);
    }

    private void saveModel(Player p, ModelDef d, boolean created) {
        try {
            plugin.content().saveModel(d);
        } catch (Exception e) {
            plugin.sfx().play(p, "error");
            lang().send(p, "error.generic", Lang.ph("error", e.getMessage()));
            return;
        }
        plugin.models().refreshModel(d);
        plugin.packs().requestRebuild();
        plugin.sfx().play(p, "success");
        lang().send(p, created ? "model.created" : "model.saved", Lang.ph("id", d.id));
        if (created) p.getInventory().addItem(plugin.content().item(d, 1));
    }

    // ------------------------------------------------------------------------------------------------------
    // pack & settings
    // ------------------------------------------------------------------------------------------------------

    public void pack(Player p) {
        String sha = plugin.packs().javaSha1();
        ChoiceMenu m = new ChoiceMenu(lang().get(p, "menu.pack.title"))
                .body(lang().get(p, "menu.pack.body", Lang.ph("sha", sha == null ? "-" : sha.substring(0, 12)), Lang.ph("url", sha == null ? "-" : plugin.packs().urlFor(p))))
                .back(this::main).rows(2);
        m.option(option(p, "menu.pack.rebuild", Material.ANVIL, "textures/blocks/anvil_top_damaged_0", pl -> {
            plugin.packs().rebuild(true);
            plugin.sfx().play(pl, "pack");
            lang().send(pl, "pack.rebuilding");
        }));
        m.option(option(p, "menu.pack.resend", Material.ENDER_EYE, "textures/items/ender_eye", pl -> {
            if (plugin.platform().isBedrock(pl)) lang().send(pl, "pack.bedrock-reconnect");
            else plugin.packs().send(pl);
        }));
        m.option(option(p, "menu.pack.reload", Material.REPEATER, "textures/items/repeater", pl -> plugin.command().reload(pl)));
        open(p, m);
    }

    public void settings(Player p) {
        var c = plugin.getConfig();
        InputMenu m = new InputMenu(lang().get(p, "menu.settings.title")).body(lang().get(p, "menu.settings.body"))
                .field(new InputMenu.Toggle("blocks", lang().get(p, "menu.settings.blocks"), c.getBoolean("custom-blocks.enabled", true)))
                .field(new InputMenu.Toggle("models", lang().get(p, "menu.settings.models"), c.getBoolean("models.enabled", true)))
                .field(new InputMenu.Toggle("push", lang().get(p, "menu.settings.push"), c.getBoolean("resource-pack.push-on-change", true)))
                .field(new InputMenu.Toggle("required", lang().get(p, "menu.settings.required"), c.getBoolean("resource-pack.required", false)))
                .field(new InputMenu.Toggle("auto_refresh", lang().get(p, "menu.settings.auto-refresh"), c.getBoolean("images.auto-refresh", true)))
                .field(new InputMenu.Toggle("ghost", lang().get(p, "menu.settings.ghost"), c.getBoolean("models.ghost-preview", true)))
                .field(new InputMenu.Slider("java_fps", lang().get(p, "menu.settings.java-fps"), 1, 20, 1, c.getInt("animation.java-max-fps", 20)))
                .field(new InputMenu.Slider("bedrock_fps", lang().get(p, "menu.settings.bedrock-fps"), 1, 20, 1, c.getInt("animation.bedrock-max-fps", 10)))
                .field(new InputMenu.Slider("view", lang().get(p, "menu.settings.view-distance"), 16, 160, 8, (float) c.getDouble("animation.view-distance", 64)))
                .onSubmit((pl, v) -> {
                    boolean blocksBefore = c.getBoolean("custom-blocks.enabled", true), modelsBefore = c.getBoolean("models.enabled", true);
                    c.set("custom-blocks.enabled", v.bool("blocks"));
                    c.set("models.enabled", v.bool("models"));
                    c.set("resource-pack.push-on-change", v.bool("push"));
                    c.set("resource-pack.required", v.bool("required"));
                    c.set("images.auto-refresh", v.bool("auto_refresh"));
                    c.set("models.ghost-preview", v.bool("ghost"));
                    c.set("animation.java-max-fps", Math.round(v.number("java_fps", 20)));
                    c.set("animation.bedrock-max-fps", Math.round(v.number("bedrock_fps", 10)));
                    c.set("animation.view-distance", Math.round(v.number("view", 64)));
                    plugin.saveConfig();
                    if (blocksBefore != v.bool("blocks") || modelsBefore != v.bool("models")) {
                        plugin.content().registerRecipes();
                        plugin.packs().requestRebuild();
                    }
                    plugin.sfx().play(pl, "success");
                    lang().send(pl, "settings.saved");
                })
                .onCancel(this::main);
        open(p, m);
    }

    public Path imagePath(String name) { return plugin.images().resolve(name); }
}
