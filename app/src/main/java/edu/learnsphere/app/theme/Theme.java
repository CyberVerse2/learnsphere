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
import javafx.scene.Parent;
import javafx.scene.Scene;

/**
 * Applies the LearnSphere theme to a scene: the AtlantaFX base theme as the user-agent stylesheet,
 * {@code learnsphere.css} and the {@link Typography} stylesheet, and the {@code dark} style class on the
 * scene root when the dark palette is active.
 *
 * <p>{@link ThemePreference#SYSTEM} follows the operating system's appearance live. The app has one
 * window, so the dark state is also published app-wide through {@link #darkProperty()} for components that
 * draw their own colours (course covers, avatars). FX thread only.
 */
public final class Theme {

    /** Style class on the scene root while the dark palette is active. */
    public static final String DARK = "dark";

    private static final String STYLESHEET = Objects.requireNonNull(
            Theme.class.getResource("learnsphere.css"), "learnsphere.css").toExternalForm();
    private static final ReadOnlyBooleanWrapper DARK_ACTIVE = new ReadOnlyBooleanWrapper(false);

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

    public static boolean isDark() {
        return DARK_ACTIVE.get();
    }

    /** The URL of {@code learnsphere.css}, for scenes outside the main window (dialogs, popups). */
    public static String stylesheet() {
        return STYLESHEET;
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
        Application.setUserAgentStylesheet(dark
                ? new PrimerDark().getUserAgentStylesheet()
                : new PrimerLight().getUserAgentStylesheet());
        Parent root = scene.getRoot();
        if (root != null) {
            if (dark && !root.getStyleClass().contains(DARK)) {
                root.getStyleClass().add(DARK);
            } else if (!dark) {
                root.getStyleClass().remove(DARK);
            }
        }
        DARK_ACTIVE.set(dark);
    }
}
