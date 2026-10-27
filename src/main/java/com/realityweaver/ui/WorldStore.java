package com.realityweaver.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Stores a user's world drafts and their memory wallpaper details as local JSON. */
public final class WorldStore {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_WORLDS = 200;
    private static final int MAX_ITEMS_PER_LIST = 300;
    private static final int MAX_ITEM_LENGTH = 2_000;
    private static final int MAX_STORY_LENGTH = 20_000;
    private static final int MAX_QUOTE_LENGTH = 2_000;
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

    private final Path storeFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public WorldStore() {
        this(Path.of(System.getProperty("user.home"), ".realityweaver", "worlds.json"));
    }

    public WorldStore(Path storeFile) {
        this.storeFile = storeFile.toAbsolutePath().normalize();
    }

    public synchronized List<WorldDraft> load() throws IOException {
        if (!Files.exists(storeFile)) {
            return List.of();
        }
        if (Files.size(storeFile) > MAX_FILE_BYTES) {
            throw new IOException("The saved worlds file is larger than expected and was not opened.");
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(storeFile, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("The saved worlds file has an invalid format.");
            }
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("version") || root.get("version").getAsInt() != SCHEMA_VERSION
                    || !root.has("worlds") || !root.get("worlds").isJsonArray()) {
                throw new IOException("The saved worlds file has an unsupported or invalid format.");
            }
            JsonArray entries = root.getAsJsonArray("worlds");
            if (entries.size() > MAX_WORLDS) {
                throw new IOException("The saved worlds file contains too many worlds.");
            }
            List<WorldDraft> worlds = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (JsonElement entry : entries) {
                if (!entry.isJsonObject()) {
                    throw new IOException("The saved worlds file contains an invalid world entry.");
                }
                WorldDraft world = readWorld(entry.getAsJsonObject());
                if (!names.add(world.name().toLowerCase(Locale.ROOT))) {
                    throw new IOException("The saved worlds file contains duplicate world names.");
                }
                worlds.add(world);
            }
            return List.copyOf(worlds);
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException invalid) {
            throw new IOException("The saved worlds file could not be read safely.", invalid);
        }
    }

    public synchronized void save(List<WorldDraft> worlds) throws IOException {
        if (worlds == null || worlds.size() > MAX_WORLDS) {
            throw new IllegalArgumentException("You can save up to " + MAX_WORLDS + " worlds.");
        }
        // Validate any existing file before replacing it so a damaged file is never silently lost.
        load();

        JsonArray entries = new JsonArray();
        Set<String> names = new HashSet<>();
        for (WorldDraft world : worlds) {
            if (world == null) {
                throw new IllegalArgumentException("A world entry is missing.");
            }
            String name = validateText(world.name(), "World names", 120, false);
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("World names must be unique.");
            }
            validateList(world.places(), "places");
            validateList(world.characters(), "characters");
            validateList(world.stories(), "stories");
            validateRatioList(world.customScreenRatios());
            String story = validateText(world.memoryStory(), "World stories", MAX_STORY_LENGTH, true);
            String quote = validateText(world.memoryQuote(), "World quotes", MAX_QUOTE_LENGTH, true);

            JsonObject entry = new JsonObject();
            entry.addProperty("name", name);
            entry.add("places", toJsonArray(world.places()));
            entry.add("characters", toJsonArray(world.characters()));
            entry.add("stories", toJsonArray(world.stories()));
            entry.addProperty("memoryPhoto", world.memoryPhoto() == null
                    ? "" : world.memoryPhoto().toAbsolutePath().normalize().toUri().toString());
            entry.addProperty("memoryStory", story);
            entry.addProperty("memoryQuote", quote);
            entry.add("customScreenRatios", toJsonArray(world.customScreenRatios()));
            entries.add(entry);
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", SCHEMA_VERSION);
        root.add("worlds", entries);
        writeAtomically(gson.toJson(root));
    }

    public Path storeFile() {
        return storeFile;
    }

    private WorldDraft readWorld(JsonObject entry) throws IOException {
        String name = validateText(requiredString(entry, "name"), "World names", 120, false);
        List<String> places = readStringArray(entry, "places", "Places");
        List<String> characters = readStringArray(entry, "characters", "Characters");
        List<String> stories = readStringArray(entry, "stories", "Story ideas");
        List<String> ratios = readStringArray(entry, "customScreenRatios", "Custom screen ratios");
        validateRatioList(ratios);
        Path photo = readPhoto(requiredString(entry, "memoryPhoto"));
        String memoryStory = validateText(requiredString(entry, "memoryStory"), "World stories",
                MAX_STORY_LENGTH, true);
        String memoryQuote = validateText(requiredString(entry, "memoryQuote"), "World quotes",
                MAX_QUOTE_LENGTH, true);
        return new WorldDraft(name, places, characters, stories, photo, memoryStory, memoryQuote, ratios);
    }

    private String requiredString(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("A saved world is missing a valid " + key + " field.");
        }
        return value.getAsString();
    }

    private List<String> readStringArray(JsonObject object, String key, String label) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > MAX_ITEMS_PER_LIST) {
            throw new IOException("The saved " + label.toLowerCase(Locale.ROOT) + " list is invalid or too large.");
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IOException("A saved " + label.toLowerCase(Locale.ROOT) + " entry is invalid.");
            }
            values.add(validateText(element.getAsString(), label, MAX_ITEM_LENGTH, false));
        }
        return List.copyOf(values);
    }

    private Path readPhoto(String value) throws IOException {
        if (value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("A saved memory photo must refer to a local file.");
            }
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("A saved memory photo path is invalid.", invalid);
        }
    }

    private void validateList(List<String> values, String label) {
        if (values == null || values.size() > MAX_ITEMS_PER_LIST) {
            throw new IllegalArgumentException("A world can contain up to " + MAX_ITEMS_PER_LIST + " " + label + ".");
        }
        values.forEach(value -> validateText(value, label, MAX_ITEM_LENGTH, false));
    }

    private void validateRatioList(List<String> ratios) throws IOException {
        if (ratios.size() > 30) {
            throw new IOException("A world contains too many custom screen ratios.");
        }
        for (String ratio : ratios) {
            String[] parts = ratio.strip().split("\\s*:\\s*");
            if (parts.length != 2) {
                throw new IOException("A saved custom screen ratio is invalid.");
            }
            try {
                double width = Double.parseDouble(parts[0]);
                double height = Double.parseDouble(parts[1]);
                double aspect = width / height;
                if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0
                        || width > 10_000 || height > 10_000 || !Double.isFinite(aspect)
                        || aspect < 0.1 || aspect > 10) {
                    throw new NumberFormatException("ratio out of range");
                }
            } catch (NumberFormatException invalid) {
                throw new IOException("A saved custom screen ratio is invalid.", invalid);
            }
        }
    }

    private String validateText(String value, String label, int maxLength, boolean allowNewlines) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > maxLength) {
            if (allowNewlines && normalized.isBlank()) {
                return "";
            }
            throw new IllegalArgumentException(label + " must contain 1 to " + maxLength + " characters.");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isISOControl(character)
                    && !(allowNewlines && (character == '\n' || character == '\r' || character == '\t'))) {
                throw new IllegalArgumentException(label + " cannot contain control characters.");
            }
        }
        return normalized;
    }

    private JsonArray toJsonArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private void writeAtomically(String json) throws IOException {
        byte[] serialized = json.getBytes(StandardCharsets.UTF_8);
        if (serialized.length > MAX_FILE_BYTES) {
            throw new IOException("The saved worlds file is larger than the local storage limit.");
        }
        Path parent = storeFile.getParent();
        if (parent == null) {
            throw new IOException("The saved worlds file has no parent folder.");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "worlds-", ".tmp");
        try {
            Files.write(temporary, serialized);
            try {
                Files.move(temporary, storeFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, storeFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
