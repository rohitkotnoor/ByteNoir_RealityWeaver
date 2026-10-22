package com.realityweaver.wallpaper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.prefs.Preferences;

/** Stores wallpaper file references and favorites in the user's local preferences. */
public final class WallpaperLibrary {
    private static final String COUNT_KEY = "library.count";

    private final Preferences preferences = Preferences.userNodeForPackage(WallpaperLibrary.class);

    public List<Path> load() {
        int count = preferences.getInt(COUNT_KEY, 0);
        List<Path> existingFiles = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String location = preferences.get("wallpaper." + index, null);
            if (location == null) {
                continue;
            }

            try {
                Path path = Path.of(URI.create(location)).toAbsolutePath().normalize();
                if (Files.isRegularFile(path)) {
                    existingFiles.add(path);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore saved entries that are no longer valid local file URLs.
            }
        }
        return List.copyOf(existingFiles);
    }

    public void add(Path imagePath) {
        Path normalized = imagePath.toAbsolutePath().normalize();
        List<Path> paths = new ArrayList<>(load());
        if (!paths.contains(normalized)) {
            paths.add(normalized);
            save(paths);
        }
    }

    public void remove(Path imagePath) {
        Path normalized = imagePath.toAbsolutePath().normalize();
        List<Path> paths = new ArrayList<>(load());
        if (paths.remove(normalized)) {
            save(paths);
        }
        preferences.remove(favoriteKey(normalized));
        preferences.remove(attributionKey(normalized));
    }

    public boolean isFavorite(Path imagePath) {
        return preferences.getBoolean(favoriteKey(imagePath), false);
    }

    public void setFavorite(Path imagePath, boolean favorite) {
        String key = favoriteKey(imagePath);
        if (favorite) {
            preferences.putBoolean(key, true);
        } else {
            preferences.remove(key);
        }
    }

    public String attribution(Path imagePath) {
        return preferences.get(attributionKey(imagePath), "");
    }

    public void setAttribution(Path imagePath, String attribution) {
        if (attribution == null || attribution.isBlank()) {
            preferences.remove(attributionKey(imagePath));
        } else {
            preferences.put(attributionKey(imagePath), attribution);
        }
    }

    private void save(List<Path> paths) {
        int previousCount = preferences.getInt(COUNT_KEY, 0);
        for (int index = 0; index < paths.size(); index++) {
            preferences.put("wallpaper." + index, paths.get(index).toUri().toString());
        }
        for (int index = paths.size(); index < previousCount; index++) {
            preferences.remove("wallpaper." + index);
        }
        preferences.putInt(COUNT_KEY, paths.size());
    }

    private String favoriteKey(Path imagePath) {
        return preferenceKey("favorite.", imagePath);
    }

    private String attributionKey(Path imagePath) {
        return preferenceKey("attribution.", imagePath);
    }

    private String preferenceKey(String prefix, Path imagePath) {
        byte[] location = imagePath.toAbsolutePath().normalize().toUri().toString()
                .getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(location);
            return prefix + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
