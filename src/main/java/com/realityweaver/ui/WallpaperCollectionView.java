package com.realityweaver.ui;

import com.realityweaver.util.AppPreferences;
import com.realityweaver.wallpaper.WallpaperAutomationService;
import com.realityweaver.wallpaper.WallpaperCollection;
import com.realityweaver.wallpaper.WallpaperCollectionExchange;
import com.realityweaver.wallpaper.WallpaperCollectionStore;
import com.realityweaver.wallpaper.WallpaperLibrary;
import com.realityweaver.wallpaper.WallpaperCyclePlanner;
import com.realityweaver.wallpaper.WallpaperCycleStateStore;
import com.realityweaver.wallpaper.WallpaperService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

/** Named collection management, cycling, and safe local collection sharing. */
public final class WallpaperCollectionView extends BorderPane implements AutoCloseable {
    private final WallpaperLibrary library;
    private final WallpaperCollectionStore store = new WallpaperCollectionStore();
    private final WallpaperCollectionExchange exchange;
    private final WallpaperAutomationService automationService;
    private final WallpaperService wallpaperService = new WallpaperService();
    private final Preferences preferences = AppPreferences.forPackage(WallpaperCollectionView.class);
    private final WallpaperCycleStateStore cycleState = new WallpaperCycleStateStore(preferences);
    private final ScheduledExecutorService cycleScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "realityweaver-collection-cycle");
        thread.setDaemon(true);
        return thread;
    });
    private final VBox collectionsList = new VBox(8);
    private final VBox wallpaperList = new VBox(8);
    private final ComboBox<Path> libraryChoice = new ComboBox<>();
    private final ComboBox<Integer> cycleInterval = new ComboBox<>();
    private final Label selectedName = new Label("Choose a collection");
    private final Label collectionDetails = new Label("Create a collection to group wallpapers for a mood, place, or game.");
    private final Label status = new Label();
    private final Button startCycleButton = new Button("Start cycling");
    private final Button sortButton = new Button("Sort: Added order");

    private volatile String selectedCollectionId;
    private volatile ScheduledFuture<?> cycleTask;
    private boolean sortedByName;

    public WallpaperCollectionView(WallpaperLibrary library, WallpaperAutomationService automationService) {
        this.library = library;
        this.automationService = automationService;
        exchange = new WallpaperCollectionExchange(store, library);
        getStyleClass().add("collection-page");
        setCenter(createContent());
        refreshLibrary();
        refreshCollections();
        configureCycleControls();
    }

    public void activate() {
        refreshLibrary();
        refreshCollections();
        restoreSavedCycle();
    }

    public void refreshLibrary() {
        Path previous = libraryChoice.getValue();
        List<Path> images = library.load();
        libraryChoice.getItems().setAll(images);
        if (previous != null && images.contains(previous)) {
            libraryChoice.setValue(previous);
        } else {
            libraryChoice.setValue(images.isEmpty() ? null : images.get(0));
        }
        libraryChoice.setDisable(images.isEmpty());
    }

    private Node createContent() {
        Label title = new Label("Your wallpaper collections");
        title.getStyleClass().add("section-title");
        Label subtitle = new Label("Group wallpapers into named sets, share them locally, or cycle through a collection.");
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);

        Button create = button("＋ New collection", this::createCollection);
        Button rename = button("Rename", this::renameCollection);
        Button delete = button("Delete", this::deleteCollection);
        HBox collectionActions = new HBox(8, create, rename, delete);
        collectionActions.setAlignment(Pos.CENTER_LEFT);
        collectionsList.getStyleClass().add("collection-list");
        ScrollPane collectionScroll = new ScrollPane(collectionsList);
        collectionScroll.setFitToWidth(true);
        collectionScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        collectionScroll.getStyleClass().add("gallery-scroll");
        VBox left = new VBox(12, collectionActions, collectionScroll);
        left.setPadding(new Insets(14));
        left.setMinWidth(290);
        left.getStyleClass().add("collection-side-panel");
        VBox.setVgrow(collectionScroll, Priority.ALWAYS);

        selectedName.getStyleClass().add("section-title");
        collectionDetails.getStyleClass().add("automation-status");
        collectionDetails.setWrapText(true);
        status.getStyleClass().add("automation-status");

        libraryChoice.setPromptText("Choose from My library");
        libraryChoice.setMaxWidth(Double.MAX_VALUE);
        libraryChoice.getStyleClass().add("automation-wallpaper-choice");
        libraryChoice.setConverter(new StringConverter<>() {
            @Override
            public String toString(Path path) {
                return path == null ? "" : path.getFileName().toString();
            }

            @Override
            public Path fromString(String value) {
                return null;
            }
        });
        Button addWallpaper = button("＋ Add from library", this::addWallpaper);
        Button importLocal = button("📁 Import local images", this::importLocalImages);
        HBox addRow = new HBox(8, libraryChoice, addWallpaper, importLocal);
        addRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(libraryChoice, Priority.ALWAYS);

        Button export = button("Export", this::exportCollection);
        Button importCollection = button("Import", this::importCollection);
        Button shuffleApply = button("Shuffle and apply", this::shuffleAndApply);
        sortButton.getStyleClass().add("quiet-button");
        sortButton.setOnAction(event -> {
            sortedByName = !sortedByName;
            sortButton.setText(sortedByName ? "Sort: Name" : "Sort: Added order");
            renderSelectedCollection();
        });
        HBox actionRow = new HBox(8, export, importCollection, sortButton, shuffleApply);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        cycleInterval.getItems().setAll(1, 2, 5, 10, 15, 30, 60, 120, 360, 720, 1440);
        cycleInterval.setValue(1);
        cycleInterval.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer minutes) {
                if (minutes == null) {
                    return "";
                }
                if (minutes < 60) {
                    return minutes + " minute" + (minutes == 1 ? "" : "s");
                }
                int hours = minutes / 60;
                return hours + " hour" + (hours == 1 ? "" : "s");
            }

            @Override
            public Integer fromString(String value) {
                return null;
            }
        });
        cycleInterval.getStyleClass().add("automation-wallpaper-choice");
        cycleInterval.valueProperty().addListener((observable, previous, minutes) -> {
            if (cycleTask != null && minutes != null) {
                WallpaperCollection selected = selectedCollection();
                if (selected != null) {
                    cycleState.saveCycle(selected.id(), minutes);
                    scheduleCycle(selected.id(), selected.name(), minutes);
                }
            }
        });
        startCycleButton.getStyleClass().add("quiet-button");
        startCycleButton.setOnAction(event -> {
            if (cycleTask == null) {
                startCycling();
            } else {
                stopCycling(true);
            }
        });
        HBox cycleRow = new HBox(8, new Label("Change every"), cycleInterval, startCycleButton);
        cycleRow.setAlignment(Pos.CENTER_LEFT);
        wallpaperList.getStyleClass().add("collection-wallpaper-list");
        ScrollPane wallpaperScroll = new ScrollPane(wallpaperList);
        wallpaperScroll.setFitToWidth(true);
        wallpaperScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        wallpaperScroll.getStyleClass().add("gallery-scroll");
        VBox right = new VBox(12, selectedName, collectionDetails, addRow, actionRow, cycleRow, status,
                wallpaperScroll);
        right.setPadding(new Insets(18));
        right.setMinWidth(430);
        right.getStyleClass().add("wallpaper-preview-panel");
        VBox.setVgrow(wallpaperScroll, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.30);
        split.getStyleClass().add("wallpaper-workspace-split");
        VBox content = new VBox(14, title, subtitle, split);
        content.setPadding(new Insets(20));
        VBox.setVgrow(split, Priority.ALWAYS);
        ScrollPane page = new ScrollPane(content);
        page.setFitToWidth(true);
        page.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        page.getStyleClass().add("automation-scroll");
        return page;
    }

    private void configureCycleControls() {
        WallpaperCycleStateStore.Cycle saved = cycleState.savedCycle();
        if (saved != null) {
            if (cycleInterval.getItems().contains(saved.intervalMinutes())) {
                cycleInterval.setValue(saved.intervalMinutes());
            }
            selectedCollectionId = saved.collectionId();
        }
    }

    private void refreshCollections() {
        String previousId = selectedCollectionId;
        List<WallpaperCollection> collections;
        try {
            collections = store.list();
        } catch (IOException failure) {
            status.setText("Collections could not be loaded: " + safeMessage(failure));
            collectionsList.getChildren().setAll(emptyLabel("Collection data could not be read safely."));
            return;
        }
        collectionsList.getChildren().clear();
        for (WallpaperCollection collection : collections) {
            Button row = new Button();
            Label name = new Label(collection.name());
            name.getStyleClass().add("wallpaper-item-name");
            Label count = new Label(collection.wallpapers().size() + " wallpapers");
            count.getStyleClass().add("wallpaper-item-category-tag");
            VBox copy = new VBox(4, name, count);
            HBox.setHgrow(copy, Priority.ALWAYS);
            Label arrow = new Label("›");
            arrow.getStyleClass().add("world-card-arrow");
            HBox content = new HBox(10, copy, arrow);
            content.setAlignment(Pos.CENTER_LEFT);
            row.setGraphic(content);
            row.setUserData(collection.id());
            row.setMaxWidth(Double.MAX_VALUE);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("collection-row");
            if (collection.id().equals(previousId)) {
                row.getStyleClass().add("collection-row-selected");
            }
            row.setOnAction(event -> selectCollection(collection));
            collectionsList.getChildren().add(row);
        }
        if (collections.isEmpty()) {
            selectedCollectionId = null;
            collectionsList.getChildren().add(emptyLabel("No collections yet. Create one to get started."));
            renderSelectedCollection();
        } else {
            WallpaperCollection selected = collections.stream()
                    .filter(collection -> collection.id().equals(previousId)).findFirst().orElse(collections.get(0));
            selectCollection(selected);
        }
    }

    private void selectCollection(WallpaperCollection collection) {
        selectedCollectionId = collection.id();
        refreshCollectionsSelectionStyle(collection.id());
        renderSelectedCollection();
    }

    private void refreshCollectionsSelectionStyle(String id) {
        for (Node node : collectionsList.getChildren()) {
            if (node instanceof Button button) {
                button.getStyleClass().remove("collection-row-selected");
                if (id.equals(button.getUserData())) {
                    button.getStyleClass().add("collection-row-selected");
                }
            }
        }
    }

    private void renderSelectedCollection() {
        wallpaperList.getChildren().clear();
        WallpaperCollection collection = selectedCollection();
        if (collection == null) {
            selectedName.setText("Choose a collection");
            collectionDetails.setText("Create a collection to group wallpapers for a mood, place, or game.");
            wallpaperList.getChildren().add(emptyLabel("Create or import a collection to see its wallpapers here."));
            return;
        }
        selectedName.setText(collection.name());
        int available = (int) collection.wallpapers().stream().filter(WallpaperCollection.WallpaperReference::isAvailable)
                .count();
        collectionDetails.setText(collection.wallpapers().size() + " wallpapers · " + available + " available on this PC");
        List<WallpaperCollection.WallpaperReference> references = new ArrayList<>(collection.wallpapers());
        if (sortedByName) {
            references.sort(Comparator.comparing(WallpaperCollection.WallpaperReference::title,
                    String.CASE_INSENSITIVE_ORDER));
        }
        if (references.isEmpty()) {
            wallpaperList.getChildren().add(emptyLabel("This collection is empty. Add wallpapers from My library or import local images."));
        } else {
            for (WallpaperCollection.WallpaperReference reference : references) {
                wallpaperList.getChildren().add(createWallpaperRow(collection, reference));
            }
        }
    }

    private Node createWallpaperRow(WallpaperCollection collection,
            WallpaperCollection.WallpaperReference reference) {
        Node thumbnail;
        Path imagePath = reference.path();
        if (imagePath != null && Files.isRegularFile(imagePath)) {
            Image image = new Image(imagePath.toUri().toString(), 96, 60, true, true, true);
            ImageView view = new ImageView(image);
            view.setFitWidth(86);
            view.setFitHeight(56);
            view.setPreserveRatio(true);
            StackPane frame = new StackPane(view);
            frame.getStyleClass().add("wallpaper-thumbnail");
            thumbnail = frame;
        } else {
            Label missing = new Label("Missing");
            missing.getStyleClass().add("wallpaper-item-category-tag");
            StackPane frame = new StackPane(missing);
            frame.setMinSize(86, 56);
            frame.getStyleClass().add("wallpaper-thumbnail");
            thumbnail = frame;
        }
        Label title = new Label(reference.title());
        title.setWrapText(true);
        title.getStyleClass().add("wallpaper-item-name");
        String detail = reference.sourceName().isBlank() ? "Local wallpaper" : reference.sourceName();
        if (!reference.license().isBlank()) {
            detail += " · " + reference.license();
        }
        Label metadata = new Label(imagePath == null || !Files.isRegularFile(imagePath) ? "File unavailable" : detail);
        metadata.setWrapText(true);
        metadata.getStyleClass().add("wallpaper-item-category-tag");
        VBox copy = new VBox(4, title, metadata);
        HBox.setHgrow(copy, Priority.ALWAYS);
        Button apply = new Button("Apply");
        apply.getStyleClass().add("quiet-button");
        apply.setDisable(imagePath == null || !Files.isRegularFile(imagePath));
        apply.setOnAction(event -> applyManually(imagePath));
        Button remove = new Button("Remove");
        remove.getStyleClass().add("automation-rule-remove");
        remove.setAccessibleText("Remove wallpaper from this collection");
        remove.setOnAction(event -> {
            try {
                store.removeWallpaper(collection.id(), reference.id());
                renderSelectedCollection();
                refreshCollections();
                showStatus("Removed from collection (original local image file preserved).");
            } catch (IOException | RuntimeException failure) {
                showStatus("Wallpaper could not be removed from the collection: " + safeMessage(failure));
            }
        });
        HBox row = new HBox(10, thumbnail, copy, apply, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8));
        row.getStyleClass().add("collection-wallpaper-row");
        return row;
    }

    private void createCollection() {
        TextInputDialog dialog = textDialog("New wallpaper collection", "Name your collection", "e.g. Cozy evenings");
        dialog.showAndWait().map(String::strip).filter(name -> !name.isBlank()).ifPresent(name -> {
            try {
                WallpaperCollection collection = store.create(name);
                selectCollection(collection);
                refreshCollections();
                showStatus("Collection created.");
            } catch (IOException | RuntimeException failure) {
                showStatus("Collection was not created: " + safeMessage(failure));
            }
        });
    }

    private void renameCollection() {
        WallpaperCollection collection = selectedCollection();
        if (collection == null) {
            showStatus("Choose a collection to rename.");
            return;
        }
        TextInputDialog dialog = textDialog("Rename collection", "Choose a new name", collection.name());
        dialog.getEditor().setText(collection.name());
        dialog.showAndWait().map(String::strip).filter(name -> !name.isBlank()).ifPresent(name -> {
            try {
                store.rename(collection.id(), name);
                refreshCollections();
                showStatus("Collection renamed.");
            } catch (IOException | RuntimeException failure) {
                showStatus("Collection was not renamed: " + safeMessage(failure));
            }
        });
    }

    private void deleteCollection() {
        WallpaperCollection collection = selectedCollection();
        if (collection == null) {
            showStatus("Choose a collection to delete.");
            return;
        }
        Alert confirmation = alert(Alert.AlertType.CONFIRMATION, "Delete collection?",
                "This removes “" + collection.name() + "” and its wallpaper references. Local image files stay in your library.");
        if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        try {
            store.delete(collection.id());
            WallpaperCycleStateStore.Cycle saved = cycleState.savedCycle();
            if (saved != null && collection.id().equals(saved.collectionId())) {
                stopCycling(true);
            }
            refreshCollections();
            showStatus("Collection deleted. Its image files remain in your library.");
        } catch (IOException | RuntimeException failure) {
            showStatus("Collection was not deleted: " + safeMessage(failure));
        }
    }

    private void addWallpaper() {
        WallpaperCollection collection = selectedCollection();
        Path path = libraryChoice.getValue();
        if (collection == null || path == null) {
            showStatus("Choose a collection and a wallpaper from My library first.");
            return;
        }
        try {
            WallpaperCollection updated = store.addWallpaper(collection.id(), path, library);
            selectCollection(updated);
            refreshCollections();
            showStatus(updated.wallpapers().size() == collection.wallpapers().size()
                    ? "That wallpaper is already in this collection." : "Wallpaper added to the collection.");
        } catch (IOException | RuntimeException failure) {
            showStatus("Wallpaper was not added: " + safeMessage(failure));
        }
    }

    private void importLocalImages() {
        WallpaperCollection collection = selectedCollection();
        if (collection == null) {
            showStatus("Choose or create a collection first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import local wallpaper images into “" + collection.name() + "”");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Wallpaper Images (*.png, *.jpg, *.jpeg, *.bmp)", "*.png", "*.jpg", "*.jpeg", "*.bmp"));
        List<File> files = chooser.showOpenMultipleDialog(getScene() != null ? getScene().getWindow() : null);
        if (files == null || files.isEmpty()) {
            return;
        }
        int added = 0;
        for (File file : files) {
            Path path = file.toPath().toAbsolutePath().normalize();
            library.add(path);
            try {
                store.addWallpaper(collection.id(), path, library);
                added++;
            } catch (Exception ignored) { }
        }
        refreshLibrary();
        refreshCollections();
        renderSelectedCollection();
        showStatus("Imported " + added + " image" + (added == 1 ? "" : "s") + " into “" + collection.name() + "”.");
    }

    private void exportCollection() {
        WallpaperCollection collection = selectedCollection();
        if (collection == null) {
            showStatus("Choose a collection to export.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export wallpaper collection");
        chooser.setInitialFileName(safeExportName(collection.name()) + ".rwcollection");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("RealityWeaver archive (*.rwcollection)", "*.rwcollection"),
                new FileChooser.ExtensionFilter("JSON manifest (*.json)", "*.json"));
        File destination = chooser.showSaveDialog(getScene().getWindow());
        if (destination == null) {
            return;
        }
        Path path = destination.toPath().toAbsolutePath().normalize();
        try {
            if (path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
                exchange.exportJson(collection.id(), path);
                showStatus("JSON collection manifest exported. Local file references may only work on this PC.");
            } else {
                ButtonType includeImages = new ButtonType("Include local images", ButtonBar.ButtonData.YES);
                ButtonType manifestOnly = new ButtonType("Manifest only", ButtonBar.ButtonData.NO);
                Alert choice = alert(Alert.AlertType.CONFIRMATION, "Include image files?",
                        "Only include images you own or are permitted to redistribute. A manifest can be shared without copying image files.");
                choice.getButtonTypes().setAll(includeImages, manifestOnly, ButtonType.CANCEL);
                ButtonType selected = choice.showAndWait().orElse(ButtonType.CANCEL);
                if (selected == ButtonType.CANCEL) {
                    return;
                }
                exchange.exportArchive(collection.id(), path, selected == includeImages);
                showStatus("Collection archive exported.");
            }
        } catch (IOException | RuntimeException failure) {
            showStatus("Collection export failed: " + safeMessage(failure));
        }
    }

    private void importCollection() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import a wallpaper collection");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "RealityWeaver files", "*.rwcollection", "*.zip", "*.json"));
        File source = chooser.showOpenDialog(getScene().getWindow());
        if (source == null) {
            return;
        }
        try {
            WallpaperCollection imported = exchange.importFile(source.toPath());
            refreshLibrary();
            refreshCollections();
            selectCollection(imported);
            showStatus("Imported “" + imported.name() + "” with " + imported.wallpapers().size() + " wallpapers.");
        } catch (IOException | RuntimeException failure) {
            showStatus("Collection import failed: " + safeMessage(failure));
        }
    }

    private void shuffleAndApply() {
        WallpaperCollection collection = selectedCollection();
        if (collection == null) {
            showStatus("Choose a collection first.");
            return;
        }
        List<Path> available = collection.wallpapers().stream().map(WallpaperCollection.WallpaperReference::path)
                .filter(path -> path != null && Files.isRegularFile(path)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (available.isEmpty()) {
            showStatus("This collection has no available image files on this PC.");
            return;
        }
        java.util.Collections.shuffle(available);
        applyManually(available.getFirst());
    }

    private void startCycling() {
        WallpaperCollection collection = selectedCollection();
        if (collection == null) {
            showStatus("Choose a collection to cycle.");
            return;
        }
        if (!automationService.isEnabled()) {
            automationService.setEnabled(true, ignored -> { });
        }
        automationService.resumeAutomation();

        List<WallpaperCollection.WallpaperReference> available = collection.wallpapers().stream()
                .filter(WallpaperCollection.WallpaperReference::isAvailable).toList();
        if (available.isEmpty()) {
            showStatus("Add at least one available wallpaper to cycle through this collection.");
            return;
        }
        int minutes = cycleInterval.getValue() == null ? 1 : cycleInterval.getValue();
        cycleState.saveCycle(collection.id(), minutes);
        scheduleCycle(collection.id(), collection.name(), minutes);
    }

    private void restoreSavedCycle() {
        if (cycleTask != null) {
            return;
        }
        WallpaperCycleStateStore.Cycle saved = cycleState.savedCycle();
        if (saved == null) {
            return;
        }
        try {
            WallpaperCollection collection = store.find(saved.collectionId());
            if (collection != null && collection.wallpapers().stream()
                    .anyMatch(WallpaperCollection.WallpaperReference::isAvailable)) {
                selectedCollectionId = collection.id();
                scheduleCycle(collection.id(), collection.name(), saved.intervalMinutes());
                renderSelectedCollection();
            }
        } catch (IOException failure) {
            showStatus("Saved collection cycling could not be restored: " + safeMessage(failure));
        }
    }

    private void scheduleCycle(String collectionId, String collectionName, int minutes) {
        ScheduledFuture<?> previous = cycleTask;
        if (previous != null) {
            previous.cancel(false);
        }
        cycleInterval.setValue(minutes);
        startCycleButton.setText("Stop cycling");

        // Immediately trigger the first cycle change so the user sees it work instantly!
        cycleScheduler.execute(() -> cycleNext(collectionId, collectionName));

        cycleTask = cycleScheduler.scheduleWithFixedDelay(() -> cycleNext(collectionId, collectionName),
                minutes, minutes, TimeUnit.MINUTES);
        showStatus("Cycling active: changing “" + collectionName + "” every " + minutes + (minutes == 1 ? " minute." : " minutes."));
    }

    private void cycleNext(String collectionId, String collectionName) {
        try {
            WallpaperCollection collection = store.find(collectionId);
            if (collection == null) {
                Platform.runLater(() -> {
                    stopCycling(true);
                    showStatus("The cycled collection is no longer available.");
                });
                return;
            }
            List<Path> available = collection.wallpapers().stream()
                    .map(WallpaperCollection.WallpaperReference::path)
                    .filter(path -> path != null && Files.isRegularFile(path)).toList();
            if (available.isEmpty()) {
                showStatus("Cycling paused: no available collection images found.");
                return;
            }
            Path chosen;
            List<String> nextHistory = List.of();
            if (available.size() == 1) {
                chosen = available.get(0);
            } else {
                WallpaperCyclePlanner.Selection choice = WallpaperCyclePlanner.next(available,
                        cycleState.recentIds(collectionId), library::stableId,
                        java.util.concurrent.ThreadLocalRandom.current());
                chosen = choice.path();
                nextHistory = choice.recentIds();
            }

            wallpaperService.apply(chosen);
            if (!nextHistory.isEmpty()) {
                cycleState.saveRecentIds(collectionId, nextHistory);
            }
            String fileName = chosen.getFileName().toString();
            int mins = cycleInterval.getValue() == null ? 1 : cycleInterval.getValue();
            showStatus("Cycling active: applied “" + fileName + "”. Next rotation in "
                    + mins + (mins == 1 ? " minute." : " minutes."));
        } catch (IOException | RuntimeException failure) {
            showStatus("Collection cycling failed: " + safeMessage(failure));
        }
    }

    private void stopCycling(boolean clearSaved) {
        ScheduledFuture<?> current = cycleTask;
        if (current != null) {
            current.cancel(false);
            cycleTask = null;
        }
        startCycleButton.setText("Start cycling");
        if (clearSaved) {
            cycleState.clearCycle();
        }
        showStatus("Cycling stopped.");
    }

    private void applyManually(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            showStatus("The selected wallpaper is missing from this PC.");
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                wallpaperService.apply(path);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            automationService.recordManualOverride();
            showStatus("Wallpaper applied. Scheduled changes pause for one hour.");
        });
        task.setOnFailed(event -> showStatus("Wallpaper could not be applied: " + safeMessage(task.getException())));
        Thread worker = new Thread(task, "realityweaver-collection-wallpaper-apply");
        worker.setDaemon(true);
        worker.start();
    }

    private WallpaperCollection selectedCollection() {
        try {
            return selectedCollectionId == null ? null : store.find(selectedCollectionId);
        } catch (IOException failure) {
            showStatus("Collection data could not be read: " + safeMessage(failure));
            return null;
        }
    }

    private TextInputDialog textDialog(String title, String header, String prompt) {
        TextInputDialog dialog = new TextInputDialog();
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText("Collection name:");
        dialog.getEditor().setPromptText(prompt);
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().add(WallpaperCollectionView.class.getResource("/styles/app.css").toExternalForm());
        pane.getStyleClass().add("world-dialog");
        return dialog;
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("quiet-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private Label emptyLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("gallery-empty");
        return label;
    }

    private void showStatus(String message) {
        Runnable update = () -> status.setText(message);
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    private String safeExportName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-").strip();
    }

    private String safeMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Check the collection details and try again." : failure.getMessage();
    }

    private Alert alert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStylesheets().add(
                WallpaperCollectionView.class.getResource("/styles/app.css").toExternalForm());
        alert.getDialogPane().getStyleClass().add("world-dialog");
        return alert;
    }

    @Override
    public void close() {
        stopCycling(false);
        cycleScheduler.shutdownNow();
    }
}
