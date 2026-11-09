package dev.simplex.itm.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-player localisation. Players whose client language is German (de_DE, de_AT, de_CH, ...) get German,
 * everybody else gets English. Bedrock players are covered too – Geyser forwards the Bedrock language.
 * Messages use MiniMessage and live in {@code plugins/SimplexImageToMap/lang/*.yml} (editable).
 */
public final class Lang {

    public static final String EN = "en";
    public static final String DE = "de";

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private final JavaPlugin plugin;
    private final Map<String, YamlConfiguration> languages = new HashMap<>();
    private String consoleLanguage = EN;

    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(String consoleLanguage) {
        this.consoleLanguage = consoleLanguage.toLowerCase(Locale.ROOT).startsWith("de") ? DE : EN;
        languages.clear();
        for (String code : List.of(EN, DE)) {
            String path = "lang/" + code + ".yml";
            File file = new File(plugin.getDataFolder(), path);
            if (!file.exists()) plugin.saveResource(path, false);
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            var stream = plugin.getResource(path);
            if (stream != null) {
                cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8)));
            }
            languages.put(code, cfg);
        }
    }

    public static String code(Locale locale) {
        return locale != null && locale.getLanguage().toLowerCase(Locale.ROOT).startsWith("de") ? DE : EN;
    }

    public String code(CommandSender sender) {
        if (sender instanceof Player p) return code(p.locale());
        return consoleLanguage;
    }

    public String raw(String lang, String key) {
        YamlConfiguration cfg = languages.getOrDefault(lang, languages.get(EN));
        if (cfg == null) return key;
        if (cfg.isList(key)) return String.join("\n", cfg.getStringList(key));
        String s = cfg.getString(key);
        if (s == null && !lang.equals(EN)) return raw(EN, key);
        return s == null ? key : s;
    }

    public Component get(CommandSender sender, String key, TagResolver... resolvers) {
        return parse(raw(code(sender), key), resolvers);
    }

    public Component get(String lang, String key, TagResolver... resolvers) {
        return parse(raw(lang, key), resolvers);
    }

    /** Item-friendly variant: not italic. */
    public Component item(CommandSender sender, String key, TagResolver... resolvers) {
        return get(sender, key, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Multi-line lore, one component per line, never italic. */
    public List<Component> lore(CommandSender sender, String key, TagResolver... resolvers) {
        String text = raw(code(sender), key);
        List<Component> lines = new ArrayList<>();
        if (text.isEmpty() || text.equals(key)) return lines;
        for (String line : text.split("\n")) {
            lines.add(parse(line, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return lines;
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(prefix(sender).append(get(sender, key, resolvers)));
    }

    public void actionBar(Player player, String key, TagResolver... resolvers) {
        player.sendActionBar(get(player, key, resolvers));
    }

    public Component prefix(CommandSender sender) {
        return parse(raw(code(sender), "prefix"));
    }

    public static Component parse(String mini, TagResolver... resolvers) {
        return MM.deserialize(mini, resolvers);
    }

    public static TagResolver ph(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    public static TagResolver comp(String key, Component value) {
        return Placeholder.component(key, value);
    }

    /** For Bedrock forms which understand § colour codes only. */
    public static String legacy(Component component) {
        return LEGACY.serialize(component);
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
