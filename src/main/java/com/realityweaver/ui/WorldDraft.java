package com.realityweaver.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** In-memory draft content for a world created during the current app session. */
final class WorldDraft {
    private final String name;
    private final ObservableList<String> places = FXCollections.observableArrayList();
    private final ObservableList<String> characters = FXCollections.observableArrayList();
    private final ObservableList<String> stories = FXCollections.observableArrayList();

    WorldDraft(String name) {
        this.name = name;
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
}
