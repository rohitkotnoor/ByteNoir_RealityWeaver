package com.realityweaver.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Shared framing and field styles for the sign-in and registration screens. */
final class AuthPageSupport {
    private AuthPageSupport() {
    }

    static BorderPane createPage(VBox card) {
        BorderPane page = new BorderPane();
        configurePage(page, card);
        return page;
    }

    static void configurePage(BorderPane page, VBox card) {
        page.getStyleClass().add("auth-page");
        page.setTop(createHeader());
        page.setCenter(card);
        page.setPadding(new Insets(0, 28, 28, 28));
    }

    static VBox createCard() {
        VBox card = new VBox(14);
        card.setMaxWidth(440);
        card.setPadding(new Insets(32));
        card.getStyleClass().add("auth-card");
        return card;
    }

    static Label eyebrow(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("eyebrow");
        return label;
    }

    static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("auth-field-label");
        return label;
    }

    static Label feedbackLabel() {
        Label label = new Label();
        label.setWrapText(true);
        label.setManaged(false);
        label.setVisible(false);
        label.getStyleClass().add("auth-feedback");
        return label;
    }

    static void showFeedback(Label label, String message, boolean error) {
        label.setText(message);
        label.getStyleClass().removeAll("auth-error", "auth-progress");
        label.getStyleClass().add(error ? "auth-error" : "auth-progress");
        label.setManaged(true);
        label.setVisible(true);
    }

    private static HBox createHeader() {
        StackPane mark = new StackPane(new Label("✦"));
        mark.getStyleClass().add("brand-mark");
        Label brandName = new Label("RealityWeaver");
        brandName.getStyleClass().add("brand-name");
        Label caption = new Label("WORLD-BUILDING STUDIO");
        caption.getStyleClass().add("brand-caption");
        VBox brandCopy = new VBox(2, brandName, caption);
        HBox brand = new HBox(12, mark, brandCopy);
        brand.setAlignment(Pos.CENTER_LEFT);

        Label deviceLabel = new Label("DEVICE LOCAL");
        deviceLabel.getStyleClass().add("workspace-label");
        HBox deviceBadge = new HBox(deviceLabel);
        deviceBadge.getStyleClass().add("workspace-badge");

        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox header = new HBox(16, brand, spacer, deviceBadge);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 20, 18, 20));
        header.getStyleClass().add("top-bar");
        return header;
    }
}
