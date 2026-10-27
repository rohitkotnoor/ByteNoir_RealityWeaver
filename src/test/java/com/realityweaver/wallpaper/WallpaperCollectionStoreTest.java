package com.realityweaver.wallpaper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WallpaperCollectionStoreTest {
    @TempDir
    Path temporaryDirectory;

    private WallpaperCollectionStore store;
    private WallpaperLibrary library;
    private Preferences libraryPreferences;

    @BeforeEach
    void setUp() {
        store = new WallpaperCollectionStore(temporaryDirectory.resolve("collections.json"));
        libraryPreferences = Preferences.userRoot().node("com/realityweaver/tests/library/" + UUID.randomUUID());
        library = new WallpaperLibrary(libraryPreferences);
    }

    @AfterEach
    void cleanTemporaryReferences() throws IOException, java.util.prefs.BackingStoreException {
        for (Path path : library.load()) {
            if (path.startsWith(temporaryDirectory)) {
                library.remove(path);
            }
        }
        libraryPreferences.removeNode();
    }

    @Test
    void collectionsPersistAndRejectDuplicateNames() throws IOException {
        WallpaperCollection created = store.create("Night drive");
        WallpaperCollectionStore reopened = new WallpaperCollectionStore(store.storeFile());

        assertEquals(created.id(), reopened.list().getFirst().id());
        assertEquals("Night drive", reopened.list().getFirst().name());
        assertThrows(IllegalArgumentException.class, () -> reopened.create("night drive"));
        assertEquals("Late nights", reopened.rename(created.id(), "Late nights").name());
        reopened.delete(created.id());
        assertTrue(reopened.list().isEmpty());
    }

    @Test
    void membershipDeduplicatesAndRemovingReferenceKeepsImageFile() throws IOException {
        Path image = createImage("one.png");
        WallpaperCollection collection = store.create("Favorites");
        WallpaperCollection added = store.addWallpaper(collection.id(), image, library);
        WallpaperCollection duplicate = store.addWallpaper(collection.id(), image, library);

        assertEquals(1, duplicate.wallpapers().size());
        store.removeWallpaper(collection.id(), added.wallpapers().getFirst().id());
        assertTrue(Files.isRegularFile(image));
        assertTrue(store.find(collection.id()).wallpapers().isEmpty());
    }

    @Test
    void favoritesAndLibraryEntriesPersistWithoutDuplicatePaths() throws IOException {
        Path image = createImage("favorite.png");
        library.add(image);
        library.add(image);
        library.setFavorite(image, true);

        WallpaperLibrary reopened = new WallpaperLibrary(libraryPreferences);
        assertEquals(List.of(image), reopened.load());
        assertTrue(reopened.isFavorite(image));

        reopened.setFavorite(image, false);
        assertFalse(library.isFavorite(image));
    }

    @Test
    void archiveSharesMetadataAndCopiesImagesIntoManagedStorage() throws IOException {
        Path image = createImage("wallpaper.png");
        library.setAttribution(image, "Source: Commons · Creator: Example · License: CC BY · File page: https://example.org/file");
        WallpaperCollection collection = store.create("Coastline");
        store.addWallpaper(collection.id(), image, library);
        Path archive = temporaryDirectory.resolve("coastline.rwcollection");
        WallpaperCollectionExchange exporter = new WallpaperCollectionExchange(store, library);
        exporter.exportArchive(collection.id(), archive, true);

        WallpaperCollectionStore receiver = new WallpaperCollectionStore(
                temporaryDirectory.resolve("receiver/collections.json"));
        WallpaperCollection imported = new WallpaperCollectionExchange(receiver, library).importFile(archive);
        WallpaperCollection.WallpaperReference reference = imported.wallpapers().getFirst();

        assertEquals("CC BY", reference.license());
        assertTrue(reference.isAvailable());
        assertTrue(reference.path().startsWith(temporaryDirectory.resolve("receiver/imported-wallpapers")));
    }

    @Test
    void jsonManifestRoundTripsMetadataAndNeverOverwritesACollection() throws IOException {
        Path image = createImage("memory.png");
        library.setAttribution(image,
                "Source: Commons · Creator: Example · License: CC BY · File page: https://example.org/file");
        WallpaperCollection original = store.create("Saved memories");
        store.addWallpaper(original.id(), image, library);

        Path manifest = temporaryDirectory.resolve("saved-memories.json");
        new WallpaperCollectionExchange(store, library).exportJson(original.id(), manifest);
        WallpaperCollectionStore receiver = new WallpaperCollectionStore(
                temporaryDirectory.resolve("json-receiver/collections.json"));
        WallpaperCollectionExchange exchange = new WallpaperCollectionExchange(receiver, library);

        WallpaperCollection imported = exchange.importFile(manifest);
        WallpaperCollection.WallpaperReference reference = imported.wallpapers().getFirst();
        assertEquals("Saved memories", imported.name());
        assertEquals("Example", reference.creator());
        assertEquals("CC BY", reference.license());
        assertTrue(reference.isAvailable());
        assertThrows(IllegalArgumentException.class, () -> exchange.importFile(manifest));
        assertEquals(1, receiver.list().size());
    }

    @Test
    void importRejectsUnsupportedManifestWithoutChangingCollections() throws IOException {
        Path manifest = temporaryDirectory.resolve("unsupported.json");
        Files.writeString(manifest,
                "{\"schemaVersion\":99,\"name\":\"Unsupported\",\"wallpapers\":[]}",
                StandardCharsets.UTF_8);

        WallpaperCollectionExchange exchange = new WallpaperCollectionExchange(store, library);
        assertThrows(IOException.class, () -> exchange.importFile(manifest));
        assertTrue(store.list().isEmpty());
    }

    @Test
    void importRejectsArchivePathTraversal() throws IOException {
        Path archive = temporaryDirectory.resolve("unsafe.zip");
        String manifest = """
                {"schemaVersion":1,"name":"Unsafe","wallpapers":[{"id":"id","title":"x.png",
                "pathUri":"","archivePath":"images/../../escape.png","creator":"","license":"",
                "sourceName":"","sourceUrl":"","attribution":""}]}
                """;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        WallpaperCollectionExchange exchange = new WallpaperCollectionExchange(store, library);
        assertThrows(IOException.class, () -> exchange.importFile(archive));
        assertFalse(Files.exists(temporaryDirectory.resolve("escape.png")));
        assertTrue(store.list().isEmpty());
    }

    @Test
    void missingLocalPathsRemainVisibleWithoutOpeningArbitraryFiles() throws IOException {
        WallpaperCollection.WallpaperReference reference = new WallpaperCollection.WallpaperReference(
                "missing-id", "file:///definitely/not/here.png", "Missing image", "", "", "", "", "");
        WallpaperCollection imported = store.importCollection("Imported", List.of(reference));

        assertEquals(1, imported.wallpapers().size());
        assertFalse(imported.wallpapers().getFirst().isAvailable());
        assertTrue(imported.wallpapers().getFirst().path() == null
                || !Files.exists(imported.wallpapers().getFirst().path()));
    }

    private Path createImage(String name) throws IOException {
        Path image = temporaryDirectory.resolve(name);
        BufferedImage bitmap = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        bitmap.setRGB(0, 0, 0x667788);
        assertTrue(ImageIO.write(bitmap, "png", image.toFile()));
        return image;
    }
}
