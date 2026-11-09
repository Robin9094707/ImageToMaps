package dev.simplex.itm.maps;

/** How an image is fitted into the rectangle of maps. */
public enum FitMode {
    /** Whole image visible, transparent borders where the aspect ratio differs. */
    FIT,
    /** Fills the whole area, overflowing parts are cropped (centered). */
    FILL,
    /** Stretches the image to the exact area. */
    STRETCH;

    public FitMode next() { return values()[(ordinal() + 1) % values().length]; }

    public static FitMode parse(String s, FitMode def) {
        try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); } catch (Exception e) { return def; }
    }
}
