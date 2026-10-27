package com.realityweaver;

import com.realityweaver.AppSettings;
import com.realityweaver.auth.AuthService;
import com.realityweaver.auth.LocalAccount;
import com.realityweaver.ui.DashboardView;
import com.realityweaver.ui.FirstRunWizardView;
import com.realityweaver.ui.LoginView;
import com.realityweaver.ui.RegisterView;
import com.realityweaver.wallpaper.WallpaperAutomationService;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Starts the RealityWeaver desktop application. */
public final class RealityWeaverApplication extends Application {
    private final AuthService authService = new AuthService();
    private final WallpaperAutomationService wallpaperAutomationService = new WallpaperAutomationService();
    private Scene scene;
    private Stage primaryStage;
    private TrayIcon trayIcon;
    private DashboardView dashboardView;

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        wallpaperAutomationService.startIfEnabled(ignored -> { });
        Parent firstPage = AppSettings.isSetupComplete() ? createStartupPage()
                : new FirstRunWizardView(this::openWorkspace, this::showLogin, wallpaperAutomationService);

        scene = new Scene(firstPage, 1180, 800);
        scene.getStylesheets().add(
                RealityWeaverApplication.class.getResource("/styles/app.css").toExternalForm());

        stage.setTitle("RealityWeaver — World-building studio");
        stage.setMinWidth(900);
        stage.setMinHeight(660);
        stage.setScene(scene);
        installSystemTray(stage);
        stage.show();
    }

    private void installSystemTray(Stage stage) {
        if (!SystemTray.isSupported()) {
            return;
        }
        PopupMenu menu = new PopupMenu();
        java.awt.MenuItem open = new java.awt.MenuItem("Open RealityWeaver");
        open.addActionListener(event -> Platform.runLater(this::showWindow));
        java.awt.MenuItem toggleAutomation = new java.awt.MenuItem("Toggle wallpaper automation");
        toggleAutomation.addActionListener(event -> {
            boolean enable = !wallpaperAutomationService.isEnabled();
            wallpaperAutomationService.setEnabled(enable, ignored -> { });
        });
        java.awt.MenuItem exit = new java.awt.MenuItem("Exit");
        exit.addActionListener(event -> Platform.runLater(Platform::exit));
        menu.add(open);
        menu.addSeparator();
        menu.add(toggleAutomation);
        menu.addSeparator();
        menu.add(exit);
        trayIcon = new TrayIcon(createTrayImage(), "RealityWeaver", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(event -> Platform.runLater(this::showWindow));
        try {
            SystemTray.getSystemTray().add(trayIcon);
            Platform.setImplicitExit(false);
            stage.setOnCloseRequest(event -> {
                event.consume();
                stage.hide();
            });
        } catch (AWTException | SecurityException unavailable) {
            trayIcon = null;
        }
    }

    private BufferedImage createTrayImage() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(112, 83, 158));
            graphics.fillOval(2, 2, 28, 28);
            graphics.setColor(new Color(238, 226, 255));
            graphics.drawString("✦", 9, 22);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void showWindow() {
        if (primaryStage != null) {
            primaryStage.show();
            primaryStage.setIconified(false);
            primaryStage.toFront();
        }
    }

    private Parent createLoginPage() {
        return new LoginView(authService, this::openWorkspace, this::showRegistration, this::continueLocally);
    }

    private Parent createRegisterPage() {
        return new RegisterView(authService, this::openWorkspace, this::showLogin, this::continueLocally);
    }

    private Parent createStartupPage() {
        return new DashboardView(new LocalAccount("Local workspace", ""), this::showLogin,
                wallpaperAutomationService);
    }

    private void continueLocally() {
        AppSettings.setSetupComplete(true);
        openWorkspace(new LocalAccount("Local workspace", ""));
    }

    private void showLogin() {
        if (dashboardView != null) {
            dashboardView.close();
            dashboardView = null;
        }
        scene.setRoot(createLoginPage());
    }

    private void showRegistration() {
        scene.setRoot(createRegisterPage());
    }

    private void openWorkspace(LocalAccount account) {
        AppSettings.setSetupComplete(true);
        dashboardView = new DashboardView(account, this::showLogin, wallpaperAutomationService);
        scene.setRoot(dashboardView);
    }

    @Override
    public void stop() {
        if (dashboardView != null) {
            dashboardView.close();
        }
        if (trayIcon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        wallpaperAutomationService.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
