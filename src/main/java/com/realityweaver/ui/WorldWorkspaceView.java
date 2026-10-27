package com.realityweaver.ui;

import com.realityweaver.wallpaper.WallpaperService;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.DisplayMode;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;

/** Workspace for places, characters, stories, and a personal memory wallpaper. */
public final class WorldWorkspaceView extends ScrollPane {
    private record ScreenRatio(String label, double width, double height, boolean primaryDisplay) {
    }

    private static final String ADD_CUSTOM_RATIO = "Add a custom ratio…";

    private final WorldDraft world;
    private final Runnable onBack;
    private final Runnable onManualApplied;
    private Dimension desktopResolution = detectDesktopResolution();
    private final WallpaperService wallpaperService = new WallpaperService();
    private final StackPane memoryPreview = new StackPane();
    private final VBox memoryOverlay = new VBox(9);
    private final Label previewWorldName = new Label();
    private final Label previewStory = new Label();
    private final Label previewQuote = new Label();
    private final Button saveWallpaperButton = new Button("Save wallpaper image");
    private final Button previewApplyButton = new Button("Preview on desktop");
    private Button choosePhotoButton;
    private Label desktopResolutionHint;
    private ImageView memoryImageView;
    private Label photoPlaceholder;

    public WorldWorkspaceView(WorldDraft world, Runnable onBack) {
        this(world, onBack, () -> { });
    }

    public WorldWorkspaceView(WorldDraft world, Runnable onBack, Runnable onManualApplied) {
        this.world = world;
        this.onBack = onBack;
        this.onManualApplied = onManualApplied;

        VBox content = new VBox(26, createHeader(), createSections(), createMemoryWallpaper());
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

    private VBox createMemoryWallpaper() {
        Label eyebrow = new Label("MAKE IT PERSONAL");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("A memory for this world");
        title.getStyleClass().add("section-title");
        Label description = new Label(
                "Bring in a photo that reminds you of this place, then add the story and quote you want to keep with it.");
        description.setWrapText(true);
        description.getStyleClass().add("world-section-description");
        VBox heading = new VBox(7, eyebrow, title, description);

        choosePhotoButton = new Button(world.memoryPhoto() == null ? "＋  Choose a photo" : "Change photo");
        choosePhotoButton.getStyleClass().add("quiet-button");
        choosePhotoButton.setOnAction(event -> chooseMemoryPhoto());

        Label storyLabel = new Label("Story or memory");
        storyLabel.getStyleClass().add("memory-field-label");
        TextArea storyEditor = new TextArea(world.memoryStory());
        storyEditor.setPromptText("What happened here? What should you remember about this place?");
        storyEditor.setWrapText(true);
        storyEditor.setPrefRowCount(4);
        storyEditor.getStyleClass().add("memory-text-area");
        storyEditor.textProperty().addListener((observable, previous, current) -> {
            world.setMemoryStory(current);
            updateMemoryOverlay();
        });
        Button useStoryIdea = new Button("Use a story idea");
        useStoryIdea.getStyleClass().add("world-back-button");
        useStoryIdea.setDisable(world.stories().isEmpty());
        useStoryIdea.setOnAction(event -> chooseStoryIdea(storyEditor));
        world.stories().addListener((ListChangeListener<String>) change ->
                useStoryIdea.setDisable(world.stories().isEmpty()));
        HBox storyHeader = new HBox(storyLabel, useStoryIdea);
        storyHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(storyLabel, Priority.ALWAYS);

        Label quoteLabel = new Label("Quote (optional)");
        quoteLabel.getStyleClass().add("memory-field-label");
        TextField quoteEditor = new TextField(world.memoryQuote());
        quoteEditor.setPromptText("A line you want to keep close");
        quoteEditor.textProperty().addListener((observable, previous, current) -> {
            world.setMemoryQuote(current);
            updateMemoryOverlay();
        });

        previewApplyButton.getStyleClass().add("primary-button");
        previewApplyButton.setMaxWidth(Double.MAX_VALUE);
        previewApplyButton.setDisable(world.memoryPhoto() == null);
        previewApplyButton.setOnAction(event -> previewBeforeApplying());

        saveWallpaperButton.getStyleClass().add("quiet-button");
        saveWallpaperButton.setText("Save wallpaper image");
        saveWallpaperButton.setMaxWidth(Double.MAX_VALUE);
        saveWallpaperButton.setDisable(world.memoryPhoto() == null);
        saveWallpaperButton.setOnAction(event -> saveMemoryWallpaper());
        desktopResolutionHint = new Label();
        desktopResolutionHint.getStyleClass().add("memory-export-hint");
        refreshDesktopResolution();

        VBox editor = new VBox(10, choosePhotoButton, storyHeader, storyEditor, quoteLabel, quoteEditor,
                previewApplyButton, saveWallpaperButton, desktopResolutionHint);
        editor.getStyleClass().add("memory-editor");
        editor.setMinWidth(260);
        HBox.setHgrow(editor, Priority.ALWAYS);

        createMemoryPreview();
        if (world.memoryPhoto() != null) {
            loadMemoryPhoto(world.memoryPhoto());
        }

        HBox body = new HBox(18, editor, memoryPreview);
        body.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(memoryPreview, Priority.ALWAYS);

        VBox card = new VBox(18, heading, body);
        card.setPadding(new Insets(22));
        card.getStyleClass().add("memory-wallpaper-card");
        return card;
    }

    private void createMemoryPreview() {
        memoryPreview.setMinHeight(280);
        memoryPreview.setPrefHeight(330);
        memoryPreview.setMinWidth(300);
        memoryPreview.getStyleClass().add("memory-wallpaper-preview");
        memoryPreview.widthProperty().addListener((observable, previous, current) -> fitMemoryImage());
        memoryPreview.heightProperty().addListener((observable, previous, current) -> fitMemoryImage());
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(memoryPreview.widthProperty());
        clip.heightProperty().bind(memoryPreview.heightProperty());
        memoryPreview.setClip(clip);

        photoPlaceholder = new Label("▧\n\nYour memory photo will appear here");
        photoPlaceholder.setAlignment(Pos.CENTER);
        photoPlaceholder.setWrapText(true);
        photoPlaceholder.getStyleClass().add("memory-preview-placeholder");

        Region shade = new Region();
        shade.getStyleClass().add("memory-preview-shade");
        StackPane.setAlignment(shade, Pos.CENTER);

        previewWorldName.setText(world.name());
        previewWorldName.getStyleClass().add("memory-preview-world");
        previewStory.setWrapText(true);
        previewStory.setMaxWidth(Double.MAX_VALUE);
        previewStory.getStyleClass().add("memory-preview-story");
        previewQuote.setWrapText(true);
        previewQuote.setMaxWidth(Double.MAX_VALUE);
        previewQuote.getStyleClass().add("memory-preview-quote");
        memoryOverlay.getChildren().setAll(previewWorldName, previewStory, previewQuote);
        memoryOverlay.setPadding(new Insets(20));
        memoryOverlay.setMaxWidth(Double.MAX_VALUE);
        StackPane.setAlignment(memoryOverlay, Pos.BOTTOM_LEFT);

        memoryPreview.getChildren().setAll(photoPlaceholder, shade, memoryOverlay);
        updateMemoryOverlay();
    }

    private void chooseMemoryPhoto() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a memory photo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }

        Path path = file.toPath().toAbsolutePath().normalize();
        Image image = new Image(path.toUri().toString(), false);
        if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
            showAlert(Alert.AlertType.ERROR, "Photo could not be opened",
                    "Choose a valid PNG, JPEG, GIF, or BMP image.");
            return;
        }

        world.setMemoryPhoto(path);
        loadMemoryPhoto(image);
        previewApplyButton.setDisable(false);
        saveWallpaperButton.setDisable(false);
        choosePhotoButton.setText("Change photo");
    }

    private void chooseStoryIdea(TextArea storyEditor) {
        if (world.stories().isEmpty()) {
            return;
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(world.stories().get(0), world.stories());
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Choose a story idea");
        dialog.setHeaderText("Add one of " + world.name() + "'s story ideas to this wallpaper.");
        dialog.setContentText("Story idea:");
        dialog.getDialogPane().getStylesheets().add(
                WorldWorkspaceView.class.getResource("/styles/app.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("world-dialog");
        dialog.showAndWait().ifPresent(storyEditor::setText);
    }

    private void loadMemoryPhoto(Path path) {
        Image image = new Image(path.toUri().toString(), false);
        if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
            world.setMemoryPhoto(null);
            previewApplyButton.setDisable(true);
            saveWallpaperButton.setDisable(true);
            showAlert(Alert.AlertType.ERROR, "Photo could not be opened",
                    "The saved photo is no longer available. Choose it again to continue.");
            return;
        }
        loadMemoryPhoto(image);
        previewApplyButton.setDisable(false);
        saveWallpaperButton.setDisable(false);
    }

    private void loadMemoryPhoto(Image image) {
        memoryImageView = new ImageView(image);
        memoryImageView.setPreserveRatio(true);
        memoryImageView.setSmooth(true);
        memoryImageView.setMouseTransparent(true);
        memoryImageView.setManaged(false);
        memoryPreview.getChildren().set(0, memoryImageView);
        if (photoPlaceholder != null) {
            photoPlaceholder.setVisible(false);
        }
        fitMemoryImage();
    }

    private void fitMemoryImage() {
        if (memoryImageView == null || memoryImageView.getImage() == null) {
            return;
        }
        double width = memoryPreview.getWidth();
        double height = memoryPreview.getHeight();
        double imageRatio = memoryImageView.getImage().getWidth() / memoryImageView.getImage().getHeight();
        double frameRatio = width / Math.max(height, 1);
        if (imageRatio > frameRatio) {
            memoryImageView.setFitHeight(height);
            memoryImageView.setFitWidth(height * imageRatio);
        } else {
            memoryImageView.setFitWidth(width);
            memoryImageView.setFitHeight(width / imageRatio);
        }
        memoryImageView.setTranslateX((width - memoryImageView.getBoundsInLocal().getWidth()) / 2);
        memoryImageView.setTranslateY((height - memoryImageView.getBoundsInLocal().getHeight()) / 2);
    }

    private void updateMemoryOverlay() {
        previewStory.setText(world.memoryStory().strip());
        previewStory.setVisible(!previewStory.getText().isBlank());
        previewQuote.setText(world.memoryQuote().strip().isBlank()
                ? ""
                : "“" + world.memoryQuote().strip() + "”");
        previewQuote.setVisible(!previewQuote.getText().isBlank());
        memoryOverlay.setVisible(world.memoryPhoto() != null);
    }

    private void previewBeforeApplying() {
        Path photoPath = world.memoryPhoto();
        if (photoPath == null) {
            return;
        }

        refreshDesktopResolution();
        ScreenRatio ratio = promptForScreenRatio();
        if (ratio == null) {
            return;
        }
        Dimension wallpaperSize = resolutionFor(ratio);
        try {
            BufferedImage composedWallpaper = renderMemoryWallpaper(
                    photoPath, wallpaperSize.width, wallpaperSize.height);
            ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
            if (!ImageIO.write(composedWallpaper, "png", imageBytes)) {
                throw new IOException("PNG image support is unavailable.");
            }

            ImageView desktopPreview = new ImageView(new Image(new ByteArrayInputStream(imageBytes.toByteArray())));
            desktopPreview.setPreserveRatio(true);
            desktopPreview.setSmooth(true);
            desktopPreview.setFitWidth(760);
            desktopPreview.setFitHeight(500);
            StackPane imageFrame = new StackPane(desktopPreview);
            imageFrame.setAlignment(Pos.CENTER);
            imageFrame.getStyleClass().add("desktop-wallpaper-preview-frame");

            Label dimensions = new Label(ratio.label() + " · "
                    + wallpaperSize.width + " × " + wallpaperSize.height);
            dimensions.getStyleClass().add("memory-field-label");
            Label note = new Label("This is the finished wallpaper, including the story and quote.");
            note.getStyleClass().add("memory-export-hint");
            VBox previewContent = new VBox(10, dimensions, imageFrame, note);
            previewContent.setAlignment(Pos.CENTER_LEFT);

            ButtonType applyType = new ButtonType("Apply wallpaper", ButtonBar.ButtonData.OK_DONE);
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(getScene().getWindow());
            dialog.setTitle("Preview desktop wallpaper");
            dialog.setHeaderText("Check the crop and text placement before applying.");
            dialog.getDialogPane().setContent(previewContent);
            dialog.getDialogPane().getButtonTypes().setAll(applyType, ButtonType.CANCEL);
            dialog.getDialogPane().getStylesheets().add(
                    WorldWorkspaceView.class.getResource("/styles/app.css").toExternalForm());
            dialog.getDialogPane().getStyleClass().add("world-dialog");
            dialog.setResultConverter(buttonType -> buttonType);
            Optional<ButtonType> result = dialog.showAndWait();
            if (result.isPresent() && result.get() == applyType) {
                applyMemoryWallpaper(composedWallpaper, wallpaperSize);
            }
        } catch (IOException failure) {
            showAlert(Alert.AlertType.ERROR, "Could not prepare wallpaper preview", failure.getMessage());
        }
    }

    private ScreenRatio promptForScreenRatio() {
        String primaryLabel = "Use primary display (" + ratioName(desktopResolution.width, desktopResolution.height) + ")";
        ScreenRatio primary = new ScreenRatio(
                primaryLabel, desktopResolution.width, desktopResolution.height, true);
        List<ScreenRatio> choices = new ArrayList<>();
        choices.add(primary);
        choices.addAll(List.of(
                new ScreenRatio("16:9", 16, 9, false),
                new ScreenRatio("16:10", 16, 10, false),
                new ScreenRatio("4:3", 4, 3, false),
                new ScreenRatio("3:2", 3, 2, false),
                new ScreenRatio("21:9", 21, 9, false),
                new ScreenRatio("32:9", 32, 9, false),
                new ScreenRatio("9:16 (portrait)", 9, 16, false)));
        for (String savedRatio : world.customScreenRatios()) {
            String[] parts = savedRatio.split("\\s*:\\s*");
            choices.add(new ScreenRatio(savedRatio,
                    Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), false));
        }
        List<String> labels = choices.stream().map(ScreenRatio::label).collect(java.util.stream.Collectors.toList());
        labels.add(ADD_CUSTOM_RATIO);

        ChoiceDialog<String> dialog = new ChoiceDialog<>(primaryLabel, labels);
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Choose screen ratio");
        dialog.setHeaderText("Choose a ratio for the wallpaper preview and image.");
        dialog.setContentText("Screen ratio:");
        dialog.getDialogPane().getStylesheets().add(
                WorldWorkspaceView.class.getResource("/styles/app.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("world-dialog");

        Optional<String> selected = dialog.showAndWait();
        if (selected.isEmpty()) {
            return null;
        }
        if (ADD_CUSTOM_RATIO.equals(selected.get())) {
            ScreenRatio custom = promptForCustomRatio();
            if (custom == null) {
                return null;
            }
            world.addCustomScreenRatio(custom.label());
            return custom;
        }
        return choices.stream()
                .filter(choice -> choice.label().equals(selected.get()))
                .findFirst()
                .orElse(primary);
    }

    private ScreenRatio promptForCustomRatio() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Add a screen ratio");
        dialog.setHeaderText("Enter a custom width:height ratio, such as 5:4 or 2.39:1.");
        dialog.setContentText("Custom ratio:");
        dialog.getEditor().setPromptText("width:height");
        dialog.getDialogPane().getStylesheets().add(
                WorldWorkspaceView.class.getResource("/styles/app.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("world-dialog");

        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return null;
        }
        String ratioText = result.get().strip();
        String[] parts = ratioText.split("\\s*:\\s*");
        if (parts.length != 2) {
            showAlert(Alert.AlertType.ERROR, "Invalid screen ratio", "Enter a ratio in width:height format.");
            return null;
        }
        try {
            double width = Double.parseDouble(parts[0]);
            double height = Double.parseDouble(parts[1]);
            double aspect = width / height;
            if (!Double.isFinite(width) || !Double.isFinite(height)
                    || width <= 0 || height <= 0 || width > 10000 || height > 10000
                    || !Double.isFinite(aspect) || aspect < 0.1 || aspect > 10) {
                throw new NumberFormatException("Ratio must be between 1:10 and 10:1.");
            }
            return new ScreenRatio(ratioText, width, height, false);
        } catch (NumberFormatException invalidRatio) {
            showAlert(Alert.AlertType.ERROR, "Invalid screen ratio", "Use positive numbers, such as 5:4 or 2.39:1.");
            return null;
        }
    }

    private Dimension resolutionFor(ScreenRatio ratio) {
        if (ratio.primaryDisplay()) {
            return new Dimension(desktopResolution);
        }
        double displayPixels = (double) desktopResolution.width * desktopResolution.height;
        double aspect = ratio.width() / ratio.height();
        int width = Math.max(1, (int) Math.round(Math.sqrt(displayPixels * aspect)));
        int height = Math.max(1, (int) Math.round(Math.sqrt(displayPixels / aspect)));
        return new Dimension(width, height);
    }

    private static String ratioName(int width, int height) {
        int divisor = greatestCommonDivisor(width, height);
        return (width / divisor) + ":" + (height / divisor);
    }

    private static int greatestCommonDivisor(int left, int right) {
        while (right != 0) {
            int remainder = left % right;
            left = right;
            right = remainder;
        }
        return Math.max(1, left);
    }

    private void applyMemoryWallpaper(BufferedImage wallpaper, Dimension wallpaperSize) {
        String safeWorldName = world.name().replaceAll("[\\\\/:*?\"<>|]", "-");
        if (safeWorldName.isBlank()) {
            safeWorldName = "memory-world";
        }
        Path destination = Path.of(System.getProperty("user.home"), "Pictures", "RealityWeaver",
                safeWorldName + "-memory-wallpaper.png");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Files.createDirectories(destination.getParent());
                if (!ImageIO.write(wallpaper, "png", destination.toFile())) {
                    throw new IOException("PNG image support is unavailable.");
                }
                wallpaperService.apply(destination);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            onManualApplied.run();
            showAlert(Alert.AlertType.INFORMATION, "Wallpaper applied",
                    "Applied at " + wallpaperSize.width + " × " + wallpaperSize.height
                            + ". A copy was saved to:\n" + destination.toAbsolutePath());
        });
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            Alert.AlertType type = failure instanceof UnsupportedOperationException
                    ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR;
            showAlert(type, failure instanceof UnsupportedOperationException
                    ? "Desktop apply is not supported" : "Could not apply wallpaper", safeMessage(failure));
        });
        Thread worker = new Thread(task, "realityweaver-memory-wallpaper-apply");
        worker.setDaemon(true);
        worker.start();
    }

    private String safeMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Check the image and try again." : failure.getMessage();
    }

    private void saveMemoryWallpaper() {
        Path photoPath = world.memoryPhoto();
        if (photoPath == null) {
            return;
        }

        refreshDesktopResolution();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save memory wallpaper");
        chooser.setInitialFileName(world.name().replaceAll("[\\\\/:*?\"<>|]", "-") + "-memory.png");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) {
            return;
        }

        File destination = file.getName().toLowerCase().endsWith(".png")
                ? file
                : new File(file.getParentFile(), file.getName() + ".png");
        try {
            BufferedImage output = renderMemoryWallpaper(photoPath);
            if (!ImageIO.write(output, "png", destination)) {
                throw new IOException("PNG image support is unavailable.");
            }
            showAlert(Alert.AlertType.INFORMATION, "Memory wallpaper saved",
                    "Your photo, story, and quote were saved to:\n" + destination.getAbsolutePath());
        } catch (IOException failure) {
            showAlert(Alert.AlertType.ERROR, "Could not save wallpaper", failure.getMessage());
        }
    }

    private BufferedImage renderMemoryWallpaper(Path photoPath) throws IOException {
        return renderMemoryWallpaper(photoPath, desktopResolution.width, desktopResolution.height);
    }

    private BufferedImage renderMemoryWallpaper(Path photoPath, int targetWidth, int targetHeight) throws IOException {
        BufferedImage photo = ImageIO.read(photoPath.toFile());
        if (photo == null) {
            throw new IOException("This photo format could not be exported as a wallpaper.");
        }

        BufferedImage output = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double scale = Math.max(
                    targetWidth / (double) photo.getWidth(),
                    targetHeight / (double) photo.getHeight());
            int scaledWidth = (int) Math.ceil(photo.getWidth() * scale);
            int scaledHeight = (int) Math.ceil(photo.getHeight() * scale);
            graphics.drawImage(photo,
                    (targetWidth - scaledWidth) / 2,
                    (targetHeight - scaledHeight) / 2,
                    scaledWidth,
                    scaledHeight,
                    null);

            double fontScale = targetHeight / 1080.0;
            graphics.setPaint(new GradientPaint(
                    0, (float) (targetHeight * 0.435), new Color(10, 9, 18, 0),
                    0, targetHeight, new Color(10, 9, 18, 226)));
            int shadeStart = (int) (targetHeight * 0.389);
            graphics.fillRect(0, shadeStart, targetWidth, targetHeight - shadeStart);

            int left = Math.max(24, (int) Math.round(targetWidth * 0.058));
            int maxTextWidth = targetWidth - 2 * left;
            int baseline = targetHeight - Math.max(40, (int) Math.round(targetHeight * 0.102));
            String quote = world.memoryQuote().strip();
            if (!quote.isBlank()) {
                graphics.setFont(new Font(Font.SANS_SERIF, Font.ITALIC,
                        Math.max(18, (int) Math.round(34 * fontScale))));
                graphics.setColor(new Color(222, 205, 255));
                List<String> quoteLines = wrapText(graphics, "“" + quote + "”", maxTextWidth, 2);
                baseline = drawLines(graphics, quoteLines, left, baseline,
                        Math.max(22, (int) Math.round(44 * fontScale)));
            }

            String story = world.memoryStory().strip();
            if (!story.isBlank()) {
                baseline -= quote.isBlank() ? 0 : Math.max(12, (int) Math.round(24 * fontScale));
                graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN,
                        Math.max(18, (int) Math.round(31 * fontScale))));
                graphics.setColor(new Color(247, 244, 252));
                List<String> storyLines = wrapText(graphics, story, maxTextWidth, 5);
                baseline = drawLines(graphics, storyLines, left, baseline,
                        Math.max(22, (int) Math.round(43 * fontScale)));
            }

            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD,
                    Math.max(22, (int) Math.round(42 * fontScale))));
            graphics.setColor(Color.WHITE);
            graphics.drawString(wrapText(graphics, world.name(), maxTextWidth, 1).get(0), left,
                    baseline - Math.max(12, (int) Math.round(20 * fontScale)));
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private List<String> wrapText(Graphics2D graphics, String text, int maxWidth, int maxLines) {
        FontMetrics metrics = graphics.getFontMetrics();
        List<String> lines = new ArrayList<>();
        String[] paragraphs = text.split("\\R");
        boolean truncated = false;
        paragraphLoop:
        for (int paragraphIndex = 0; paragraphIndex < paragraphs.length; paragraphIndex++) {
            String paragraph = paragraphs[paragraphIndex];
            String[] words = paragraph.split("\\s+");
            StringBuilder line = new StringBuilder();
            for (int wordIndex = 0; wordIndex < words.length; wordIndex++) {
                String word = words[wordIndex];
                if (word.isBlank()) {
                    continue;
                }
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (metrics.stringWidth(candidate) > maxWidth && line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                    if (lines.size() == maxLines) {
                        truncated = wordIndex < words.length || paragraphIndex < paragraphs.length - 1;
                        break paragraphLoop;
                    }
                }
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            }
            if (line.length() > 0 && lines.size() < maxLines) {
                lines.add(line.toString());
            }
            if (lines.size() == maxLines) {
                truncated = paragraphIndex < paragraphs.length - 1;
                break;
            }
        }
        if (truncated && !lines.isEmpty()) {
            String last = lines.get(maxLines - 1);
            while (!last.isEmpty() && metrics.stringWidth(last + "…") > maxWidth) {
                last = last.substring(0, last.length() - 1).stripTrailing();
            }
            lines.set(maxLines - 1, last + "…");
        }
        return lines;
    }

    private int drawLines(Graphics2D graphics, List<String> lines, int x, int baseline, int lineHeight) {
        for (int index = lines.size() - 1; index >= 0; index--) {
            graphics.drawString(lines.get(index), x, baseline);
            baseline -= lineHeight;
        }
        return baseline;
    }

    private static Dimension detectDesktopResolution() {
        try {
            DisplayMode mode = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDisplayMode();
            if (mode.getWidth() > 0 && mode.getHeight() > 0) {
                return new Dimension(mode.getWidth(), mode.getHeight());
            }
        } catch (HeadlessException | SecurityException | java.awt.AWTError ignored) {
            // Use a standard landscape size when display information is unavailable.
        }
        return new Dimension(1920, 1080);
    }

    private void refreshDesktopResolution() {
        desktopResolution = detectDesktopResolution();
        if (desktopResolutionHint != null) {
            desktopResolutionHint.setText("Default ratio: primary display ("
                    + desktopResolution.width + " × " + desktopResolution.height
                    + "). Choose another ratio or add a custom one before applying.");
        }
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
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
