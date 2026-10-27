package edu.learnsphere.app.theme;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import edu.learnsphere.core.service.ThemePreference;
import java.util.Objects;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

/**
 * Applies the LearnSphere theme to a scene: the AtlantaFX base theme as the user-agent stylesheet,
 * {@code learnsphere.css} and the {@link Typography} stylesheet, and the {@code dark} style class on the
 * scene root when the dark palette is active.
 *
 * <p>{@link ThemePreference#SYSTEM} follows the operating system's appearance live (macOS and Windows).
 * Choosing light or dark overrides it for the scene, including the window's own title bar. The app has one
 * window, so the dark state is also published app-wide through {@link #darkProperty()} for components that
 * draw their own colours (course covers, avatars, charts).
 *
 * <p>A part of the scene can show the other palette (the component gallery shows both side by side): give
 * it the style class {@link #SCOPE_LIGHT} or {@link #SCOPE_DARK}. {@link #isDark(Node)} answers for a node
 * with those scopes taken into account. FX thread only.
 */
public final class Theme {

    /** Style class on the scene root while the dark palette is active. */
    public static final String DARK = "dark";
    /** Style class that gives a subtree the light palette whatever the window uses. */
    public static final String SCOPE_LIGHT = "theme-light";
    /** Style class that gives a subtree the dark palette whatever the window uses. */
    public static final String SCOPE_DARK = "theme-dark";

    private static final String STYLESHEET = Objects.requireNonNull(
            Theme.class.getResource("learnsphere.css"), "learnsphere.css").toExternalForm();
    private static final ReadOnlyBooleanWrapper DARK_ACTIVE = new ReadOnlyBooleanWrapper(false);
    // Full URLs, so the base theme loads whatever class loader the FX thread has (the screenshot tour starts
    // the toolkit without one).
    private static final String BASE_LIGHT = resolve(new PrimerLight());
    private static final String BASE_DARK = resolve(new PrimerDark());

    private final Scene scene;
    private final ChangeListener<ColorScheme> systemListener = (obs, old, scheme) -> refresh();
    private ThemePreference preference = ThemePreference.SYSTEM;

    private Theme(Scene scene) {
        this.scene = scene;
        scene.rootProperty().addListener((obs, old, root) -> {
            if (old != null) {
                old.getStyleClass().remove(DARK);
            }
            refresh();
        });
    }

    /**
     * Installs the theme on {@code scene} with the given preference. Call once per scene, after
     * {@link Typography#load()}.
     */
    public static Theme install(Scene scene, ThemePreference preference) {
        Theme theme = new Theme(scene);
        scene.getStylesheets().setAll(STYLESHEET, Typography.stylesheet());
        Platform.getPreferences().colorSchemeProperty().addListener(theme.systemListener);
        theme.setPreference(preference);
        return theme;
    }

    /** True while the dark palette is active (app-wide). */
    public static ReadOnlyBooleanProperty darkProperty() {
        return DARK_ACTIVE.getReadOnlyProperty();
    }

    /** True while the window uses the dark palette. */
    public static boolean isDark() {
        return DARK_ACTIVE.get();
    }

    /**
     * True when {@code node} shows the dark palette: the nearest {@link #SCOPE_LIGHT} or {@link #SCOPE_DARK}
     * ancestor decides, then the scene root's {@link #DARK} class, then the app-wide state.
     */
    public static boolean isDark(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.getStyleClass().contains(SCOPE_DARK)) {
                return true;
            }
            if (n.getStyleClass().contains(SCOPE_LIGHT)) {
                return false;
            }
            if (n.getParent() == null && n.getScene() != null && n.getScene().getRoot() == n) {
                return n.getStyleClass().contains(DARK);
            }
        }
        return isDark();
    }

    /** The URL of {@code learnsphere.css}, for scenes outside the main window. */
    public static String stylesheet() {
        return STYLESHEET;
    }

    /**
     * Styles a stock dialog like the rest of the app: both stylesheets, the current palette and the
     * {@code ls-dialog} style class. Call before showing it.
     */
    public static <D extends Dialog<?>> D style(D dialog) {
        var pane = dialog.getDialogPane();
        pane.getStylesheets().setAll(STYLESHEET, Typography.stylesheet());
        if (!pane.getStyleClass().contains("ls-dialog")) {
            pane.getStyleClass().add("ls-dialog");
        }
        pane.getStyleClass().remove(SCOPE_DARK);
        pane.getStyleClass().remove(SCOPE_LIGHT);
        pane.getStyleClass().add(isDark() ? SCOPE_DARK : SCOPE_LIGHT);
        return dialog;
    }

    public ThemePreference preference() {
        return preference;
    }

    /** Switches between system, light and dark. */
    public void setPreference(ThemePreference newPreference) {
        preference = Objects.requireNonNull(newPreference, "newPreference");
        refresh();
    }

    /** Stops following the system appearance. */
    public void dispose() {
        Platform.getPreferences().colorSchemeProperty().removeListener(systemListener);
    }

    private void refresh() {
        boolean dark = switch (preference) {
            case LIGHT -> false;
            case DARK -> true;
            case SYSTEM -> Platform.getPreferences().getColorScheme() == ColorScheme.DARK;
        };
        Application.setUserAgentStylesheet(dark ? BASE_DARK : BASE_LIGHT);
        // The window frame follows the chosen palette too (null: follow the system).
        scene.getPreferences().setColorScheme(preference == ThemePreference.SYSTEM
                ? null : dark ? ColorScheme.DARK : ColorScheme.LIGHT);
        Parent root = scene.getRoot();
        if (root != null) {
            if (dark && !root.getStyleClass().contains(DARK)) {
                root.getStyleClass().add(DARK);
            } else if (!dark) {
                root.getStyleClass().remove(DARK);
            }
            matchSceneFill(root);
        }
        DARK_ACTIVE.set(dark);
    }

    private static String resolve(atlantafx.base.theme.Theme base) {
        String path = base.getUserAgentStylesheet();
        var url = base.getClass().getResource(path.startsWith("/") ? path : "/" + path);
        return url == null ? path : url.toExternalForm();
    }

    /** Paints the scene behind the root in the root's own background colour, so resizing never flashes white. */
    private void matchSceneFill(Parent root) {
        root.applyCss();
        if (root instanceof Region region) {
            Background background = region.getBackground();
            if (background != null && !background.getFills().isEmpty()) {
                Paint fill = background.getFills().getFirst().getFill();
                scene.setFill(fill instanceof Color ? fill : Color.TRANSPARENT);
            }
        }
    }
}
