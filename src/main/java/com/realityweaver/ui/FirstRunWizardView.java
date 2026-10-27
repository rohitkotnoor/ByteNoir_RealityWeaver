package com.realityweaver.ui;

import com.realityweaver.AppSettings;
import com.realityweaver.auth.LocalAccount;
import com.realityweaver.wallpaper.WallpaperAutomationService;
import com.realityweaver.wallpaper.WallpaperService;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Four short onboarding steps; the user can finish directly in a local workspace. */
public final class FirstRunWizardView extends BorderPane {
    private static final List<String> TOPICS = List.of(
            "Any topic", "Nature", "Architecture", "Space", "Games", "Animals", "Vehicles");

    private final Consumer<LocalAccount> onFinished;
    private final Runnable onSignIn;
    private final WallpaperAutomationService automationService;
    private final WallpaperService wallpaperService = new WallpaperService();
    private final VBox stepContent = new VBox(16);
    private final Label stepIndicator = new Label();
    private final Label calendarSelection = new Label("No calendar file selected");
    private final Button backButton = new Button("Back");
    private final Button nextButton = new Button("Continue");
    private final Button skipButton = new Button("Skip setup");
    private final ComboBox<WallpaperService.WallpaperFit> fitChoice = new ComboBox<>();
    private final ComboBox<String> topicChoice = new ComboBox<>();
    private final javafx.scene.control.CheckBox approximateWeather = new javafx.scene.control.CheckBox(
            "Allow approximate location for weather rules");
    private Path calendarFile;
    private int step;

    public FirstRunWizardView(Consumer<LocalAccount> onFinished, Runnable onSignIn,
            WallpaperAutomationService automationService) {
        this.onFinished = onFinished;
        this.onSignIn = onSignIn;
        this.automationService = automationService;
        buildPage();
        showStep();
    }

    private void buildPage() {
        getStyleClass().add("setup-page");
        Label brand = new Label("✦  RealityWeaver");
        brand.getStyleClass().add("brand-name");
        Label caption = new Label("A DESKTOP FOR YOUR WORLDS");
        caption.getStyleClass().add("brand-caption");
        VBox masthead = new VBox(4, brand, caption);
        masthead.setPadding(new Insets(24, 34, 12, 34));
        setTop(masthead);

        stepContent.getStyleClass().add("setup-card");
        stepContent.setMaxWidth(720);
        stepContent.setPadding(new Insets(34));
        ScrollPane scroll = new ScrollPane(stepContent);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("content-scroll");
        StackPane center = new StackPane(scroll);
        center.setPadding(new Insets(10, 24, 20, 24));
        setCenter(center);

        stepIndicator.getStyleClass().add("eyebrow");
        backButton.getStyleClass().add("quiet-button");
        backButton.setOnAction(event -> {
            step = Math.max(0, step - 1);
            showStep();
        });
        nextButton.getStyleClass().add("primary-button");
        nextButton.setOnAction(event -> {
            if (step < 3) {
                step++;
                showStep();
            } else {
                finish();
            }
        });
        skipButton.getStyleClass().add("auth-link-button");
        skipButton.setOnAction(event -> finish());
        HBox footer = new HBox(12, stepIndicator, skipButton, backButton, nextButton);
        footer.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(stepIndicator, Priority.ALWAYS);
        footer.setPadding(new Insets(12, 34, 24, 34));
        setBottom(footer);
    }

    private void showStep() {
        stepIndicator.setText("STEP " + (step + 1) + " OF 4");
        backButton.setDisable(step == 0);
        nextButton.setText(step == 3 ? "Open my workspace" : step == 0 ? "Get started" : "Continue");
        skipButton.setVisible(step < 3);
        skipButton.setManaged(step < 3);
        stepContent.getChildren().clear();
        stepContent.getChildren().addAll(stepTitle(), stepPanel());
    }

    private Label stepTitle() {
        Label title = new Label(switch (step) {
            case 0 -> "Welcome to RealityWeaver";
            case 1 -> "Make it feel like yours";
            case 2 -> "Optional connections";
            default -> "You’re ready to explore";
        });
        title.getStyleClass().add("auth-title");
        title.setWrapText(true);
        return title;
    }

    private Node stepPanel() {
        return switch (step) {
            case 0 -> welcomePanel();
            case 1 -> preferencesPanel();
            case 2 -> integrationsPanel();
            default -> finishPanel();
        };
    }

    private VBox welcomePanel() {
        Label explanation = paragraph("Find wallpapers, save collections, and let your desktop follow your day. "
                + "Your images and settings stay on this computer.");
        Label localUse = paragraph("You can start right away without creating an account. Sign-in remains optional.");
        Button signIn = new Button("I already have an account · Sign in");
        signIn.getStyleClass().add("auth-link-button");
        signIn.setOnAction(event -> onSignIn.run());
        return new VBox(14, explanation, localUse, signIn);
    }

    private VBox preferencesPanel() {
        fitChoice.getItems().setAll(WallpaperService.WallpaperFit.values());
        fitChoice.setValue(wallpaperService.preferredFit());
        fitChoice.getStyleClass().add("automation-wallpaper-choice");
        topicChoice.getItems().setAll(TOPICS);
        topicChoice.setValue(AppSettings.defaultWallpaperTopic());
        topicChoice.getStyleClass().add("automation-wallpaper-choice");
        Label description = paragraph("Choose a starting topic and how new images should fit your desktop. "
                + "You can change both later in Settings.");
        HBox topicRow = labelledChoice("Starting wallpaper topic", topicChoice);
        HBox fitRow = labelledChoice("Desktop image fit", fitChoice);
        return new VBox(16, description, topicRow, fitRow);
    }

    private VBox integrationsPanel() {
        approximateWeather.setSelected(automationService.isAutomaticLocationEnabled());
        approximateWeather.getStyleClass().add("automation-option");
        Label weatherInfo = paragraph("If enabled, weather rules estimate your location from your internet connection. "
                + "The approximate location is sent to ipapi.co and weather coordinates to Open-Meteo. You can leave this off and enter a city instead.");
        Button chooseCalendar = new Button("Choose a local .ics calendar");
        chooseCalendar.getStyleClass().add("quiet-button");
        chooseCalendar.setOnAction(event -> chooseCalendarFile());
        calendarSelection.setWrapText(true);
        calendarSelection.getStyleClass().add("setup-note");
        Label calendarInfo = paragraph("On Windows, RealityWeaver can also read the default calendar from Classic Outlook when it is installed. Calendar access is optional; rules can be configured later.");
        return new VBox(12, approximateWeather, weatherInfo, chooseCalendar, calendarSelection, calendarInfo);
    }

    private VBox finishPanel() {
        String topic = topicChoice.getValue() == null ? "Any topic" : topicChoice.getValue();
        String fit = fitChoice.getValue() == null ? "Fill" : fitChoice.getValue().toString();
        Label summary = paragraph("Your workspace will open with " + topic + " as its starting topic and " + fit
                + " as its desktop fit. You can search immediately, import local wallpapers, or create a collection.");
        Label account = paragraph("No account is required. You can create or sign in to a local account later if you want.");
        return new VBox(14, summary, account);
    }

    private HBox labelledChoice(String title, ComboBox<?> choice) {
        Label label = new Label(title);
        label.getStyleClass().add("auth-field-label");
        HBox row = new HBox(16, label, choice);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(label, Priority.ALWAYS);
        choice.setPrefWidth(250);
        return row;
    }

    private Label paragraph(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("auth-description");
        return label;
    }

    private void chooseCalendarFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a local calendar file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("iCalendar file (*.ics)", "*.ics"));
        File selected = chooser.showOpenDialog(getScene().getWindow());
        if (selected != null) {
            calendarFile = selected.toPath().toAbsolutePath().normalize();
            calendarSelection.setText("Selected: " + calendarFile.getFileName());
        }
    }

    private void finish() {
        wallpaperService.setPreferredFit(fitChoice.getValue() == null
                ? WallpaperService.WallpaperFit.FILL : fitChoice.getValue());
        AppSettings.setDefaultWallpaperTopic(topicChoice.getValue());
        automationService.setAutomaticLocationEnabled(approximateWeather.isSelected());
        if (calendarFile != null) {
            automationService.setCalendarFile(calendarFile);
        }
        AppSettings.setSetupComplete(true);
        onFinished.accept(new LocalAccount("Local workspace", ""));
    }
}
