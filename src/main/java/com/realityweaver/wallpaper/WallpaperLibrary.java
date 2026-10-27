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
    private static final String CATEGORIES_KEY = "library.categories";
    private static final String RECENT_COUNT_KEY = "recent.count";
    private static final int MAX_RECENT = 8;
    private static final List<String> DEFAULT_CATEGORIES = List.of(
            "Uncategorized", "Nature", "Games", "Places", "Art", "Personal");

    private final Preferences preferences;

    public WallpaperLibrary() {
        this(Preferences.userNodeForPackage(WallpaperLibrary.class));
    }

    WallpaperLibrary(Preferences preferences) {
        this.preferences = preferences;
    }

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
        preferences.remove(metadataKey("sourceName.", normalized));
        preferences.remove(metadataKey("creator.", normalized));
        preferences.remove(metadataKey("license.", normalized));
        preferences.remove(metadataKey("sourceUrl.", normalized));
        preferences.remove(categoryKey(normalized));

        List<Path> recent = new ArrayList<>(recent());
        if (recent.remove(normalized)) {
            saveRecent(recent);
        }
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

    public List<String> categories() {
        List<String> categories = new ArrayList<>(DEFAULT_CATEGORIES);
        for (String category : storedCategories()) {
            if (categories.stream().noneMatch(existing -> existing.equalsIgnoreCase(category))) {
                categories.add(category);
            }
        }
        return List.copyOf(categories);
    }

    public String addCategory(String category) {
        String cleaned = category == null ? "" : category.strip().replaceAll("[\\r\\n]+", " ");
        String normalized = cleaned.length() > 32 ? cleaned.substring(0, 32).strip() : cleaned;
        if (normalized.isBlank() || normalized.equalsIgnoreCase("All categories")) {
            return null;
        }

        String existing = categories().stream()
                .filter(value -> value.equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        List<String> customCategories = new ArrayList<>(storedCategories());
        customCategories.add(normalized);
        preferences.put(CATEGORIES_KEY, String.join("\n", customCategories));
        return normalized;
    }

    public String category(Path imagePath) {
        String saved = preferences.get(categoryKey(imagePath), DEFAULT_CATEGORIES.get(0));
        return categories().stream()
                .filter(value -> value.equalsIgnoreCase(saved))
                .findFirst()
                .orElse(DEFAULT_CATEGORIES.get(0));
    }

    public void setCategory(Path imagePath, String category) {
        String normalized = category == null ? "" : category.strip();
        String savedCategory = categories().stream()
                .filter(existing -> existing.equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(null);
        if (savedCategory == null || savedCategory.equalsIgnoreCase("All categories")) {
            throw new IllegalArgumentException("Choose a category from the saved list.");
        }
        preferences.put(categoryKey(imagePath), savedCategory);
    }

    public List<Path> recent() {
        int count = Math.min(preferences.getInt(RECENT_COUNT_KEY, 0), MAX_RECENT);
        List<Path> recent = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String location = preferences.get("recent." + index, null);
            if (location == null) {
                continue;
            }
            try {
                Path path = Path.of(URI.create(location)).toAbsolutePath().normalize();
                if (Files.isRegularFile(path) && !recent.contains(path)) {
                    recent.add(path);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore stale or malformed recent entries.
            }
        }
        return List.copyOf(recent);
    }

    public void recordRecent(Path imagePath) {
        Path normalized = imagePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            return;
        }
        List<Path> recent = new ArrayList<>(recent());
        recent.remove(normalized);
        recent.add(0, normalized);
        if (recent.size() > MAX_RECENT) {
            recent = new ArrayList<>(recent.subList(0, MAX_RECENT));
        }
        saveRecent(recent);
    }

    public String attribution(Path imagePath) {
        return preferences.get(attributionKey(imagePath), "");
    }

    public SourceMetadata sourceMetadata(Path imagePath) {
        String attribution = attribution(imagePath);
        String sourceName = preferences.get(metadataKey("sourceName.", imagePath), "");
        String creator = preferences.get(metadataKey("creator.", imagePath), "");
        String license = preferences.get(metadataKey("license.", imagePath), "");
        String sourceUrl = preferences.get(metadataKey("sourceUrl.", imagePath), "");
        if (sourceName.isBlank() && attribution.startsWith("Source: ")) {
            int separator = attribution.indexOf(" · ");
            sourceName = separator < 0 ? attribution.substring("Source: ".length())
                    : attribution.substring("Source: ".length(), separator);
        }
        if (creator.isBlank()) {
            creator = attributionField(attribution, "Creator: ");
        }
        if (license.isBlank()) {
            license = attributionField(attribution, "License: ");
        }
        if (sourceUrl.isBlank()) {
            sourceUrl = attributionField(attribution, "File page: ");
        }
        return new SourceMetadata(sourceName, creator, license, sourceUrl, attribution);
    }

    public void setSourceMetadata(Path imagePath, OnlineWallpaper wallpaper) {
        putOrRemove(metadataKey("sourceName.", imagePath), wallpaper.sourceName());
        putOrRemove(metadataKey("creator.", imagePath), wallpaper.creator());
        putOrRemove(metadataKey("license.", imagePath), wallpaper.license());
        putOrRemove(metadataKey("sourceUrl.", imagePath), wallpaper.descriptionUri().toString());
    }

    public String stableId(Path imagePath) {
        return preferenceKey("", imagePath);
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

    private List<String> storedCategories() {
        String stored = preferences.get(CATEGORIES_KEY, "");
        if (stored.isBlank()) {
            return List.of();
        }
        return stored.lines()
                .map(String::strip)
                .filter(value -> !value.isBlank() && !value.equalsIgnoreCase("All categories"))
                .toList();
    }

    private void saveRecent(List<Path> paths) {
        int previousCount = preferences.getInt(RECENT_COUNT_KEY, 0);
        for (int index = 0; index < paths.size(); index++) {
            preferences.put("recent." + index, paths.get(index).toUri().toString());
        }
        for (int index = paths.size(); index < previousCount; index++) {
            preferences.remove("recent." + index);
        }
        preferences.putInt(RECENT_COUNT_KEY, paths.size());
    }

    private String favoriteKey(Path imagePath) {
        return preferenceKey("favorite.", imagePath);
    }

    private String attributionKey(Path imagePath) {
        return preferenceKey("attribution.", imagePath);
    }

    private String categoryKey(Path imagePath) {
        return preferenceKey("category.", imagePath);
    }

    private String metadataKey(String prefix, Path imagePath) {
        return preferenceKey(prefix, imagePath);
    }

    private void putOrRemove(String key, String value) {
        if (value == null || value.isBlank()) {
            preferences.remove(key);
        } else {
            preferences.put(key, value);
        }
    }

    private String attributionField(String attribution, String marker) {
        int start = attribution.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = attribution.indexOf(" · ", start);
        return (end < 0 ? attribution.substring(start) : attribution.substring(start, end)).strip();
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

    public record SourceMetadata(String sourceName, String creator, String license, String sourceUrl,
            String attribution) { }
}
