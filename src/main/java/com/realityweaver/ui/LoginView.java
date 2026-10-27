package com.realityweaver.ui;

import com.realityweaver.auth.AuthService;
import com.realityweaver.auth.LocalAccount;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
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

/** Sign-in screen for existing local accounts. */
public final class LoginView extends BorderPane {
    private final AuthService authService;
    private final Consumer<LocalAccount> onAuthenticated;
    private final Runnable onRegister;
    private final Runnable onContinueLocally;
    private final VBox card = AuthPageSupport.createCard();
    private final TextField email = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label feedback = AuthPageSupport.feedbackLabel();
    private final Button signIn = new Button("Sign in");

    public LoginView(AuthService authService, Consumer<LocalAccount> onAuthenticated, Runnable onRegister) {
        this(authService, onAuthenticated, onRegister, () -> { });
    }

    public LoginView(AuthService authService, Consumer<LocalAccount> onAuthenticated, Runnable onRegister,
            Runnable onContinueLocally) {
        this.authService = authService;
        this.onAuthenticated = onAuthenticated;
        this.onRegister = onRegister;
        this.onContinueLocally = onContinueLocally;
        buildPage();
    }

    private void buildPage() {
        Label title = new Label("Welcome back");
        title.getStyleClass().add("auth-title");
        Label description = new Label("Sign in to open your RealityWeaver workspace.");
        description.setWrapText(true);
        description.getStyleClass().add("auth-description");

        email.setPromptText("you@example.com");
        email.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= 254 ? change : null));
        password.setPromptText("Enter your password");
        password.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= 1024 ? change : null));
        VBox emailField = new VBox(7, AuthPageSupport.fieldLabel("Email"), email);
        VBox passwordField = new VBox(7, AuthPageSupport.fieldLabel("Password"), password);

        signIn.getStyleClass().add("primary-button");
        signIn.setMaxWidth(Double.MAX_VALUE);
        signIn.setDefaultButton(true);
        signIn.setOnAction(event -> submit());
        email.setOnAction(event -> submit());
        password.setOnAction(event -> submit());

        Button registerLink = new Button("New to RealityWeaver?  Create an account");
        registerLink.getStyleClass().add("auth-link-button");
        registerLink.setOnAction(event -> onRegister.run());

        Button localWorkspace = new Button("Continue without an account");
        localWorkspace.getStyleClass().add("auth-link-button");
        localWorkspace.setOnAction(event -> onContinueLocally.run());

        Label localNote = new Label("Accounts are optional and stored on this device.");
        localNote.getStyleClass().add("auth-note");
        localNote.setWrapText(true);

        card.getChildren().setAll(
                AuthPageSupport.eyebrow("YOUR CREATIVE SPACE"), title, description,
                emailField, passwordField, feedback, signIn, registerLink, localWorkspace, localNote);
        AuthPageSupport.configurePage(this, card);
    }

    private void submit() {
        String cleanEmail = email.getText().strip();
        char[] enteredPassword = password.getText().toCharArray();
        if (!cleanEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            Arrays.fill(enteredPassword, '\0');
            AuthPageSupport.showFeedback(feedback, "Enter a valid email address.", true);
            email.requestFocus();
            return;
        }
        if (enteredPassword.length < 10 || enteredPassword.length > 1024) {
            Arrays.fill(enteredPassword, '\0');
            AuthPageSupport.showFeedback(feedback, "Enter your password (at least 10 characters).", true);
            password.requestFocus();
            return;
        }

        password.clear();
        card.setDisable(true);
        AuthPageSupport.showFeedback(feedback, "Signing in…", false);
        CompletableFuture.supplyAsync(() -> {
            try {
                Optional<LocalAccount> account = authService.authenticate(cleanEmail, enteredPassword);
                return account.map(LoginResult::success)
                        .orElseGet(() -> LoginResult.failure("Email or password is incorrect."));
            } catch (IllegalArgumentException ex) {
                return LoginResult.failure(ex.getMessage());
            } catch (IOException ex) {
                return LoginResult.failure("Local account storage could not be read.");
            } catch (RuntimeException ex) {
                return LoginResult.failure("Sign-in could not be completed. Try again.");
            } finally {
                Arrays.fill(enteredPassword, '\0');
            }
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            card.setDisable(false);
            if (error != null) {
                AuthPageSupport.showFeedback(feedback, "Sign-in could not be completed. Try again.", true);
            } else if (result.account() != null) {
                onAuthenticated.accept(result.account());
            } else {
                AuthPageSupport.showFeedback(feedback, result.message(), true);
            }
        }));
    }

    private record LoginResult(LocalAccount account, String message) {
        private static LoginResult success(LocalAccount account) {
            return new LoginResult(account, "");
        }

        private static LoginResult failure(String message) {
            return new LoginResult(null, message);
        }
    }
}
