package com.realityweaver;

import com.realityweaver.auth.AuthService;
import com.realityweaver.auth.LocalAccount;
import com.realityweaver.ui.DashboardView;
import com.realityweaver.ui.LoginView;
import com.realityweaver.ui.RegisterView;
import java.io.IOException;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Starts the RealityWeaver desktop application. */
public final class RealityWeaverApplication extends Application {
    private final AuthService authService = new AuthService();
    private Scene scene;

    @Override
    public void start(Stage stage) {
        Parent firstPage;
        try {
            firstPage = authService.hasAccounts() ? createLoginPage() : createRegisterPage();
        } catch (IOException ex) {
            firstPage = createLoginPage();
        }

        scene = new Scene(firstPage, 1180, 800);
        scene.getStylesheets().add(
                RealityWeaverApplication.class.getResource("/styles/app.css").toExternalForm());

        stage.setTitle("RealityWeaver — World-building studio");
        stage.setMinWidth(900);
        stage.setMinHeight(660);
        stage.setScene(scene);
        stage.show();
    }

    private Parent createLoginPage() {
        return new LoginView(authService, this::openWorkspace, this::showRegistration);
    }

    private Parent createRegisterPage() {
        return new RegisterView(authService, this::openWorkspace, this::showLogin);
    }

    private void showLogin() {
        scene.setRoot(createLoginPage());
    }

    private void showRegistration() {
        scene.setRoot(createRegisterPage());
    }

    private void openWorkspace(LocalAccount account) {
        scene.setRoot(new DashboardView(account, this::showLogin));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
