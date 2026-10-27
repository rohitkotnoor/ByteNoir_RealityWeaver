package com.realityweaver.wallpaper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

final class WallpaperCycleStateStoreTest {
    @Test
    void selectedCycleAndNoRepeatHistorySurviveRestart() throws Exception {
        Preferences preferences = Preferences.userRoot().node("com/realityweaver/tests/cycle/" + UUID.randomUUID());
        try {
            String collectionId = UUID.randomUUID().toString();
            WallpaperCycleStateStore store = new WallpaperCycleStateStore(preferences);
            store.saveCycle(collectionId, 30);
            store.saveRecentIds(collectionId, List.of("aabb11", "ccdd22"));

            WallpaperCycleStateStore reopened = new WallpaperCycleStateStore(preferences);
            assertEquals(new WallpaperCycleStateStore.Cycle(collectionId, 30), reopened.savedCycle());
            assertEquals(List.of("aabb11", "ccdd22"), reopened.recentIds(collectionId));

            reopened.clearCycle();
            assertNull(store.savedCycle());
        } finally {
            preferences.removeNode();
        }
    }
}
