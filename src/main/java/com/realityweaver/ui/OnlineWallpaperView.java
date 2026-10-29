package com.realityweaver.ui;

import com.realityweaver.AppSettings;
import com.realityweaver.wallpaper.OnlineWallpaper;
import com.realityweaver.wallpaper.WallpaperCollection;
import com.realityweaver.wallpaper.WallpaperCollectionStore;
import com.realityweaver.wallpaper.WallpaperLibrary;
import com.realityweaver.wallpaper.WallpaperSearchService;
import com.realityweaver.wallpaper.WallpaperService;
import com.realityweaver.wallpaper.WallpaperSource;
import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
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
            "Nature", "City", "Architecture", "Space", "Fantasy", "Ocean", "Forest", "Desert",
            "Japanese anime", "Movie superstars", "Fashion models", "GTA 6 wallpaper",
            "Gods & mythology", "4K HD desktop");

    private static final List<String> AESTHETIC_PRESETS = List.of(
            "Lo-Fi Anime", "Dark Academia", "Studio Ghibli", "Cyberpunk Neon",
            "Minimalist Pastel", "Cozy Coffee & Rain", "Cosmic Galaxy", "Nordic Mountain 4K");

    private final WallpaperSearchService searchService = new WallpaperSearchService();
    private final WallpaperLibrary library = new WallpaperLibrary();
    private final WallpaperService wallpaperService;
    private final Runnable onManualApplied;
    private final Consumer<Path> onSaved;
    private final ExecutorService requests = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "realityweaver-wallpaper-search");
        thread.setDaemon(true);
        return thread;
    });

    private final TextField searchField = new TextField();
    private final ComboBox<WallpaperSource> sourceSelector = new ComboBox<>();
    private final ComboBox<String> searchCategory = new ComboBox<>();
    private final ComboBox<String> orientationFilter = new ComboBox<>();
    private final ComboBox<String> resolutionFilter = new ComboBox<>();
    private final Button searchButton = new Button("Search");
    private final Button bingButton = new Button("Bing 4K Daily");
    private final Button trendingButton = new Button("Wallhaven Top");
    private final Button picsumButton = new Button("Curated Photos");
    private final Label statusLabel = new Label("Search for a destination or topic, or browse Bing 4K and Wallhaven wallpapers.");
    private final Label resultCount = new Label();
    private final FlowPane resultGrid = new FlowPane(10, 10);
    private final StackPane previewFrame = new StackPane();
    private final Label selectedTitle = new Label("Choose a result");
    private final Label selectedMetadata = new Label("Creator and license details will appear here.");
    private final Hyperlink sourceLink = new Hyperlink("Open image source page ↗");
    private final Button downloadButton = new Button("Download to library");
    private final Button applyButton = new Button("Set as Wallpaper");
    private final Button addToCollectionButton = new Button("＋ Add to Collection");
    private final Button fullPreviewButton = new Button("🔍 Full Details & Preview");
    private final Button loadMoreButton = new Button("Load more");

    private List<OnlineWallpaper> results = List.of();
    private OnlineWallpaper selected;
    private Task<byte[]> activePreviewTask;
    private Task<List<OnlineWallpaper>> activeSearchTask;
    private int searchGeneration;
    private int currentPage;
    private String currentQuery = "";
    private WallpaperSource currentSource = WallpaperSource.WALLHAVEN;
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
        showPreviewPlaceholder("Select an image to preview or apply as desktop wallpaper");
        renderResults();

        // Automatically fetch initial batch so Discover is never an empty canvas
        Platform.runLater(this::searchTrending);
    }

    private HBox createSearchBar() {
        sourceSelector.setItems(FXCollections.observableArrayList(WallpaperSource.values()));
        sourceSelector.setValue(WallpaperSource.WALLHAVEN);
        sourceSelector.setPrefWidth(215);
        sourceSelector.getStyleClass().add("online-source-selector");

        searchField.setPromptText("Search anime, cyberpunk, nature, gaming, mountains, or aesthetic...");
        searchField.setOnAction(event -> search());
        HBox.setHgrow(searchField, Priority.ALWAYS);

        searchButton.getStyleClass().add("primary-button");
        searchButton.setOnAction(event -> search());

        bingButton.getStyleClass().add("quiet-button");
        bingButton.setOnAction(event -> searchBing());

        trendingButton.getStyleClass().add("quiet-button");
        trendingButton.setOnAction(event -> searchTrending());

        picsumButton.getStyleClass().add("quiet-button");
        picsumButton.setOnAction(event -> searchPicsum());

        return new HBox(8, sourceSelector, searchField, searchButton, bingButton, trendingButton, picsumButton);
    }

    private VBox createExploreRow() {
        FlowPane aestheticRow = new FlowPane(8, 8);
        aestheticRow.setAlignment(Pos.CENTER_LEFT);
        Label aestheticLabel = new Label("Pinterest Vibes:");
        aestheticLabel.getStyleClass().add("online-result-details");
        aestheticRow.getChildren().add(aestheticLabel);
        for (String preset : AESTHETIC_PRESETS) {
            aestheticRow.getChildren().add(createSuggestionButton(preset, WallpaperSource.WALLHAVEN));
        }

        FlowPane placesRow = new FlowPane(8, 8);
        placesRow.setAlignment(Pos.CENTER_LEFT);
        Label placesLabel = new Label("Destinations:");
        placesLabel.getStyleClass().add("online-result-details");
        placesRow.getChildren().add(placesLabel);
        for (String place : List.of("Kyoto", "Iceland", "Santorini", "Patagonia", "Tokyo Night")) {
            placesRow.getChildren().add(createSuggestionButton(place, null));
        }

        FlowPane categoriesRow = new FlowPane(8, 8);
        categoriesRow.setAlignment(Pos.CENTER_LEFT);
        Label categoriesLabel = new Label("Categories:");
        categoriesLabel.getStyleClass().add("online-result-details");
        categoriesRow.getChildren().add(categoriesLabel);
        for (String category : SEARCH_CATEGORIES) {
            WallpaperSource suggestedSource = category.equals("Japanese anime")
                    || category.equals("Movie superstars") || category.equals("Fashion models")
                    || category.equals("GTA 6 wallpaper") || category.equals("Gods & mythology")
                    || category.equals("4K HD desktop")
                    ? WallpaperSource.WALLHAVEN : null;
            categoriesRow.getChildren().add(createSuggestionButton(category, suggestedSource));
        }

        VBox row = new VBox(6, aestheticRow, placesRow, categoriesRow);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button createSuggestionButton(String value, WallpaperSource suggestedSource) {
        Button suggestion = new Button(value);
        suggestion.getStyleClass().add("quiet-button");
        suggestion.setOnAction(event -> {
            searchField.setText(value);
            if (suggestedSource != null) {
                sourceSelector.setValue(suggestedSource);
            }
            search();
        });
        return suggestion;
    }

    private Label createSourceNote() {
        Label note = new Label(
                "Bing 4K UHD, Picsum, and Wallhaven provide direct wallpaper feeds with no key needed. Pexels and Pixabay keys can be configured in Settings.");
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

        searchCategory.getItems().setAll("Any topic", "Nature", "Architecture", "Space", "Japanese anime",
                "Movie superstars", "Fashion models", "Gaming", "GTA 6 wallpaper",
                "Gods & mythology", "4K HD desktop");
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
        resolutionFilter.getItems().setAll("Any resolution", "HD (1920px+)", "4K (3840px+)");
        resolutionFilter.setValue("Any resolution");
        resolutionFilter.getStyleClass().add("online-source-selector");
        resolutionFilter.setOnAction(event -> renderResults());
        HBox filters = new HBox(8, new Label("Topic"), searchCategory,
                new Label("Orientation"), orientationFilter, new Label("Resolution"), resolutionFilter);
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
        previewFrame.setCursor(javafx.scene.Cursor.HAND);
        previewFrame.setOnMouseClicked(event -> {
            if (selected != null) {
                openFullPreview(selected);
            }
        });

        selectedTitle.getStyleClass().add("wallpaper-selected-name");
        selectedTitle.setWrapText(true);
        selectedMetadata.getStyleClass().add("online-metadata");
        selectedMetadata.setWrapText(true);

        sourceLink.setDisable(true);
        sourceLink.getStyleClass().add("commons-link");
        sourceLink.setOnAction(event -> openSourcePage());

        applyButton.getStyleClass().add("primary-button");
        applyButton.setDisable(true);
        applyButton.setMaxWidth(Double.MAX_VALUE);
        applyButton.setOnAction(event -> applySelected());

        downloadButton.getStyleClass().add("quiet-button");
        downloadButton.setDisable(true);
        downloadButton.setMaxWidth(Double.MAX_VALUE);
        downloadButton.setOnAction(event -> downloadSelected());

        addToCollectionButton.getStyleClass().add("quiet-button");
        addToCollectionButton.setDisable(true);
        addToCollectionButton.setMaxWidth(Double.MAX_VALUE);
        addToCollectionButton.setOnAction(event -> addSelectedToCollection());

        fullPreviewButton.getStyleClass().add("quiet-button");
        fullPreviewButton.setDisable(true);
        fullPreviewButton.setMaxWidth(Double.MAX_VALUE);
        fullPreviewButton.setOnAction(event -> openFullPreview(selected));

        VBox panel = new VBox(10,
                previewTitleLabel,
                previewFrame,
                selectedTitle,
                selectedMetadata,
                sourceLink,
                applyButton,
                downloadButton,
                addToCollectionButton,
                fullPreviewButton);
        panel.getStyleClass().add("online-preview-panel");
        return panel;
    }

    private void search() {
        String query = searchField.getText().strip();
        WallpaperSource source = sourceSelector.getValue();
        if (source == null) {
            source = WallpaperSource.WALLHAVEN;
        }

        String category = searchCategory.getValue();
        if (category != null && !category.equals("Any topic") && !query.contains(category)) {
            query = query.isBlank() ? category : query + " " + category;
        }

        runSearch(query, source, false, 1);
    }

    private void searchTrending() {
        sourceSelector.setValue(WallpaperSource.WALLHAVEN);
        runSearch("", WallpaperSource.WALLHAVEN, true, 1);
    }

    private void searchBing() {
        sourceSelector.setValue(WallpaperSource.BING_UHD);
        runSearch("", WallpaperSource.BING_UHD, false, 1);
    }

    private void searchPicsum() {
        sourceSelector.setValue(WallpaperSource.PICSUM);
        runSearch("", WallpaperSource.PICSUM, false, 1);
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
        bingButton.setDisable(true);
        picsumButton.setDisable(true);
        loadMoreButton.setDisable(true);
        statusLabel.setText(trending ? "Finding this month's top Wallhaven wallpapers…" : "Searching " + source + "…");
        if (page == 1) {
            resultGrid.getChildren().setAll(messageRow(trending ? "Loading trending wallpapers…" : "Searching " + source + "…"));
        }
        downloadButton.setDisable(true);
        applyButton.setDisable(true);
        addToCollectionButton.setDisable(true);
        fullPreviewButton.setDisable(true);
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
            bingButton.setDisable(false);
            picsumButton.setDisable(false);
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
                    ? "No supported images found. Try another topic or provider."
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
            bingButton.setDisable(false);
            picsumButton.setDisable(false);
            loadMoreButton.setDisable(page == 1);
            if (page == 1) {
                results = List.of();
                statusLabel.setText("Search failed: " + errorMessage(task.getException())
                        + " (Local collections and saved wallpapers remain accessible).");
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
                .filter(this::matchesResolution)
                .toList();
        resultCount.setText(results.isEmpty() ? "" : visible.size() + " shown · " + results.size() + " loaded");
        if (results.isEmpty()) {
            resultGrid.getChildren().add(messageRow("No results yet. Search a topic or click Bing 4K, Wallhaven Top, or Curated Photos above."));
            return;
        }
        if (visible.isEmpty()) {
            resultGrid.getChildren().add(messageRow("No loaded images match this orientation. Choose another filter or load more."));
            return;
        }

        for (OnlineWallpaper wallpaper : visible) {
            ImageView imageView = new ImageView();
            imageView.setFitWidth(224);
            imageView.setFitHeight(132);
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);

            Label placeholderLabel = new Label("Loading preview…");
            placeholderLabel.getStyleClass().add("online-result-details");
            StackPane imageMark = new StackPane(placeholderLabel, imageView);
            imageMark.getStyleClass().add("online-result-mark");
            imageMark.setMinHeight(132);
            imageMark.setPrefHeight(132);
            imageMark.setMaxWidth(Double.MAX_VALUE);

            // Asynchronous on-disk cache + HTTP header fetching
            Path cached = searchService.getCachedThumbnailPath(wallpaper.thumbnailUri());
            if (cached != null && Files.isRegularFile(cached)) {
                try {
                    Image image = new Image(cached.toUri().toString(), 224, 132, true, true, false);
                    imageView.setImage(image);
                    placeholderLabel.setVisible(false);
                } catch (Exception ignored) { }
            } else {
                requests.execute(() -> {
                    try {
                        byte[] data = searchService.fetchThumbnail(wallpaper);
                        if (data != null && data.length > 0) {
                            Image image = new Image(new ByteArrayInputStream(data), 224, 132, true, true);
                            Platform.runLater(() -> {
                                imageView.setImage(image);
                                placeholderLabel.setVisible(false);
                            });
                        }
                    } catch (Exception ignored) {
                        Platform.runLater(() -> {
                            try {
                                Image direct = new Image(wallpaper.thumbnailUri().toString(), 224, 132, true, true, true);
                                imageView.setImage(direct);
                                placeholderLabel.setVisible(false);
                            } catch (Exception e) {
                                placeholderLabel.setText("Preview unavailable");
                            }
                        });
                    }
                });
            }

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
            preview.setOnAction(event -> selectResult(wallpaper));

            Button setQuick = new Button("Set Wallpaper");
            setQuick.getStyleClass().add("primary-button");
            setQuick.setOnAction(event -> {
                selectResult(wallpaper);
                applySelected();
            });

            HBox actions = new HBox(6, preview, setQuick);
            HBox.setHgrow(preview, Priority.ALWAYS);
            HBox.setHgrow(setQuick, Priority.ALWAYS);

            Button favorite = new Button("☆ Save as favorite");
            favorite.getStyleClass().add("favorite-action-button");
            favorite.setMaxWidth(Double.MAX_VALUE);
            favorite.setOnAction(event -> saveResultAsFavorite(wallpaper, favorite));

            VBox card = new VBox(8, imageMark, title, metadata, actions, favorite);
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
                    if (event.getClickCount() == 2) {
                        openFullPreview(wallpaper);
                    }
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

    private boolean matchesResolution(OnlineWallpaper wallpaper) {
        String filter = resolutionFilter.getValue();
        if (filter == null || filter.equals("Any resolution")) {
            return true;
        }
        int longestEdge = Math.max(wallpaper.width(), wallpaper.height());
        return switch (filter) {
            case "HD (1920px+)" -> longestEdge >= 1920;
            case "4K (3840px+)" -> longestEdge >= 3840;
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

    private void addSelectedToCollection() {
        if (selected == null) {
            return;
        }
        WallpaperCollectionStore store = new WallpaperCollectionStore();
        List<WallpaperCollection> collections;
        try {
            collections = store.list();
        } catch (IOException failure) {
            statusLabel.setText("Collections could not be opened: " + safeMessage(failure));
            return;
        }
        if (collections.isEmpty()) {
            statusLabel.setText("No collections found. Create a collection in the Collections tab first.");
            return;
        }
        ChoiceDialog<WallpaperCollection> dialog = new ChoiceDialog<>(collections.get(0), collections);
        dialog.setTitle("Add to Collection");
        dialog.setHeaderText("Add “" + selected.title() + "” to a collection");
        dialog.setContentText("Select collection:");
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("world-dialog");
        dialog.showAndWait().ifPresent(chosenCollection -> {
            OnlineWallpaper chosen = selected;
            String safeName = chosen.suggestedFileName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
            Path target = Path.of(System.getProperty("user.home"), ".realityweaver", "wallpaper-library",
                UUID.randomUUID() + "-" + safeName).toAbsolutePath().normalize();
            addToCollectionButton.setDisable(true);
            statusLabel.setText("Downloading image for collection “" + chosenCollection.name() + "”…");
            Task<Path> task = new Task<>() {
                @Override
                protected Path call() throws Exception {
                    searchService.download(chosen, target);
                    return target;
                }
            };
            task.setOnSucceeded(event -> {
                addToCollectionButton.setDisable(false);
                Path saved = task.getValue();
                library.add(saved);
                library.setAttribution(saved, chosen.attribution());
                library.setSourceMetadata(saved, chosen);
                try {
                    store.addWallpaper(chosenCollection.id(), saved, library);
                    statusLabel.setText("Added to collection “" + chosenCollection.name() + "” with attribution!");
                } catch (Exception ex) {
                    statusLabel.setText("Saved to library, but could not link to collection: " + ex.getMessage());
                }
                onSaved.accept(saved);
            });
            task.setOnFailed(event -> {
                addToCollectionButton.setDisable(false);
                statusLabel.setText("Download failed: " + errorMessage(task.getException()));
            });
            requests.execute(task);
        });
    }

    private void openFullPreview(OnlineWallpaper wallpaper) {
        if (wallpaper == null) {
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Wallpaper Preview & Details");
        dialog.setHeaderText(null);
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("world-dialog");

        ImageView fullView = new ImageView();
        fullView.setPreserveRatio(true);
        fullView.setFitWidth(660);
        fullView.setFitHeight(380);

        Label loading = new Label("Loading image preview…");
        loading.getStyleClass().add("online-status");
        StackPane imagePane = new StackPane(loading, fullView);
        imagePane.setMinSize(660, 380);
        imagePane.getStyleClass().add("wallpaper-preview");

        Task<byte[]> imgTask = new Task<>() {
            @Override
            protected byte[] call() throws Exception {
                return searchService.fetchThumbnail(wallpaper);
            }
        };
        imgTask.setOnSucceeded(ev -> {
            Image img = new Image(new ByteArrayInputStream(imgTask.getValue()));
            fullView.setImage(img);
            loading.setVisible(false);
        });
        requests.execute(imgTask);

        Label title = new Label(wallpaper.title());
        title.getStyleClass().add("wallpaper-selected-name");
        Label meta = new Label(metadataText(wallpaper));
        meta.getStyleClass().add("online-metadata");
        Hyperlink link = new Hyperlink("Open original source page ↗");
        link.getStyleClass().add("commons-link");
        link.setOnAction(e -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(wallpaper.descriptionUri());
                }
            } catch (Exception ignored) { }
        });

        Button applyQuick = new Button("Set as Wallpaper Directly");
        applyQuick.getStyleClass().add("primary-button");
        applyQuick.setOnAction(e -> {
            dialog.close();
            selectResult(wallpaper);
            applySelected();
        });

        VBox content = new VBox(12, imagePane, title, meta, link, applyQuick);
        content.setPadding(new Insets(16));
        content.setPrefWidth(700);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
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
        addToCollectionButton.setDisable(false);
        fullPreviewButton.setDisable(false);
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
            statusLabel.setText("Preview ready. Click preview to inspect full size.");
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

    private String safeMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null ? "Unknown issue" : failure.getMessage();
    }
}
