package com.realityweaver.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;

/**
 * Robust, file-backed preferences implementation that persists configuration
 * in JSON format under the user's data directory. Completely eliminates
 * Windows registry permissions issues (error code 5) and works seamlessly
 * across all platforms and sandboxed environments.
 */
public class FilePreferences extends AbstractPreferences {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Map<String, String>>>() { }.getType();

    private static volatile Path defaultStorePath = Path.of(
            System.getProperty("user.home"), ".realityweaver", "preferences.json");
    private static FilePreferences userRootInstance;
    private static FilePreferences systemRootInstance;

    private final Path storePath;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Map<String, FilePreferences> children = new ConcurrentHashMap<>();
    private final boolean inMemoryOnly;

    public FilePreferences(Path storePath) {
        this(null, "", storePath, false);
        if (storePath != null && Files.exists(storePath)) {
            loadFromFile();
        }
    }

    public FilePreferences(AbstractPreferences parent, String name) {
        this(parent, name, ((FilePreferences) parent).storePath, ((FilePreferences) parent).inMemoryOnly);
    }

    public FilePreferences(AbstractPreferences parent, String name, Path storePath, boolean inMemoryOnly) {
        super(parent, name);
        this.storePath = storePath;
        this.inMemoryOnly = inMemoryOnly;
    }

    public static synchronized void setDefaultStorePath(Path path) {
        defaultStorePath = path;
        userRootInstance = null;
        systemRootInstance = null;
    }

    public static synchronized FilePreferences getUserRoot() {
        if (userRootInstance == null) {
            userRootInstance = createRoot(defaultStorePath, false);
        }
        return userRootInstance;
    }

    public static synchronized FilePreferences getSystemRoot() {
        if (systemRootInstance == null) {
            Path sysPath = defaultStorePath.resolveSibling("system-preferences.json");
            systemRootInstance = createRoot(sysPath, false);
        }
        return systemRootInstance;
    }

    public static FilePreferences createInMemoryRoot() {
        return createRoot(null, true);
    }

    public static FilePreferences createRoot(Path file, boolean inMemory) {
        FilePreferences root = new FilePreferences(null, "", file, inMemory);
        if (!inMemory && file != null && Files.exists(file)) {
            root.loadFromFile();
        }
        return root;
    }

    public synchronized void loadFromFile() {
        if (inMemoryOnly || storePath == null || !Files.exists(storePath)) {
            return;
        }
        try {
            String json = Files.readString(storePath, StandardCharsets.UTF_8);
            Map<String, Map<String, String>> allNodes = GSON.fromJson(json, MAP_TYPE);
            if (allNodes != null) {
                for (Map.Entry<String, Map<String, String>> entry : allNodes.entrySet()) {
                    String nodePath = entry.getKey();
                    FilePreferences targetNode = findOrCreateNode(nodePath);
                    if (targetNode != null && entry.getValue() != null) {
                        targetNode.values.clear();
                        targetNode.values.putAll(entry.getValue());
                    }
                }
            }
        } catch (Exception ignored) {
            // If file is unreadable, start with existing state rather than crashing.
        }
    }

    private FilePreferences findOrCreateNode(String nodePath) {
        if (nodePath == null || nodePath.isEmpty() || nodePath.equals("/")) {
            return this;
        }
        String[] parts = nodePath.split("/");
        FilePreferences current = this;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            current = (FilePreferences) current.node(part);
        }
        return current;
    }

    public synchronized void saveToFile() {
        if (inMemoryOnly || storePath == null) {
            return;
        }
        try {
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, Map<String, String>> allNodes = new HashMap<>();
            collectNodes(this, allNodes);

            Path tempFile = Files.createTempFile(parent != null ? parent : Path.of("."), "prefs-", ".tmp");
            try {
                Files.writeString(tempFile, GSON.toJson(allNodes), StandardCharsets.UTF_8);
                try {
                    Files.move(tempFile, storePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempFile, storePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException ignored) {
            // Best effort save
        }
    }

    private void collectNodes(FilePreferences node, Map<String, Map<String, String>> collector) {
        if (!node.values.isEmpty()) {
            collector.put(node.absolutePath(), new HashMap<>(node.values));
        }
        for (FilePreferences child : node.children.values()) {
            collectNodes(child, collector);
        }
    }

    @Override
    protected void putSpi(String key, String value) {
        values.put(key, value);
        saveToFile();
    }

    @Override
    protected String getSpi(String key) {
        return values.get(key);
    }

    @Override
    protected void removeSpi(String key) {
        values.remove(key);
        saveToFile();
    }

    @Override
    protected void removeNodeSpi() throws BackingStoreException {
        values.clear();
        children.clear();
        if (parent() instanceof FilePreferences p) {
            p.children.remove(name());
        }
        saveToFile();
    }

    @Override
    protected String[] keysSpi() throws BackingStoreException {
        return values.keySet().toArray(new String[0]);
    }

    @Override
    protected String[] childrenNamesSpi() throws BackingStoreException {
        return children.keySet().toArray(new String[0]);
    }

    @Override
    protected AbstractPreferences childSpi(String name) {
        return children.computeIfAbsent(name, n -> new FilePreferences(this, n, storePath, inMemoryOnly));
    }

    @Override
    protected void syncSpi() throws BackingStoreException {
        loadFromFile();
    }

    @Override
    protected void flushSpi() throws BackingStoreException {
        saveToFile();
    }
}
