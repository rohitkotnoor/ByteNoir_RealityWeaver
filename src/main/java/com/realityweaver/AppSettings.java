package com.realityweaver;

import com.realityweaver.util.AppPreferences;
import java.util.List;
import java.util.prefs.Preferences;

/** Shared, small application preferences used by onboarding and the main screens. */
public final class AppSettings {
    private static final String SETUP_COMPLETE = "setup.complete";
    private static final String DEFAULT_TOPIC = "wallpaper.defaultTopic";
    private static final List<String> TOPICS = List.of(
            "Any topic", "Nature", "Architecture", "Space", "Games", "Animals", "Vehicles");
    private static final Preferences PREFERENCES = AppPreferences.forPackage(AppSettings.class);

    private AppSettings() {
    }

    public static boolean isSetupComplete() {
        return PREFERENCES.getBoolean(SETUP_COMPLETE, false);
    }

    public static void setSetupComplete(boolean complete) {
        PREFERENCES.putBoolean(SETUP_COMPLETE, complete);
    }

    public static String defaultWallpaperTopic() {
        String saved = PREFERENCES.get(DEFAULT_TOPIC, "Any topic");
        return TOPICS.contains(saved) ? saved : "Any topic";
    }

    public static void setDefaultWallpaperTopic(String topic) {
        PREFERENCES.put(DEFAULT_TOPIC, TOPICS.contains(topic) ? topic : "Any topic");
    }
}
