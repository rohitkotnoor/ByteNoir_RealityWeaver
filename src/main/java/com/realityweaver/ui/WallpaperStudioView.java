package com.realityweaver.ui;

import com.realityweaver.StartupService;
import com.realityweaver.wallpaper.WallpaperLibrary;
import com.realityweaver.wallpaper.WallpaperAutomationService;
import com.realityweaver.wallpaper.WallpaperSearchService;
import com.realityweaver.wallpaper.WallpaperService;
import java.io.File;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

/** Wallpaper gallery with import, preview, favorites, and desktop apply actions. */
public final class WallpaperStudioView extends BorderPane {
    private final WallpaperLibrary library = new WallpaperLibrary();
    private final WallpaperAutomationService automationService;
    private final WallpaperService wallpaperService = new WallpaperService();
    private final StartupService startupService = new StartupService();
    private final WallpaperCollectionView collectionsView;
    private TabPane studioTabs;
    private final List<Path> imagePaths = new ArrayList<>(library.load());
    private final StackPane previewFrame = new StackPane();
    private final Label selectedName = new Label("No wallpaper selected");
    private final Label selectedDetails = new Label("Import an image to preview it here.");
    private final Label libraryCount = new Label();
    private final VBox galleryItems = new VBox(8);
    private final VBox recentWallpaperPanel = new VBox(8);
    private final HBox recentWallpaperRow = new HBox(10);
    private final ComboBox<String> categoryFilter = new ComboBox<>();
    private final ComboBox<String> selectedCategorySelector = new ComboBox<>();
    private final Button favoriteFilter = new Button("☆ Favorites only");
    private final Button addCategoryButton = new Button("＋ Category");
    private final Button favoriteButton = new Button("☆ Add to favorites");
    private final Button applyButton = new Button("Set as Wallpaper");
    private final Button removeButton = new Button("Remove from library");
    private final ComboBox<WallpaperService.WallpaperFit> wallpaperFitChoice = new ComboBox<>();
    private final Label applyStatus = new Label();
    private final CheckBox automationEnabled = new CheckBox("Enable automatic wallpaper changes");
    private final Label automationStatus = new Label("Automation is off.");
    private final Button resumeAutomationButton = new Button("Resume Automation");
    private final VBox timeRulesList = new VBox(7);
    private final VBox weatherRulesList = new VBox(7);
    private final VBox calendarRulesList = new VBox(7);
    private final List<ComboBox<Path>> automationWallpaperChoices = new ArrayList<>();
    private final TextField timeRuleInput = new TextField();
    private final ComboBox<Path> timeWallpaperChoice = createWallpaperChoice();
    private final CheckBox automaticWeatherLocation = new CheckBox("Use this PC's approximate location");
    private final TextField weatherCityInput = new TextField();
    private final ComboBox<WallpaperAutomationService.WeatherCondition> weatherConditionChoice = new ComboBox<>();
    private final ComboBox<Path> weatherWallpaperChoice = createWallpaperChoice();
    private final TextField calendarKeywordInput = new TextField();
    private final Label calendarFileStatus = new Label("No .ics calendar selected");
    private final CheckBox outlookCalendarEnabled = new CheckBox("Read this PC's Outlook calendar automatically");
    private final ComboBox<Path> calendarWallpaperChoice = createWallpaperChoice();

    private Path selectedPath;
    private boolean favoritesOnly;

    public WallpaperStudioView() {
        this(new WallpaperAutomationService());
    }

    public WallpaperStudioView(WallpaperAutomationService automationService) {
        this.automationService = automationService;
        collectionsView = new WallpaperCollectionView(library, automationService);
        getStyleClass().add("wallpaper-page");
        setCenter(createContent());
        refreshGallery();
        if (!imagePaths.isEmpty()) {
            Path initialSelection = library.recent().stream()
                    .filter(imagePaths::contains)
                    .findFirst()
                    .orElse(imagePaths.get(0));
            selectWallpaper(initialSelection);
        } else {
            showPlaceholder();
        }
        automationEnabled.setSelected(automationService.isEnabled());
        automationService.startIfEnabled(this::updateAutomationStatus);
        collectionsView.activate();
    }

    private ScrollPane createContent() {
        VBox content = new VBox(24, createHeading(), createStudioTabs());
        content.setPadding(new Insets(38, 48, 52, 48));
        content.setMaxWidth(Double.MAX_VALUE);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("content-scroll");
        return scrollPane;
    }

    private TabPane createStudioTabs() {
        TabPane tabs = new TabPane();
        studioTabs = tabs;
        tabs.getStyleClass().add("wallpaper-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefHeight(700);
        tabs.setMinHeight(600);

        Tab libraryTab = new Tab("My Wallpapers", createWorkspace());
        OnlineWallpaperView onlineView = new OnlineWallpaperView(this::addDownloadedWallpaper,
                wallpaperService, automationService::recordManualOverride);
        ScrollPane onlineScroll = new ScrollPane(onlineView);
        onlineScroll.setFitToWidth(true);
        onlineScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        onlineScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        onlineScroll.getStyleClass().add("online-tab-scroll");
        Tab onlineTab = new Tab("Discover", onlineScroll);
        Tab collectionsTab = new Tab("Collections", collectionsView);
        Tab automationTab = new Tab("Automation", createAutomationPanel());
        Tab settingsTab = new Tab("Settings", createSettingsPanel());
        tabs.getTabs().addAll(libraryTab, onlineTab, collectionsTab, automationTab, settingsTab);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected == collectionsTab) {
                collectionsView.activate();
            }
        });
        return tabs;
    }

    public void showSection(String section) {
        if (studioTabs == null || section == null) {
            return;
        }
        studioTabs.getTabs().stream()
                .filter(tab -> tab.getText().equalsIgnoreCase(section))
                .findFirst()
                .ifPresent(studioTabs.getSelectionModel()::select);
    }

    private ScrollPane createSettingsPanel() {
        Label currentTitle = new Label("Current desktop wallpaper");
        currentTitle.getStyleClass().add("section-title");
        Path currentWall = wallpaperService.getCurrentWallpaper().orElse(null);
        Label currentWallStatus = new Label(currentWall != null
                ? "Active on desktop: " + currentWall.getFileName() + " (" + currentWall + ")"
                : "Active on desktop: Default or managed externally.");
        currentWallStatus.setWrapText(true);
        currentWallStatus.getStyleClass().add("memory-export-hint");
        VBox desktopCard = new VBox(10, currentTitle, currentWallStatus);
        desktopCard.getStyleClass().add("automation-section-card");

        Label title = new Label("Wallpaper scaling & fit");
        title.getStyleClass().add("section-title");
        Label detail = new Label("Choose how images fit your desktop screen. Windows and GNOME apply all four modes (Fill, Fit, Stretch, Center); macOS uses desktop preferences.");
        detail.getStyleClass().add("page-subtitle");
        detail.setWrapText(true);
        wallpaperFitChoice.getItems().setAll(WallpaperService.WallpaperFit.values());
        wallpaperFitChoice.setValue(wallpaperService.preferredFit());
        wallpaperFitChoice.getStyleClass().add("automation-wallpaper-choice");
        wallpaperFitChoice.setOnAction(event -> wallpaperService.setPreferredFit(wallpaperFitChoice.getValue()));
        Label fitLabel = new Label("Desktop image fit mode");
        fitLabel.getStyleClass().add("memory-field-label");
        HBox fitRow = new HBox(12, fitLabel, wallpaperFitChoice);
        fitRow.setAlignment(Pos.CENTER_LEFT);
        VBox appearanceCard = new VBox(12, title, detail, fitRow);
        appearanceCard.getStyleClass().add("automation-section-card");

        Label apiTitle = new Label("Provider API keys (optional)");
        apiTitle.getStyleClass().add("section-title");
        Label apiDetail = new Label("Bing 4K UHD, Picsum, and Wallhaven work out-of-the-box without keys. To connect Pexels, Pixabay, or Wallhaven personal accounts, paste your keys below.");
        apiDetail.setWrapText(true);
        apiDetail.getStyleClass().add("page-subtitle");

        TextField wallhavenInput = new TextField(WallpaperSearchService.getWallhavenApiKey());
        wallhavenInput.setPromptText("Wallhaven API key (optional)");
        Button saveWallhaven = new Button("Save Wallhaven");
        saveWallhaven.getStyleClass().add("quiet-button");
        saveWallhaven.setOnAction(e -> {
            WallpaperSearchService.setWallhavenApiKey(wallhavenInput.getText());
            showAlert(Alert.AlertType.INFORMATION, "Wallhaven API Key", wallhavenInput.getText().isBlank() ? "Key cleared" : "Wallhaven API key saved successfully!");
        });
        HBox wallhavenRow = new HBox(8, wallhavenInput, saveWallhaven);
        wallhavenRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(wallhavenInput, Priority.ALWAYS);

        TextField pexelsInput = new TextField(WallpaperSearchService.getPexelsApiKey());
        pexelsInput.setPromptText("Pexels API key (optional — unlocks Pexels photography)");
        Button savePexels = new Button("Save Pexels");
        savePexels.getStyleClass().add("quiet-button");
        savePexels.setOnAction(e -> {
            WallpaperSearchService.setPexelsApiKey(pexelsInput.getText());
            showAlert(Alert.AlertType.INFORMATION, "Pexels API Key", pexelsInput.getText().isBlank() ? "Key cleared" : "Pexels API key saved successfully!");
        });
        HBox pexelsRow = new HBox(8, pexelsInput, savePexels);
        pexelsRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pexelsInput, Priority.ALWAYS);

        TextField pixabayInput = new TextField(WallpaperSearchService.getPixabayApiKey());
        pixabayInput.setPromptText("Pixabay API key (optional — unlocks Pixabay wallpapers)");
        Button savePixabay = new Button("Save Pixabay");
        savePixabay.getStyleClass().add("quiet-button");
        savePixabay.setOnAction(e -> {
            WallpaperSearchService.setPixabayApiKey(pixabayInput.getText());
            showAlert(Alert.AlertType.INFORMATION, "Pixabay API Key", pixabayInput.getText().isBlank() ? "Key cleared" : "Pixabay API key saved successfully!");
        });
        HBox pixabayRow = new HBox(8, pixabayInput, savePixabay);
        pixabayRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pixabayInput, Priority.ALWAYS);

        VBox apiCard = new VBox(10, apiTitle, apiDetail,
                new Label("Wallhaven API Key:"), wallhavenRow,
                new Label("Pexels API Key:"), pexelsRow,
                new Label("Pixabay API Key:"), pixabayRow);
        apiCard.getStyleClass().add("automation-section-card");

        Label cacheTitle = new Label("Thumbnail cache storage");
        cacheTitle.getStyleClass().add("section-title");
        long cacheBytes = WallpaperSearchService.getThumbnailCacheSizeBytes();
        int cacheCount = WallpaperSearchService.getThumbnailCacheFileCount();
        Label cacheInfo = new Label("Cached thumbnails: " + cacheCount + " files (" + (cacheBytes / 1024) + " KB)");
        cacheInfo.getStyleClass().add("memory-export-hint");
        Button clearCache = new Button("Clear Thumbnail Cache");
        clearCache.getStyleClass().add("quiet-button");
        clearCache.setOnAction(event -> {
            int cleared = WallpaperSearchService.clearThumbnailCache();
            cacheInfo.setText("Cleared " + cleared + " cached thumbnail files (0 KB).");
        });
        VBox cacheCard = new VBox(10, cacheTitle,
                new Label("Online discovery thumbnails are safely cached on disk to speed up subsequent queries and avoid re-downloading."),
                cacheInfo, clearCache);
        cacheCard.getStyleClass().add("automation-section-card");

        Label storageTitle = new Label("Your saved data");
        storageTitle.getStyleClass().add("section-title");
        Label storagePath = new Label(startupService.dataFolder().toString());
        storagePath.setWrapText(true);
        storagePath.getStyleClass().add("memory-export-hint");
        Button openStorage = new Button("Open app data folder");
        openStorage.getStyleClass().add("quiet-button");
        openStorage.setOnAction(event -> openStorageFolder());
        VBox storageCard = new VBox(10, storageTitle,
                new Label("Collections, worlds, downloaded images, and local preferences are stored here."),
                storagePath, openStorage);
        storageCard.getStyleClass().add("automation-section-card");

        Label startupTitle = new Label("Start with Windows");
        startupTitle.getStyleClass().add("section-title");
        CheckBox launchOnSignIn = new CheckBox("Open RealityWeaver when I sign in");
        launchOnSignIn.getStyleClass().add("automation-toggle");
        launchOnSignIn.setSelected(startupService.isEnabled());
        launchOnSignIn.setDisable(!startupService.isSupported());
        Label startupStatus = new Label(startupService.isSupported()
                ? "This setting applies to the packaged Windows app."
                : "Sign-in launch is available in the packaged Windows app.");
        startupStatus.setWrapText(true);
        startupStatus.getStyleClass().add("memory-export-hint");
        launchOnSignIn.setOnAction(event -> updateStartupPreference(launchOnSignIn, startupStatus));
        VBox startupCard = new VBox(10, startupTitle, launchOnSignIn, startupStatus);
        startupCard.getStyleClass().add("automation-section-card");

        Label integrationsTitle = new Label("Weather & calendar provider notes");
        integrationsTitle.getStyleClass().add("section-title");
        Label integrations = new Label("Weather is provided by Open-Meteo with no API key required. "
                + "Calendar rules support remote Google Calendar / iCal feeds, local .ics files, and Classic Outlook. Configure them under the Automation tab.");
        integrations.setWrapText(true);
        integrations.getStyleClass().add("page-subtitle");
        VBox integrationsCard = new VBox(10, integrationsTitle, integrations);
        integrationsCard.getStyleClass().add("automation-section-card");

        VBox content = new VBox(16, desktopCard, appearanceCard, apiCard, cacheCard, storageCard, startupCard, integrationsCard);
        content.setPadding(new Insets(20));
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("automation-scroll");
        return scrollPane;
    }

    private void updateStartupPreference(CheckBox toggle, Label status) {
        boolean enabled = toggle.isSelected();
        toggle.setDisable(true);
        status.setText(enabled ? "Adding the sign-in shortcut…" : "Removing the sign-in shortcut…");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                startupService.setEnabled(enabled);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            toggle.setDisable(!startupService.isSupported());
            status.setText(enabled ? "RealityWeaver will open when you sign in to Windows."
                    : "RealityWeaver will no longer open automatically at sign-in.");
        });
        task.setOnFailed(event -> {
            toggle.setSelected(startupService.isEnabled());
            toggle.setDisable(!startupService.isSupported());
            status.setText("Could not update sign-in launch: " + safeMessage(task.getException()));
            showAlert(Alert.AlertType.ERROR, "Startup setting could not be changed", status.getText());
        });
        Thread worker = new Thread(task, "realityweaver-startup-setting");
        worker.setDaemon(true);
        worker.start();
    }

    private void openStorageFolder() {
        Path folder = startupService.dataFolder();
        try {
            java.nio.file.Files.createDirectories(folder);
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new IOException("This desktop cannot open folders automatically.");
            }
            Desktop.getDesktop().open(folder.toFile());
        } catch (IOException | UnsupportedOperationException failure) {
            showAlert(Alert.AlertType.ERROR, "Could not open the data folder", safeMessage(failure));
        }
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

    private SplitPane createWorkspace() {
        VBox previewPanel = createPreviewPanel();
        VBox galleryPanel = createGalleryPanel();
        previewPanel.setMinWidth(620);
        galleryPanel.setMinWidth(280);

        SplitPane workspace = new SplitPane(previewPanel, galleryPanel);
        workspace.setDividerPositions(0.74);
        workspace.getStyleClass().add("wallpaper-workspace-split");
        return workspace;
    }

    private ScrollPane createAutomationPanel() {
        Label title = new Label("Let your wallpaper follow your day");
        title.getStyleClass().add("section-title");
        Label description = new Label("Create dynamic rules for time of day, atmospheric weather, or calendar events. "
                + "Automation runs continuously in the background or system tray.");
        description.getStyleClass().add("page-subtitle");
        description.setWrapText(true);

        automationEnabled.getStyleClass().add("automation-toggle");
        automationEnabled.setSelected(automationService.isEnabled());
        automationEnabled.setOnAction(event -> automationService.setEnabled(
                automationEnabled.isSelected(), this::updateAutomationStatus));
        Button checkNow = new Button("Check rules now");
        checkNow.getStyleClass().add("quiet-button");
        checkNow.setOnAction(event -> {
            if (!automationEnabled.isSelected()) {
                automationStatus.setText("Turn on automation before checking rules.");
                return;
            }
            automationStatus.setText("Checking saved rules…");
            automationService.checkNow();
        });

        resumeAutomationButton.getStyleClass().add("quiet-button");
        resumeAutomationButton.setVisible(automationService.isManualOverrideActive());
        resumeAutomationButton.setManaged(automationService.isManualOverrideActive());
        resumeAutomationButton.setOnAction(event -> {
            automationService.resumeAutomation();
            resumeAutomationButton.setVisible(false);
            resumeAutomationButton.setManaged(false);
            updateAutomationStatus("Manual override cleared. Automation resumed.");
        });

        HBox controls = new HBox(14, automationEnabled, checkNow, resumeAutomationButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        automationStatus.getStyleClass().add("automation-status");
        VBox statusCard = new VBox(8, controls, automationStatus);
        statusCard.getStyleClass().add("automation-status-card");

        timeRuleInput.setPromptText("Local time, e.g. 06:00");
        timeRuleInput.setPrefColumnCount(12);
        Button morningPreset = createTimePreset("Morning · 06:00", "06:00");
        Button eveningPreset = createTimePreset("Evening · 18:00", "18:00");
        Button nightPreset = createTimePreset("Night · 21:00", "21:00");
        HBox timePresets = new HBox(8, morningPreset, eveningPreset, nightPreset);
        Button addTime = new Button("＋ Add time rule");
        addTime.getStyleClass().add("quiet-button");
        addTime.setOnAction(event -> addTimeAutomationRule());
        HBox timeForm = new HBox(10, timeRuleInput, timeWallpaperChoice, addTime);
        timeForm.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(timeWallpaperChoice, Priority.ALWAYS);

        weatherCityInput.setPromptText("City, e.g. Bengaluru");
        weatherCityInput.setPrefColumnCount(16);
        automaticWeatherLocation.setSelected(automationService.isAutomaticLocationEnabled());
        automaticWeatherLocation.getStyleClass().add("automation-option");
        automaticWeatherLocation.selectedProperty().addListener((observable, wasSelected, isSelected) -> {
            weatherCityInput.setDisable(isSelected);
            automationService.setAutomaticLocationEnabled(isSelected);
        });
        weatherCityInput.setDisable(automaticWeatherLocation.isSelected());
        weatherConditionChoice.getItems().setAll(WallpaperAutomationService.WeatherCondition.values());
        weatherConditionChoice.setValue(WallpaperAutomationService.WeatherCondition.CLEAR);
        weatherConditionChoice.getStyleClass().add("automation-wallpaper-choice");
        Button addWeather = new Button("＋ Add weather rule");
        addWeather.getStyleClass().add("quiet-button");
        addWeather.setOnAction(event -> addWeatherAutomationRule());
        HBox weatherForm = new HBox(10, weatherCityInput, weatherConditionChoice, weatherWallpaperChoice,
                addWeather);
        weatherForm.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(weatherCityInput, Priority.ALWAYS);
        HBox.setHgrow(weatherWallpaperChoice, Priority.ALWAYS);

        Button chooseCalendar = new Button("Choose .ics calendar");
        chooseCalendar.getStyleClass().add("quiet-button");
        chooseCalendar.setOnAction(event -> chooseCalendarFile());
        outlookCalendarEnabled.setSelected(automationService.isOutlookCalendarEnabled());
        outlookCalendarEnabled.getStyleClass().add("automation-option");
        outlookCalendarEnabled.selectedProperty().addListener((observable, wasSelected, isSelected) -> {
            automationService.setOutlookCalendarEnabled(isSelected);
            if (automationEnabled.isSelected()) {
                automationService.checkNow();
            }
        });
        HBox calendarFileRow = new HBox(10, chooseCalendar, calendarFileStatus);
        calendarFileRow.setAlignment(Pos.CENTER_LEFT);
        calendarFileStatus.getStyleClass().add("automation-status");

        TextField calendarUrlInput = new TextField();
        calendarUrlInput.setPromptText("Remote iCal / Google Calendar URL (https://... or webcal://...)");
        calendarUrlInput.setText(automationService.calendarUrl());
        Button saveUrl = new Button("Save Feed");
        saveUrl.getStyleClass().add("quiet-button");
        saveUrl.setOnAction(event -> {
            automationService.setCalendarUrl(calendarUrlInput.getText());
            automationStatus.setText(calendarUrlInput.getText().isBlank() ? "Remote calendar feed cleared." : "Remote calendar feed saved.");
            if (automationEnabled.isSelected()) {
                automationService.checkNow();
            }
        });
        Button clearUrl = new Button("Clear Feed");
        clearUrl.getStyleClass().add("quiet-button");
        clearUrl.setOnAction(event -> {
            calendarUrlInput.clear();
            automationService.setCalendarUrl("");
            automationStatus.setText("Remote calendar feed removed.");
        });
        HBox calendarUrlRow = new HBox(8, calendarUrlInput, saveUrl, clearUrl);
        calendarUrlRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(calendarUrlInput, Priority.ALWAYS);

        calendarKeywordInput.setPromptText("Event title contains… e.g. meeting");
        Button addCalendar = new Button("＋ Add event rule");
        addCalendar.getStyleClass().add("quiet-button");
        addCalendar.setOnAction(event -> addCalendarAutomationRule());
        HBox calendarForm = new HBox(10, calendarKeywordInput, calendarWallpaperChoice, addCalendar);
        calendarForm.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(calendarKeywordInput, Priority.ALWAYS);
        HBox.setHgrow(calendarWallpaperChoice, Priority.ALWAYS);

        CheckBox intervalToggle = new CheckBox("Rotate wallpaper automatically");
        intervalToggle.getStyleClass().add("automation-toggle");
        intervalToggle.setSelected(automationService.isIntervalCycleEnabled());

        ComboBox<Integer> intervalMins = new ComboBox<>();
        intervalMins.getItems().setAll(1, 2, 5, 10, 15, 30, 60, 120);
        intervalMins.setValue(automationService.intervalCycleMinutes());
        intervalMins.getStyleClass().add("automation-wallpaper-choice");
        intervalMins.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer mins) {
                if (mins == null) return "";
                if (mins < 60) return mins + " minute" + (mins == 1 ? "" : "s");
                return (mins / 60) + " hour" + ((mins / 60) == 1 ? "" : "s");
            }
            @Override
            public Integer fromString(String string) { return null; }
        });

        ComboBox<String> intervalSource = new ComboBox<>();
        intervalSource.getItems().add("Entire Library (all wallpapers)");
        try {
            for (com.realityweaver.wallpaper.WallpaperCollection col : new com.realityweaver.wallpaper.WallpaperCollectionStore().list()) {
                intervalSource.getItems().add("Vault: " + col.name());
            }
        } catch (Exception ignored) { }
        intervalSource.setValue("Entire Library (all wallpapers)");
        intervalSource.getStyleClass().add("automation-wallpaper-choice");

        Button cycleNow = new Button("Cycle Next Now");
        cycleNow.getStyleClass().add("quiet-button");
        cycleNow.setOnAction(ev -> {
            automationService.triggerIntervalCycleNow();
            automationStatus.setText("Next wallpaper cycled.");
        });

        Runnable updateIntervalSettings = () -> {
            boolean active = intervalToggle.isSelected();
            int mins = intervalMins.getValue() != null ? intervalMins.getValue() : 5;
            String srcVal = intervalSource.getValue();
            String colId = "";
            if (srcVal != null && srcVal.startsWith("Vault: ")) {
                String colName = srcVal.substring("Vault: ".length()).strip();
                try {
                    for (com.realityweaver.wallpaper.WallpaperCollection col : new com.realityweaver.wallpaper.WallpaperCollectionStore().list()) {
                        if (col.name().equalsIgnoreCase(colName)) {
                            colId = col.id();
                            break;
                        }
                    }
                } catch (Exception ignored) { }
            }
            automationService.setIntervalCycle(active, mins, colId);
        };

        intervalToggle.setOnAction(e -> updateIntervalSettings.run());
        intervalMins.setOnAction(e -> {
            if (intervalToggle.isSelected()) updateIntervalSettings.run();
        });
        intervalSource.setOnAction(e -> {
            if (intervalToggle.isSelected()) updateIntervalSettings.run();
        });

        HBox intervalControls = new HBox(12, intervalToggle, new Label("every"), intervalMins, intervalSource, cycleNow);
        intervalControls.setAlignment(Pos.CENTER_LEFT);

        VBox sections = new VBox(14,
                createAutomationSection("Interval Slideshow (Change every X minutes)",
                        "Automatically cycles your desktop wallpaper every few minutes from your library or a chosen collection.",
                        intervalControls, new VBox()),
                createAutomationSection("Time of day", "Uses this PC's local clock. Changes at each scheduled time.",
                        new VBox(9, timePresets, timeForm), timeRulesList),
                createAutomationSection("Atmospheric weather (Open-Meteo)", "Checks live weather every 15 minutes. Automatically maps clear, cloudy, rainy, snowy, or stormy conditions.",
                        new VBox(9, automaticWeatherLocation, weatherForm), weatherRulesList),
                createAutomationSection("Calendar events & meetings", "Reads Classic Outlook, local .ics files, or remote Google Calendar / webcal feeds. Wallpaper applies while matching meeting is underway.",
                        new VBox(9, outlookCalendarEnabled, calendarFileRow, calendarUrlRow, calendarForm), calendarRulesList));
        VBox content = new VBox(18, title, description, statusCard, sections);
        content.setPadding(new Insets(20));
        content.setMaxWidth(Double.MAX_VALUE);
        refreshCalendarFileLabel();
        refreshAutomationWallpaperChoices();
        refreshAutomationRuleLists();

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("automation-scroll");
        return scrollPane;
    }

    private VBox createAutomationSection(String title, String detail, Node form, VBox rules) {
        Label sectionTitle = new Label(title);
        sectionTitle.getStyleClass().add("automation-section-title");
        Label sectionDetail = new Label(detail);
        sectionDetail.getStyleClass().add("automation-section-detail");
        sectionDetail.setWrapText(true);
        VBox card = new VBox(10, sectionTitle, sectionDetail, form, rules);
        card.getStyleClass().add("automation-section-card");
        return card;
    }

    private ComboBox<Path> createWallpaperChoice() {
        ComboBox<Path> choice = new ComboBox<>();
        choice.setPromptText("Choose wallpaper");
        choice.setMaxWidth(Double.MAX_VALUE);
        choice.getStyleClass().add("automation-wallpaper-choice");
        choice.setConverter(new StringConverter<>() {
            @Override
            public String toString(Path path) {
                return path == null ? "" : path.getFileName().toString();
            }

            @Override
            public Path fromString(String value) {
                return null;
            }
        });
        choice.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Path path, boolean empty) {
                super.updateItem(path, empty);
                setText(empty || path == null ? null : path.getFileName().toString());
            }
        });
        automationWallpaperChoices.add(choice);
        return choice;
    }

    private Button createTimePreset(String label, String time) {
        Button preset = new Button(label);
        preset.getStyleClass().add("quiet-button");
        preset.setOnAction(event -> timeRuleInput.setText(time));
        return preset;
    }

    private void refreshAutomationWallpaperChoices() {
        for (ComboBox<Path> choice : automationWallpaperChoices) {
            Path previous = choice.getValue();
            choice.getItems().setAll(imagePaths);
            if (previous != null && imagePaths.contains(previous)) {
                choice.setValue(previous);
            } else if (selectedPath != null && imagePaths.contains(selectedPath)) {
                choice.setValue(selectedPath);
            } else {
                choice.setValue(imagePaths.isEmpty() ? null : imagePaths.get(0));
            }
            choice.setDisable(imagePaths.isEmpty());
        }
    }

    private void addTimeAutomationRule() {
        try {
            automationService.addTimeRule(timeRuleInput.getText().strip(), timeWallpaperChoice.getValue());
            timeRuleInput.clear();
            refreshAutomationRuleLists();
            automationStatus.setText("Time rule saved.");
            automationService.checkNow();
        } catch (RuntimeException failure) {
            showAlert(Alert.AlertType.WARNING, "Time rule not added", safeMessage(failure));
        }
    }

    private void addWeatherAutomationRule() {
        try {
            automationService.addWeatherRule(weatherCityInput.getText(), weatherConditionChoice.getValue(),
                    weatherWallpaperChoice.getValue(), automaticWeatherLocation.isSelected());
            refreshAutomationRuleLists();
            automationStatus.setText("Weather rule saved.");
            automationService.checkNow();
        } catch (RuntimeException failure) {
            showAlert(Alert.AlertType.WARNING, "Weather rule not added", safeMessage(failure));
        }
    }

    private void chooseCalendarFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose an iCalendar file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("iCalendar files", "*.ics"));
        File selected = chooser.showOpenDialog(getScene().getWindow());
        if (selected == null) {
            return;
        }
        Path path = selected.toPath().toAbsolutePath().normalize();
        automationService.setCalendarFile(path);
        refreshCalendarFileLabel();
        automationStatus.setText("Calendar file selected.");
    }

    private void addCalendarAutomationRule() {
        Path calendar = automationService.calendarFile();
        boolean hasLocalCalendar = calendar != null && java.nio.file.Files.isRegularFile(calendar);
        boolean hasRemoteCalendar = !automationService.calendarUrl().isBlank();
        if (!automationService.isOutlookCalendarEnabled() && !hasLocalCalendar && !hasRemoteCalendar) {
            showAlert(Alert.AlertType.WARNING, "Choose a calendar source first",
                    "Enable this PC's Outlook calendar, provide a remote iCal feed, or select a local .ics file.");
            return;
        }
        try {
            automationService.addCalendarRule(calendarKeywordInput.getText(), calendarWallpaperChoice.getValue());
            calendarKeywordInput.clear();
            refreshAutomationRuleLists();
            automationStatus.setText("Calendar rule saved.");
            automationService.checkNow();
        } catch (RuntimeException failure) {
            showAlert(Alert.AlertType.WARNING, "Calendar rule not added", safeMessage(failure));
        }
    }

    private void refreshCalendarFileLabel() {
        Path file = automationService.calendarFile();
        calendarFileStatus.setText(file == null ? "No .ics fallback selected" : "Local fallback: " + file.getFileName());
    }

    private void refreshAutomationRuleLists() {
        timeRulesList.getChildren().clear();
        for (WallpaperAutomationService.TimeRule rule : automationService.timeRules()) {
            String target = (rule.collectionId() != null && !rule.collectionId().isBlank())
                    ? "Collection: " + rule.collectionId()
                    : (rule.wallpaper() != null ? rule.wallpaper().getFileName().toString() : "—");
            timeRulesList.getChildren().add(createAutomationRuleRow(
                    rule.enabled(),
                    rule.time() + " daily → " + target,
                    enabled -> automationService.setTimeRuleEnabled(rule.id(), enabled),
                    () -> automationService.removeTimeRule(rule.id())));
        }

        weatherRulesList.getChildren().clear();
        for (WallpaperAutomationService.WeatherRule rule : automationService.weatherRules()) {
            String target = (rule.collectionId() != null && !rule.collectionId().isBlank())
                    ? "Collection: " + rule.collectionId()
                    : (rule.wallpaper() != null ? rule.wallpaper().getFileName().toString() : "—");
            weatherRulesList.getChildren().add(createAutomationRuleRow(
                    rule.enabled(),
                    (rule.automaticLocation() ? "PC location (approx.)" : rule.city()) + " · " + rule.condition()
                            + " → " + target,
                    enabled -> automationService.setWeatherRuleEnabled(rule.id(), enabled),
                    () -> automationService.removeWeatherRule(rule.id())));
        }

        calendarRulesList.getChildren().clear();
        for (WallpaperAutomationService.CalendarRule rule : automationService.calendarRules()) {
            String target = (rule.collectionId() != null && !rule.collectionId().isBlank())
                    ? "Collection: " + rule.collectionId()
                    : (rule.wallpaper() != null ? rule.wallpaper().getFileName().toString() : "—");
            calendarRulesList.getChildren().add(createAutomationRuleRow(
                    rule.enabled(),
                    "Title contains “" + rule.titleContains() + "” → " + target,
                    enabled -> automationService.setCalendarRuleEnabled(rule.id(), enabled),
                    () -> automationService.removeCalendarRule(rule.id())));
        }
    }

    private HBox createAutomationRuleRow(boolean enabled, String description,
            java.util.function.Consumer<Boolean> onToggle, Runnable removeRule) {
        CheckBox toggle = new CheckBox();
        toggle.setSelected(enabled);
        toggle.getStyleClass().add("automation-rule-toggle");
        toggle.setOnAction(event -> {
            onToggle.accept(toggle.isSelected());
            if (automationEnabled.isSelected()) {
                automationService.checkNow();
            }
        });
        Label text = new Label(description);
        text.setWrapText(true);
        text.getStyleClass().add("automation-rule-text");
        if (!enabled) {
            text.setOpacity(0.5);
        }
        Button remove = new Button("×");
        remove.getStyleClass().add("automation-rule-remove");
        remove.setAccessibleText("Remove automation rule");
        remove.setOnAction(event -> {
            removeRule.run();
            refreshAutomationRuleLists();
        });
        HBox row = new HBox(10, toggle, text, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        row.getStyleClass().add("automation-rule-row");
        return row;
    }

    private void updateAutomationStatus(String message) {
        Runnable update = () -> {
            automationStatus.setText(message);
            boolean override = automationService.isManualOverrideActive();
            resumeAutomationButton.setVisible(override);
            resumeAutomationButton.setManaged(override);
        };
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    private String safeMessage(Throwable failure) {
        return failure.getMessage() == null ? "Check the rule details and try again." : failure.getMessage();
    }

    private VBox createPreviewPanel() {
        previewFrame.setMinHeight(380);
        previewFrame.setPrefHeight(420);
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
        favoriteButton.getStyleClass().add("favorite-action-button");
        favoriteButton.setDisable(true);
        favoriteButton.setOnAction(event -> toggleSelectedFavorite());
        removeButton.getStyleClass().add("quiet-button");
        removeButton.setDisable(true);
        removeButton.setOnAction(event -> removeSelectedWallpaper());
        HBox actions = new HBox(10, applyButton, favoriteButton, removeButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        applyStatus.getStyleClass().add("automation-status");

        Label categoryLabel = new Label("Favorite category");
        categoryLabel.getStyleClass().add("memory-field-label");
        selectedCategorySelector.getStyleClass().add("wallpaper-selected-category");
        selectedCategorySelector.setItems(javafx.collections.FXCollections.observableArrayList(library.categories()));
        selectedCategorySelector.setDisable(true);
        selectedCategorySelector.setOnAction(event -> {
            if (selectedPath != null && selectedCategorySelector.getValue() != null) {
                library.setCategory(selectedPath, selectedCategorySelector.getValue());
                refreshGallery();
            }
        });
        HBox categoryRow = new HBox(10, categoryLabel, selectedCategorySelector);
        categoryRow.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(14, previewFrame, selectedCopy, categoryRow, actions, applyStatus);
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
        galleryScroll.setMinHeight(180);
        VBox.setVgrow(galleryScroll, Priority.ALWAYS);
        galleryScroll.getStyleClass().add("gallery-scroll");

        categoryFilter.getStyleClass().add("wallpaper-category-selector");
        categoryFilter.setMaxWidth(Double.MAX_VALUE);
        categoryFilter.setOnAction(event -> refreshGallery());
        addCategoryButton.getStyleClass().add("quiet-button");
        addCategoryButton.setOnAction(event -> addCategory());
        HBox categoryRow = new HBox(8, categoryFilter, addCategoryButton);
        categoryRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(categoryFilter, Priority.ALWAYS);
        refreshCategoryFilter();

        Label recentTitle = new Label("Latest wallpaper");
        recentTitle.getStyleClass().add("recent-wallpaper-title");
        recentWallpaperRow.setAlignment(Pos.CENTER_LEFT);
        recentWallpaperPanel.getChildren().setAll(recentTitle, recentWallpaperRow);
        recentWallpaperPanel.getStyleClass().add("recent-wallpaper-panel");
        refreshRecentWallpaper();

        VBox panel = new VBox(12, titleRow, favoriteFilter, categoryRow, recentWallpaperPanel, galleryScroll);
        panel.setMinWidth(250);
        panel.setPrefWidth(285);
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
        collectionsView.refreshLibrary();
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
        library.recordRecent(path);
        selectedCategorySelector.setDisable(false);
        selectedCategorySelector.setValue(library.category(path));
        ImageView imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.fitWidthProperty().bind(previewFrame.widthProperty().subtract(24));
        imageView.fitHeightProperty().bind(previewFrame.heightProperty().subtract(24));
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
        favoriteButton.setDisable(false);
        removeButton.setDisable(false);
        updateFavoriteButton();
        refreshGallery();
    }

    private void addDownloadedWallpaper(Path path) {
        imagePaths.clear();
        imagePaths.addAll(library.load());
        collectionsView.refreshLibrary();
        selectWallpaper(path);
        refreshGallery();
    }

    private Image readImage(Path path) {
        Image image = new Image(path.toUri().toString(), false);
        return image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0 ? null : image;
    }

    private void refreshGallery() {
        galleryItems.getChildren().clear();
        refreshRecentWallpaper();
        refreshAutomationWallpaperChoices();
        String category = categoryFilter.getValue();
        List<Path> visiblePaths = imagePaths.stream()
                .filter(path -> !favoritesOnly || library.isFavorite(path))
                .filter(path -> category == null || category.equals("All categories")
                        || library.category(path).equalsIgnoreCase(category))
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
        Label category = new Label(library.category(path));
        category.getStyleClass().add("wallpaper-item-category-tag");
        VBox copy = new VBox(5, name, category);
        copy.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(copy, Priority.ALWAYS);

        boolean favorite = library.isFavorite(path);
        Button star = new Button(favorite ? "★" : "☆");
        star.getStyleClass().add("star-button");
        star.setAccessibleText(favorite ? "Remove from favorites" : "Add to favorites");
        star.setOnAction(event -> {
            library.setFavorite(path, !library.isFavorite(path));
            if (path.equals(selectedPath)) {
                updateFavoriteButton();
            }
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

    private void toggleSelectedFavorite() {
        if (selectedPath == null) {
            return;
        }
        library.setFavorite(selectedPath, !library.isFavorite(selectedPath));
        updateFavoriteButton();
        refreshGallery();
    }

    private void updateFavoriteButton() {
        boolean favorite = selectedPath != null && library.isFavorite(selectedPath);
        favoriteButton.setText(favorite ? "★ Remove from favorites" : "☆ Add to favorites");
        favoriteButton.setAccessibleText(favorite ? "Remove selected wallpaper from favorites"
                : "Add selected wallpaper to favorites");
    }

    private void refreshCategoryFilter() {
        String previous = categoryFilter.getValue();
        List<String> options = new ArrayList<>();
        options.add("All categories");
        options.addAll(library.categories());
        categoryFilter.getItems().setAll(options);
        categoryFilter.setValue(options.contains(previous) ? previous : "All categories");

        selectedCategorySelector.getItems().setAll(library.categories());
        if (selectedPath != null) {
            selectedCategorySelector.setValue(library.category(selectedPath));
        }
    }

    private void addCategory() {
        TextInputDialog dialog = new TextInputDialog();
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.setTitle("Add wallpaper category");
        dialog.setHeaderText("Create a category for your favorite wallpapers.");
        dialog.setContentText("Category name:");
        dialog.getEditor().setPromptText("e.g. Cozy games");
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().add(
                WallpaperStudioView.class.getResource("/styles/app.css").toExternalForm());
        pane.getStyleClass().add("world-dialog");

        dialog.showAndWait().map(String::strip).filter(value -> !value.isBlank()).ifPresent(value -> {
            String category = library.addCategory(value);
            if (category == null) {
                showAlert(Alert.AlertType.WARNING, "Category name is reserved",
                        "Choose another name. “All categories” is used by the library filter.");
                return;
            }
            refreshCategoryFilter();
            refreshGallery();
        });
    }

    private void refreshRecentWallpaper() {
        recentWallpaperRow.getChildren().clear();
        Path latest = library.recent().stream().findFirst().orElse(null);
        if (latest == null) {
            Label empty = new Label("Select a wallpaper to see your latest one here.");
            empty.setWrapText(true);
            empty.getStyleClass().add("recent-wallpaper-empty");
            recentWallpaperRow.getChildren().add(empty);
            return;
        }

        Image thumbnail = new Image(latest.toUri().toString(), 76, 54, true, true, true);
        ImageView imageView = new ImageView(thumbnail);
        imageView.setFitWidth(72);
        imageView.setFitHeight(52);
        imageView.setPreserveRatio(true);
        StackPane imageFrame = new StackPane(imageView);
        imageFrame.getStyleClass().add("wallpaper-thumbnail");

        Label name = new Label(latest.getFileName().toString());
        name.setWrapText(true);
        name.getStyleClass().add("wallpaper-item-name");
        Label detail = new Label("Most recently opened");
        detail.getStyleClass().add("recent-wallpaper-empty");
        VBox copy = new VBox(4, name, detail);
        copy.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(copy, Priority.ALWAYS);

        HBox item = new HBox(10, imageFrame, copy);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(8));
        item.getStyleClass().add("recent-wallpaper-row");
        item.setOnMouseClicked(event -> selectWallpaper(latest));
        recentWallpaperRow.getChildren().setAll(item);
    }

    private void applySelectedWallpaper() {
        if (selectedPath == null) {
            return;
        }

        Path path = selectedPath;
        applyButton.setDisable(true);
        applyStatus.setText("Applying wallpaper…");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                wallpaperService.apply(path);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            applyButton.setDisable(false);
            automationService.recordManualOverride();
            applyStatus.setText("Wallpaper applied. Automatic changes pause for one hour.");
        });
        task.setOnFailed(event -> {
            applyButton.setDisable(false);
            applyStatus.setText("Wallpaper could not be applied: " + safeMessage(task.getException()));
        });
        Thread worker = new Thread(task, "realityweaver-wallpaper-apply");
        worker.setDaemon(true);
        worker.start();
    }

    private void removeSelectedWallpaper() {
        if (selectedPath == null) {
            return;
        }

        Path removed = selectedPath;
        library.remove(removed);
        imagePaths.remove(removed);
        collectionsView.refreshLibrary();
        selectedPath = null;
        if (imagePaths.isEmpty()) {
            showPlaceholder();
            selectedName.setText("No wallpaper selected");
            selectedDetails.setText("Import an image to preview it here.");
            applyButton.setDisable(true);
            favoriteButton.setDisable(true);
            removeButton.setDisable(true);
            selectedCategorySelector.setDisable(true);
            selectedCategorySelector.setValue(null);
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

    public void close() {
        collectionsView.close();
    }
}
