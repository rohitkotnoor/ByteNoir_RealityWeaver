package com.realityweaver.wallpaper;

import com.realityweaver.util.AppPreferences;
import java.util.List;
import java.util.prefs.Preferences;

/** Persists the selected collection cycle and a short no-repeat history. */
public final class WallpaperCycleStateStore {
    private static final String COLLECTION_KEY = "collection.cycle.id";
    private static final String INTERVAL_KEY = "collection.cycle.minutes";
    private static final String HISTORY_PREFIX = "collection.cycle.history.";
    private static final int MAX_HISTORY = 3;

    private final Preferences preferences;

    public WallpaperCycleStateStore() {
        this(AppPreferences.forPackage(WallpaperCycleStateStore.class));
    }

    public WallpaperCycleStateStore(Preferences preferences) {
        this.preferences = preferences;
    }

    public Cycle savedCycle() {
        String id = preferences.get(COLLECTION_KEY, "");
        int minutes = preferences.getInt(INTERVAL_KEY, 0);
        return id.isBlank() || minutes <= 0 ? null : new Cycle(id, minutes);
    }

    public void saveCycle(String collectionId, int intervalMinutes) {
        if (collectionId == null || !collectionId.matches("[A-Za-z0-9-]{1,64}") || intervalMinutes <= 0) {
            throw new IllegalArgumentException("Choose a valid collection and cycle interval.");
        }
        preferences.put(COLLECTION_KEY, collectionId);
        preferences.putInt(INTERVAL_KEY, intervalMinutes);
    }

    public void clearCycle() {
        preferences.remove(COLLECTION_KEY);
        preferences.remove(INTERVAL_KEY);
    }

    public List<String> recentIds(String collectionId) {
        String key = historyKey(collectionId);
        String saved = preferences.get(key, "");
        return saved.isBlank() ? List.of()
                : saved.lines().filter(value -> !value.isBlank()).distinct().limit(MAX_HISTORY).toList();
    }

    public void saveRecentIds(String collectionId, List<String> recentIds) {
        List<String> valid = recentIds == null ? List.of() : recentIds.stream()
                .filter(value -> value != null && value.matches("[A-Fa-f0-9-]{1,128}"))
                .distinct().limit(MAX_HISTORY).toList();
        preferences.put(historyKey(collectionId), String.join("\n", valid));
    }

    public void clearRecentIds(String collectionId) {
        preferences.remove(historyKey(collectionId));
    }

    private String historyKey(String collectionId) {
        if (collectionId == null || !collectionId.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Choose a valid collection identifier.");
        }
        return HISTORY_PREFIX + collectionId;
    }

    public record Cycle(String collectionId, int intervalMinutes) { }
}
