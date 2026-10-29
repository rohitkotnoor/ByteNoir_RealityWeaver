package com.realityweaver.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FilePreferencesTest {
    @TempDir
    Path tempDir;

    @Test
    void preferencesSaveAndLoadFromCustomFile() throws Exception {
        Path prefFile = tempDir.resolve("prefs.json");
        FilePreferences root = new FilePreferences(prefFile);

        Preferences child = root.node("app/settings");
        child.put("theme", "cosmic-dark");
        child.putInt("volume", 85);
        child.putBoolean("enabled", true);
        child.flush();

        assertTrue(Files.exists(prefFile));

        // Reopen from disk
        FilePreferences reopenedRoot = new FilePreferences(prefFile);
        Preferences reopenedChild = reopenedRoot.node("app/settings");
        assertEquals("cosmic-dark", reopenedChild.get("theme", ""));
        assertEquals(85, reopenedChild.getInt("volume", 0));
        assertTrue(reopenedChild.getBoolean("enabled", false));
        assertEquals("default", reopenedChild.get("nonexistent", "default"));
    }

    @Test
    void keysAndChildrenNamesWorkCorrectly() throws Exception {
        Path prefFile = tempDir.resolve("keys-test.json");
        FilePreferences root = new FilePreferences(prefFile);

        Preferences child = root.node("library");
        child.put("k1", "v1");
        child.put("k2", "v2");

        Preferences sub = child.node("subvault");
        sub.put("nested", "true");

        String[] keys = child.keys();
        assertEquals(2, keys.length);

        String[] children = child.childrenNames();
        assertEquals(1, children.length);
        assertEquals("subvault", children[0]);

        // Remove a key
        child.remove("k1");
        assertEquals(1, child.keys().length);
        assertEquals("v2", child.get("k2", ""));
    }

    @Test
    void nodeRemovalWorks() throws Exception {
        Path prefFile = tempDir.resolve("remove-node-test.json");
        FilePreferences root = new FilePreferences(prefFile);

        Preferences node = root.node("temporary/node");
        node.put("data", "123");
        assertTrue(node.nodeExists(""));

        node.removeNode();
        assertFalse(root.nodeExists("temporary/node"));
    }
}

