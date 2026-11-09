package dev.simplex.itm.content;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shaped crafting recipe. Ingredients are vanilla materials ({@code minecraft:stone}) or Simplex content
 * ({@code simplex:marble} for blocks, {@code simplex:model/chair} for models).
 */
public record RecipeDef(List<String> shape, Map<Character, String> ingredients, int amount) {

    public static RecipeDef read(ConfigurationSection s) {
        if (s == null) return null;
        List<String> shape = s.getStringList("shape");
        if (shape.isEmpty()) return null;
        Map<Character, String> ing = new LinkedHashMap<>();
        ConfigurationSection i = s.getConfigurationSection("ingredients");
        if (i != null) for (String k : i.getKeys(false)) if (!k.isEmpty()) ing.put(k.charAt(0), i.getString(k));
        return new RecipeDef(shape, ing, Math.max(1, s.getInt("amount", 1)));
    }

    public void write(ConfigurationSection parent, String key) {
        parent.set(key + ".shape", shape);
        for (var e : ingredients.entrySet()) parent.set(key + ".ingredients." + e.getKey(), e.getValue());
        parent.set(key + ".amount", amount);
    }

    /** Builds a recipe from a 3×3 grid of ingredient ids (null = empty), trimming empty rows/columns. */
    public static RecipeDef fromGrid(String[] grid, int amount) {
        int minR = 3, maxR = -1, minC = 3, maxC = -1;
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) if (grid[r * 3 + c] != null) {
            minR = Math.min(minR, r); maxR = Math.max(maxR, r); minC = Math.min(minC, c); maxC = Math.max(maxC, c);
        }
        if (maxR < 0) return null;
        Map<String, Character> symbols = new LinkedHashMap<>();
        Map<Character, String> ingredients = new LinkedHashMap<>();
        List<String> shape = new ArrayList<>();
        char next = 'A';
        for (int r = minR; r <= maxR; r++) {
            StringBuilder row = new StringBuilder();
            for (int c = minC; c <= maxC; c++) {
                String id = grid[r * 3 + c];
                if (id == null) {
                    row.append(' ');
                    continue;
                }
                Character sym = symbols.get(id);
                if (sym == null) {
                    sym = next++;
                    symbols.put(id, sym);
                    ingredients.put(sym, id);
                }
                row.append(sym);
            }
            shape.add(row.toString());
        }
        return new RecipeDef(shape, ingredients, amount);
    }
}
