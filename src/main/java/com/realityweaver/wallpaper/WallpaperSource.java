package com.realityweaver.wallpaper;

/** Image catalogs available in the wallpaper browser. */
public enum WallpaperSource {
    WALLHAVEN("Wallhaven · 4K & Anime"),
    BING_UHD("Bing 4K UHD Daily"),
    PICSUM("Picsum Curated Photos"),
    PEXELS("Pexels Photography"),
    PIXABAY("Pixabay Wallpapers"),
    OPENVERSE("Openverse · Creative Commons"),
    WIKIMEDIA_COMMONS("Wikimedia Commons"),
    NASA_IMAGES("NASA Images · Space"),
    ART_INSTITUTE("Art Institute of Chicago · Fine Art");

    private final String label;

    WallpaperSource(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
