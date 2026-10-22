package com.realityweaver.ui;

import com.realityweaver.wallpaper.WallpaperLibrary;
import com.realityweaver.wallpaper.WallpaperService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Wallpaper gallery with import, preview, favorites, and desktop apply actions. */
public final class WallpaperStudioView extends BorderPane {
    private final WallpaperLibrary library = new WallpaperLibrary();
    private final WallpaperService wallpaperService = new WallpaperService();
    private final List<Path> imagePaths = new ArrayList<>(library.load());
    private final StackPane previewFrame = new StackPane();
    private final Label selectedName = new Label("No wallpaper selected");
    private final Label selectedDetails = new Label("Import an image to preview it here.");
    private final Label libraryCount = new Label();
    private final VBox galleryItems = new VBox(8);
    private final Button favoriteFilter = new Button("☆ Favorites only");
    private final Button applyButton = new Button("Set as desktop wallpaper");
    private final Button removeButton = new Button("Remove from library");

    private Path selectedPath;
    private boolean favoritesOnly;

    public WallpaperStudioView() {
        getStyleClass().add("wallpaper-page");
        setCenter(createContent());
        refreshGallery();
        if (!imagePaths.isEmpty()) {
            selectWallpaper(imagePaths.get(0));
        } else {
            showPlaceholder();
        }
    }

    private ScrollPane createContent() {
        VBox content = new VBox(24, createHeading(), createStudioTabs());
        content.setPadding(new Insets(38, 48, 52, 48));
        content.setMaxWidth(1240);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("content-scroll");
        return scrollPane;
    }

    private TabPane createStudioTabs() {
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("wallpaper-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefHeight(570);
        tabs.setMinHeight(480);

        Tab libraryTab = new Tab("My library", createWorkspace());
        Tab onlineTab = new Tab("Find online", new OnlineWallpaperView(this::addDownloadedWallpaper));
        tabs.getTabs().addAll(libraryTab, onlineTab);
        return tabs;
    }

    private HBox createHeading() {
        Label eyebrow = new Label("MAKE YOUR DESKTOP YOURS");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("Wallpaper studio");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Import, preview, and save the images you want close at hand.");
        subtitle.getStyleClass().add("page-subtitle");
        VBox copy = new VBox(8, eyebrow, title, subtitle);

        Button importButton = new Button("＋  Import image");
        importButton.getStyleClass().add("primary-button");
        importButton.setOnAction(event -> importImage());

        HBox heading = new HBox(copy, importButton);
        heading.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(copy, Priority.ALWAYS);
        return heading;
    }

    private HBox createWorkspace() {
        VBox previewPanel = createPreviewPanel();
        VBox galleryPanel = createGalleryPanel();
        HBox workspace = new HBox(18, previewPanel, galleryPanel);
        workspace.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(previewPanel, Priority.ALWAYS);
        return workspace;
    }

    private VBox createPreviewPanel() {
        previewFrame.setMinHeight(300);
        previewFrame.setPrefHeight(350);
        previewFrame.setMaxWidth(Double.MAX_VALUE);
        previewFrame.getStyleClass().add("wallpaper-preview");
        showPlaceholder();

        selectedName.getStyleClass().add("wallpaper-selected-name");
        selectedDetails.getStyleClass().add("wallpaper-selected-details");
        selectedDetails.setWrapText(true);
        VBox selectedCopy = new VBox(5, selectedName, selectedDetails);

        applyButton.getStyleClass().add("primary-button");
        applyButton.setDisable(true);
        applyButton.setOnAction(event -> applySelectedWallpaper());
        removeButton.getStyleClass().add("quiet-button");
        removeButton.setDisable(true);
        removeButton.setOnAction(event -> removeSelectedWallpaper());
        HBox actions = new HBox(10, applyButton, removeButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(14, previewFrame, selectedCopy, actions);
        panel.getStyleClass().add("wallpaper-preview-panel");
        HBox.setHgrow(panel, Priority.ALWAYS);
        return panel;
    }

    private VBox createGalleryPanel() {
        Label title = new Label("Your library");
        title.getStyleClass().add("section-title");
        libraryCount.getStyleClass().add("count-label");

        HBox titleRow = new HBox(title, libraryCount);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(title, Priority.ALWAYS);

        favoriteFilter.getStyleClass().add("filter-button");
        favoriteFilter.setOnAction(event -> {
            favoritesOnly = !favoritesOnly;
            updateFavoriteFilterStyle();
            refreshGallery();
        });

        ScrollPane galleryScroll = new ScrollPane(galleryItems);
        galleryScroll.setFitToWidth(true);
        galleryScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        galleryScroll.setPrefHeight(380);
        galleryScroll.getStyleClass().add("gallery-scroll");

        VBox panel = new VBox(12, titleRow, favoriteFilter, galleryScroll);
        panel.setMinWidth(280);
        panel.setPrefWidth(330);
        panel.getStyleClass().add("wallpaper-gallery-panel");
        return panel;
    }

    private void importImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import a wallpaper image");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));

        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }

        Path path = file.toPath().toAbsolutePath().normalize();
        Image image = readImage(path);
        if (image == null) {
            showAlert(Alert.AlertType.ERROR, "Image could not be opened",
                    "Choose a valid PNG, JPEG, GIF, or BMP image.");
            return;
        }

        library.add(path);
        if (!imagePaths.contains(path)) {
            imagePaths.add(path);
        }
        selectWallpaper(path, image);
        refreshGallery();
    }

    private void selectWallpaper(Path path) {
        Image image = readImage(path);
        if (image == null) {
            showAlert(Alert.AlertType.ERROR, "Image could not be opened",
                    "This image is no longer available or is not supported.");
            return;
        }
        selectWallpaper(path, image);
    }

    private void selectWallpaper(Path path, Image image) {
        selectedPath = path;
        ImageView imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setFitWidth(620);
        imageView.setFitHeight(326);
        StackPane.setMargin(imageView, new Insets(10));
        previewFrame.getChildren().setAll(imageView);

        selectedName.setText(path.getFileName().toString());
        String details = Math.round(image.getWidth()) + " × " + Math.round(image.getHeight()) + " pixels";
        String attribution = library.attribution(path);
        if (!attribution.isBlank()) {
            details += "\n" + attribution;
        }
        selectedDetails.setText(details);
        applyButton.setDisable(false);
        removeButton.setDisable(false);
        refreshGallery();
    }

    private void addDownloadedWallpaper(Path path) {
        imagePaths.clear();
        imagePaths.addAll(library.load());
        selectWallpaper(path);
        refreshGallery();
    }

    private Image readImage(Path path) {
        Image image = new Image(path.toUri().toString(), false);
        return image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0 ? null : image;
    }

    private void refreshGallery() {
        galleryItems.getChildren().clear();
        List<Path> visiblePaths = imagePaths.stream()
                .filter(path -> !favoritesOnly || library.isFavorite(path))
                .toList();
        libraryCount.setText(visiblePaths.size() + (visiblePaths.size() == 1 ? " image" : " images"));

        if (visiblePaths.isEmpty()) {
            Label empty = new Label(favoritesOnly
                    ? "No favorites yet. Star an image to keep it here."
                    : "Your library is empty. Import an image to get started.");
            empty.setWrapText(true);
            empty.getStyleClass().add("gallery-empty");
            galleryItems.getChildren().add(empty);
            return;
        }

        for (Path path : visiblePaths) {
            galleryItems.getChildren().add(createGalleryItem(path));
        }
    }

    private HBox createGalleryItem(Path path) {
        Image thumbnail = new Image(path.toUri().toString(), 92, 60, true, true, true);
        ImageView imageView = new ImageView(thumbnail);
        imageView.setFitWidth(72);
        imageView.setFitHeight(56);
        imageView.setPreserveRatio(true);
        StackPane thumbnailFrame = new StackPane(imageView);
        thumbnailFrame.getStyleClass().add("wallpaper-thumbnail");

        Label name = new Label(path.getFileName().toString());
        name.setWrapText(true);
        name.getStyleClass().add("wallpaper-item-name");
        VBox copy = new VBox(name);
        copy.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(copy, Priority.ALWAYS);

        boolean favorite = library.isFavorite(path);
        Button star = new Button(favorite ? "★" : "☆");
        star.getStyleClass().add("star-button");
        star.setAccessibleText(favorite ? "Remove from favorites" : "Add to favorites");
        star.setOnAction(event -> {
            library.setFavorite(path, !library.isFavorite(path));
            refreshGallery();
        });

        HBox item = new HBox(10, thumbnailFrame, copy, star);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(8));
        item.getStyleClass().add("wallpaper-item");
        if (path.equals(selectedPath)) {
            item.getStyleClass().add("wallpaper-item-selected");
        }
        item.setOnMouseClicked(event -> selectWallpaper(path));
        return item;
    }

    private void updateFavoriteFilterStyle() {
        favoriteFilter.setText(favoritesOnly ? "★ Showing favorites" : "☆ Favorites only");
        favoriteFilter.getStyleClass().remove("filter-button-selected");
        if (favoritesOnly) {
            favoriteFilter.getStyleClass().add("filter-button-selected");
        }
    }

    private void applySelectedWallpaper() {
        if (selectedPath == null) {
            return;
        }

        try {
            wallpaperService.apply(selectedPath);
            showAlert(Alert.AlertType.INFORMATION, "Wallpaper applied",
                    "Your desktop background has been updated.");
        } catch (UnsupportedOperationException unsupported) {
            showAlert(Alert.AlertType.INFORMATION, "Not supported on this system", unsupported.getMessage());
        } catch (IOException failure) {
            showAlert(Alert.AlertType.ERROR, "Could not apply wallpaper", failure.getMessage());
        }
    }

    private void removeSelectedWallpaper() {
        if (selectedPath == null) {
            return;
        }

        Path removed = selectedPath;
        library.remove(removed);
        imagePaths.remove(removed);
        selectedPath = null;
        if (imagePaths.isEmpty()) {
            showPlaceholder();
            selectedName.setText("No wallpaper selected");
            selectedDetails.setText("Import an image to preview it here.");
            applyButton.setDisable(true);
            removeButton.setDisable(true);
        } else {
            selectWallpaper(imagePaths.get(0));
        }
        refreshGallery();
    }

    private void showPlaceholder() {
        Label icon = new Label("▧");
        icon.getStyleClass().add("preview-placeholder-icon");
        Label title = new Label("Your next desktop view");
        title.getStyleClass().add("preview-placeholder-title");
        Label subtitle = new Label("Import an image to see it here");
        subtitle.getStyleClass().add("preview-placeholder-copy");
        VBox placeholder = new VBox(9, icon, title, subtitle);
        placeholder.setAlignment(Pos.CENTER);
        previewFrame.getChildren().setAll(placeholder);
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.initOwner(getScene().getWindow());
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
