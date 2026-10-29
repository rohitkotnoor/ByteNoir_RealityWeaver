package com.realityweaver.util;

import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * Factory class for java.util.prefs that returns FilePreferences
 * backed by JSON files instead of the Windows registry.
 */
public class FilePreferencesFactory implements PreferencesFactory {
    @Override
    public Preferences systemRoot() {
        return FilePreferences.getSystemRoot();
    }

    @Override
    public Preferences userRoot() {
        return FilePreferences.getUserRoot();
    }
}

