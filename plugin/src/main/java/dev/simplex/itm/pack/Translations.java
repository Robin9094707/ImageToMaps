package dev.simplex.itm.pack;

import com.google.gson.JsonObject;
import dev.simplex.common.SlotLayout;
import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.content.BlockDef;
import dev.simplex.itm.content.ModelDef;
import dev.simplex.itm.util.Texts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** All content translation keys in English and German, used for the Java pack, the Bedrock pack and Geyser. */
public final class Translations {

    private final Map<String, Map<String, String>> byLang = new LinkedHashMap<>();

    public static Translations collect(SimplexPlugin plugin) {
        Translations t = new Translations();
        for (String lang : new String[]{"en", "de"}) {
            Map<String, String> m = new TreeMap<>();
            m.put("simplex.tag.block", plugin.lang().raw(lang, "item.tag-block"));
            m.put("simplex.tag.model", plugin.lang().raw(lang, "item.tag-model"));
            if (plugin.content().blocksEnabled()) {
                for (BlockDef b : plugin.content().blocks()) {
                    m.put("simplex.block." + b.id, b.name.get(lang));
                    m.put("simplex.block." + b.id + ".desc", b.description.get(lang));
                }
            }
            for (ModelDef d : plugin.content().models()) {
                m.put("simplex.model." + d.id, d.name.get(lang));
                m.put("simplex.model." + d.id + ".desc", d.description.get(lang));
            }
            for (String s : plugin.content().soundFiles()) m.put("simplex.sound." + Texts.toId(s), s);
            t.byLang.put(lang, m);
        }
        return t;
    }

    public Map<String, String> get(String lang) { return byLang.get(lang); }

    public JsonObject json(String lang) {
        JsonObject o = new JsonObject();
        byLang.get(lang).forEach(o::addProperty);
        return o;
    }

    /** Bedrock .lang file: content keys + slot keys used by the Geyser extension's block / item registrations. */
    public String bedrockLang(SimplexPlugin plugin, String lang) {
        StringBuilder sb = new StringBuilder();
        sb.append("itemGroup.name.simplex=Simplex\n");
        byLang.get(lang).forEach((k, v) -> sb.append(k).append('=').append(clean(v)).append('\n'));
        if (plugin.content().blocksEnabled()) {
            for (BlockDef b : plugin.content().blocks()) {
                sb.append(SlotLayout.blockSlot(b.slot).nameKey()).append('=').append(clean(b.name.get(lang))).append('\n');
                if (b.itemSlot >= 0) sb.append("simplex.item.i").append(b.itemSlot).append('=').append(clean(b.name.get(lang))).append('\n');
            }
        }
        for (ModelDef d : plugin.content().models()) {
            if (d.itemSlot >= 0) sb.append("simplex.item.i").append(d.itemSlot).append('=').append(clean(d.name.get(lang))).append('\n');
        }
        return sb.toString();
    }

    /** Java .properties for the Geyser extension (it translates Java item names server side). */
    public String properties(String lang) {
        StringBuilder sb = new StringBuilder();
        byLang.get(lang).forEach((k, v) -> sb.append(escape(k)).append('=').append(escape(v)).append('\n'));
        return sb.toString();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("\n", " ").replace("\r", "");
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '=' -> sb.append("\\=");
                case ':' -> sb.append("\\:");
                default -> {
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
