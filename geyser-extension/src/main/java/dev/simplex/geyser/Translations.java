package dev.simplex.geyser;

import org.geysermc.geyser.api.extension.Extension;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes Geyser translate {@code simplex.*} keys (custom block / item names) into the player's language.
 * Geyser translates Java chat/item components server side using its own locale tables, so we add our keys
 * to those tables. If anything fails the Java {@code fallback} (English) is used, so nothing ever breaks.
 */
final class Translations {

    private final Extension extension;
    private final Map<String, Map<String, String>> byLocale = new ConcurrentHashMap<>();
    private boolean warned;

    Translations(Extension extension) {
        this.extension = extension;
    }

    void load(String locale, String propertiesText) {
        try {
            Properties p = new Properties();
            p.load(new StringReader(propertiesText));
            Map<String, String> map = new ConcurrentHashMap<>();
            for (String key : p.stringPropertyNames()) map.put(key, p.getProperty(key));
            byLocale.put(locale.toLowerCase(), map);
            // Refresh every already loaded locale.
            for (String loaded : geyserLocales().keySet()) inject(loaded);
        } catch (Exception e) {
            extension.logger().debug("Could not load Simplex translations: " + e.getMessage());
        }
    }

    void inject(String locale) {
        Map<String, Map<String, String>> tables = geyserLocales();
        Map<String, String> table = tables.get(locale);
        if (table == null) return;
        Map<String, String> ours = byLocale.get(locale);
        if (ours == null && locale.startsWith("de")) ours = byLocale.get("de_de");
        if (ours == null) ours = byLocale.get("en_us");
        if (ours == null) return;
        try {
            table.putAll(ours);
        } catch (UnsupportedOperationException e) {
            if (!warned) {
                warned = true;
                extension.logger().debug("Geyser locale table is immutable – Bedrock players see English custom names.");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, String>> geyserLocales() {
        try {
            Class<?> cls = Class.forName("org.geysermc.geyser.text.MinecraftLocale");
            Field f = cls.getField("LOCALE_MAPPINGS");
            return (Map<String, Map<String, String>>) f.get(null);
        } catch (Throwable t) {
            return Map.of();
        }
    }
}
