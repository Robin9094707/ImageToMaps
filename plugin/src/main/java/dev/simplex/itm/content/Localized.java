package dev.simplex.itm.content;

import org.bukkit.configuration.ConfigurationSection;

/** A text in English and German. */
public record Localized(String en, String de) {

    public String get(String lang) {
        return "de".equals(lang) && de != null && !de.isBlank() ? de : en;
    }

    public static Localized read(ConfigurationSection parent, String key, String def) {
        if (parent == null) return new Localized(def, def);
        if (parent.isConfigurationSection(key)) {
            ConfigurationSection s = parent.getConfigurationSection(key);
            String en = s.getString("en", def);
            return new Localized(en, s.getString("de", en));
        }
        String v = parent.getString(key, def);
        return new Localized(v, v);
    }

    public void write(ConfigurationSection parent, String key) {
        parent.set(key + ".en", en);
        parent.set(key + ".de", de);
    }
}
