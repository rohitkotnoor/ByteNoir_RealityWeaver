package com.realityweaver.ui;

import com.realityweaver.AppSettings;
import com.realityweaver.wallpaper.OnlineWallpaper;
import com.realityweaver.wallpaper.WallpaperLibrary;
import com.realityweaver.wallpaper.WallpaperSearchService;
import com.realityweaver.wallpaper.WallpaperService;
import com.realityweaver.wallpaper.WallpaperSource;
import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Searches multiple image catalogs and saves selected results into the local wallpaper library. */
public final class OnlineWallpaperView extends VBox {
    private static final List<String> SEARCH_CATEGORIES = List.of(
            "Nature", "City", "Architecture", "Space", "Fantasy", "Ocean", "Forest", "Desert");

    private final WallpaperSearchService searchService = new WallpaperSearchService();
    private final WallpaperLibrary library = new WallpaperLibrary();
    private final WallpaperService wallpaperService;
    private final Runnable onManualApplied;
    private final Consumer<Path> onSaved;
    private final ExecutorService requests = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "realityweaver-wallpaper-search");
        thread.setDaemon(true);
        return thread;
    });

    private final TextField searchField = new TextField();
    private final ComboBox<WallpaperSource> sourceSelector = new ComboBox<>();
    private final ComboBox<String> searchCategory = new ComboBox<>();
    private final ComboBox<String> orientationFilter = new ComboBox<>();
    private final Button searchButton = new Button("Search");
    private final Button trendingButton = new Button("Trending");
    private final Label statusLabel = new Label("Search for a destination or topic, or browse trending wallpapers.");
    private final Label resultCount = new Label();
    private final FlowPane resultGrid = new FlowPane(10, 10);
    private final StackPane previewFrame = new StackPane();
    private final Label selectedTitle = new Label("Choose a result");
    private final Label selectedMetadata = new Label("Creator and license details will appear here.");
    private final Hyperlink sourceLink = new Hyperlink("Open image source page ↗");
    private final Button downloadButton = new Button("Download to library");
    private final Button applyButton = new Button("Set as Wallpaper");
    private final Button loadMoreButton = new Button("Load more");

    private List<OnlineWallpaper> results = List.of();
    private OnlineWallpaper selected;
    private Task<byte[]> activePreviewTask;
    private Task<List<OnlineWallpaper>> activeSearchTask;
    private int searchGeneration;
    private int currentPage;
    private String currentQuery = "";
    private WallpaperSource currentSource = WallpaperSource.OPENVERSE;
    private boolean currentTrending;

    public OnlineWallpaperView(Consumer<Path> onSaved) {
        this(onSaved, new WallpaperService(), () -> { });
    }

    public OnlineWallpaperView(Consumer<Path> onSaved, WallpaperService wallpaperService,
            Runnable onManualApplied) {
        this.onSaved = onSaved;
        this.wallpaperService = wallpaperService;
        this.onManualApplied = onManualApplied;
        getStyleClass().add("online-wallpaper-view");
        setSpacing(14);
        getChildren().addAll(createSearchBar(), createExploreRow(), createSourceNote(), createResultsWorkspace());
        VBox.setVgrow(getChildren().get(3), Priority.ALWAYS);
        statusLabel.setWrapText(true);
        statusLabel.getStyleClass().add("online-status");
        showPreviewPlaceholder("Search to find wallpaper images");
        renderResults();
    }

    private HBox createSearchBar() {
        sourceSelector.setItems(FXCollections.observableArrayList(WallpaperSource.values()));
        sourceSelector.setValue(WallpaperSource.OPENVERSE);
        sourceSelector.setPrefWidth(205);
        sourceSelector.getStyleClass().add("online-source-selector");

        searchField.setPromptText("Try Kyoto, Iceland, mountains, fantasy city, or architecture");
        searchField.setOnAction(event -> search());
        HBox.setHgrow(searchField, Priority.ALWAYS);

        searchButton.getStyleClass().add("quiet-button");
        searchButton.setOnAction(event -> search());
        trendingButton.getStyleClass().add("quiet-button");
        trendingButton.setOnAction(event -> searchTrending());
        return new HBox(10, sourceSelector, searchField, searchButton, trendingButton);
    }

    private VBox createExploreRow() {
        FlowPane placesRow = new FlowPane(8, 8);
        placesRow.setAlignment(Pos.CENTER_LEFT);
        Label placesLabel = new Label("Places:");
        placesLabel.getStyleClass().add("online-result-details");
        placesRow.getChildren().add(placesLabel);
        for (String place : List.of("Kyoto", "Iceland", "Santorini", "Patagonia", "New York")) {
            placesRow.getChildren().add(createSuggestionButton(place));
        }

        FlowPane categoriesRow = new FlowPane(8, 8);
        categoriesRow.setAlignment(Pos.CENTER_LEFT);
        Label categoriesLabel = new Label("Categories:");
        categoriesLabel.getStyleClass().add("online-result-details");
        categoriesRow.getChildren().add(categoriesLabel);
        for (String category : SEARCH_CATEGORIES) {
            categoriesRow.getChildren().add(createSuggestionButton(category));
        }

        VBox row = new VBox(8, placesRow, categoriesRow);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button createSuggestionButton(String value) {
        Button suggestion = new Button(value);
        suggestion.getStyleClass().add("quiet-button");
        suggestion.setOnAction(event -> {
            searchField.setText(value);
            search();
        });
        return suggestion;
    }

    private Label createSourceNote() {
        Label note = new Label(
                "Search Openverse, Commons, Wallhaven, NASA Images, or public-domain Art Institute works. Review each source page for reuse terms and attribution.");
        note.setWrapText(true);
        note.getStyleClass().add("commons-note");
        return note;
    }

    private HBox createResultsWorkspace() {
        Label resultsTitle = new Label("Search results");
        resultsTitle.getStyleClass().add("section-title");
        resultCount.getStyleClass().add("count-label");
        loadMoreButton.getStyleClass().add("quiet-button");
        loadMoreButton.setDisable(true);
        loadMoreButton.setOnAction(event -> {
            if (currentPage > 0) {
                runSearch(currentQuery, currentSource, currentTrending, currentPage + 1);
            }
        });
        HBox resultsHeader = new HBox(resultsTitle, resultCount, loadMoreButton);
        resultsHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(resultsTitle, Priority.ALWAYS);

        searchCategory.getItems().setAll("Any topic", "Nature", "Architecture", "Space", "Games", "Animals", "Vehicles");
        searchCategory.setValue(AppSettings.defaultWallpaperTopic());
        searchCategory.setPromptText("Topic");
        searchCategory.getStyleClass().add("online-source-selector");
        searchCategory.setOnAction(event -> {
            if (!searchField.getText().isBlank()) {
                search();
            }
        });
        orientationFilter.getItems().setAll("Any orientation", "Landscape", "Portrait", "Square");
        orientationFilter.setValue("Any orientation");
        orientationFilter.getStyleClass().add("online-source-selector");
        orientationFilter.setOnAction(event -> renderResults());
        HBox filters = new HBox(8, new Label("Topic"), searchCategory,
                new Label("Orientation"), orientationFilter);
        filters.setAlignment(Pos.CENTER_LEFT);

        resultGrid.setPrefWrapLength(760);
        resultGrid.setMaxWidth(Double.MAX_VALUE);
        resultGrid.setRowValignment(javafx.geometry.VPos.TOP);
        ScrollPane resultScroll = new ScrollPane(resultGrid);
        resultScroll.setFitToWidth(true);
        resultScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        resultScroll.setPrefHeight(385);
        resultScroll.getStyleClass().add("online-results-scroll");

        VBox resultPanel = new VBox(11, resultsHeader, filters, resultScroll, statusLabel);
        resultPanel.setMinWidth(300);
        resultPanel.getStyleClass().add("online-results-panel");
        HBox.setHgrow(resultPanel, Priority.ALWAYS);

        VBox previewPanel = createOnlinePreviewPanel();
        previewPanel.setMinWidth(280);
        previewPanel.setPrefWidth(320);

        HBox workspace = new HBox(14, resultPanel, previewPanel);
        workspace.setAlignment(Pos.TOP_LEFT);
        return workspace;
    }

    private VBox createOnlinePreviewPanel() {
        Label previewTitleLabel = new Label("Preview");
        previewTitleLabel.getStyleClass().add("section-title");

        previewFrame.setMinHeight(230);
        previewFrame.setPrefHeight(260);
        previewFrame.getStyleClass().add("wallpaper-preview");

        selectedTitle.getStyleClass().add("wallpaper-selected-name");
        selectedTitle.setWrapText(true);
        selectedMetadata.getStyleClass().add("online-metadata");
        selectedMetadata.setWrapText(true);

        sourceLink.setDisable(true);
        sourceLink.getStyleClass().add("commons-link");
        sourceLink.setOnAction(event -> openSourcePage());

        downloadButton.getStyleClass().add("primary-button");
        downloadButton.setDisable(true);
        downloadButton.setMaxWidth(Double.MAX_VALUE);
        downloadButton.setOnAction(event -> downloadSelected());
        applyButton.getStyleClass().add("quiet-button");
        applyButton.setDisable(true);
        applyButton.setMaxWidth(Double.MAX_VALUE);
        applyButton.setOnAction(event -> applySelected());

        VBox panel = new VBox(11,
                previewTitleLabel,
                previewFrame,
                selectedTitle,
                selectedMetadata,
                sourceLink,
                downloadButton,
                applyButton);
        panel.getStyleClass().add("online-preview-panel");
        return panel;
    }

    private void search() {
        String query = searchField.getText().strip();
        if (query.isBlank()) {
            statusLabel.setText("Enter a search term first.");
            return;
        }

        String category = searchCategory.getValue();
        if (category != null && !category.equals("Any topic")) {
            query += " " + category;
        }

        WallpaperSource source = sourceSelector.getValue();
        runSearch(query, source == null ? WallpaperSource.OPENVERSE : source, false, 1);
    }

    private void searchTrending() {
        sourceSelector.setValue(WallpaperSource.WALLHAVEN);
        runSearch("", WallpaperSource.WALLHAVEN, true, 1);
    }

    private void runSearch(String query, WallpaperSource source, boolean trending, int page) {
        int generation = ++searchGeneration;
        if (activeSearchTask != null) {
            activeSearchTask.cancel();
        }
        if (page == 1) {
            currentQuery = query;
            currentSource = source;
            currentTrending = trending;
            currentPage = 0;
            results = List.of();
            selected = null;
        }
        if (activePreviewTask != null) {
            activePreviewTask.cancel();
            activePreviewTask = null;
        }

        searchButton.setDisable(true);
        trendingButton.setDisable(true);
        loadMoreButton.setDisable(true);
        statusLabel.setText(trending ? "Finding this month's top wallpapers…" : "Searching " + source + "…");
        if (page == 1) {
            resultGrid.getChildren().setAll(messageRow(trending ? "Loading trending wallpapers…" : "Searching…"));
        }
        downloadButton.setDisable(true);
        applyButton.setDisable(true);
        sourceLink.setDisable(true);
        showPreviewPlaceholder(trending ? "Loading trending wallpapers" : "Loading search results");

        Task<List<OnlineWallpaper>> task = new Task<>() {
            @Override
            protected List<OnlineWallpaper> call() throws Exception {
                List<OnlineWallpaper> found = trending
                        ? searchService.trending(page)
                        : searchService.search(query, source, page);
                return found.stream().filter(OnlineWallpaper::isJavaFxImage).toList();
            }
        };
        activeSearchTask = task;
        task.setOnSucceeded(event -> {
            if (generation != searchGeneration) {
                return;
            }
            searchButton.setDisable(false);
            trendingButton.setDisable(false);
            List<OnlineWallpaper> pageResults = task.getValue();
            if (page == 1) {
                results = pageResults;
            } else {
                LinkedHashMap<java.net.URI, OnlineWallpaper> merged = new LinkedHashMap<>();
                results.forEach(result -> merged.put(result.fileUri(), result));
                pageResults.forEach(result -> merged.putIfAbsent(result.fileUri(), result));
                results = List.copyOf(merged.values());
            }
            currentPage = page;
            loadMoreButton.setDisable(pageResults.isEmpty());
            statusLabel.setText(results.isEmpty()
                    ? "No supported images found. Try another topic or source."
                    : trending ? "Wallhaven monthly toplist. Select a result to preview or apply it."
                            : page > 1 ? "Loaded more results. Select a wallpaper to preview or apply it."
                                    : "Select a result to preview or apply it.");
            renderResults();
            if (page == 1 && !results.isEmpty()) {
                selectResult(results.get(0));
            }
        });
        task.setOnFailed(event -> {
            if (generation != searchGeneration) {
                return;
            }
            searchButton.setDisable(false);
            trendingButton.setDisable(false);
            loadMoreButton.setDisable(page == 1);
            if (page == 1) {
                results = List.of();
                resultCount.setText("");
                statusLabel.setText("Search failed: " + errorMessage(task.getException()));
            } else {
                statusLabel.setText("Could not load another page: " + errorMessage(task.getException()));
            }
            renderResults();
        });
        requests.execute(task);
    }

    private void renderResults() {
        resultGrid.getChildren().clear();
        List<OnlineWallpaper> visible = results.stream()
                .filter(this::matchesOrientation)
                .toList();
        resultCount.setText(results.isEmpty() ? "" : visible.size() + " shown · " + results.size() + " loaded");
        if (results.isEmpty()) {
            resultGrid.getChildren().add(messageRow("No results yet. Search a destination or browse trending wallpapers."));
            return;
        }
        if (visible.isEmpty()) {
            resultGrid.getChildren().add(messageRow("No loaded images match this orientation. Choose another filter or load more."));
            return;
        }

        for (OnlineWallpaper wallpaper : visible) {
            ImageView imageView = new ImageView();
            if ("https".equalsIgnoreCase(wallpaper.thumbnailUri().getScheme())) {
                Image image = new Image(wallpaper.thumbnailUri().toString(), 224, 132, true, true, true);
                imageView.setImage(image);
            }
            imageView.setFitWidth(224);
            imageView.setFitHeight(132);
            imageView.setPreserveRatio(true);
            StackPane imageMark = new StackPane(imageView);
            imageMark.getStyleClass().add("online-result-mark");
            imageMark.setMinHeight(132);
            imageMark.setPrefHeight(132);
            imageMark.setMaxWidth(Double.MAX_VALUE);

            Label title = new Label(wallpaper.title());
            title.setWrapText(true);
            title.getStyleClass().add("wallpaper-item-name");
            title.setMaxHeight(42);
            String detail = wallpaper.sourceName();
            if (wallpaper.width() > 0 && wallpaper.height() > 0) {
                detail += " · " + wallpaper.width() + " × " + wallpaper.height();
            }
            if (!wallpaper.license().isBlank()) {
                detail += " · " + wallpaper.license();
            }
            Label metadata = new Label(detail);
            metadata.getStyleClass().add("online-result-details");
            metadata.setWrapText(true);
            metadata.setMaxHeight(34);
            Button preview = new Button("Preview");
            preview.getStyleClass().add("quiet-button");
            preview.setMaxWidth(Double.MAX_VALUE);
            preview.setOnAction(event -> selectResult(wallpaper));
            Button favorite = new Button("☆ Save as favorite");
            favorite.getStyleClass().add("favorite-action-button");
            favorite.setMaxWidth(Double.MAX_VALUE);
            favorite.setOnAction(event -> saveResultAsFavorite(wallpaper, favorite));
            VBox card = new VBox(8, imageMark, title, metadata, preview, favorite);
            card.setPrefWidth(248);
            card.setMaxWidth(248);
            card.setPadding(new Insets(10));
            card.getStyleClass().add("online-result-card");
            if (wallpaper.equals(selected)) {
                card.getStyleClass().add("online-result-selected");
            }
            card.setOnMouseClicked(event -> {
                if (!(event.getTarget() instanceof Button)) {
                    selectResult(wallpaper);
                }
            });
            resultGrid.getChildren().add(card);
        }
    }

    private boolean matchesOrientation(OnlineWallpaper wallpaper) {
        String filter = orientationFilter.getValue();
        if (filter == null || filter.equals("Any orientation")
                || wallpaper.width() <= 0 || wallpaper.height() <= 0) {
            return true;
        }
        return switch (filter) {
            case "Landscape" -> wallpaper.width() > wallpaper.height();
            case "Portrait" -> wallpaper.height() > wallpaper.width();
            case "Square" -> wallpaper.width() == wallpaper.height();
            default -> true;
        };
    }

    private void saveResultAsFavorite(OnlineWallpaper wallpaper, Button button) {
        String safeName = wallpaper.suggestedFileName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        Path target = Path.of(System.getProperty("user.home"), ".realityweaver", "wallpaper-library",
                UUID.randomUUID() + "-" + safeName).toAbsolutePath().normalize();
        button.setDisable(true);
        button.setText("Saving…");
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                searchService.download(wallpaper, target);
                return target;
            }
        };
        task.setOnSucceeded(event -> {
            Path saved = task.getValue();
            library.add(saved);
            library.setAttribution(saved, wallpaper.attribution());
            library.setSourceMetadata(saved, wallpaper);
            library.setFavorite(saved, true);
            onSaved.accept(saved);
            button.setDisable(false);
            button.setText("★ Saved to favorites");
            statusLabel.setText("Saved to your library and added to favorites with source attribution.");
        });
        task.setOnFailed(event -> {
            button.setDisable(false);
            button.setText("☆ Save as favorite");
            statusLabel.setText("Could not save this favorite: " + errorMessage(task.getException()));
        });
        requests.execute(task);
    }

    private HBox messageRow(String message) {
        Label text = new Label(message);
        text.setWrapText(true);
        text.getStyleClass().add("gallery-empty");
        HBox row = new HBox(text);
        row.setPadding(new Insets(14, 10, 14, 10));
        return row;
    }

    private void selectResult(OnlineWallpaper wallpaper) {
        selected = wallpaper;
        downloadButton.setDisable(false);
        applyButton.setDisable(false);
        sourceLink.setDisable(false);
        selectedTitle.setText(wallpaper.title());
        selectedMetadata.setText(metadataText(wallpaper));
        sourceLink.setText("Open source page ↗");
        statusLabel.setText("Loading preview…");
        showPreviewPlaceholder("Loading preview…");
        renderResults();

        if (activePreviewTask != null) {
            activePreviewTask.cancel();
        }
        OnlineWallpaper chosen = wallpaper;
        Task<byte[]> task = new Task<>() {
            @Override
            protected byte[] call() throws Exception {
                return searchService.fetchThumbnail(chosen);
            }
        };
        activePreviewTask = task;
        task.setOnSucceeded(event -> {
            if (!chosen.equals(selected)) {
                return;
            }
            Image image = new Image(new ByteArrayInputStream(task.getValue()));
            if (image.isError()) {
                statusLabel.setText("Preview could not be decoded. You can still open the source page.");
                return;
            }
            ImageView imageView = new ImageView(image);
            imageView.fitWidthProperty().bind(previewFrame.widthProperty().subtract(24));
            imageView.fitHeightProperty().bind(previewFrame.heightProperty().subtract(24));
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);
            previewFrame.getChildren().setAll(imageView);
            statusLabel.setText("Preview ready. Check the credit and license before saving.");
        });
        task.setOnFailed(event -> {
            if (chosen.equals(selected)) {
                statusLabel.setText("Preview unavailable: " + errorMessage(task.getException()));
            }
        });
        requests.execute(task);
    }

    private String metadataText(OnlineWallpaper wallpaper) {
        String creator = wallpaper.creator().isBlank() ? "Creator not listed" : wallpaper.creator();
        String license = wallpaper.license().isBlank() ? "Check file page for license" : wallpaper.license();
        return creator + "\n" + license + "\n" + wallpaper.width() + " × " + wallpaper.height()
                + " pixels · " + wallpaper.sourceName();
    }

    private void showPreviewPlaceholder(String message) {
        Label icon = new Label("▧");
        icon.getStyleClass().add("preview-placeholder-icon");
        Label copy = new Label(message);
        copy.setWrapText(true);
        copy.getStyleClass().add("preview-placeholder-copy");
        VBox placeholder = new VBox(6, icon, copy);
        placeholder.setAlignment(Pos.CENTER);
        previewFrame.getChildren().setAll(placeholder);
    }

    private void openSourcePage() {
        if (selected == null) {
            return;
        }

        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                statusLabel.setText("Open this source page in your browser: " + selected.descriptionUri());
                return;
            }
            Desktop.getDesktop().browse(selected.descriptionUri());
        } catch (IOException | UnsupportedOperationException failure) {
            statusLabel.setText("Could not open source page: " + errorMessage(failure));
        }
    }

    private void downloadSelected() {
        if (selected == null) {
            return;
        }

        OnlineWallpaper chosen = selected;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save wallpaper to your library");
        chooser.setInitialFileName(chosen.suggestedFileName());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Image file", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
        File destination = chooser.showSaveDialog(getScene().getWindow());
        if (destination == null) {
            return;
        }

        downloadButton.setDisable(true);
        statusLabel.setText("Downloading image…");
        Path target = destination.toPath().toAbsolutePath().normalize();
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                searchService.download(chosen, target);
                return target;
            }
        };
        task.setOnSucceeded(event -> {
            library.add(target);
            library.setAttribution(target, chosen.attribution());
            library.setSourceMetadata(target, chosen);
            onSaved.accept(target);
            downloadButton.setDisable(false);
            statusLabel.setText("Saved to your library with creator, license, and source credit.");
        });
        task.setOnFailed(event -> {
            downloadButton.setDisable(false);
            statusLabel.setText("Download failed: " + errorMessage(task.getException()));
        });
        requests.execute(task);
    }

    private void applySelected() {
        if (selected == null) {
            return;
        }
        OnlineWallpaper chosen = selected;
        String safeName = chosen.suggestedFileName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        Path target = Path.of(System.getProperty("user.home"), ".realityweaver", "applied-wallpapers",
                UUID.randomUUID() + "-" + safeName).toAbsolutePath().normalize();
        applyButton.setDisable(true);
        statusLabel.setText("Downloading and applying wallpaper…");
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                try {
                    searchService.download(chosen, target);
                    wallpaperService.apply(target);
                    return target;
                } catch (Exception failure) {
                    java.nio.file.Files.deleteIfExists(target);
                    throw failure;
                }
            }
        };
        task.setOnSucceeded(event -> {
            Path saved = task.getValue();
            library.add(saved);
            library.setAttribution(saved, chosen.attribution());
            library.setSourceMetadata(saved, chosen);
            onManualApplied.run();
            onSaved.accept(saved);
            applyButton.setDisable(false);
            statusLabel.setText("Wallpaper applied and saved to your library with source attribution.");
        });
        task.setOnFailed(event -> {
            applyButton.setDisable(false);
            statusLabel.setText("Wallpaper could not be applied: " + errorMessage(task.getException()));
        });
        requests.execute(task);
    }

    private String errorMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "Please check your internet connection and try again.";
        }
        return error.getMessage();
    }
}
