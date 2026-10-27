package com.realityweaver.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void draftsPersistPlacesStoriesPhotosQuotesAndCustomRatios() throws IOException {
        Path photo = temporaryDirectory.resolve("memory photo.png");
        Files.writeString(photo, "local photo placeholder");
        WorldDraft world = new WorldDraft("Glass Coast", List.of("The lighthouse"),
                List.of("Mira"), List.of("A signal returns at dusk"), photo,
                "We watched the storm roll in from the cliffs.", "The sea remembers.", List.of("5:4", "2.39:1"));
        WorldStore store = new WorldStore(temporaryDirectory.resolve("worlds.json"));

        store.save(List.of(world));
        WorldDraft reopened = store.load().getFirst();

        assertEquals("Glass Coast", reopened.name());
        assertEquals(List.of("The lighthouse"), reopened.places());
        assertEquals(List.of("Mira"), reopened.characters());
        assertEquals(List.of("A signal returns at dusk"), reopened.stories());
        assertEquals(photo.toAbsolutePath().normalize(), reopened.memoryPhoto());
        assertEquals("We watched the storm roll in from the cliffs.", reopened.memoryStory());
        assertEquals("The sea remembers.", reopened.memoryQuote());
        assertEquals(List.of("5:4", "2.39:1"), reopened.customScreenRatios());
    }

    @Test
    void worldEditsNotifyTheDashboardToPersistChanges() {
        WorldDraft world = new WorldDraft("Night Garden");
        AtomicInteger changes = new AtomicInteger();
        world.setOnChange(changes::incrementAndGet);

        world.places().add("Moonlit bridge");
        world.characters().add("Keeper");
        world.stories().add("A door opens");
        world.setMemoryStory("A quiet night.");
        world.setMemoryQuote("Stay curious.");
        world.setMemoryPhoto(temporaryDirectory.resolve("photo.png"));
        world.addCustomScreenRatio("4:3");

        assertEquals(7, changes.get());
    }

    @Test
    void damagedStoreIsNotSilentlyOverwritten() throws IOException {
        Path file = temporaryDirectory.resolve("worlds.json");
        String damagedData = "{broken saved worlds";
        Files.writeString(file, damagedData);
        WorldStore store = new WorldStore(file);

        assertThrows(IOException.class, store::load);
        assertThrows(IOException.class, () -> store.save(List.of(new WorldDraft("A new world"))));
        assertEquals(damagedData, Files.readString(file));
    }

    @Test
    void duplicateWorldNamesAreRejected() throws IOException {
        WorldStore store = new WorldStore(temporaryDirectory.resolve("worlds.json"));

        assertThrows(IllegalArgumentException.class,
                () -> store.save(List.of(new WorldDraft("The Coast"), new WorldDraft("the coast"))));
        assertTrue(store.load().isEmpty());
    }
}
