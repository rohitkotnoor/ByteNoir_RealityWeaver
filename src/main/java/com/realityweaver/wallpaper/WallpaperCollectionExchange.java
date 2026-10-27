package com.realityweaver.wallpaper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/** Imports and exports versioned JSON collection manifests and safe ZIP archives. */
public final class WallpaperCollectionExchange {
    public static final int SCHEMA_VERSION = 1;
    private static final String MANIFEST_NAME = "manifest.json";
    private static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 2_100;
    private static final long MAX_ARCHIVE_BYTES = 500L * 1024 * 1024;
    private static final long MAX_IMAGE_BYTES = 100L * 1024 * 1024;

    private final WallpaperCollectionStore store;
    private final WallpaperLibrary library;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public WallpaperCollectionExchange(WallpaperCollectionStore store, WallpaperLibrary library) {
        this.store = store;
        this.library = library;
    }

    public void exportJson(String collectionId, Path destination) throws IOException {
        WallpaperCollection collection = requireCollection(collectionId);
        List<ManifestWallpaper> entries = collection.wallpapers().stream()
                .map(reference -> manifestReference(reference, ""))
                .toList();
        writeManifest(new Manifest(SCHEMA_VERSION, collection.name(), entries), destination);
    }

    public void exportArchive(String collectionId, Path destination, boolean includeImages) throws IOException {
        WallpaperCollection collection = requireCollection(collectionId);
        Map<String, Path> archiveImages = new HashMap<>();
        List<ManifestWallpaper> entries = new ArrayList<>();
        for (WallpaperCollection.WallpaperReference reference : collection.wallpapers()) {
            Path image = reference.path();
            String archivePath = "";
            if (includeImages && image != null && Files.isRegularFile(image)) {
                String safeName = safeFileName(reference.title(), image.getFileName().toString());
                archivePath = "images/" + reference.id() + "/" + safeName;
                archiveImages.put(archivePath, image);
            }
            entries.add(manifestReference(reference, archivePath));
        }
        writeArchive(new Manifest(SCHEMA_VERSION, collection.name(), entries), archiveImages, destination);
    }

    public WallpaperCollection importFile(Path source) throws IOException {
        Path archive = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(archive)) {
            throw new IOException("Choose an existing JSON manifest or collection ZIP file.");
        }
        if (Files.size(archive) > MAX_ARCHIVE_BYTES) {
            throw new IOException("The collection import is larger than the 500 MB safety limit.");
        }
        String filename = archive.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return filename.endsWith(".json") ? importJson(archive) : importArchive(archive);
    }

    private WallpaperCollection importJson(Path source) throws IOException {
        if (Files.size(source) > MAX_MANIFEST_BYTES) {
            throw new IOException("The collection manifest is larger than expected.");
        }
        Manifest manifest = parseManifest(Files.readString(source, StandardCharsets.UTF_8));
        List<WallpaperCollection.WallpaperReference> references = new ArrayList<>();
        for (ManifestWallpaper entry : manifest.wallpapers()) {
            Path availablePath = safeAvailableLocalPath(entry.pathUri());
            if (availablePath != null) {
                library.add(availablePath);
            }
            references.add(toReference(entry, availablePath));
        }
        return store.importCollection(manifest.name(), references);
    }

    private WallpaperCollection importArchive(Path source) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            if (zip.size() > MAX_ARCHIVE_ENTRIES) {
                throw new IOException("The archive contains too many files.");
            }
            ZipEntry manifestEntry = zip.getEntry(MANIFEST_NAME);
            if (manifestEntry == null || manifestEntry.isDirectory()) {
                throw new IOException("The archive does not contain a root manifest.json file.");
            }
            Manifest manifest = parseManifest(new String(readLimited(zip.getInputStream(manifestEntry),
                    MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));

            Set<String> declaredPaths = new HashSet<>();
            for (ManifestWallpaper entry : manifest.wallpapers()) {
                if (entry.archivePath() != null && !entry.archivePath().isBlank()) {
                    String safePath = validateArchivePath(entry.archivePath());
                    if (!declaredPaths.add(safePath)) {
                        throw new IOException("The manifest contains a duplicate archive image path.");
                    }
                    ZipEntry imageEntry = zip.getEntry(safePath);
                    if (imageEntry == null || imageEntry.isDirectory()) {
                        throw new IOException("A referenced image is missing from the archive.");
                    }
                    if (imageEntry.getSize() > MAX_IMAGE_BYTES) {
                        throw new IOException("An image in the archive exceeds the 100 MB limit.");
                    }
                }
            }
            validateArchiveEntries(zip, declaredPaths);

            Path managedDirectory = store.storeFile().getParent().resolve("imported-wallpapers");
            Files.createDirectories(managedDirectory);
            Path staging = Files.createTempDirectory(managedDirectory, ".import-");
            List<Path> createdFiles = new ArrayList<>();
            List<WallpaperCollection.WallpaperReference> references = new ArrayList<>();
            long totalBytes = 0;
            try {
                for (ManifestWallpaper entry : manifest.wallpapers()) {
                    String archivePath = entry.archivePath() == null ? "" : entry.archivePath().strip();
                    if (archivePath.isBlank()) {
                        Path availablePath = safeAvailableLocalPath(entry.pathUri());
                        if (availablePath != null) {
                            library.add(availablePath);
                        }
                        references.add(toReference(entry, availablePath));
                        continue;
                    }

                    ZipEntry zipEntry = zip.getEntry(archivePath);
                    Path stagedImage = resolveInside(staging, archivePath.substring("images/".length()));
                    Files.createDirectories(stagedImage.getParent());
                    long copied = copyLimited(zip.getInputStream(zipEntry), stagedImage, MAX_IMAGE_BYTES);
                    totalBytes += copied;
                    if (totalBytes > MAX_ARCHIVE_BYTES) {
                        throw new IOException("The archive expands beyond the 500 MB safety limit.");
                    }
                    validateImage(stagedImage);
                    String filename = safeFileName(entry.title(), stagedImage.getFileName().toString());
                    Path importedImage = managedDirectory.resolve(UUID.randomUUID() + "-" + filename).normalize();
                    if (!importedImage.startsWith(managedDirectory)) {
                        throw new IOException("The image filename is not safe.");
                    }
                    Files.move(stagedImage, importedImage);
                    createdFiles.add(importedImage);
                    library.add(importedImage);
                    references.add(toReference(entry, importedImage));
                }
                WallpaperCollection imported = store.importCollection(manifest.name(), references);
                return imported;
            } catch (IOException | RuntimeException failure) {
                for (Path createdFile : createdFiles) {
                    library.remove(createdFile);
                    Files.deleteIfExists(createdFile);
                }
                throw failure;
            } finally {
                deleteTree(staging);
            }
        }
    }

    private void validateArchiveEntries(ZipFile zip, Set<String> declaredPaths) throws IOException {
        var entries = zip.entries();
        int count = 0;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (++count > MAX_ARCHIVE_ENTRIES) {
                throw new IOException("The archive contains too many files.");
            }
            String name = entry.getName();
            if (MANIFEST_NAME.equals(name) && !entry.isDirectory()) {
                continue;
            }
            String safePath = validateArchivePath(name);
            if (entry.isDirectory()) {
                if (!safePath.startsWith("images/")) {
                    throw new IOException("The archive contains an unexpected directory.");
                }
            } else if (!declaredPaths.contains(safePath)) {
                throw new IOException("The archive contains an image not declared in its manifest.");
            }
        }
    }

    private Manifest parseManifest(String json) throws IOException {
        try {
            Manifest manifest = gson.fromJson(json, Manifest.class);
            if (manifest == null || manifest.schemaVersion() != SCHEMA_VERSION || manifest.name() == null
                    || manifest.wallpapers() == null || manifest.wallpapers().size() > 2_000) {
                throw new IOException("The manifest has an unsupported version or invalid fields.");
            }
            for (ManifestWallpaper entry : manifest.wallpapers()) {
                if (entry == null || entry.title() == null || entry.title().length() > 512
                        || (entry.pathUri() != null && entry.pathUri().length() > 8_192)
                        || (entry.archivePath() != null && entry.archivePath().length() > 1_024)) {
                    throw new IOException("The manifest contains an invalid wallpaper entry.");
                }
            }
            return manifest;
        } catch (JsonParseException | IllegalStateException invalid) {
            throw new IOException("The collection manifest is not valid JSON.", invalid);
        }
    }

    private void writeManifest(Manifest manifest, Path destination) throws IOException {
        Path output = destination.toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(output, gson.toJson(manifest), StandardCharsets.UTF_8);
    }

    private void writeArchive(Manifest manifest, Map<String, Path> images, Path destination) throws IOException {
        Path output = destination.toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(MANIFEST_NAME));
            zip.write(gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            byte[] buffer = new byte[32 * 1024];
            for (Map.Entry<String, Path> image : images.entrySet()) {
                String safePath = validateArchivePath(image.getKey());
                zip.putNextEntry(new ZipEntry(safePath));
                try (InputStream input = Files.newInputStream(image.getValue())) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private ManifestWallpaper manifestReference(WallpaperCollection.WallpaperReference reference,
            String archivePath) {
        return new ManifestWallpaper(reference.id(), reference.title(), reference.pathUri(), archivePath,
                reference.creator(), reference.license(), reference.sourceName(), reference.sourceUrl(),
                reference.attribution());
    }

    private WallpaperCollection.WallpaperReference toReference(ManifestWallpaper entry, Path path) {
        String id = path == null ? entry.id() : library.stableId(path);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        String pathUri = path == null ? "missing:" + id : path.toAbsolutePath().normalize().toUri().toString();
        return new WallpaperCollection.WallpaperReference(id,
                pathUri, entry.title(), safe(entry.creator()), safe(entry.license()), safe(entry.sourceName()),
                safeHttpsUrl(entry.sourceUrl()), safe(entry.attribution()));
    }

    private Path safeAvailableLocalPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(rawPath);
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            Path path = Path.of(uri).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                return null;
            }
            validateImage(path);
            return path;
        } catch (IllegalArgumentException | IOException invalidPath) {
            return null;
        }
    }

    private void validateImage(Path image) throws IOException {
        long size = Files.size(image);
        if (size <= 0 || size > MAX_IMAGE_BYTES) {
            throw new IOException("A wallpaper image is empty or exceeds the 100 MB limit.");
        }
        var decoded = ImageIO.read(image.toFile());
        if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0
                || (long) decoded.getWidth() * decoded.getHeight() > 150_000_000L) {
            throw new IOException("An imported file is not a supported wallpaper image.");
        }
    }

    private byte[] readLimited(InputStream input, int limit) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new IOException("The collection manifest is too large.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private long copyLimited(InputStream input, Path output, long limit) throws IOException {
        try (input; var target = Files.newOutputStream(output)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            long total = 0;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new IOException("An image expands beyond the 100 MB safety limit.");
                }
                target.write(buffer, 0, read);
            }
            return total;
        }
    }

    private Path resolveInside(Path root, String relative) throws IOException {
        String safe = validateArchivePath("images/" + relative);
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(safe.substring("images/".length())).normalize();
        if (!resolved.startsWith(base)) {
            throw new IOException("The archive contains an unsafe image path.");
        }
        return resolved;
    }

    private String validateArchivePath(String path) throws IOException {
        if (path == null || path.isBlank() || path.length() > 1_024 || path.startsWith("/")
                || path.contains(":") || path.indexOf('\0') >= 0) {
            throw new IOException("The archive contains an unsafe path.");
        }
        String normalizedSlashes = path.replace('\\', '/');
        String checkedPath = normalizedSlashes.endsWith("/")
                ? normalizedSlashes.substring(0, normalizedSlashes.length() - 1) : normalizedSlashes;
        Path normalized = Path.of(checkedPath).normalize();
        boolean unsafeSegment = java.util.Arrays.stream(checkedPath.split("/", -1))
                .anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."));
        if (normalized.isAbsolute() || normalized.startsWith("..") || unsafeSegment
                || !(normalizedSlashes.equals(MANIFEST_NAME) || normalizedSlashes.startsWith("images/"))) {
            throw new IOException("The archive contains an unsafe path.");
        }
        return normalizedSlashes;
    }

    private String safeFileName(String title, String fallback) {
        String value = (title == null || title.isBlank() ? fallback : title).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .strip();
        if (value.isBlank() || value.equals(".") || value.equals("..")) {
            value = "wallpaper.jpg";
        }
        if (value.length() > 120) {
            value = value.substring(0, 120);
        }
        return value;
    }

    private String safe(String value) {
        return value == null ? "" : value.strip().substring(0, Math.min(2_000, value.strip().length()));
    }

    private String safeHttpsUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(value.strip());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    ? uri.toASCIIString() : "";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    private void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private WallpaperCollection requireCollection(String id) throws IOException {
        WallpaperCollection collection = store.find(id);
        if (collection == null) {
            throw new IllegalArgumentException("Choose a collection first.");
        }
        return collection;
    }

    private record Manifest(int schemaVersion, String name, List<ManifestWallpaper> wallpapers) { }

    private record ManifestWallpaper(String id, String title, String pathUri, String archivePath, String creator,
            String license, String sourceName, String sourceUrl, String attribution) { }
}
