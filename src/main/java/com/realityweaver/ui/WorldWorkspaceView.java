package com.realityweaver.ui;

import java.util.Optional;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Workspace for adding the places, characters, and story ideas in a world. */
public final class WorldWorkspaceView extends ScrollPane {
    private final WorldDraft world;
    private final Runnable onBack;

    public WorldWorkspaceView(WorldDraft world, Runnable onBack) {
        this.world = world;
        this.onBack = onBack;

        VBox content = new VBox(26, createHeader(), createSections());
        content.setPadding(new Insets(38, 48, 52, 48));
        content.setMaxWidth(1160);
        content.setFillWidth(true);

        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().add("content-scroll");
    }

    private VBox createHeader() {
        Button backButton = new Button("←  All worlds");
        backButton.getStyleClass().add("world-back-button");
        backButton.setOnAction(event -> onBack.run());

        Label eyebrow = new Label("WORLD WORKSPACE");
        eyebrow.getStyleClass().add("eyebrow");

        Label title = new Label(world.name());
        title.getStyleClass().add("page-title");
        title.setWrapText(true);

        Label subtitle = new Label(
                "Your world is ready to take shape. Add the places, people, and story ideas that belong here.");
        subtitle.setWrapText(true);
        subtitle.getStyleClass().add("page-subtitle");

        VBox heading = new VBox(8, eyebrow, title, subtitle);
        heading.setMaxWidth(760);
        return new VBox(18, backButton, heading);
    }

    private HBox createSections() {
        HBox sections = new HBox(16,
                createSection("Places", "Place", "Settings, landmarks, and hidden corners.", "⌖", world.places()),
                createSection("Characters", "Character", "People and creatures who live here.", "✦", world.characters()),
                createSection("Stories", "Story idea", "Mysteries, conflicts, and moments to explore.", "✧", world.stories()));
        sections.setAlignment(Pos.TOP_LEFT);
        return sections;
    }

    private VBox createSection(
            String title,
            String itemName,
            String description,
            String iconText,
            ObservableList<String> items) {
        Label icon = new Label(iconText);
        icon.getStyleClass().add("world-section-icon");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("section-title");

        Label count = new Label();
        count.getStyleClass().add("count-label");
        HBox titleRow = new HBox(10, icon, titleLabel, count);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);

        Label descriptionLabel = new Label(description);
        descriptionLabel.setWrapText(true);
        descriptionLabel.getStyleClass().add("world-section-description");

        VBox itemList = new VBox(9);
        itemList.getStyleClass().add("world-item-list");

        Button addButton = new Button("＋  Add " + itemName.toLowerCase());
        addButton.getStyleClass().add("quiet-button");
        addButton.setMaxWidth(Double.MAX_VALUE);
        addButton.setOnAction(event -> promptForItem(itemName, items));

        VBox section = new VBox(16, titleRow, descriptionLabel, itemList, addButton);
        section.setPadding(new Insets(20));
        section.setMinWidth(0);
        section.setMaxWidth(Double.MAX_VALUE);
        section.getStyleClass().add("world-section-card");
        HBox.setHgrow(section, Priority.ALWAYS);

        Runnable renderItems = () -> {
            count.setText(items.size() == 1 ? "1" : Integer.toString(items.size()));
            itemList.getChildren().clear();
            if (items.isEmpty()) {
                Label empty = new Label("Nothing here yet. Start with one idea.");
                empty.setWrapText(true);
                empty.getStyleClass().add("world-item-empty");
                itemList.getChildren().add(empty);
                return;
            }

            for (String item : items) {
                itemList.getChildren().add(createItemRow(item, items));
            }
        };
        items.addListener((ListChangeListener<String>) change -> renderItems.run());
        renderItems.run();
        return section;
    }

    private HBox createItemRow(String item, ObservableList<String> items) {
        Label itemLabel = new Label(item);
        itemLabel.setWrapText(true);
        itemLabel.getStyleClass().add("world-item-name");
        HBox.setHgrow(itemLabel, Priority.ALWAYS);

        Button removeButton = new Button("×");
        removeButton.getStyleClass().add("world-item-remove");
        removeButton.setAccessibleText("Remove " + item);
        removeButton.setOnAction(event -> items.remove(item));

        HBox row = new HBox(10, itemLabel, removeButton);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));
        row.getStyleClass().add("world-item-row");
        return row;
    }

    private void promptForItem(String itemName, ObservableList<String> items) {
        TextInputDialog dialog = new TextInputDialog();
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.setTitle("Add " + itemName.toLowerCase());
        dialog.setHeaderText("Add to " + world.name());
        dialog.setContentText("What would you like to add?");
        dialog.getEditor().setPromptText("Write a name or a short idea");

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getStylesheets().add(
                WorldWorkspaceView.class.getResource("/styles/app.css").toExternalForm());
        dialogPane.getStyleClass().add("world-dialog");

        Optional<String> result = dialog.showAndWait();
        result.map(String::strip)
                .filter(value -> !value.isBlank())
                .ifPresent(items::add);
    }
}
