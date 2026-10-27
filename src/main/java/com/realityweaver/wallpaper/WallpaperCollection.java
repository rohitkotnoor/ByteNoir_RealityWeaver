package com.realityweaver.wallpaper;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/** A named, persistent set of wallpaper references. */
public record WallpaperCollection(String id, String name, List<WallpaperReference> wallpapers) {
    public WallpaperCollection {
        wallpapers = wallpapers == null ? List.of() : List.copyOf(wallpapers);
    }

    public record WallpaperReference(
            String id,
            String pathUri,
            String title,
            String creator,
            String license,
            String sourceName,
            String sourceUrl,
            String attribution) {
        public Path path() {
            if (pathUri == null || pathUri.isBlank()) {
                return null;
            }
            try {
                URI uri = URI.create(pathUri);
                return "file".equalsIgnoreCase(uri.getScheme())
                        ? Path.of(uri).toAbsolutePath().normalize()
                        : null;
            } catch (IllegalArgumentException invalidPath) {
                return null;
            }
        }

        public boolean isAvailable() {
            Path localPath = path();
            return localPath != null && java.nio.file.Files.isRegularFile(localPath);
        }
    }
}
