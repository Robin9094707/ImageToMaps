package dev.simplex.itm.maps;

import dev.simplex.itm.SimplexPlugin;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** The {@code images/} folder: listing, safe resolving, URL downloads and live file watching. */
public final class ImageLibrary {

    public static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");

    private final SimplexPlugin plugin;
    private final Path folder;
    private WatchService watcher;
    private Thread watchThread;
    private final Map<String, Long> pending = new ConcurrentHashMap<>();

    public ImageLibrary(SimplexPlugin plugin) {
        this.plugin = plugin;
        this.folder = plugin.getDataFolder().toPath().resolve("images");
        try {
            Files.createDirectories(folder);
        } catch (IOException ignored) {
        }
    }

    public Path folder() { return folder; }

    public List<String> list() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder)) {
            s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> EXTENSIONS.contains(ext(n)))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Resolves a name inside the images folder, null if missing or outside of it. Name may omit the extension. */
    public Path resolve(String name) {
        if (name == null || name.isBlank() || name.contains("..") || name.contains("/") || name.contains("\\")) return null;
        Path p = folder.resolve(name).normalize();
        if (!p.startsWith(folder)) return null;
        if (Files.isRegularFile(p)) return p;
        for (String e : EXTENSIONS) {
            Path c = folder.resolve(name + "." + e);
            if (Files.isRegularFile(c)) return c;
        }
        return null;
    }

    public String canonicalName(String name) {
        Path p = resolve(name);
        return p == null ? null : p.getFileName().toString();
    }

    private static String ext(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** Downloads an image from http(s) into the folder. Completes with the stored file name. */
    public CompletableFuture<String> download(String url, String name) {
        long max = plugin.getConfig().getLong("images.max-download-mb", 25) * 1024 * 1024;
        return CompletableFuture.supplyAsync(() -> {
            try {
                URI uri = URI.create(url);
                if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                    throw new IOException("Only http/https URLs are allowed");
                }
                HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
                HttpResponse<InputStream> res = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                        .header("User-Agent", "SimplexImageToMap").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
                String type = res.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
                String e = type.contains("gif") ? "gif" : type.contains("jpeg") || type.contains("jpg") ? "jpg" : type.contains("bmp") ? "bmp" : "png";
                String urlExt = ext(uri.getPath());
                if (EXTENSIONS.contains(urlExt)) e = urlExt.equals("jpeg") ? "jpg" : urlExt;
                String safe = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
                Path tmp = Files.createTempFile(folder, ".dl", ".tmp");
                try (InputStream in = res.body()) {
                    byte[] buf = new byte[65536];
                    long total = 0;
                    try (var out = Files.newOutputStream(tmp)) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            total += n;
                            if (total > max) throw new IOException("File too large");
                            out.write(buf, 0, n);
                        }
                    }
                    ArtRenderer.size(tmp); // validates the image
                    Path target = folder.resolve(safe + "." + e);
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    return target.getFileName().toString();
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex.getMessage(), ex);
            }
        }, plugin.workers());
    }

    // ---- live file watching ----------------------------------------------------------------------------

    public void startWatching() {
        if (!plugin.getConfig().getBoolean("images.auto-refresh", true)) return;
        try {
            watcher = folder.getFileSystem().newWatchService();
            folder.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException e) {
            plugin.getLogger().warning("Image auto refresh disabled: " + e.getMessage());
            return;
        }
        watchThread = new Thread(() -> {
            try {
                while (true) {
                    WatchKey key = watcher.take();
                    for (WatchEvent<?> ev : key.pollEvents()) {
                        if (ev.context() instanceof Path p) pending.put(p.getFileName().toString(), System.currentTimeMillis());
                    }
                    key.reset();
                }
            } catch (InterruptedException | ClosedWatchServiceException ignored) {
            }
        }, "Simplex-ImageWatcher");
        watchThread.setDaemon(true);
        watchThread.start();
        // debounce: refresh once the file was quiet for 1.5 s (uploads via FTP write in chunks)
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            pending.entrySet().removeIf(e -> {
                if (now - e.getValue() < 1500) return false;
                String name = e.getKey();
                if (EXTENSIONS.contains(ext(name)) && plugin.displays().usesImage(name)) {
                    plugin.getLogger().info("Image '" + name + "' changed – refreshing displays.");
                    plugin.displays().refreshImage(name, null);
                }
                return true;
            });
        }, 20L, 20L);
    }

    public void stopWatching() {
        try {
            if (watcher != null) watcher.close();
        } catch (IOException ignored) {
        }
        if (watchThread != null) watchThread.interrupt();
    }
}
