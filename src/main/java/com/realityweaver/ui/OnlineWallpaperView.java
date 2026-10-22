package com.realityweaver.ui;

import com.realityweaver.wallpaper.OnlineWallpaper;
import com.realityweaver.wallpaper.WallpaperLibrary;
import com.realityweaver.wallpaper.WallpaperSearchService;
import com.realityweaver.wallpaper.WallpaperSource;
import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Searches multiple image catalogs and saves selected results into the local wallpaper library. */
public final class OnlineWallpaperView extends VBox {
    private final WallpaperSearchService searchService = new WallpaperSearchService();
    private final WallpaperLibrary library = new WallpaperLibrary();
    private final Consumer<Path> onSaved;
    private final ExecutorService requests = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "realityweaver-wallpaper-search");
        thread.setDaemon(true);
        return thread;
    });

    private final TextField searchField = new TextField();
    private final ComboBox<WallpaperSource> sourceSelector = new ComboBox<>();
    private final Button searchButton = new Button("Search");
    private final Button trendingButton = new Button("Trending");
    private final Label statusLabel = new Label("Search for a destination or topic, or browse trending wallpapers.");
    private final Label resultCount = new Label();
    private final VBox resultList = new VBox(8);
    private final StackPane previewFrame = new StackPane();
    private final Label selectedTitle = new Label("Choose a result");
    private final Label selectedMetadata = new Label("Creator and license details will appear here.");
    private final Hyperlink sourceLink = new Hyperlink("Open image source page ↗");
    private final Button downloadButton = new Button("Download to library");

    private List<OnlineWallpaper> results = List.of();
    private OnlineWallpaper selected;
    private Task<byte[]> activePreviewTask;

    public OnlineWallpaperView(Consumer<Path> onSaved) {
        this.onSaved = onSaved;
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

        searchField.setPromptText("Try Kyoto, Iceland, mountains, or nebula");
        searchField.setOnAction(event -> search());
        HBox.setHgrow(searchField, Priority.ALWAYS);

        searchButton.getStyleClass().add("quiet-button");
        searchButton.setOnAction(event -> search());
        trendingButton.getStyleClass().add("quiet-button");
        trendingButton.setOnAction(event -> searchTrending());
        return new HBox(10, sourceSelector, searchField, searchButton, trendingButton);
    }

    private HBox createExploreRow() {
        Label label = new Label("Explore places:");
        label.getStyleClass().add("online-result-details");
        HBox row = new HBox(8, label);
        row.setAlignment(Pos.CENTER_LEFT);
        for (String place : List.of("Kyoto", "Iceland", "Santorini", "Patagonia", "New York")) {
            Button suggestion = new Button(place);
            suggestion.getStyleClass().add("quiet-button");
            suggestion.setOnAction(event -> {
                searchField.setText(place);
                search();
            });
            row.getChildren().add(suggestion);
        }
        return row;
    }

    private Label createSourceNote() {
        Label note = new Label(
                "Openverse searches many image libraries; Commons and Wallhaven are available too. Review each source page for reuse terms and attribution.");
        note.setWrapText(true);
        note.getStyleClass().add("commons-note");
        return note;
    }

    private HBox createResultsWorkspace() {
        Label resultsTitle = new Label("Search results");
        resultsTitle.getStyleClass().add("section-title");
        resultCount.getStyleClass().add("count-label");
        HBox resultsHeader = new HBox(resultsTitle, resultCount);
        resultsHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(resultsTitle, Priority.ALWAYS);

        ScrollPane resultScroll = new ScrollPane(resultList);
        resultScroll.setFitToWidth(true);
        resultScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        resultScroll.setPrefHeight(385);
        resultScroll.getStyleClass().add("online-results-scroll");

        VBox resultPanel = new VBox(11, resultsHeader, resultScroll, statusLabel);
        resultPanel.setMinWidth(300);
        resultPanel.getStyleClass().add("online-results-panel");
        HBox.setHgrow(resultPanel, Priority.ALWAYS);

        VBox previewPanel = createOnlinePreviewPanel();
        previewPanel.setMinWidth(245);
        previewPanel.setPrefWidth(280);

        HBox workspace = new HBox(14, resultPanel, previewPanel);
        workspace.setAlignment(Pos.TOP_LEFT);
        return workspace;
    }

    private VBox createOnlinePreviewPanel() {
        Label previewTitleLabel = new Label("Preview");
        previewTitleLabel.getStyleClass().add("section-title");

        previewFrame.setMinHeight(150);
        previewFrame.setPrefHeight(175);
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

        VBox panel = new VBox(11,
                previewTitleLabel,
                previewFrame,
                selectedTitle,
                selectedMetadata,
                sourceLink,
                downloadButton);
        panel.getStyleClass().add("online-preview-panel");
        return panel;
    }

    private void search() {
        String query = searchField.getText().strip();
        if (query.isBlank()) {
            statusLabel.setText("Enter a search term first.");
            return;
        }

        WallpaperSource source = sourceSelector.getValue();
        runSearch(query, source == null ? WallpaperSource.OPENVERSE : source, false);
    }

    private void searchTrending() {
        sourceSelector.setValue(WallpaperSource.WALLHAVEN);
        runSearch("", WallpaperSource.WALLHAVEN, true);
    }

    private void runSearch(String query, WallpaperSource source, boolean trending) {
        if (activePreviewTask != null) {
            activePreviewTask.cancel();
            activePreviewTask = null;
        }

        searchButton.setDisable(true);
        trendingButton.setDisable(true);
        statusLabel.setText(trending ? "Finding this month's top wallpapers…" : "Searching " + source + "…");
        resultList.getChildren().setAll(messageRow(trending ? "Loading trending wallpapers…" : "Searching…"));
        selected = null;
        downloadButton.setDisable(true);
        sourceLink.setDisable(true);
        showPreviewPlaceholder(trending ? "Loading trending wallpapers" : "Loading search results");

        Task<List<OnlineWallpaper>> task = new Task<>() {
            @Override
            protected List<OnlineWallpaper> call() throws Exception {
                List<OnlineWallpaper> found = trending
                        ? searchService.trending()
                        : searchService.search(query, source);
                return found.stream().filter(OnlineWallpaper::isJavaFxImage).toList();
            }
        };
        task.setOnSucceeded(event -> {
            searchButton.setDisable(false);
            trendingButton.setDisable(false);
            results = task.getValue();
            statusLabel.setText(results.isEmpty()
                    ? "No supported images found. Try another destination or source."
                    : trending ? "Trending on Wallhaven this month. Select an image to preview it."
                            : "Select a result to preview it.");
            resultCount.setText(results.size() + (results.size() == 1 ? " result" : " results"));
            renderResults();
            if (!results.isEmpty()) {
                selectResult(results.get(0));
            }
        });
        task.setOnFailed(event -> {
            searchButton.setDisable(false);
            trendingButton.setDisable(false);
            results = List.of();
            resultCount.setText("");
            statusLabel.setText("Search failed: " + errorMessage(task.getException()));
            renderResults();
        });
        requests.execute(task);
    }

    private void renderResults() {
        resultList.getChildren().clear();
        if (results.isEmpty()) {
            resultList.getChildren().add(messageRow("No results yet. Search a destination or browse trending wallpapers."));
            return;
        }

        for (OnlineWallpaper wallpaper : results) {
            Label imageIcon = new Label("▧");
            imageIcon.getStyleClass().add("online-result-icon");
            StackPane imageMark = new StackPane(imageIcon);
            imageMark.getStyleClass().add("online-result-mark");

            Label title = new Label(wallpaper.title());
            title.setWrapText(true);
            title.getStyleClass().add("wallpaper-item-name");
            String detail = wallpaper.sourceName() + " · " + wallpaper.width() + " × " + wallpaper.height();
            if (!wallpaper.license().isBlank()) {
                detail += " · " + wallpaper.license();
            }
            Label metadata = new Label(detail);
            metadata.getStyleClass().add("online-result-details");
            VBox copy = new VBox(5, title, metadata);
            copy.setAlignment(Pos.CENTER_LEFT);

            HBox row = new HBox(11, imageMark, copy);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(10));
            row.getStyleClass().add("online-result-row");
            if (wallpaper.equals(selected)) {
                row.getStyleClass().add("online-result-selected");
            }
            row.setOnMouseClicked(event -> selectResult(wallpaper));
            resultList.getChildren().add(row);
        }
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
            imageView.setFitWidth(260);
            imageView.setFitHeight(160);
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

    private String errorMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "Please check your internet connection and try again.";
        }
        return error.getMessage();
    }
}
