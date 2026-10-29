package com.realityweaver.ui;

import com.realityweaver.auth.LocalAccount;
import com.realityweaver.wallpaper.WallpaperAutomationService;
import com.realityweaver.wallpaper.WallpaperCollection;
import com.realityweaver.wallpaper.WallpaperCollectionStore;
import com.realityweaver.wallpaper.WallpaperLibrary;
import com.realityweaver.wallpaper.WallpaperService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/** Central dashboard showcasing active desktop wallpaper, live atmospheric automation, vaults, and worlds. */
public final class DashboardView extends BorderPane {
    private final LocalAccount account;
    private final Runnable onSignOut;
    private final WallpaperAutomationService wallpaperAutomationService;
    private final WallpaperLibrary wallpaperLibrary = new WallpaperLibrary();
    private final WallpaperCollectionStore collectionStore = new WallpaperCollectionStore();
    private final WorldStore worldStore = new WorldStore();
    private final List<WorldDraft> worlds = new ArrayList<>();
    private final WallpaperService wallpaperService = new WallpaperService();
    private final Random random = new Random();

    private final StackPane screenHost = new StackPane();
    private final Button homeNav = new Button("Home");
    private final Button discoverNav = new Button("Discover");
    private final Button libraryNav = new Button("My Library");
    private final Button collectionsNav = new Button("Collections");
    private final Button automationNav = new Button("Automation");
    private final Button settingsNav = new Button("Settings");

    private final VBox worldList = new VBox(12);
    private final VBox emptyState = new VBox(12);
    private final Label worldCount = new Label("0 worlds");
    private final Label savedWallpaperCount = new Label("0");
    private final Label collectionCount = new Label("0");
    private final Label favoriteCount = new Label("0");
    private final Label activeRulesCount = new Label("0");

    private final Label currentWallpaperName = new Label("Detecting current wallpaper…");
    private final Label currentWallpaperMeta = new Label();
    private final StackPane monitorScreenFrame = new StackPane();
    private final Label automationBadgeLabel = new Label("Automation Off");
    private final HBox automationBadge = new HBox(6, new Circle(4), automationBadgeLabel);
    private final Label weatherConditionLabel = new Label("Open-Meteo Weather: Checking…");
    private final Label weatherTempLabel = new Label();
    private final Label automationRuleStatus = new Label("Checking automation rules…");
    private final Button resumeOverrideButton = new Button("Resume Automation");

    private final HBox collectionsShortcutRow = new HBox(12);
    private final HBox quickWallpapersRow = new HBox(12);
    private final Label worldStoreStatus = new Label("Worlds and memory notes save on this computer.");
    private final PauseTransition worldSaveDebounce = new PauseTransition(Duration.millis(350));
    private boolean worldStorageReadable = true;
    private Node studioScreen;
    private WallpaperStudioView wallpaperScreen;

    public DashboardView(LocalAccount account, Runnable onSignOut,
            WallpaperAutomationService wallpaperAutomationService) {
        this.account = account;
        this.onSignOut = onSignOut;
        this.wallpaperAutomationService = wallpaperAutomationService;
        try {
            worlds.addAll(worldStore.load());
        } catch (IOException unavailable) {
            worldStorageReadable = false;
            worldStoreStatus.setText("Saved worlds could not be opened safely. The original file was left untouched.");
            worldStoreStatus.getStyleClass().add("world-store-error");
        }
        worlds.forEach(this::watchWorldChanges);
        worldSaveDebounce.setOnFinished(event -> saveWorlds());
        getStyleClass().add("app-shell");

        wallpaperScreen = new WallpaperStudioView(wallpaperAutomationService);
        wallpaperScreen.setVisible(false);
        wallpaperScreen.setManaged(false);

        studioScreen = createScrollableDashboard();
        refreshAllDashboardData();

        // Listen for live automation updates
        wallpaperAutomationService.setStatusListener(statusMessage -> {
            Platform.runLater(() -> {
                automationRuleStatus.setText(statusMessage);
                refreshAutomationCard();
            });
        });

        screenHost.getChildren().setAll(studioScreen, wallpaperScreen);
        setTop(createTopBar());
        setCenter(screenHost);
    }

    private HBox createTopBar() {
        StackPane mark = new StackPane(new Label("✦"));
        mark.getStyleClass().add("brand-mark");

        Label brandName = new Label("RealityWeaver");
        brandName.getStyleClass().add("brand-name");
        Label brandCaption = new Label("SMART DESKTOP STUDIO");
        brandCaption.getStyleClass().add("brand-caption");
        VBox brandCopy = new VBox(2, brandName, brandCaption);
        brandCopy.setAlignment(Pos.CENTER_LEFT);

        HBox brand = new HBox(12, mark, brandCopy);
        brand.setAlignment(Pos.CENTER_LEFT);

        homeNav.getStyleClass().setAll("nav-button", "nav-button-selected");
        discoverNav.getStyleClass().setAll("nav-button");
        libraryNav.getStyleClass().setAll("nav-button");
        collectionsNav.getStyleClass().setAll("nav-button");
        automationNav.getStyleClass().setAll("nav-button");
        settingsNav.getStyleClass().setAll("nav-button");

        homeNav.setOnAction(event -> showHomeScreen());
        discoverNav.setOnAction(event -> openWallpaperSection("Discover"));
        libraryNav.setOnAction(event -> openWallpaperSection("My Wallpapers"));
        collectionsNav.setOnAction(event -> openWallpaperSection("Collections"));
        automationNav.setOnAction(event -> openWallpaperSection("Automation"));
        settingsNav.setOnAction(event -> openWallpaperSection("Settings"));

        HBox navigation = new HBox(4, homeNav, discoverNav, libraryNav, collectionsNav, automationNav, settingsNav);
        navigation.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Circle statusDot = new Circle(4);
        statusDot.getStyleClass().add("status-dot");
        Label workspace = new Label("LOCAL WORKSPACE");
        workspace.getStyleClass().add("workspace-label");
        HBox status = new HBox(8, statusDot, workspace);
        status.setAlignment(Pos.CENTER);
        status.getStyleClass().add("workspace-badge");

        Label accountName = new Label(account.displayName());
        accountName.getStyleClass().add("account-name");
        accountName.setMaxWidth(130);
        accountName.setTextOverrun(OverrunStyle.ELLIPSIS);
        boolean localWorkspace = account.email() == null || account.email().isBlank();
        Button signOut = new Button(localWorkspace ? "Sign in" : "Sign out");
        signOut.getStyleClass().add("sign-out-button");
        signOut.setOnAction(event -> {
            wallpaperAutomationService.setStatusListener(null);
            close();
            onSignOut.run();
        });
        HBox profile = new HBox(12, accountName, signOut);
        profile.setAlignment(Pos.CENTER);

        HBox topBar = new HBox(18, brand, navigation, spacer, status, profile);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(16, 40, 16, 40));
        topBar.getStyleClass().add("top-bar");
        return topBar;
    }

    private void showHomeScreen() {
        studioScreen.setVisible(true);
        studioScreen.setManaged(true);
        wallpaperScreen.setVisible(false);
        wallpaperScreen.setManaged(false);
        updateNavSelection(homeNav);
        refreshAllDashboardData();
    }

    public void openWallpaperSection(String section) {
        studioScreen.setVisible(false);
        studioScreen.setManaged(false);
        wallpaperScreen.setVisible(true);
        wallpaperScreen.setManaged(true);

        if ("Discover".equalsIgnoreCase(section)) {
            updateNavSelection(discoverNav);
        } else if ("My Wallpapers".equalsIgnoreCase(section)) {
            updateNavSelection(libraryNav);
        } else if ("Collections".equalsIgnoreCase(section)) {
            updateNavSelection(collectionsNav);
        } else if ("Automation".equalsIgnoreCase(section)) {
            updateNavSelection(automationNav);
        } else if ("Settings".equalsIgnoreCase(section)) {
            updateNavSelection(settingsNav);
        }

        wallpaperScreen.showSection(section);
    }

    private void updateNavSelection(Button selected) {
        List<Button> allNavs = List.of(homeNav, discoverNav, libraryNav, collectionsNav, automationNav, settingsNav);
        for (Button btn : allNavs) {
            btn.getStyleClass().remove("nav-button-selected");
        }
        selected.getStyleClass().add("nav-button-selected");
    }

    private ScrollPane createScrollableDashboard() {
        VBox content = new VBox(26);
        content.setPadding(new Insets(32, 44, 48, 44));
        content.setMaxWidth(1180);

        content.getChildren().addAll(
                createHeaderGreeting(),
                createDualCoreShowcase(),
                createStatsRow(),
                createCollectionsShowcase(),
                createQuickWallpapersSection(),
                createWorldsSection(),
                createPromptRow());

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("content-scroll");
        return scrollPane;
    }

    private VBox createHeaderGreeting() {
        int hour = LocalTime.now().getHour();
        String greeting = (hour >= 5 && hour < 12) ? "Good morning"
                : (hour >= 12 && hour < 18) ? "Good afternoon" : "Good evening";

        Label eyebrow = new Label("REALITY WEAVER · MULTIVERSAL WORKSPACE");
        eyebrow.getStyleClass().add("eyebrow");

        Label title = new Label(greeting + ", " + account.displayName() + ".");
        title.getStyleClass().add("page-title");

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"));
        Label subtitle = new Label(today + " · Intelligent desktop wallpaper management, atmospheric automation, and creative drafts.");
        subtitle.getStyleClass().add("page-subtitle");

        return new VBox(6, eyebrow, title, subtitle);
    }

    private HBox createDualCoreShowcase() {
        VBox activeWallpaperCard = createActiveWallpaperCard();
        VBox automationLiveCard = createAutomationLiveCard();

        HBox.setHgrow(activeWallpaperCard, Priority.ALWAYS);
        HBox.setHgrow(automationLiveCard, Priority.ALWAYS);

        HBox showcase = new HBox(20, activeWallpaperCard, automationLiveCard);
        showcase.setAlignment(Pos.CENTER_LEFT);
        return showcase;
    }

    private VBox createActiveWallpaperCard() {
        Label kicker = new Label("CURRENT MANIFESTATION");
        kicker.getStyleClass().add("hero-kicker");

        Label title = new Label("Active Desktop Wallpaper");
        title.getStyleClass().add("section-title");

        monitorScreenFrame.setMinSize(230, 130);
        monitorScreenFrame.setPrefSize(250, 140);
        monitorScreenFrame.setMaxSize(280, 150);
        monitorScreenFrame.getStyleClass().add("monitor-screen");

        currentWallpaperName.getStyleClass().add("monitor-title");
        currentWallpaperName.setWrapText(true);
        currentWallpaperName.setMaxWidth(300);

        currentWallpaperMeta.getStyleClass().add("monitor-meta");
        currentWallpaperMeta.setWrapText(true);
        currentWallpaperMeta.setMaxWidth(300);

        Button reapplyBtn = new Button("Re-apply Fit");
        reapplyBtn.getStyleClass().add("quiet-button");
        reapplyBtn.setOnAction(event -> {
            Path current = wallpaperService.getCurrentWallpaper().orElse(null);
            if (current != null && Files.isRegularFile(current)) {
                try {
                    wallpaperService.apply(current);
                    showAlert("Wallpaper re-applied with " + wallpaperService.preferredFit() + " fit mode.");
                } catch (Exception e) {
                    showAlert("Could not re-apply: " + e.getMessage());
                }
            } else {
                showAlert("No active wallpaper file detected to re-apply.");
            }
        });

        Button libraryBtn = new Button("My Library");
        libraryBtn.getStyleClass().add("quiet-button");
        libraryBtn.setOnAction(event -> openWallpaperSection("My Wallpapers"));

        Button discoverBtn = new Button("Discover Wallpapers");
        discoverBtn.getStyleClass().add("primary-button");
        discoverBtn.setOnAction(event -> openWallpaperSection("Discover"));

        HBox actions = new HBox(8, discoverBtn, libraryBtn, reapplyBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox metaBox = new VBox(6, kicker, title, currentWallpaperName, currentWallpaperMeta, actions);
        metaBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(metaBox, Priority.ALWAYS);

        HBox content = new HBox(16, monitorScreenFrame, metaBox);
        content.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(content);
        card.getStyleClass().add("monitor-mockup");
        return card;
    }

    private VBox createAutomationLiveCard() {
        Label kicker = new Label("EYE OF AGAMOTTO");
        kicker.getStyleClass().add("hero-kicker");

        Label title = new Label("Live Dynamic Automation");
        title.getStyleClass().add("section-title");

        HBox header = new HBox(12, title, automationBadge);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(title, Priority.ALWAYS);

        weatherConditionLabel.getStyleClass().add("weather-condition-text");
        weatherTempLabel.getStyleClass().add("weather-temp-text");
        HBox weatherBox = new HBox(10, weatherConditionLabel, weatherTempLabel);
        weatherBox.setAlignment(Pos.CENTER_LEFT);
        weatherBox.getStyleClass().add("weather-pill");

        automationRuleStatus.getStyleClass().add("automation-rule-text");
        automationRuleStatus.setWrapText(true);

        resumeOverrideButton.getStyleClass().add("quiet-button");
        resumeOverrideButton.setVisible(false);
        resumeOverrideButton.setManaged(false);
        resumeOverrideButton.setOnAction(event -> {
            wallpaperAutomationService.resumeAutomation();
            resumeOverrideButton.setVisible(false);
            resumeOverrideButton.setManaged(false);
            refreshAutomationCard();
        });

        Button checkNowBtn = new Button("Check Rules Now");
        checkNowBtn.getStyleClass().add("quiet-button");
        checkNowBtn.setOnAction(event -> {
            if (!wallpaperAutomationService.isEnabled()) {
                showAlert("Enable automation first to evaluate rules.");
                return;
            }
            wallpaperAutomationService.checkNow();
            refreshAutomationCard();
        });

        Button configBtn = new Button("Configure Rules");
        configBtn.getStyleClass().add("quiet-button");
        configBtn.setOnAction(event -> openWallpaperSection("Automation"));

        Button toggleBtn = new Button(wallpaperAutomationService.isEnabled() ? "Pause Engine" : "Enable Engine");
        toggleBtn.getStyleClass().add("quiet-button");
        toggleBtn.setOnAction(event -> {
            boolean next = !wallpaperAutomationService.isEnabled();
            wallpaperAutomationService.setEnabled(next, status -> Platform.runLater(this::refreshAutomationCard));
            toggleBtn.setText(next ? "Pause Engine" : "Enable Engine");
            refreshAutomationCard();
        });

        HBox actions = new HBox(8, checkNowBtn, configBtn, toggleBtn, resumeOverrideButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(10, kicker, header, weatherBox, automationRuleStatus, actions);
        card.getStyleClass().add("automation-live-card");
        return card;
    }

    private HBox createStatsRow() {
        return new HBox(12,
                dashboardStat("SAVED WALLPAPERS", savedWallpaperCount),
                dashboardStat("WALLPAPER VAULTS", collectionCount),
                dashboardStat("STARRED FAVORITES", favoriteCount),
                dashboardStat("AUTOMATION RULES", activeRulesCount));
    }

    private VBox dashboardStat(String title, Label value) {
        Label label = new Label(title);
        label.getStyleClass().add("dashboard-stat-label");
        value.getStyleClass().add("dashboard-stat-value");
        VBox card = new VBox(5, value, label);
        card.getStyleClass().add("dashboard-stat-card");
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private VBox createCollectionsShowcase() {
        Label title = new Label("Wallpaper Vaults");
        title.getStyleClass().add("section-title");

        Button openCollections = new Button("Manage Vaults →");
        openCollections.getStyleClass().add("quiet-button");
        openCollections.setOnAction(event -> openWallpaperSection("Collections"));

        HBox header = new HBox(title, openCollections);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(title, Priority.ALWAYS);

        collectionsShortcutRow.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(collectionsShortcutRow);
        scroll.setFitToHeight(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("gallery-scroll");

        return new VBox(12, header, scroll);
    }

    private VBox createQuickWallpapersSection() {
        Label title = new Label("Quick Apply & Favorites");
        title.getStyleClass().add("section-title");

        Button openLibrary = new Button("View Full Library →");
        openLibrary.getStyleClass().add("quiet-button");
        openLibrary.setOnAction(event -> openWallpaperSection("My Wallpapers"));

        HBox header = new HBox(title, openLibrary);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(title, Priority.ALWAYS);

        quickWallpapersRow.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(quickWallpapersRow);
        scroll.setFitToHeight(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("gallery-scroll");

        return new VBox(12, header, scroll);
    }

    private VBox createWorldsSection() {
        Label sectionTitle = new Label("Your Creative Worlds");
        sectionTitle.getStyleClass().add("section-title");
        worldCount.getStyleClass().add("count-label");

        Button newWorld = new Button("＋ New World");
        newWorld.getStyleClass().add("quiet-button");
        newWorld.setOnAction(event -> promptForWorldName());

        HBox sectionHeader = new HBox(sectionTitle, worldCount, newWorld);
        sectionHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sectionTitle, Priority.ALWAYS);

        Label emptyIcon = new Label("✦");
        emptyIcon.getStyleClass().add("empty-icon");
        Label emptyTitle = new Label("Your multiverse canvas is ready");
        emptyTitle.getStyleClass().add("empty-title");
        Label emptyCopy = new Label("Draft lore, collect world images, and weave settings into stories.");
        emptyCopy.setWrapText(true);
        emptyCopy.setMaxWidth(510);
        emptyCopy.getStyleClass().add("empty-copy");
        Button emptyButton = new Button("Create your first world");
        emptyButton.getStyleClass().add("quiet-button");
        emptyButton.setOnAction(event -> promptForWorldName());

        emptyState.getChildren().setAll(emptyIcon, emptyTitle, emptyCopy, emptyButton);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setPadding(new Insets(24, 20, 24, 20));
        emptyState.getStyleClass().add("empty-state");

        worldList.getStyleClass().add("world-list");
        worldStoreStatus.setWrapText(true);
        worldStoreStatus.getStyleClass().add("world-item-empty");
        refreshWorldCards();

        return new VBox(14, sectionHeader, worldList);
    }

    private HBox createPromptRow() {
        return new HBox(12,
                createPromptCard("01", "Discover & Explore", "Search high-res art on Openverse, NASA, and Wikimedia Commons."),
                createPromptCard("02", "Curate in Vaults", "Assemble collections and run automated timed slideshows."),
                createPromptCard("03", "Atmospheric Magic", "Let wallpapers shift dynamically with local weather and calendar events."));
    }

    private VBox createPromptCard(String number, String title, String description) {
        Label numberLabel = new Label(number);
        numberLabel.getStyleClass().add("prompt-number");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("prompt-title");
        Label descriptionLabel = new Label(description);
        descriptionLabel.setWrapText(true);
        descriptionLabel.getStyleClass().add("prompt-description");

        VBox card = new VBox(8, numberLabel, titleLabel, descriptionLabel);
        card.setPadding(new Insets(16));
        card.getStyleClass().add("prompt-card");
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private void refreshAllDashboardData() {
        List<Path> images = wallpaperLibrary.load();
        savedWallpaperCount.setText(Integer.toString(images.size()));
        favoriteCount.setText(Long.toString(images.stream().filter(wallpaperLibrary::isFavorite).count()));
        try {
            collectionCount.setText(Integer.toString(collectionStore.list().size()));
        } catch (IOException unavailable) {
            collectionCount.setText("0");
        }

        int rulesTotal = wallpaperAutomationService.timeRules().size()
                + wallpaperAutomationService.weatherRules().size()
                + wallpaperAutomationService.calendarRules().size();
        activeRulesCount.setText(Integer.toString(rulesTotal));

        refreshActiveWallpaperCard();
        refreshAutomationCard();
        refreshCollectionsShowcase();
        refreshQuickWallpapers(images);
    }

    private void refreshActiveWallpaperCard() {
        Path current = wallpaperService.getCurrentWallpaper().orElse(null);
        if (current == null) {
            List<Path> recent = wallpaperService.recentApplied();
            if (!recent.isEmpty()) {
                current = recent.getFirst();
            }
        }

        if (current != null && Files.isRegularFile(current)) {
            currentWallpaperName.setText(current.getFileName().toString());
            String fitMode = wallpaperService.preferredFit().toString();
            String details = "Scaling Fit: " + fitMode;
            try {
                long sizeKb = Files.size(current) / 1024;
                details += " · " + sizeKb + " KB";
            } catch (Exception ignored) { }
            currentWallpaperMeta.setText(details);

            Image img = new Image(current.toUri().toString(), 250, 140, true, true, true);
            ImageView iv = new ImageView(img);
            iv.setFitWidth(240);
            iv.setFitHeight(135);
            iv.setPreserveRatio(true);
            monitorScreenFrame.getChildren().setAll(iv);
        } else {
            currentWallpaperName.setText("No wallpaper applied yet");
            currentWallpaperMeta.setText("Choose an image from Discover or your Library to manifest on your desktop.");
            Label placeholder = new Label("▧ No Desktop Image");
            placeholder.getStyleClass().add("monitor-screen-empty");
            monitorScreenFrame.getChildren().setAll(placeholder);
        }
    }

    private void refreshAutomationCard() {
        boolean enabled = wallpaperAutomationService.isEnabled();
        boolean override = wallpaperAutomationService.isManualOverrideActive();

        automationBadge.getChildren().clear();
        Circle dot = new Circle(4);
        automationBadge.getChildren().add(dot);

        if (!enabled) {
            automationBadge.getStyleClass().setAll("status-badge-off");
            dot.setStyle("-fx-fill: #9d97ab;");
            automationBadgeLabel.setText("Automation Off");
            automationBadge.getChildren().add(automationBadgeLabel);
        } else if (override) {
            automationBadge.getStyleClass().setAll("status-badge-paused");
            dot.setStyle("-fx-fill: #f6ad55;");
            long remMin = wallpaperAutomationService.manualOverrideRemaining().toMinutes();
            automationBadgeLabel.setText("Paused (" + remMin + "m override)");
            automationBadge.getChildren().add(automationBadgeLabel);
            resumeOverrideButton.setVisible(true);
            resumeOverrideButton.setManaged(true);
        } else {
            automationBadge.getStyleClass().setAll("status-badge-active");
            dot.setStyle("-fx-fill: #5fe3a1;");
            automationBadgeLabel.setText("Automation Active");
            automationBadge.getChildren().add(automationBadgeLabel);
            resumeOverrideButton.setVisible(false);
            resumeOverrideButton.setManaged(false);
        }

        WallpaperAutomationService.CachedWeather weather = wallpaperAutomationService.lastWeather();
        if (weather != null) {
            String icon = switch (weather.condition()) {
                case CLEAR -> "☀️";
                case CLOUDY -> "☁️";
                case RAIN -> "🌧️";
                case SNOW -> "❄️";
                case FOG -> "🌫️";
                case STORM -> "⛈️";
            };
            weatherConditionLabel.setText(icon + " " + weather.condition() + " · " + weather.locationLabel());
            if (!Double.isNaN(weather.temperature())) {
                weatherTempLabel.setText(String.format("%.1f °C", weather.temperature()));
            } else {
                weatherTempLabel.setText("");
            }
        } else {
            weatherConditionLabel.setText("Open-Meteo Weather: Syncing conditions…");
            weatherTempLabel.setText("");
        }

        automationRuleStatus.setText(wallpaperAutomationService.latestStatus());
    }

    private void refreshCollectionsShowcase() {
        collectionsShortcutRow.getChildren().clear();
        try {
            List<WallpaperCollection> collections = collectionStore.list();
            if (collections.isEmpty()) {
                Label empty = new Label("No wallpaper vaults created yet. Create one to organize your favorites.");
                empty.getStyleClass().add("recent-wallpaper-empty");
                collectionsShortcutRow.getChildren().add(empty);
                return;
            }

            for (WallpaperCollection col : collections) {
                VBox card = new VBox(8);
                card.getStyleClass().add("dashboard-vault-card");

                Label name = new Label(col.name());
                name.getStyleClass().add("dashboard-vault-title");
                name.setWrapText(true);

                Label count = new Label(col.wallpapers().size() + (col.wallpapers().size() == 1 ? " wallpaper" : " wallpapers"));
                count.getStyleClass().add("dashboard-vault-count");

                Button shuffleBtn = new Button("Shuffle & Apply");
                shuffleBtn.getStyleClass().add("quiet-button");
                shuffleBtn.setOnAction(event -> shuffleAndApplyCollection(col));

                Button openBtn = new Button("Open Vault");
                openBtn.getStyleClass().add("quiet-button");
                openBtn.setOnAction(event -> openWallpaperSection("Collections"));

                HBox cardActions = new HBox(6, shuffleBtn, openBtn);
                cardActions.setAlignment(Pos.CENTER_LEFT);

                card.getChildren().addAll(name, count, cardActions);
                collectionsShortcutRow.getChildren().add(card);
            }
        } catch (IOException e) {
            Label err = new Label("Could not load vaults: " + e.getMessage());
            err.getStyleClass().add("recent-wallpaper-empty");
            collectionsShortcutRow.getChildren().add(err);
        }
    }

    private void shuffleAndApplyCollection(WallpaperCollection col) {
        if (col.wallpapers().isEmpty()) {
            showAlert("Collection “" + col.name() + "” is empty.");
            return;
        }
        List<Path> available = col.wallpapers().stream()
                .filter(WallpaperCollection.WallpaperReference::isAvailable)
                .map(WallpaperCollection.WallpaperReference::path)
                .filter(p -> p != null && Files.isRegularFile(p))
                .toList();
        if (available.isEmpty()) {
            showAlert("No available local wallpaper files found in collection “" + col.name() + "”.");
            return;
        }

        Path chosen = available.get(random.nextInt(available.size()));
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                wallpaperService.apply(chosen);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            wallpaperAutomationService.recordManualOverride();
            refreshActiveWallpaperCard();
            showAlert("Applied “" + chosen.getFileName() + "” from “" + col.name() + "”.");
        });
        task.setOnFailed(event -> showAlert("Could not apply wallpaper: " + task.getException().getMessage()));
        Thread worker = new Thread(task, "realityweaver-shuffle-apply");
        worker.setDaemon(true);
        worker.start();
    }

    private void refreshQuickWallpapers(List<Path> allWallpapers) {
        quickWallpapersRow.getChildren().clear();
        if (allWallpapers.isEmpty()) {
            Label empty = new Label("No wallpapers saved yet. Import an image or download from Discover.");
            empty.getStyleClass().add("recent-wallpaper-empty");
            quickWallpapersRow.getChildren().add(empty);
            return;
        }

        // Top 6 recent / favorites
        List<Path> candidates = new ArrayList<>(allWallpapers);
        Collections.reverse(candidates);
        List<Path> display = candidates.stream().limit(6).toList();

        for (Path p : display) {
            VBox card = new VBox(6);
            card.getStyleClass().add("dashboard-quick-wallpaper");
            card.setAlignment(Pos.CENTER);

            Image thumb = new Image(p.toUri().toString(), 160, 95, true, true, true);
            ImageView iv = new ImageView(thumb);
            iv.setFitWidth(150);
            iv.setFitHeight(90);
            iv.setPreserveRatio(true);

            Label name = new Label(p.getFileName().toString());
            name.getStyleClass().add("dashboard-quick-name");
            name.setWrapText(true);
            name.setMaxWidth(150);

            Button applyBtn = new Button("Apply Desktop");
            applyBtn.getStyleClass().add("quiet-button");
            applyBtn.setOnAction(event -> {
                Task<Void> task = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        wallpaperService.apply(p);
                        return null;
                    }
                };
                task.setOnSucceeded(e -> {
                    wallpaperAutomationService.recordManualOverride();
                    refreshActiveWallpaperCard();
                    showAlert("Desktop wallpaper applied: " + p.getFileName());
                });
                task.setOnFailed(e -> showAlert("Failed: " + task.getException().getMessage()));
                Thread worker = new Thread(task, "realityweaver-quick-apply");
                worker.setDaemon(true);
                worker.start();
            });

            card.getChildren().addAll(iv, name, applyBtn);
            quickWallpapersRow.getChildren().add(card);
        }
    }

    private void promptForWorldName() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Create a world");
        dialog.setHeaderText(null);
        dialog.setContentText("What should we call it?");
        dialog.getEditor().setPromptText("e.g. The Glass Coast");

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getStylesheets().add(
                DashboardView.class.getResource("/styles/app.css").toExternalForm());
        dialogPane.getStyleClass().add("world-dialog");

        Optional<String> result = dialog.showAndWait();
        result.map(String::strip)
                .filter(name -> !name.isBlank())
                .ifPresent(this::addWorld);
    }

    private void addWorld(String name) {
        if (!worldStorageReadable) {
            showWorldStorageError("Saved worlds could not be opened. Resolve the saved file issue before adding a world.");
            return;
        }
        if (worlds.stream().anyMatch(world -> world.name().equalsIgnoreCase(name))) {
            showAlert("A world with that name already exists.");
            return;
        }
        WorldDraft world = new WorldDraft(name);
        worlds.add(world);
        try {
            worldStore.save(worlds);
        } catch (IOException | IllegalArgumentException failure) {
            worlds.remove(world);
            showWorldStorageError("Could not save this world: " + safeMessage(failure));
            return;
        }
        watchWorldChanges(world);
        refreshWorldCards();
        openWorld(world);
    }

    private Button createWorldCard(WorldDraft world) {
        Label icon = new Label("✦");
        icon.getStyleClass().add("world-card-icon");
        Label worldName = new Label(world.name());
        worldName.getStyleClass().add("world-card-title");
        Label details = new Label("Saved on this computer");
        details.getStyleClass().add("world-card-details");

        VBox copy = new VBox(5, worldName, details);
        HBox.setHgrow(copy, Priority.ALWAYS);
        Label arrow = new Label("↗");
        arrow.getStyleClass().add("world-card-arrow");

        HBox cardContent = new HBox(14, icon, copy, arrow);
        cardContent.setAlignment(Pos.CENTER_LEFT);

        Button card = new Button();
        card.setGraphic(cardContent);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("world-card");
        card.setOnAction(event -> openWorld(world));
        return card;
    }

    private void refreshWorldCards() {
        worldCount.setText(worlds.size() == 1 ? "1 world" : worlds.size() + " worlds");
        List<Node> children = new ArrayList<>();
        if (worlds.isEmpty()) {
            children.add(emptyState);
        }
        children.add(worldStoreStatus);
        worlds.stream().map(this::createWorldCard).forEach(children::add);
        worldList.getChildren().setAll(children);
    }

    private void watchWorldChanges(WorldDraft world) {
        world.setOnChange(() -> {
            if (worldStorageReadable) {
                worldSaveDebounce.playFromStart();
            }
        });
    }

    private void saveWorlds() {
        if (!worldStorageReadable) {
            return;
        }
        try {
            worldStore.save(worlds);
            worldStoreStatus.setText("Worlds and memory notes save on this computer.");
            worldStoreStatus.getStyleClass().remove("world-store-error");
        } catch (IOException | IllegalArgumentException failure) {
            showWorldStorageError("Could not save world changes: " + safeMessage(failure));
        }
    }

    private void showWorldStorageError(String message) {
        worldStoreStatus.setText(message);
        if (!worldStoreStatus.getStyleClass().contains("world-store-error")) {
            worldStoreStatus.getStyleClass().add("world-store-error");
        }
    }

    private String safeMessage(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Check the local storage folder and try again." : failure.getMessage();
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        if (getScene() != null && getScene().getWindow() != null) {
            alert.initOwner(getScene().getWindow());
        }
        alert.setTitle("RealityWeaver");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void openWorld(WorldDraft world) {
        WorldWorkspaceView worldScreen = new WorldWorkspaceView(world, () -> showHomeScreen(),
                wallpaperAutomationService::recordManualOverride);
        screenHost.getChildren().setAll(worldScreen);
    }

    public void close() {
        worldSaveDebounce.stop();
        saveWorlds();
        wallpaperScreen.close();
    }
}
