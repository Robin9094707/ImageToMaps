package dev.simplex.itm;

import dev.simplex.itm.command.SimplexCommand;
import dev.simplex.itm.maps.ColorMatcher;
import dev.simplex.itm.maps.DisplayManager;
import dev.simplex.itm.maps.ImageLibrary;
import dev.simplex.itm.maps.placement.PlacementManager;
import dev.simplex.itm.ui.Menus;
import dev.simplex.itm.ui.UiService;
import dev.simplex.itm.util.Lang;
import dev.simplex.itm.util.Platform;
import dev.simplex.itm.util.Sfx;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

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
 * Simplex ImageToMap – images & animated GIFs on map walls for Java and Bedrock (Geyser / Floodgate) players.
 */
public final class SimplexPlugin extends JavaPlugin {

    public record Settings(boolean bedrockForms) {}

    private Lang lang;
    private Platform platform;
    private Sfx sfx;
    private Settings settings;
    private UiService ui;
    private Menus menus;
    private ImageLibrary images;
    private DisplayManager displays;
    private PlacementManager placement;
    private SimplexCommand command;
    private ExecutorService workers;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        boolean firstRun = !new java.io.File(getDataFolder(), "data").exists();
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
        images = new ImageLibrary(this);
        displays = new DisplayManager(this);
        placement = new PlacementManager(this);

        displays.load();
        images.startWatching();

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(ui, this);
        pm.registerEvents(displays, this);
        pm.registerEvents(placement, this);

        command = new SimplexCommand(this);
        registerCommand("simplex", "Simplex ImageToMap – images and animated GIFs on maps", List.of("sim", "itm", "imagetomap"), command);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            displays.tick();
            placement.tick();
        }, 1L, 1L);
        getLogger().info("Simplex ImageToMap enabled – Floodgate: " + platform.floodgate() + ", Geyser API: " + platform.geyser());
    }

    @Override
    public void onDisable() {
        if (placement != null) placement.stopAll();
        if (displays != null) displays.save();
        if (images != null) images.stopWatching();
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
        displays.all().forEach(d -> displays.render(d, null));
    }

    /** Copies the bundled example images on first start. */
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
            getLogger().info("Installed example images.");
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
    public ImageLibrary images() { return images; }
    public DisplayManager displays() { return displays; }
    public PlacementManager placement() { return placement; }
    public SimplexCommand command() { return command; }
    public ExecutorService workers() { return workers; }
}
