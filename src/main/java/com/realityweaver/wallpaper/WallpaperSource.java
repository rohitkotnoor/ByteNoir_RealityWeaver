package com.realityweaver.wallpaper;

/** Image catalogs available in the wallpaper browser. */
public enum WallpaperSource {
    OPENVERSE("Openverse · many libraries"),
    WIKIMEDIA_COMMONS("Wikimedia Commons"),
    WALLHAVEN("Wallhaven"),
    NASA_IMAGES("NASA Images · space"),
    ART_INSTITUTE("Art Institute of Chicago · public domain");

    private final String label;

    WallpaperSource(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
