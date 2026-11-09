package dev.simplex.itm.maps;

/** Item frame look used for a display. Bedrock clients can not render invisible frames (Geyser shows them normally). */
public enum FrameStyle {
    INVISIBLE(false, true),
    NORMAL(false, false),
    GLOW_INVISIBLE(true, true),
    GLOW(true, false);

    public final boolean glow;
    public final boolean invisible;

    FrameStyle(boolean glow, boolean invisible) {
        this.glow = glow;
        this.invisible = invisible;
    }

    public FrameStyle next() { return values()[(ordinal() + 1) % values().length]; }

    public String langKey() { return "frame-style." + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'); }

    public static FrameStyle parse(String s, FrameStyle def) {
        try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_')); } catch (Exception e) { return def; }
    }
}
