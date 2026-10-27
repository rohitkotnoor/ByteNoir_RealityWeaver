package com.realityweaver.wallpaper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Persists named wallpaper collections separately from the existing library preferences. */
public final class WallpaperCollectionStore {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_COLLECTIONS = 250;
    private static final int MAX_WALLPAPERS_PER_COLLECTION = 2_000;
    private static final long MAX_STORE_BYTES = 16L * 1024 * 1024;

    private final Path storeFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public WallpaperCollectionStore() {
        this(Path.of(System.getProperty("user.home"), ".realityweaver", "collections.json"));
    }

    public WallpaperCollectionStore(Path storeFile) {
        this.storeFile = storeFile.toAbsolutePath().normalize();
    }

    public synchronized List<WallpaperCollection> list() throws IOException {
        return List.copyOf(read().collections());
    }

    public synchronized WallpaperCollection find(String id) throws IOException {
        return read().collections().stream().filter(collection -> collection.id().equals(id))
                .findFirst().orElse(null);
    }

    public synchronized WallpaperCollection create(String name) throws IOException {
        String normalized = validateName(name);
        StoreData data = read();
        ensureUniqueName(data.collections(), normalized, null);
        if (data.collections().size() >= MAX_COLLECTIONS) {
            throw new IllegalStateException("You can save up to " + MAX_COLLECTIONS + " collections.");
        }
        WallpaperCollection collection = new WallpaperCollection(UUID.randomUUID().toString(), normalized, List.of());
        List<WallpaperCollection> collections = new ArrayList<>(data.collections());
        collections.add(collection);
        write(collections);
        return collection;
    }

    public synchronized WallpaperCollection rename(String id, String name) throws IOException {
        String normalized = validateName(name);
        List<WallpaperCollection> collections = new ArrayList<>(read().collections());
        WallpaperCollection current = requireCollection(collections, id);
        ensureUniqueName(collections, normalized, id);
        WallpaperCollection renamed = new WallpaperCollection(current.id(), normalized, current.wallpapers());
        collections.set(collections.indexOf(current), renamed);
        write(collections);
        return renamed;
    }

    public synchronized void delete(String id) throws IOException {
        List<WallpaperCollection> collections = new ArrayList<>(read().collections());
        if (!collections.removeIf(collection -> collection.id().equals(id))) {
            throw new IllegalArgumentException("That collection no longer exists.");
        }
        write(collections);
    }

    public synchronized WallpaperCollection addWallpaper(String collectionId, Path path,
            WallpaperLibrary library) throws IOException {
        Path normalizedPath = validateImagePath(path);
        List<WallpaperCollection> collections = new ArrayList<>(read().collections());
        WallpaperCollection collection = requireCollection(collections, collectionId);
        String pathUri = normalizedPath.toUri().toString();
        if (collection.wallpapers().stream().anyMatch(reference -> pathUri.equals(reference.pathUri()))) {
            return collection;
        }
        if (collection.wallpapers().size() >= MAX_WALLPAPERS_PER_COLLECTION) {
            throw new IllegalStateException("A collection can contain up to " + MAX_WALLPAPERS_PER_COLLECTION
                    + " wallpapers.");
        }

        WallpaperLibrary.SourceMetadata metadata = library.sourceMetadata(normalizedPath);
        WallpaperCollection.WallpaperReference reference = new WallpaperCollection.WallpaperReference(
                library.stableId(normalizedPath), pathUri, normalizedPath.getFileName().toString(),
                metadata.creator(), metadata.license(), metadata.sourceName(), metadata.sourceUrl(),
                metadata.attribution());
        List<WallpaperCollection.WallpaperReference> references = new ArrayList<>(collection.wallpapers());
        references.add(reference);
        WallpaperCollection updated = new WallpaperCollection(collection.id(), collection.name(), references);
        collections.set(collections.indexOf(collection), updated);
        write(collections);
        return updated;
    }

    public synchronized WallpaperCollection removeWallpaper(String collectionId, String wallpaperId)
            throws IOException {
        List<WallpaperCollection> collections = new ArrayList<>(read().collections());
        WallpaperCollection collection = requireCollection(collections, collectionId);
        List<WallpaperCollection.WallpaperReference> references = new ArrayList<>(collection.wallpapers());
        if (!references.removeIf(reference -> reference.id().equals(wallpaperId))) {
            throw new IllegalArgumentException("That wallpaper is no longer in the collection.");
        }
        WallpaperCollection updated = new WallpaperCollection(collection.id(), collection.name(), references);
        collections.set(collections.indexOf(collection), updated);
        write(collections);
        return updated;
    }

    /** Imports as a new collection; a matching name or ID is an error, never an overwrite. */
    public synchronized WallpaperCollection importCollection(String name,
            List<WallpaperCollection.WallpaperReference> references) throws IOException {
        String normalized = validateName(name);
        if (references == null || references.size() > MAX_WALLPAPERS_PER_COLLECTION) {
            throw new IllegalArgumentException("The imported collection has too many wallpaper entries.");
        }
        StoreData data = read();
        ensureUniqueName(data.collections(), normalized, null);
        if (data.collections().size() >= MAX_COLLECTIONS) {
            throw new IllegalStateException("You can save up to " + MAX_COLLECTIONS + " collections.");
        }
        List<WallpaperCollection.WallpaperReference> unique = new ArrayList<>();
        for (WallpaperCollection.WallpaperReference reference : references) {
            if (reference == null || reference.pathUri() == null || reference.pathUri().length() > 8_192) {
                throw new IllegalArgumentException("The imported collection contains an invalid wallpaper path.");
            }
            Path path = reference.path();
            String id = path == null ? reference.id() : stableReferenceId(path);
            if (id == null || id.isBlank()) {
                id = UUID.randomUUID().toString();
            }
            String pathUri = path == null ? reference.pathUri() : path.toUri().toString();
            boolean duplicate = unique.stream().anyMatch(existing -> existing.pathUri().equals(pathUri));
            if (!duplicate) {
                unique.add(new WallpaperCollection.WallpaperReference(id, pathUri,
                        safeTitle(reference.title(), path), safe(reference.creator()), safe(reference.license()),
                        safe(reference.sourceName()), safeSourceUrl(reference.sourceUrl()),
                        safe(reference.attribution())));
            }
        }
        WallpaperCollection imported = new WallpaperCollection(UUID.randomUUID().toString(), normalized, unique);
        List<WallpaperCollection> collections = new ArrayList<>(data.collections());
        collections.add(imported);
        write(collections);
        return imported;
    }

    public Path storeFile() {
        return storeFile;
    }

    private StoreData read() throws IOException {
        if (!Files.exists(storeFile)) {
            return new StoreData(SCHEMA_VERSION, List.of());
        }
        long size = Files.size(storeFile);
        if (size > MAX_STORE_BYTES) {
            throw new IOException("The collection library is larger than expected and was not loaded.");
        }
        try {
            StoreData data = gson.fromJson(Files.readString(storeFile, StandardCharsets.UTF_8), StoreData.class);
            if (data == null || data.version() != SCHEMA_VERSION || data.collections() == null
                    || data.collections().size() > MAX_COLLECTIONS) {
                throw new IOException("The collection library has an unsupported or invalid format.");
            }
            validateLoaded(data.collections());
            return data;
        } catch (JsonParseException | IllegalStateException invalid) {
            throw new IOException("The collection library could not be read safely.", invalid);
        }
    }

    private void validateLoaded(List<WallpaperCollection> collections) throws IOException {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (WallpaperCollection collection : collections) {
            if (collection == null || collection.id() == null || collection.id().isBlank()
                    || collection.name() == null || collection.wallpapers() == null
                    || collection.wallpapers().size() > MAX_WALLPAPERS_PER_COLLECTION
                    || ids.contains(collection.id())
                    || names.stream().anyMatch(name -> name.equalsIgnoreCase(collection.name()))) {
                throw new IOException("The collection library contains an invalid or duplicate entry.");
            }
            ids.add(collection.id());
            names.add(collection.name());
            for (WallpaperCollection.WallpaperReference reference : collection.wallpapers()) {
                if (reference == null || reference.id() == null || reference.pathUri() == null
                        || reference.pathUri().length() > 8_192) {
                    throw new IOException("A saved collection has an invalid wallpaper reference.");
                }
            }
        }
    }

    private void write(List<WallpaperCollection> collections) throws IOException {
        Path parent = storeFile.getParent();
        if (parent == null) {
            throw new IOException("The collection library has no parent directory.");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "collections-", ".tmp");
        try {
            Files.writeString(temporary, gson.toJson(new StoreData(SCHEMA_VERSION, collections)),
                    StandardCharsets.UTF_8);
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

    private WallpaperCollection requireCollection(List<WallpaperCollection> collections, String id) {
        return collections.stream().filter(collection -> collection.id().equals(id))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("That collection no longer exists."));
    }

    private void ensureUniqueName(List<WallpaperCollection> collections, String name, String exceptId) {
        if (collections.stream().anyMatch(collection -> !collection.id().equals(exceptId)
                && collection.name().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("A collection with that name already exists.");
        }
    }

    private String validateName(String name) {
        String normalized = name == null ? "" : name.strip().replaceAll("[\\r\\n\\p{Cntrl}]+", " ");
        if (normalized.isBlank() || normalized.length() > 64) {
            throw new IllegalArgumentException("Collection names must contain 1 to 64 characters.");
        }
        return normalized;
    }

    private Path validateImagePath(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("Choose an image from your library first.");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("That wallpaper is missing from this device.");
        }
        return normalized;
    }

    private String safeTitle(String title, Path path) {
        if (title != null && !title.isBlank()) {
            return title.strip().substring(0, Math.min(512, title.strip().length()));
        }
        return path == null ? "Unavailable wallpaper" : path.getFileName().toString();
    }

    private String safeSourceUrl(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(sourceUrl.strip());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    ? uri.toASCIIString() : "";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.strip().substring(0, Math.min(2_000, value.strip().length()));
    }

    private String stableReferenceId(Path path) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(path.toAbsolutePath().normalize().toUri().toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", impossible);
        }
    }

    private record StoreData(int version, List<WallpaperCollection> collections) { }
}
