package edu.learnsphere.app.ui;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.components.Avatar;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.SphereArt;
import edu.learnsphere.core.model.Profile;
import edu.learnsphere.core.service.ProfileSummary;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;

/**
 * App mark and name, primary navigation, the "Studying" list, then Settings and the profile chip at the
 * bottom. The selected item follows the navigator.
 */
public final class Sidebar extends VBox {

    private final Navigator navigator;
    private final ToggleGroup group = new ToggleGroup();
    private final Map<Route, ToggleButton> items = new EnumMap<>(Route.class);

    public Sidebar(AppContext context, Navigator navigator) {
        this.navigator = navigator;
        getStyleClass().add("sidebar");

        Label name = new Label("LearnSphere");
        name.getStyleClass().add("sidebar-brand-name");
        HBox brand = new HBox(SphereArt.mark(30), name);
        brand.getStyleClass().add("sidebar-brand");

        Label studying = new Label("STUDYING");
        studying.getStyleClass().addAll("text-overline", "sidebar-section");
        Label hint = new Label("Courses you study appear here.");
        hint.getStyleClass().add("sidebar-hint");
        hint.setWrapText(true);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Region divider = new Region();
        divider.getStyleClass().add("sidebar-divider");
        VBox.setMargin(divider, new javafx.geometry.Insets(10, 8, 8, 8));

        getChildren().addAll(brand,
                item(Route.HOME, Feather.HOME),
                item(Route.LIBRARY, Feather.BOOK_OPEN),
                item(Route.PROGRESS, Feather.BAR_CHART_2),
                item(Route.STUDIO, Feather.EDIT_3),
                studying, hint, spacer,
                item(Route.SETTINGS, Feather.SETTINGS),
                divider,
                profileChip(context));

        group.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null && old != null && items.containsValue(old)
                    && items.get(navigator.current()) == old) {
                old.setSelected(true);
            }
        });
        navigator.currentProperty().addListener((obs, old, route) -> select(route));
        select(navigator.current());
    }

    private ToggleButton item(Route route, Ikon icon) {
        ToggleButton button = new ToggleButton(route.title(), Icons.of(icon));
        button.getStyleClass().setAll("nav-item");
        button.setToggleGroup(group);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setFocusTraversable(true);
        button.setOnAction(event -> {
            if (navigator.current() == route) {
                button.setSelected(true);
            } else {
                navigator.navigate(route);
            }
        });
        items.put(route, button);
        return button;
    }

    private void select(Route route) {
        ToggleButton button = route == null ? null : items.get(route);
        if (button != null) {
            button.setSelected(true);
        } else if (group.getSelectedToggle() != null) {
            group.getSelectedToggle().setSelected(false);
        }
    }

    private HBox profileChip(AppContext context) {
        List<Profile> profiles = context.profiles().list();
        HBox chip;
        if (profiles.isEmpty()) {
            Label label = new Label("Create a profile");
            label.getStyleClass().add("profile-chip-name");
            chip = new HBox(Icons.of(Feather.USER, 18), label);
        } else {
            ProfileSummary profile = ProfileSummary.of(profiles.getFirst());
            Label name = new Label(profile.displayName());
            name.getStyleClass().add("profile-chip-name");
            Label detail = new Label(profiles.size() == 1 ? "Your profile" : "Switch profile");
            detail.getStyleClass().add("profile-chip-detail");
            VBox text = new VBox(1, name, detail);
            HBox.setHgrow(text, Priority.ALWAYS);
            chip = new HBox(new Avatar(profile.initials(), profile.avatarHue(), 34), text, Icons.of(Feather.CHEVRON_RIGHT, 16));
        }
        chip.getStyleClass().add("profile-chip");
        chip.setOnMouseClicked(event -> navigator.navigate(Route.PROFILES));
        return chip;
    }
}
