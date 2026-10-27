package edu.learnsphere.app.ui.profiles;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.View;
import edu.learnsphere.app.ui.components.Avatar;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.SphereArt;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.Workspace;
import java.util.Arrays;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * Choosing who is studying (full screen): every profile on this computer as a tile with its avatar, a
 * password prompt for protected profiles, and a way to add another profile.
 */
public final class ProfilesPage implements View {

    private final AppContext context;
    private final Navigator navigator;
    private final Disposer disposer = new Disposer();
    private final StackPane root = new StackPane();
    private final FlowPane tiles = new FlowPane(20, 20);
    private final VBox unlock = new VBox(12);
    private final Label error = new Label();

    public ProfilesPage(AppContext context, Navigator navigator) {
        this.context = context;
        this.navigator = navigator;
        root.getStyleClass().addAll("full-screen", "profiles-page");

        SphereArt mark = SphereArt.mark(56);
        Label title = new Label("Who's studying?");
        title.getStyleClass().addAll("text-display", "full-screen-title");
        Label subtitle = new Label("Each profile keeps its own courses and progress.");
        subtitle.getStyleClass().add("text-muted");
        tiles.setAlignment(Pos.CENTER);
        tiles.setRowValignment(javafx.geometry.VPos.TOP);
        tiles.setPrefWrapLength(4 * 180);
        tiles.setMaxWidth(4 * 200);
        unlock.setAlignment(Pos.CENTER);
        unlock.setMaxWidth(320);
        unlock.setVisible(false);
        unlock.setManaged(false);
        error.getStyleClass().addAll("text-caption", "text-danger");
        error.setWrapText(true);

        VBox column = new VBox(12, mark, title, subtitle, spacer(20), tiles, unlock);
        column.setAlignment(Pos.CENTER);
        column.getStyleClass().add("full-screen-column");
        root.getChildren().add(column);
    }

    @Override
    public Region root() {
        return root;
    }

    @Override
    public void onShown() {
        disposer.add(UiTasks.run(() -> context.profiles().list(), this::showProfiles, message -> {
            error.setText(message.text());
            unlock.getChildren().setAll(error);
            unlock.setVisible(true);
            unlock.setManaged(true);
        }));
    }

    @Override
    public void dispose() {
        disposer.dispose();
    }

    private void showProfiles(List<ProfileSummary> profiles) {
        tiles.getChildren().clear();
        Workspace open = context.workspace();
        for (ProfileSummary profile : profiles) {
            boolean current = open != null && open.profile().id().equals(profile.id());
            tiles.getChildren().add(tile(profile, current));
        }
        Label addLabel = new Label("Add a profile");
        addLabel.getStyleClass().add("profile-tile-name");
        VBox addBox = new VBox(10, plus(), addLabel);
        addBox.setAlignment(Pos.TOP_CENTER);
        Button add = new Button(null, addBox);
        add.getStyleClass().addAll("profile-tile", "add");
        add.setAccessibleText("Add a profile");
        add.setOnAction(event -> navigator.navigate(Destination.ONBOARDING));
        tiles.getChildren().add(add);
    }

    private Node tile(ProfileSummary profile, boolean current) {
        Avatar avatar = new Avatar(profile.initials(), profile.avatarHue(), 72);
        Label name = new Label(profile.displayName());
        name.getStyleClass().add("profile-tile-name");
        String detail = current ? "Open now"
                : profile.lastOpenedAt() == null ? "Not opened yet" : "Opened " + Formats.ago(profile.lastOpenedAt(), context.clock());
        Label meta = new Label(detail);
        meta.getStyleClass().add("text-caption");
        VBox box = new VBox(10, avatar, name, meta);
        if (profile.passwordProtected()) {
            box.getChildren().add(Icons.of(Feather.LOCK, 14));
        }
        box.setAlignment(Pos.TOP_CENTER);
        Button tile = new Button(null, box);
        tile.getStyleClass().add("profile-tile");
        if (current) {
            tile.getStyleClass().add("current");
        }
        tile.setAccessibleText(profile.displayName());
        tile.setOnAction(event -> choose(profile, current));
        return tile;
    }

    private void choose(ProfileSummary profile, boolean current) {
        if (current) {
            navigator.reset(Destination.HOME);
            return;
        }
        if (!profile.passwordProtected()) {
            open(profile, null);
            return;
        }
        PasswordField password = new PasswordField();
        password.setPromptText("Password for " + profile.displayName());
        Button openButton = new Button("Open profile");
        openButton.getStyleClass().add("primary");
        openButton.setDefaultButton(true);
        openButton.setMaxWidth(Double.MAX_VALUE);
        openButton.setOnAction(event -> open(profile, password.getText().toCharArray()));
        error.setText("");
        unlock.getChildren().setAll(password, openButton, error);
        unlock.setVisible(true);
        unlock.setManaged(true);
        password.requestFocus();
    }

    private void open(ProfileSummary profile, char[] password) {
        error.setText("");
        disposer.add(UiTasks.run(() -> context.open(profile.id(), password), workspace -> {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }, message -> error.setText(message.text())));
    }

    private static Node plus() {
        StackPane circle = new StackPane(Icons.of(Feather.PLUS, 26));
        circle.getStyleClass().add("profile-add-circle");
        circle.setMinSize(72, 72);
        circle.setMaxSize(72, 72);
        return circle;
    }

    private static Region spacer(double height) {
        Region spacer = new Region();
        spacer.setMinHeight(height);
        return spacer;
    }
}
