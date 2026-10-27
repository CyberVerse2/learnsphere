package edu.learnsphere.app.ui.profiles;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.View;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.Avatar;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.service.ProfileSummary;
import java.util.Objects;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

/**
 * Opening a profile that has a password (full screen): its avatar and name, the password field, Open, and
 * "Switch profile" for someone else. A wrong password says so in one sentence and keeps the field ready for
 * another try; the profile in use (if any) stays open until the right one is entered.
 */
public final class UnlockPage implements View {

    private static final String STYLESHEET = Objects.requireNonNull(
            UnlockPage.class.getResource("profiles.css"), "profiles.css").toExternalForm();

    private final AppContext context;
    private final Navigator navigator;
    private final String profileId;
    private final Disposer disposer = new Disposer();
    private final StackPane root = new StackPane();
    private final VBox column = new VBox();
    private final PasswordField password = new PasswordField();
    private final Button open = new Button("Open profile");
    private final Label error = new Label();
    private final ActivityIndicator working = new ActivityIndicator(16);
    private boolean busy;

    public UnlockPage(AppContext context, Navigator navigator, Destination.Unlock destination) {
        this.context = context;
        this.navigator = navigator;
        this.profileId = destination.profileId();
        root.getStyleClass().addAll("full-screen", "unlock-page");
        root.getStylesheets().add(STYLESHEET);
        column.getStyleClass().add("unlock-column");
        column.setAlignment(Pos.TOP_CENTER);
        column.setMaxWidth(Region.USE_PREF_SIZE);
        column.setMaxHeight(Region.USE_PREF_SIZE);
        column.getChildren().setAll(Skeleton.circle(88), Skeleton.line(200), Skeleton.block(320, 40));
        root.getChildren().add(column);
    }

    @Override
    public Region root() {
        return root;
    }

    @Override
    public void onShown() {
        disposer.add(UiTasks.run(() -> context.profiles().find(profileId), this::show,
                message -> navigator.reset(Destination.PROFILES)));
    }

    @Override
    public void dispose() {
        disposer.dispose();
        password.clear();
    }

    /** The password field (tests). */
    public PasswordField passwordField() {
        return password;
    }

    /** The sentence under the field, empty when there is none (tests). */
    public String errorText() {
        return error.getText();
    }

    private void show(Optional<ProfileSummary> found) {
        if (found.isEmpty()) {
            navigator.reset(Destination.PROFILES);
            return;
        }
        ProfileSummary profile = found.get();
        if (!profile.passwordProtected()) {
            // The password was removed in the meantime: nothing to ask.
            submit(profile);
        }
        Avatar avatar = new Avatar(profile.initials(), profile.avatarHue(), 88);
        Label name = new Label(profile.displayName());
        name.getStyleClass().addAll("text-display", "unlock-name");
        name.setWrapText(true);
        name.setTextAlignment(TextAlignment.CENTER);
        name.setMnemonicParsing(false);
        Label prompt = new Label("Enter your password to open this profile.");
        prompt.getStyleClass().addAll("text-muted", "unlock-prompt");

        password.setPromptText("Password");
        password.setAccessibleText("Password for " + profile.displayName());
        password.getStyleClass().add("unlock-password");
        password.textProperty().addListener((obs, old, text) -> {
            if (!busy && !text.isEmpty()) {
                error.setText("");
            }
        });
        password.setOnAction(event -> submit(profile));

        open.getStyleClass().addAll("primary", "large", "unlock-open");
        open.setMaxWidth(Double.MAX_VALUE);
        open.disableProperty().bind(password.textProperty().isEmpty());
        open.setOnAction(event -> submit(profile));

        error.getStyleClass().add("unlock-error");
        error.setWrapText(true);
        error.setTextAlignment(TextAlignment.CENTER);
        error.setAccessibleRole(AccessibleRole.TEXT);
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        error.visibleProperty().bind(error.managedProperty());

        working.setVisible(false);
        working.setManaged(false);
        HBox form = new HBox(password);
        form.setAlignment(Pos.CENTER);
        VBox fields = new VBox(12, form, open, error);
        fields.getStyleClass().add("unlock-fields");
        fields.setMaxWidth(320);
        fields.setMinWidth(320);

        Button switchProfile = new Button("Switch profile");
        switchProfile.getStyleClass().addAll("quiet", "unlock-switch");
        switchProfile.setOnAction(event -> navigator.navigate(Destination.PROFILES));

        HBox heading = new HBox(10, name, working);
        heading.setAlignment(Pos.CENTER);
        VBox text = new VBox(8, heading, prompt);
        text.setAlignment(Pos.CENTER);
        column.getChildren().setAll(avatar, text, fields, switchProfile);
        Platform.runLater(password::requestFocus);
    }

    private void submit(ProfileSummary profile) {
        if (busy) {
            return;
        }
        if (profile.passwordProtected() && password.getText().isEmpty()) {
            return;
        }
        busy = true;
        error.setText("");
        password.setDisable(true);
        working.setVisible(true);
        working.setManaged(true);
        char[] typed = profile.passwordProtected() ? password.getText().toCharArray() : null;
        // The window shows Home once the profile is open.
        disposer.add(UiTasks.run(() -> context.open(profile.id(), typed), workspace -> busy = false, this::failed));
    }

    private void failed(UserMessage message) {
        busy = false;
        working.setVisible(false);
        working.setManaged(false);
        password.setDisable(false);
        error.setText(message.text());
        password.selectAll();
        password.requestFocus();
    }
}
