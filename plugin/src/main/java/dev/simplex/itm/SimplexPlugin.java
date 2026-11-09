package dev.simplex.itm;

import dev.simplex.itm.command.SimplexCommand;
import dev.simplex.itm.content.ContentManager;
import dev.simplex.itm.content.blocks.BlockIndex;
import dev.simplex.itm.content.blocks.BlockListener;
import dev.simplex.itm.content.models.ModelManager;
import dev.simplex.itm.maps.ColorMatcher;
import dev.simplex.itm.maps.DisplayManager;
import dev.simplex.itm.maps.ImageLibrary;
import dev.simplex.itm.maps.placement.PlacementManager;
import dev.simplex.itm.pack.PackManager;
import dev.simplex.itm.ui.Menus;
import dev.simplex.itm.ui.RecipeEditor;
import dev.simplex.itm.ui.UiService;
import dev.simplex.itm.util.Lang;
import dev.simplex.itm.util.Platform;
import dev.simplex.itm.util.Sfx;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Simplex ImageToMap – images & animated GIFs on maps, custom blocks and 3D models for Java and Bedrock (Geyser).
 */
public final class SimplexPlugin extends JavaPlugin {

    public record Settings(boolean bedrockForms) {}

    private Lang lang;
    private Platform platform;
    private Sfx sfx;
    private Settings settings;
    private UiService ui;
    private Menus menus;
    private RecipeEditor recipeEditor;
    private ImageLibrary images;
    private DisplayManager displays;
    private PlacementManager placement;
    private ContentManager content;
    private BlockIndex blockIndex;
    private BlockListener blockListener;
    private ModelManager models;
    private PackManager packs;
    private SimplexCommand command;
    private ExecutorService workers;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        boolean firstRun = !new File(getDataFolder(), "data").exists();
        workers = Executors.newFixedThreadPool(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)), r -> {
            Thread t = new Thread(r, "Simplex-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        if (firstRun && getConfig().getBoolean("install-examples", true)) installExamples();

        lang = new Lang(this);
        platform = new Platform();
        sfx = new Sfx();
        loadSettings();

        // warm up the colour table off-thread
        workers.submit(ColorMatcher::get);

        ui = new UiService(this);
        menus = new Menus(this);
        recipeEditor = new RecipeEditor(this);
        images = new ImageLibrary(this);
        displays = new DisplayManager(this);
        placement = new PlacementManager(this);
        content = new ContentManager(this);
        blockIndex = new BlockIndex(this);
        models = new ModelManager(this);
        packs = new PackManager(this);
        blockListener = new BlockListener(this);

        content.load();
        blockIndex.load();
        models.load();
        displays.load();
        images.startWatching();

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(ui, this);
        pm.registerEvents(recipeEditor, this);
        pm.registerEvents(displays, this);
        pm.registerEvents(placement, this);
        pm.registerEvents(blockListener, this);
        pm.registerEvents(models, this);
        pm.registerEvents(packs, this);

        command = new SimplexCommand(this);
        registerCommand("simplex", "Simplex ImageToMap – images, custom blocks and 3D models", List.of("sim", "itm", "imagetomap"), command);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            displays.tick();
            placement.tick();
        }, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, models::tick, 2L, 2L);
        Bukkit.getScheduler().runTaskTimer(this, blockIndex::saveIfDirty, 600L, 600L);

        packs.start();
        checkPaperConfig();
        getLogger().info("Simplex ImageToMap enabled – Floodgate: " + platform.floodgate() + ", Geyser API: " + platform.geyser());
    }

    @Override
    public void onDisable() {
        if (placement != null) placement.stopAll();
        if (models != null) models.shutdown();
        if (displays != null) displays.save();
        if (blockIndex != null) blockIndex.saveIfDirty();
        if (images != null) images.stopWatching();
        if (packs != null) packs.stop();
        if (workers != null) workers.shutdownNow();
    }

    private void loadSettings() {
        reloadConfig();
        lang.load(getConfig().getString("console-language", "en"));
        sfx.load(getConfig().getConfigurationSection("sounds"));
        settings = new Settings(getConfig().getBoolean("ui.bedrock-forms", true));
    }

    public void reloadEverything() {
        loadSettings();
        content.load();
        models.load();
        for (var p : Bukkit.getOnlinePlayers()) blockListener.refreshInventory(p);
        displays.all().forEach(d -> displays.render(d, null));
        packs.requestRebuild();
    }

    /** Warns when Paper's block update settings that keep custom blocks stable are not enabled. */
    private void checkPaperConfig() {
        File f = new File(Bukkit.getWorldContainer(), "config/paper-global.yml");
        if (!f.isFile() || !content.blocksEnabled()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        boolean mushroom = cfg.getBoolean("block-updates.disable-mushroom-block-updates", false);
        boolean chorus = cfg.getBoolean("block-updates.disable-chorus-plant-updates", false);
        if (!mushroom || !chorus) {
            getLogger().warning("For rock-solid custom blocks set in config/paper-global.yml:  block-updates.disable-mushroom-block-updates: true"
                    + " and block-updates.disable-chorus-plant-updates: true  (the plugin also cancels physics, but this is the safest way).");
        }
    }

    /** Copies the bundled example content (blocks, textures, models, sounds, images) on first start. */
    private void installExamples() {
        try (InputStream in = getResource("examples.zip")) {
            if (in == null) return;
            Path root = getDataFolder().toPath();
            try (ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    Path target = root.resolve(e.getName()).normalize();
                    if (!target.startsWith(root)) continue;
                    if (e.isDirectory()) {
                        Files.createDirectories(target);
                    } else if (Files.notExists(target)) {
                        Files.createDirectories(target.getParent());
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            getLogger().info("Installed example blocks, models and images.");
        } catch (IOException e) {
            getLogger().warning("Could not install examples: " + e.getMessage());
        }
    }

    public Lang lang() { return lang; }
    public Platform platform() { return platform; }
    public Sfx sfx() { return sfx; }
    public Settings settings() { return settings; }
    public UiService ui() { return ui; }
    public Menus menus() { return menus; }
    public RecipeEditor recipeEditor() { return recipeEditor; }
    public ImageLibrary images() { return images; }
    public DisplayManager displays() { return displays; }
    public PlacementManager placement() { return placement; }
    public ContentManager content() { return content; }
    public BlockIndex blockIndex() { return blockIndex; }
    public BlockListener blockListener() { return blockListener; }
    public ModelManager models() { return models; }
    public PackManager packs() { return packs; }
    public SimplexCommand command() { return command; }
    public ExecutorService workers() { return workers; }
}
