package com.realityweaver.util;

import java.util.prefs.Preferences;

/**
 * Central access point for application preferences.
 * By default returns durable, JSON file-backed preferences located under
 * ~/.realityweaver/preferences.json, eliminating registry dependencies.
 */
public final class AppPreferences {
    private AppPreferences() {
    }

    public static Preferences userRoot() {
        return FilePreferences.getUserRoot();
    }

    public static Preferences forPackage(Class<?> clazz) {
        String pkg = clazz.getPackageName().replace('.', '/');
        return userRoot().node(pkg);
    }

    public static Preferences forName(String nodeName) {
        return userRoot().node(nodeName);
    }

    public static Preferences inMemory() {
        return FilePreferences.createInMemoryRoot();
    }
}

