package edu.learnsphere.app.ui.profiles;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.SystemFiles;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.View;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.Avatar;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.SphereArt;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.service.ProfileSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * Choosing who is studying (full screen): every profile on this computer as a tile with its avatar, name,
 * when it was last opened and a lock when it has a password, then a tile to add a profile and a way to
 * restore a backup. Choosing a profile opens it (asking for its password first when it has one), which
 * closes the profile in use. Each tile's menu edits the name and colour, adds, changes or removes the
 * password, backs the profile up, or deletes it. While a profile is open, Back (or Escape) returns to it.
 */
public final class ProfilesPage implements View {

    /** Tiles per row before wrapping. */
    static final int TILES_PER_ROW = 4;
    private static final double TILE_WIDTH = 172;
    private static final double TILE_GAP = 20;
    private static final String STYLESHEET = Objects.requireNonNull(
            ProfilesPage.class.getResource("profiles.css"), "profiles.css").toExternalForm();

    private final AppContext context;
    private final Navigator navigator;
    private final ProfileActions actions;
    private final Disposer disposer = new Disposer();
    private final StackPane root = new StackPane();
    private final FlowPane tiles = new FlowPane(TILE_GAP, TILE_GAP);
    private final Label status = new Label();
    private final Map<String, Button> tileButtons = new LinkedHashMap<>();
    private final List<ProfileSummary> shown = new ArrayList<>();
    private final Button back = new Button("Back");
    private boolean opening;

    public ProfilesPage(AppContext context, Navigator navigator, Supplier<SystemFiles> files) {
        this.context = context;
        this.navigator = navigator;
        this.actions = new ProfileActions(context, files);
        root.getStyleClass().addAll("full-screen", "profiles-page");
        root.getStylesheets().add(STYLESHEET);

        SphereArt mark = SphereArt.mark(56);
        Label title = new Label("Who's studying?");
        title.getStyleClass().addAll("text-display", "full-screen-title");
        Label subtitle = new Label("Each profile keeps its own courses and progress.");
        subtitle.getStyleClass().add("text-muted");
        VBox heading = new VBox(12, mark, title, subtitle);
        heading.setAlignment(Pos.CENTER);

        tiles.getStyleClass().add("profile-tiles");
        tiles.setAlignment(Pos.CENTER);
        tiles.setRowValignment(VPos.TOP);
        tiles.setPrefWrapLength(TILES_PER_ROW * TILE_WIDTH + (TILES_PER_ROW - 1) * TILE_GAP);
        tiles.setMaxWidth(TILES_PER_ROW * TILE_WIDTH + (TILES_PER_ROW - 1) * TILE_GAP);
        showLoading();

        status.getStyleClass().addAll("profiles-status");
        status.setWrapText(true);
        status.managedProperty().bind(status.textProperty().isNotEmpty());
        status.visibleProperty().bind(status.managedProperty());

        Button restore = new Button("Restore from a backup", Icons.of(Feather.ARCHIVE, 16));
        restore.getStyleClass().addAll("quiet", "neutral", "restore-button");
        restore.setOnAction(event -> actions.restore(root, restored -> reload(restored.id())));

        VBox column = new VBox(heading, tiles, status, restore);
        column.getStyleClass().add("profiles-column");
        column.setAlignment(Pos.CENTER);
        column.setMaxWidth(Region.USE_PREF_SIZE);

        StackPane centring = new StackPane(column);
        centring.getStyleClass().add("profiles-centring");
        ScrollPane scroller = new ScrollPane(centring);
        scroller.getStyleClass().add("profiles-scroller");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        centring.minHeightProperty().bind(scroller.viewportBoundsProperty().map(bounds -> bounds.getHeight()));

        back.setGraphic(Icons.of(Feather.ARROW_LEFT, 16));
        back.getStyleClass().addAll("quiet", "neutral", "full-screen-back");
        back.setOnAction(event -> goBack());
        StackPane.setAlignment(back, Pos.TOP_LEFT);
        StackPane.setMargin(back, new Insets(0));
        back.setVisible(false);
        back.setManaged(false);

        root.getChildren().addAll(scroller, back);
        root.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && canGoBack()) {
                event.consume();
                goBack();
            }
        });
    }

    @Override
    public Region root() {
        return root;
    }

    @Override
    public void onShown() {
        boolean canReturn = canGoBack();
        back.setVisible(canReturn);
        back.setManaged(canReturn);
        reload(null);
    }

    @Override
    public void dispose() {
        disposer.dispose();
    }

    /** The names on the tiles, in order (tests). */
    public List<String> profileNames() {
        return shown.stream().map(ProfileSummary::displayName).toList();
    }

    /** The tile of a profile, or null (tests and keyboard focus). */
    public Button tile(String profileId) {
        return tileButtons.get(profileId);
    }

    /** The actions behind each tile's menu (tests). */
    public ProfileActions actions() {
        return actions;
    }

    private boolean canGoBack() {
        return context.workspace() != null && navigator.canGoBack();
    }

    private void goBack() {
        if (!navigator.back()) {
            navigator.reset(Destination.HOME);
        }
    }

    /** Reloads the tiles, then focuses {@code focusId}'s tile (or the open profile's, or the first). */
    private void reload(String focusId) {
        disposer.add(UiTasks.run(() -> context.profiles().list(), profiles -> {
            showProfiles(profiles);
            String target = focusId != null ? focusId : context.openProfileId();
            Button tile = target == null ? null : tileButtons.get(target);
            if (tile == null && !tileButtons.isEmpty()) {
                tile = tileButtons.values().iterator().next();
            }
            if (tile != null) {
                tile.requestFocus();
            }
        }, message -> {
            tiles.getChildren().clear();
            status.setText(message.text());
        }));
    }

    private void showLoading() {
        tiles.getChildren().clear();
        for (int i = 0; i < 2; i++) {
            VBox placeholder = new VBox(12, Skeleton.circle(72), Skeleton.line(110), Skeleton.line(70));
            placeholder.setAlignment(Pos.TOP_CENTER);
            placeholder.getStyleClass().add("profile-tile-placeholder");
            tiles.getChildren().add(placeholder);
        }
    }

    private void showProfiles(List<ProfileSummary> profiles) {
        status.setText("");
        shown.clear();
        shown.addAll(profiles);
        tileButtons.clear();
        tiles.getChildren().clear();
        String openId = context.openProfileId();
        for (ProfileSummary profile : profiles) {
            tiles.getChildren().add(tile(profile, profile.id().equals(openId)));
        }
        tiles.getChildren().add(addTile());
    }

    private Node tile(ProfileSummary profile, boolean current) {
        Avatar avatar = new Avatar(profile.initials(), profile.avatarHue(), 72);
        Label name = new Label(profile.displayName());
        name.getStyleClass().add("profile-tile-name");
        name.setWrapText(true);
        name.setMaxWidth(TILE_WIDTH - 32);
        name.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        name.setMnemonicParsing(false);
        Label meta = new Label(detail(profile, current));
        meta.getStyleClass().addAll("text-caption", "profile-tile-meta");
        HBox metaLine = new HBox(5, meta);
        metaLine.setAlignment(Pos.CENTER);
        if (profile.passwordProtected()) {
            var lock = Icons.of(Feather.LOCK, 12);
            lock.getStyleClass().add("profile-lock");
            metaLine.getChildren().addFirst(lock);
        }
        VBox box = new VBox(10, avatar, name, metaLine);
        box.setAlignment(Pos.TOP_CENTER);
        Button tile = new Button(null, box);
        tile.getStyleClass().add("profile-tile");
        if (current) {
            tile.getStyleClass().add("current");
        }
        tile.setAccessibleText(profile.displayName() + (profile.passwordProtected() ? ", has a password" : "")
                + ", " + detail(profile, current));
        tile.setOnAction(event -> choose(profile, current, meta, metaLine));
        tileButtons.put(profile.id(), tile);

        IconButton more = new IconButton(Feather.MORE_HORIZONTAL, "More for " + profile.displayName());
        more.small();
        more.getStyleClass().add("profile-tile-menu");
        ContextMenu menu = menu(profile);
        more.setOnAction(event -> menu.show(more, Side.BOTTOM, 0, 4));
        tile.setOnContextMenuRequested(event -> {
            event.consume();
            menu.show(tile, event.getScreenX(), event.getScreenY());
        });
        StackPane holder = new StackPane(tile, more);
        holder.getStyleClass().add("profile-tile-holder");
        StackPane.setAlignment(more, Pos.TOP_RIGHT);
        StackPane.setMargin(more, new Insets(8, 8, 0, 0));
        return holder;
    }

    private String detail(ProfileSummary profile, boolean current) {
        if (current) {
            return "Open now";
        }
        return profile.lastOpenedAt() == null ? "Not opened yet"
                : "Opened " + Formats.ago(profile.lastOpenedAt(), context.clock());
    }

    private ContextMenu menu(ProfileSummary profile) {
        MenuItem edit = new MenuItem("Edit name and colour");
        edit.setOnAction(event -> actions.edit(profile, root, saved -> reload(saved.id())));
        List<MenuItem> items = new ArrayList<>(List.of(edit));
        if (profile.passwordProtected()) {
            MenuItem change = new MenuItem("Change password");
            change.setOnAction(event -> actions.changePassword(profile, root, saved -> reload(saved.id())));
            MenuItem remove = new MenuItem("Remove password");
            remove.setOnAction(event -> actions.removePassword(profile, root, saved -> reload(saved.id())));
            items.addAll(List.of(change, remove));
        } else {
            MenuItem add = new MenuItem("Add a password");
            add.setOnAction(event -> actions.addPassword(profile, root, saved -> reload(saved.id())));
            items.add(add);
        }
        MenuItem backup = new MenuItem("Back up…");
        backup.setOnAction(event -> actions.backup(profile, root));
        MenuItem delete = new MenuItem("Delete profile…");
        delete.getStyleClass().add("danger-item");
        delete.setOnAction(event -> actions.delete(profile, root, () -> reload(null), () -> {
            back.setVisible(false);
            back.setManaged(false);
            reload(profile.id());
        }));
        items.addAll(List.of(new SeparatorMenuItem(), backup, new SeparatorMenuItem(), delete));
        ContextMenu menu = new ContextMenu(items.toArray(MenuItem[]::new));
        menu.getStyleClass().add("profile-menu");
        return menu;
    }

    private Node addTile() {
        StackPane circle = new StackPane(Icons.of(Feather.PLUS, 26));
        circle.getStyleClass().add("profile-add-circle");
        circle.setMinSize(72, 72);
        circle.setMaxSize(72, 72);
        Label label = new Label("Add a profile");
        label.getStyleClass().add("profile-tile-name");
        VBox box = new VBox(10, circle, label);
        box.setAlignment(Pos.TOP_CENTER);
        Button add = new Button(null, box);
        add.getStyleClass().addAll("profile-tile", "add");
        add.setAccessibleText("Add a profile");
        add.setOnAction(event -> navigator.navigate(Destination.ADD_PROFILE));
        return add;
    }

    private void choose(ProfileSummary profile, boolean current, Label meta, HBox metaLine) {
        if (opening) {
            return;
        }
        if (current) {
            navigator.reset(Destination.HOME);
            return;
        }
        if (profile.passwordProtected()) {
            navigator.navigate(new Destination.Unlock(profile.id()));
            return;
        }
        opening = true;
        tileButtons.values().forEach(button -> button.setDisable(true));
        meta.setText("Opening…");
        metaLine.getChildren().addFirst(new ActivityIndicator(12));
        // Opening closes the profile in use first; the window shows Home when the new one is open.
        disposer.add(UiTasks.run(() -> context.open(profile.id(), null), workspace -> opening = false,
                message -> {
                    opening = false;
                    showError(message);
                }));
    }

    private void showError(UserMessage message) {
        Toasts.error(root, message);
        reload(null);
    }
}
