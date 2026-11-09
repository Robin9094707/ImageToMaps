package dev.simplex.itm.pack;

import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.util.Lang;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Builds both packs (debounced, off the main thread), hosts them, pushes the Java pack live to every online Java
 * player whenever content changes, and publishes the Bedrock pack for the Geyser extension.
 */
public final class PackManager implements Listener {

    private static final UUID JAVA_PACK_ID = UUID.fromString("5f1f2b3c-0d7e-4a51-9b62-51e3c2a7d001");

    private final SimplexPlugin plugin;
    private final Path root, javaDir, bedrockDir;
    private final File stateFile;
    private PackServer server;
    private volatile String javaSha1, javaFile;
    private volatile String bedrockSha1;
    private int pending = -1;
    private boolean building, again;

    public PackManager(SimplexPlugin plugin) {
        this.plugin = plugin;
        this.root = plugin.getDataFolder().toPath().resolve("generated");
        this.javaDir = root.resolve("java");
        this.bedrockDir = root.resolve("bedrock");
        this.stateFile = new File(plugin.getDataFolder(), "data/pack.yml");
    }

    public void start() {
        try {
            Files.createDirectories(javaDir);
            Files.createDirectories(bedrockDir);
        } catch (IOException ignored) {
        }
        if (plugin.getConfig().getBoolean("resource-pack.web-server.enabled", true)) {
            server = new PackServer(root, plugin.getLogger());
            server.start(plugin.getConfig().getString("resource-pack.web-server.bind", "0.0.0.0"), plugin.getConfig().getInt("resource-pack.web-server.port", 8163));
        }
        rebuild(false);
    }

    public void stop() {
        if (server != null) server.stop();
    }

    public String javaSha1() { return javaSha1; }

    /** Debounced rebuild (collapses many quick edits into one build). */
    public void requestRebuild() {
        if (pending >= 0) Bukkit.getScheduler().cancelTask(pending);
        pending = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending = -1;
            rebuild(true);
        }, 30L).getTaskId();
    }

    public synchronized void rebuild(boolean push) {
        if (building) {
            again = true;
            return;
        }
        building = true;
        YamlConfiguration state = YamlConfiguration.loadConfiguration(stateFile);
        UUID header = uuid(state, "bedrock.header");
        UUID module = uuid(state, "bedrock.module");
        int lastVersion = state.getInt("bedrock.version", 0);
        String lastHash = state.getString("bedrock.content-hash", "");
        long started = System.currentTimeMillis();
        // snapshot texts on the main thread, build the zips asynchronously
        CompletableFuture.supplyAsync(() -> {
            try {
                byte[] java = new JavaPackBuilder(plugin).build();
                String[] hash = new String[1];
                BedrockPackBuilder.Output bedrock = new BedrockPackBuilder(plugin).build(header, module, lastVersion, lastHash, h -> hash[0] = h);
                return new Object[]{java, bedrock, hash[0]};
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, plugin.workers()).whenComplete((result, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            building = false;
            if (err != null) {
                plugin.getLogger().severe("Resource pack build failed: " + (err.getCause() != null ? err.getCause() : err));
                if (err.getCause() != null) err.getCause().printStackTrace();
            } else {
                try {
                    publish((byte[]) result[0], (BedrockPackBuilder.Output) result[1], (String) result[2], state, header, module, push);
                    plugin.getLogger().info("Resource packs built in " + (System.currentTimeMillis() - started) + " ms.");
                } catch (IOException e) {
                    plugin.getLogger().severe("Could not write resource packs: " + e.getMessage());
                }
            }
            if (again) {
                again = false;
                rebuild(true);
            }
        }));
    }

    private void publish(byte[] java, BedrockPackBuilder.Output bedrock, String bedrockHash, YamlConfiguration state,
                         UUID header, UUID module, boolean push) throws IOException {
        String jSha = sha1(java);
        String jName = "SimplexPack-" + jSha.substring(0, 12) + ".zip";
        Files.write(javaDir.resolve(jName), java);
        boolean javaChanged = !jSha.equals(javaSha1);
        javaSha1 = jSha;
        javaFile = jName;
        cleanup(javaDir, jName);

        String bSha = sha1(bedrock.data());
        String bName = "SimplexPack-" + bSha.substring(0, 12) + ".mcpack";
        Files.write(bedrockDir.resolve(bName), bedrock.data());
        Translations tr = Translations.collect(plugin);
        Files.writeString(bedrockDir.resolve("lang_en_us.properties"), tr.properties("en"), StandardCharsets.ISO_8859_1);
        Files.writeString(bedrockDir.resolve("lang_de_de.properties"), tr.properties("de"), StandardCharsets.ISO_8859_1);
        String latest = "sha1=" + bSha + "\nfile=" + bName + "\nversion=" + bedrock.version() + "\nlocales=en_us,de_de\n";
        Files.writeString(bedrockDir.resolve("latest.properties"), latest, StandardCharsets.UTF_8);
        boolean bedrockChanged = !bSha.equals(bedrockSha1);
        bedrockSha1 = bSha;
        cleanup(bedrockDir, bName);

        state.set("bedrock.header", header.toString());
        state.set("bedrock.module", module.toString());
        state.set("bedrock.version", bedrock.version());
        state.set("bedrock.content-hash", bedrockHash);
        state.save(stateFile);

        if (push && javaChanged && plugin.getConfig().getBoolean("resource-pack.push-on-change", true)) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (plugin.platform().isBedrock(p)) continue;
                send(p);
                plugin.lang().send(p, "pack.updated-java");
                plugin.sfx().play(p, "pack");
            }
        }
        if (push && bedrockChanged) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (plugin.platform().isBedrock(p)) plugin.lang().send(p, "pack.updated-bedrock");
            }
        }
    }

    private static void cleanup(Path dir, String keep) {
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("SimplexPack-") && !n.equals(keep);
            }).sorted().forEach(p -> {
                try {
                    // keep files for a while: clients may still be downloading them
                    if (System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() > 10 * 60_000L) Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static UUID uuid(YamlConfiguration state, String key) {
        String s = state.getString(key);
        try {
            if (s != null) return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
        }
        return UUID.randomUUID();
    }

    private static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // sending
    // ------------------------------------------------------------------------------------------------------

    public String urlFor(Player player) {
        String external = plugin.getConfig().getString("resource-pack.external-url", "");
        if (external != null && !external.isBlank()) {
            return external.endsWith("/") ? external + "java/" + javaFile : external + "/java/" + javaFile;
        }
        String host = plugin.getConfig().getString("resource-pack.public-host", "");
        if (host == null || host.isBlank()) {
            InetSocketAddress vh = player.getVirtualHost();
            host = vh != null ? vh.getHostString() : Bukkit.getIp();
            if (host == null || host.isBlank()) host = "127.0.0.1";
        }
        int port = plugin.getConfig().getInt("resource-pack.public-port", plugin.getConfig().getInt("resource-pack.web-server.port", 8163));
        return "http://" + host + ":" + port + "/java/" + javaFile;
    }

    public void send(Player player) {
        if (javaSha1 == null || javaFile == null) return;
        try {
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(JAVA_PACK_ID, URI.create(urlFor(player)), javaSha1);
            ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                    .packs(info)
                    .required(plugin.getConfig().getBoolean("resource-pack.required", false))
                    .replace(false)
                    .prompt(plugin.lang().get(player, "pack.prompt"))
                    .build();
            player.sendResourcePacks(request);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not send resource pack to " + player.getName() + ": " + e.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (plugin.platform().isBedrock(p) || !plugin.getConfig().getBoolean("resource-pack.send-on-join", true)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) send(p);
        }, plugin.getConfig().getLong("resource-pack.join-delay-ticks", 20));
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!JAVA_PACK_ID.equals(event.getID())) return;
        switch (event.getStatus()) {
            case DECLINED -> plugin.lang().send(event.getPlayer(), "pack.declined");
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                plugin.lang().send(event.getPlayer(), "pack.failed", Lang.ph("status", event.getStatus().name()));
                plugin.getLogger().warning("Resource pack " + event.getStatus() + " for " + event.getPlayer().getName() + " (" + urlFor(event.getPlayer()) + ")");
            }
            default -> {
            }
        }
    }
}
