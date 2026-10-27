package com.realityweaver.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

/** Editable world content that is persisted by the local world store. */
final class WorldDraft {
    private final String name;
    private final ObservableList<String> places = FXCollections.observableArrayList();
    private final ObservableList<String> characters = FXCollections.observableArrayList();
    private final ObservableList<String> stories = FXCollections.observableArrayList();
    private final List<String> customScreenRatios = new ArrayList<>();
    private Runnable onChange = () -> { };
    private Path memoryPhoto;
    private String memoryStory = "";
    private String memoryQuote = "";

    WorldDraft(String name) {
        this(name, List.of(), List.of(), List.of(), null, "", "", List.of());
    }

    WorldDraft(String name, List<String> places, List<String> characters, List<String> stories,
            Path memoryPhoto, String memoryStory, String memoryQuote, List<String> customScreenRatios) {
        this.name = name;
        this.places.setAll(places);
        this.characters.setAll(characters);
        this.stories.setAll(stories);
        this.memoryPhoto = memoryPhoto;
        this.memoryStory = memoryStory == null ? "" : memoryStory;
        this.memoryQuote = memoryQuote == null ? "" : memoryQuote;
        this.customScreenRatios.addAll(customScreenRatios);
        this.places.addListener((ListChangeListener<String>) change -> onChange.run());
        this.characters.addListener((ListChangeListener<String>) change -> onChange.run());
        this.stories.addListener((ListChangeListener<String>) change -> onChange.run());
    }

    String name() {
        return name;
    }

    ObservableList<String> places() {
        return places;
    }

    ObservableList<String> characters() {
        return characters;
    }

    ObservableList<String> stories() {
        return stories;
    }

    List<String> customScreenRatios() {
        return List.copyOf(customScreenRatios);
    }

    void addCustomScreenRatio(String ratio) {
        String normalized = ratio == null ? "" : ratio.strip();
        customScreenRatios.removeIf(existing -> existing.equalsIgnoreCase(normalized));
        if (!normalized.isBlank()) {
            customScreenRatios.add(normalized);
            onChange.run();
        }
    }

    Path memoryPhoto() {
        return memoryPhoto;
    }

    void setMemoryPhoto(Path memoryPhoto) {
        this.memoryPhoto = memoryPhoto == null ? null : memoryPhoto.toAbsolutePath().normalize();
        onChange.run();
    }

    String memoryStory() {
        return memoryStory;
    }

    void setMemoryStory(String memoryStory) {
        this.memoryStory = memoryStory == null ? "" : memoryStory;
        onChange.run();
    }

    String memoryQuote() {
        return memoryQuote;
    }

    void setMemoryQuote(String memoryQuote) {
        this.memoryQuote = memoryQuote == null ? "" : memoryQuote;
        onChange.run();
    }

    void setOnChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> { } : onChange;
    }
}
