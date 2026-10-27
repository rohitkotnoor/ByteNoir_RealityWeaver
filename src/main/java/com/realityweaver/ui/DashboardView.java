package com.realityweaver.ui;

import com.realityweaver.auth.LocalAccount;
import com.realityweaver.wallpaper.WallpaperAutomationService;
import com.realityweaver.wallpaper.WallpaperCollectionStore;
import com.realityweaver.wallpaper.WallpaperLibrary;
import com.realityweaver.wallpaper.WallpaperService;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

/** Builds the local workspace dashboard and its persisted world drafts. */
public final class DashboardView extends BorderPane {
    private final LocalAccount account;
    private final Runnable onSignOut;
    private final WallpaperAutomationService wallpaperAutomationService;
    private final WallpaperLibrary wallpaperLibrary = new WallpaperLibrary();
    private final WallpaperCollectionStore collectionStore = new WallpaperCollectionStore();
    private final WorldStore worldStore = new WorldStore();
    private final List<WorldDraft> worlds = new ArrayList<>();
    private final WallpaperService wallpaperService = new WallpaperService();
    private final StackPane screenHost = new StackPane();
    private final Button studioNav = new Button("Home");
    private final Button wallpaperNav = new Button("Wallpaper studio");
    private final VBox worldList = new VBox(12);
    private final VBox emptyState = new VBox(12);
    private final Label worldCount = new Label("0 worlds");
    private final Label savedWallpaperCount = new Label("0");
    private final Label collectionCount = new Label("0");
    private final Label favoriteCount = new Label("0");
    private final Label automationSummary = new Label("Off");
    private final Label lastAppliedSummary = new Label("No wallpapers applied yet");
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
        studioScreen = createScrollableDashboard();
        refreshWallpaperOverview();
        wallpaperScreen = new WallpaperStudioView(wallpaperAutomationService);
        wallpaperScreen.setVisible(false);
        wallpaperScreen.setManaged(false);
        screenHost.getChildren().setAll(studioScreen, wallpaperScreen);
        setTop(createTopBar());
        setCenter(screenHost);
    }

    private HBox createTopBar() {
        StackPane mark = new StackPane(new Label("✦"));
        mark.getStyleClass().add("brand-mark");

        Label brandName = new Label("RealityWeaver");
        brandName.getStyleClass().add("brand-name");
        Label brandCaption = new Label("WORLD-BUILDING STUDIO");
        brandCaption.getStyleClass().add("brand-caption");
        VBox brandCopy = new VBox(2, brandName, brandCaption);
        brandCopy.setAlignment(Pos.CENTER_LEFT);

        HBox brand = new HBox(12, mark, brandCopy);
        brand.setAlignment(Pos.CENTER_LEFT);

        studioNav.getStyleClass().setAll("nav-button", "nav-button-selected");
        wallpaperNav.getStyleClass().setAll("nav-button");
        studioNav.setOnAction(event -> showScreen(false));
        wallpaperNav.setOnAction(event -> showScreen(true));
        HBox navigation = new HBox(4, studioNav, wallpaperNav);
        navigation.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

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

        HBox topBar = new HBox(20, brand, navigation, spacer, status, profile);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(18, 48, 18, 48));
        topBar.getStyleClass().add("top-bar");
        return topBar;
    }

    private void showScreen(boolean showWallpapers) {
        screenHost.getChildren().setAll(studioScreen, wallpaperScreen);
        studioScreen.setVisible(!showWallpapers);
        studioScreen.setManaged(!showWallpapers);
        wallpaperScreen.setVisible(showWallpapers);
        wallpaperScreen.setManaged(showWallpapers);

        updateNavigation(showWallpapers);
        if (!showWallpapers) {
            refreshWallpaperOverview();
        }
    }

    private void updateNavigation(boolean showWallpapers) {
        studioNav.getStyleClass().remove("nav-button-selected");
        wallpaperNav.getStyleClass().remove("nav-button-selected");
        (showWallpapers ? wallpaperNav : studioNav).getStyleClass().add("nav-button-selected");
    }

    private ScrollPane createScrollableDashboard() {
        VBox content = new VBox(28);
        content.setPadding(new Insets(38, 48, 52, 48));
        content.setMaxWidth(1160);

        content.getChildren().addAll(
                createPageIntro(),
                createHero(),
                createWallpaperOverview(),
                createWorldsSection(),
                createPromptRow());

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("content-scroll");
        return scrollPane;
    }

    private VBox createWallpaperOverview() {
        Label title = new Label("Wallpaper studio overview");
        title.getStyleClass().add("section-title");
        Button browse = new Button("Open wallpaper studio");
        browse.getStyleClass().add("quiet-button");
        browse.setOnAction(event -> openWallpaperSection("My Wallpapers"));
        HBox header = new HBox(title, browse);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(title, javafx.scene.layout.Priority.ALWAYS);

        HBox stats = new HBox(10,
                dashboardStat("Saved wallpapers", savedWallpaperCount),
                dashboardStat("Collections", collectionCount),
                dashboardStat("Favorites", favoriteCount),
                dashboardStat("Automation", automationSummary));
        Label recentLabel = new Label();
        recentLabel.getStyleClass().add("recent-wallpaper-empty");
        wallpaperOverviewRecent = recentLabel;
        HBox quickActions = new HBox(8,
                wallpaperQuickAction("Discover", "Discover"),
                wallpaperQuickAction("My wallpapers", "My Wallpapers"),
                wallpaperQuickAction("Collections", "Collections"),
                wallpaperQuickAction("Automation", "Automation"));
        quickActions.setAlignment(Pos.CENTER_LEFT);
        VBox overview = new VBox(11, header, stats, recentLabel, quickActions);
        overview.getStyleClass().add("dashboard-wallpaper-overview");
        return overview;
    }

    private Button wallpaperQuickAction(String label, String section) {
        Button action = new Button(label);
        action.getStyleClass().add("quiet-button");
        action.setOnAction(event -> openWallpaperSection(section));
        return action;
    }

    private void openWallpaperSection(String section) {
        showScreen(true);
        wallpaperScreen.showSection(section);
    }

    private Label wallpaperOverviewRecent;

    private VBox dashboardStat(String title, Label value) {
        Label label = new Label(title);
        label.getStyleClass().add("dashboard-stat-label");
        value.getStyleClass().add("dashboard-stat-value");
        VBox card = new VBox(5, value, label);
        card.getStyleClass().add("dashboard-stat-card");
        HBox.setHgrow(card, javafx.scene.layout.Priority.ALWAYS);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private void refreshWallpaperOverview() {
        List<java.nio.file.Path> images = wallpaperLibrary.load();
        savedWallpaperCount.setText(Integer.toString(images.size()));
        favoriteCount.setText(Long.toString(images.stream().filter(wallpaperLibrary::isFavorite).count()));
        try {
            collectionCount.setText(Integer.toString(collectionStore.list().size()));
        } catch (IOException unavailable) {
            collectionCount.setText("—");
        }
        automationSummary.setText(wallpaperAutomationService.isEnabled() ? "On" : "Off");
        if (wallpaperOverviewRecent != null) {
            List<java.nio.file.Path> applied = wallpaperService.recentApplied();
            wallpaperOverviewRecent.setText(applied.isEmpty() ? "Last applied by RealityWeaver: none yet"
                    : "Last applied by RealityWeaver: " + applied.getFirst().getFileName());
        }
    }

    private VBox createPageIntro() {
        Label eyebrow = new Label("YOUR CREATIVE SPACE");
        eyebrow.getStyleClass().add("eyebrow");

        Label title = new Label("Make room for the impossible.");
        title.getStyleClass().add("page-title");

        Label subtitle = new Label("Gather the places, people, and ideas that bring a world to life.");
        subtitle.getStyleClass().add("page-subtitle");

        return new VBox(8, eyebrow, title, subtitle);
    }

    private HBox createHero() {
        Label kicker = new Label("A STUDIO FOR YOUR IMAGINATION");
        kicker.getStyleClass().add("hero-kicker");

        Label title = new Label("Your next world\nstarts here.");
        title.getStyleClass().add("hero-title");

        Label description = new Label(
                "Start with a spark. Shape it into a place with its own stories, characters, and rules.");
        description.setWrapText(true);
        description.getStyleClass().add("hero-description");
        description.setMaxWidth(470);

        Button createButton = new Button("Create a world");
        createButton.getStyleClass().add("primary-button");
        createButton.setOnAction(event -> promptForWorldName());

        VBox copy = new VBox(14, kicker, title, description, createButton);
        copy.setAlignment(Pos.CENTER_LEFT);
        copy.setMaxWidth(570);

        StackPane artwork = createArtwork();
        HBox.setHgrow(copy, javafx.scene.layout.Priority.ALWAYS);

        HBox hero = new HBox(24, copy, artwork);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.setPadding(new Insets(34, 40, 34, 40));
        hero.setMinHeight(278);
        hero.getStyleClass().add("hero-card");
        return hero;
    }

    private StackPane createArtwork() {
        Circle outerOrbit = new Circle(105);
        outerOrbit.getStyleClass().add("orbit-ring");
        Circle innerOrbit = new Circle(74);
        innerOrbit.getStyleClass().add("orbit-ring-inner");
        Circle planet = new Circle(46);
        planet.getStyleClass().add("planet");

        Label star = new Label("✦");
        star.getStyleClass().add("planet-star");
        Label sparkleOne = new Label("✧");
        sparkleOne.getStyleClass().add("art-sparkle");
        StackPane.setAlignment(sparkleOne, Pos.TOP_RIGHT);
        StackPane.setMargin(sparkleOne, new Insets(38, 38, 0, 0));
        Label sparkleTwo = new Label("·");
        sparkleTwo.getStyleClass().add("art-sparkle-small");
        StackPane.setAlignment(sparkleTwo, Pos.BOTTOM_LEFT);
        StackPane.setMargin(sparkleTwo, new Insets(0, 0, 36, 42));

        StackPane art = new StackPane(outerOrbit, innerOrbit, planet, star, sparkleOne, sparkleTwo);
        art.setMinSize(250, 230);
        art.setPrefSize(270, 230);
        art.setMaxSize(300, 250);
        art.getStyleClass().add("hero-art");
        return art;
    }

    private VBox createWorldsSection() {
        Label sectionTitle = new Label("Your worlds");
        sectionTitle.getStyleClass().add("section-title");
        worldCount.getStyleClass().add("count-label");

        HBox sectionHeader = new HBox(sectionTitle, worldCount);
        sectionHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sectionTitle, javafx.scene.layout.Priority.ALWAYS);

        Label emptyIcon = new Label("＋");
        emptyIcon.getStyleClass().add("empty-icon");
        Label emptyTitle = new Label("Your canvas is clear");
        emptyTitle.getStyleClass().add("empty-title");
        Label emptyCopy = new Label("Create a world to collect its places, characters, and stories in one space.");
        emptyCopy.setWrapText(true);
        emptyCopy.setMaxWidth(510);
        emptyCopy.getStyleClass().add("empty-copy");
        Button emptyButton = new Button("Create your first world");
        emptyButton.getStyleClass().add("quiet-button");
        emptyButton.setOnAction(event -> promptForWorldName());

        emptyState.getChildren().setAll(emptyIcon, emptyTitle, emptyCopy, emptyButton);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setPadding(new Insets(26, 24, 28, 24));
        emptyState.getStyleClass().add("empty-state");

        worldList.getStyleClass().add("world-list");
        worldStoreStatus.setWrapText(true);
        worldStoreStatus.getStyleClass().add("world-item-empty");
        refreshWorldCards();

        return new VBox(14, sectionHeader, worldList);
    }

    private HBox createPromptRow() {
        return new HBox(
                createPromptCard("01", "Shape a place", "Sketch the setting where your story begins."),
                createPromptCard("02", "Meet the locals", "Give your world a few memorable voices."),
                createPromptCard("03", "Find a spark", "Capture the question that keeps you curious."));
    }

    private VBox createPromptCard(String number, String title, String description) {
        Label numberLabel = new Label(number);
        numberLabel.getStyleClass().add("prompt-number");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("prompt-title");
        Label descriptionLabel = new Label(description);
        descriptionLabel.setWrapText(true);
        descriptionLabel.getStyleClass().add("prompt-description");

        VBox card = new VBox(10, numberLabel, titleLabel, descriptionLabel);
        card.setPadding(new Insets(18));
        card.getStyleClass().add("prompt-card");
        HBox.setHgrow(card, javafx.scene.layout.Priority.ALWAYS);
        return card;
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
        HBox.setHgrow(copy, javafx.scene.layout.Priority.ALWAYS);
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
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.initOwner(getScene().getWindow());
        alert.setTitle("Worlds");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void openWorld(WorldDraft world) {
        WorldWorkspaceView worldScreen = new WorldWorkspaceView(world, () -> showScreen(false),
                wallpaperAutomationService::recordManualOverride);
        screenHost.getChildren().setAll(worldScreen);
        updateNavigation(false);
    }

    public void close() {
        worldSaveDebounce.stop();
        saveWorlds();
        wallpaperScreen.close();
    }
}
