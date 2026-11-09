package dev.simplex.itm.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/** Tiny built-in web server that hosts the generated packs (no external hosting needed). */
public final class PackServer {

    private final Path root;
    private final Logger logger;
    private HttpServer server;

    public PackServer(Path root, Logger logger) {
        this.root = root;
        this.logger = logger;
    }

    public boolean start(String bind, int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(bind == null || bind.isBlank() ? "0.0.0.0" : bind, port), 32);
            server.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "Simplex-PackServer");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/", this::handle);
            server.start();
            logger.info("Resource pack web server listening on port " + port + ".");
            return true;
        } catch (IOException e) {
            logger.severe("Could not start the resource pack web server on port " + port + ": " + e.getMessage()
                    + " – set resource-pack.external-url or choose another port.");
            return false;
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Path file = root.resolve(path.replaceFirst("^/+", "")).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            String name = file.getFileName().toString();
            String type = name.endsWith(".zip") ? "application/zip" : name.endsWith(".mcpack") ? "application/octet-stream" : "text/plain; charset=utf-8";
            ex.getResponseHeaders().add("Content-Type", type);
            ex.getResponseHeaders().add("Cache-Control", name.endsWith(".properties") ? "no-cache" : "public, max-age=31536000, immutable");
            long size = Files.size(file);
            if ("HEAD".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, size);
            try (OutputStream out = ex.getResponseBody()) {
                Files.copy(file, out);
            }
        }
    }
}
