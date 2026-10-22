package com.realityweaver;

import com.realityweaver.ui.DashboardView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Starts the RealityWeaver desktop application. */
public final class RealityWeaverApplication extends Application {
    @Override
    public void start(Stage stage) {
        DashboardView dashboard = new DashboardView();
        Scene scene = new Scene(dashboard, 1180, 800);
        scene.getStylesheets().add(
                RealityWeaverApplication.class.getResource("/styles/app.css").toExternalForm());

        stage.setTitle("RealityWeaver — World-building studio");
        stage.setMinWidth(900);
        stage.setMinHeight(660);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
