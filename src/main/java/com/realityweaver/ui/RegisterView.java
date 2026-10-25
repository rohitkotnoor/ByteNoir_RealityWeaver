package com.realityweaver.ui;

import com.realityweaver.auth.AuthService;
import com.realityweaver.auth.LocalAccount;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

/** Account creation screen for this device. */
public final class RegisterView extends BorderPane {
    private final AuthService authService;
    private final Consumer<LocalAccount> onAuthenticated;
    private final Runnable onLogin;
    private final VBox card = AuthPageSupport.createCard();
    private final TextField displayName = new TextField();
    private final TextField email = new TextField();
    private final PasswordField password = new PasswordField();
    private final PasswordField confirmation = new PasswordField();
    private final Label feedback = AuthPageSupport.feedbackLabel();
    private final Button register = new Button("Create account");

    public RegisterView(AuthService authService, Consumer<LocalAccount> onAuthenticated, Runnable onLogin) {
        this.authService = authService;
        this.onAuthenticated = onAuthenticated;
        this.onLogin = onLogin;
        buildPage();
    }

    private void buildPage() {
        Label title = new Label("Create your account");
        title.getStyleClass().add("auth-title");
        Label description = new Label("Set up a local account to open your RealityWeaver workspace.");
        description.setWrapText(true);
        description.getStyleClass().add("auth-description");

        displayName.setPromptText("Your name");
        displayName.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= 60 ? change : null));
        email.setPromptText("you@example.com");
        email.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= 254 ? change : null));
        password.setPromptText("At least 10 characters");
        password.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= 1024 ? change : null));
        confirmation.setPromptText("Re-enter your password");
        confirmation.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= 1024 ? change : null));

        VBox nameField = new VBox(7, AuthPageSupport.fieldLabel("Name"), displayName);
        VBox emailField = new VBox(7, AuthPageSupport.fieldLabel("Email"), email);
        VBox passwordField = new VBox(7, AuthPageSupport.fieldLabel("Password"), password);
        VBox confirmationField = new VBox(7, AuthPageSupport.fieldLabel("Confirm password"), confirmation);

        register.getStyleClass().add("primary-button");
        register.setMaxWidth(Double.MAX_VALUE);
        register.setDefaultButton(true);
        register.setOnAction(event -> submit());
        displayName.setOnAction(event -> submit());
        email.setOnAction(event -> submit());
        password.setOnAction(event -> submit());
        confirmation.setOnAction(event -> submit());

        Button loginLink = new Button("Already have an account?  Sign in");
        loginLink.getStyleClass().add("auth-link-button");
        loginLink.setOnAction(event -> onLogin.run());

        Label localNote = new Label("Your account and password verifier stay on this device.");
        localNote.getStyleClass().add("auth-note");
        localNote.setWrapText(true);

        card.getChildren().setAll(
                AuthPageSupport.eyebrow("YOUR CREATIVE SPACE"), title, description,
                nameField, emailField, passwordField, confirmationField,
                feedback, register, loginLink, localNote);
        AuthPageSupport.configurePage(this, card);
    }

    private void submit() {
        String cleanName = displayName.getText().strip();
        String cleanEmail = email.getText().strip();
        char[] enteredPassword = password.getText().toCharArray();
        char[] repeatedPassword = confirmation.getText().toCharArray();
        boolean passwordsMatch = Arrays.equals(enteredPassword, repeatedPassword);
        Arrays.fill(repeatedPassword, '\0');

        if (cleanName.isBlank()) {
            clear(enteredPassword);
            AuthPageSupport.showFeedback(feedback, "Enter your name.", true);
            displayName.requestFocus();
            return;
        }
        if (!cleanEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            clear(enteredPassword);
            AuthPageSupport.showFeedback(feedback, "Enter a valid email address.", true);
            email.requestFocus();
            return;
        }
        if (enteredPassword.length < 10 || enteredPassword.length > 1024) {
            clear(enteredPassword);
            AuthPageSupport.showFeedback(feedback, "Use a password between 10 and 1024 characters.", true);
            password.requestFocus();
            return;
        }
        if (!passwordsMatch) {
            clear(enteredPassword);
            AuthPageSupport.showFeedback(feedback, "The passwords do not match.", true);
            confirmation.clear();
            confirmation.requestFocus();
            return;
        }

        password.clear();
        confirmation.clear();
        card.setDisable(true);
        AuthPageSupport.showFeedback(feedback, "Creating your account…", false);
        CompletableFuture.supplyAsync(() -> {
            try {
                return RegisterResult.success(authService.register(cleanName, cleanEmail, enteredPassword));
            } catch (AuthService.DuplicateEmailException ex) {
                return RegisterResult.failure("An account with this email already exists. Sign in instead.");
            } catch (IllegalArgumentException ex) {
                return RegisterResult.failure(ex.getMessage());
            } catch (IOException ex) {
                return RegisterResult.failure("Local account storage could not be written.");
            } catch (RuntimeException ex) {
                return RegisterResult.failure("Account creation could not be completed. Try again.");
            } finally {
                clear(enteredPassword);
            }
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            card.setDisable(false);
            if (error != null) {
                AuthPageSupport.showFeedback(feedback, "Account creation could not be completed. Try again.", true);
            } else if (result.account() != null) {
                onAuthenticated.accept(result.account());
            } else {
                AuthPageSupport.showFeedback(feedback, result.message(), true);
            }
        }));
    }

    private static void clear(char[] password) {
        Arrays.fill(password, '\0');
    }

    private record RegisterResult(LocalAccount account, String message) {
        private static RegisterResult success(LocalAccount account) {
            return new RegisterResult(account, "");
        }

        private static RegisterResult failure(String message) {
            return new RegisterResult(null, message);
        }
    }
}
