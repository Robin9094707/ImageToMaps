package dev.simplex.itm.util;

import java.util.Locale;
import java.util.regex.Pattern;

public final class Texts {

    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,40}");

    private Texts() {}

    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Turns any text into a resource-location safe id: lower case, a-z 0-9 and underscores. */
    public static String toId(String text) {
        String s = text.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_");
        s = s.replaceAll("^_|_$", "");
        if (s.isEmpty()) s = "unnamed";
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    public static String baseName(String file) {
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        String n = file.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    public static String extension(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
