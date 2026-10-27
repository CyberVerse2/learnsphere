package edu.learnsphere.app.ui;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.profile.AppPreferences;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.util.Subscription;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * The application menus with their keyboard shortcuts ({@link KeyCombination#SHORTCUT_DOWN}: Command on macOS,
 * Ctrl elsewhere). On macOS the bar moves into the system menu bar; elsewhere it sits at the top of the
 * window with the {@code in-window} style.
 *
 * <p>While a form or progress card is open over the window, the items that leave the page are disabled, so
 * their shortcuts do nothing until it closes.
 *
 * <ul>
 *   <li>File: Switch profile (Shift+Shortcut+P), Settings (Shortcut+,), Quit (Shortcut+Q, not on macOS,
 *       whose application menu has its own)</li>
 *   <li>Go: Home, Library, Progress, Studio (Shortcut+1 to 4), Start studying (Shift+Shortcut+S), Back
 *       (Shortcut+[)</li>
 *   <li>View: Appearance (Match computer, Light, Dark), Reduce motion, and in demo mode Components
 *       (Shift+Shortcut+G)</li>
 * </ul>
 */
public final class AppMenus {

    private final AppContext context;
    private final Navigator navigator;
    private final MenuBar bar = new MenuBar();
    private final Map<ThemePreference, RadioMenuItem> themes = new EnumMap<>(ThemePreference.class);
    private final CheckMenuItem reduceMotion = new CheckMenuItem("Reduce motion");
    private final Subscription preferencesListener;

    /** Builds the menus for {@code window}, using the system menu bar when {@code systemMenuBar} is true. */
    public AppMenus(MainWindow window, boolean systemMenuBar) {
        this.context = window.context();
        this.navigator = window.navigator();
        bar.setUseSystemMenuBar(systemMenuBar);
        if (!systemMenuBar) {
            bar.getStyleClass().add("in-window");
        }
        // Nothing navigates away under an open form: its work would finish on a page that has gone.
        BooleanBinding formOpen = Bindings.isNotEmpty(window.overlays().modals().getChildren());
        BooleanBinding noProfileOrForm = Bindings.createBooleanBinding(() -> context.workspace() == null,
                context.workspaceProperty()).or(formOpen);

        MenuItem switchProfile = item("Switch profile", shortcut(KeyCode.P, true),
                () -> navigator.navigate(Destination.PROFILES));
        switchProfile.disableProperty().bind(formOpen);
        MenuItem settings = item("Settings", shortcut(KeyCode.COMMA, false),
                () -> navigator.navigate(Destination.SETTINGS));
        settings.disableProperty().bind(noProfileOrForm);
        Menu file = new Menu("File");
        file.getItems().addAll(switchProfile, new SeparatorMenuItem(), settings);
        if (!systemMenuBar) {
            file.getItems().addAll(new SeparatorMenuItem(),
                    item("Quit LearnSphere", shortcut(KeyCode.Q, false), () -> quit(window)));
        }

        Menu go = new Menu("Go");
        go.getItems().addAll(
                item("Home", shortcut(KeyCode.DIGIT1, false), () -> navigator.navigate(Destination.HOME)),
                item("Library", shortcut(KeyCode.DIGIT2, false), () -> navigator.navigate(Destination.LIBRARY)),
                item("Progress", shortcut(KeyCode.DIGIT3, false),
                        () -> navigator.navigate(Destination.Progress.overview())),
                item("Studio", shortcut(KeyCode.DIGIT4, false), () -> navigator.navigate(Destination.Studio.courses())),
                new SeparatorMenuItem(),
                item("Start studying", shortcut(KeyCode.S, true),
                        () -> navigator.navigate(new Destination.Study(SessionRequest.studyNext()))));
        go.getItems().forEach(menuItem -> {
            if (!(menuItem instanceof SeparatorMenuItem)) {
                menuItem.disableProperty().bind(noProfileOrForm);
            }
        });
        MenuItem back = item("Back", shortcut(KeyCode.OPEN_BRACKET, false), navigator::back);
        back.disableProperty().bind(navigator.canGoBackProperty().not().or(formOpen));
        go.getItems().addAll(new SeparatorMenuItem(), back);

        Menu appearance = new Menu("Appearance");
        ToggleGroup group = new ToggleGroup();
        for (ThemePreference preference : ThemePreference.values()) {
            RadioMenuItem option = new RadioMenuItem(themeLabel(preference));
            option.setToggleGroup(group);
            option.setOnAction(event -> save(p -> p.withTheme(preference)));
            themes.put(preference, option);
            appearance.getItems().add(option);
        }
        reduceMotion.setOnAction(event -> save(p -> p.withReduceMotion(reduceMotion.isSelected())));
        Menu view = new Menu("View");
        view.getItems().addAll(appearance, reduceMotion);
        if (context.isDemo()) {
            MenuItem gallery = item("Components", shortcut(KeyCode.G, true),
                    () -> navigator.navigate(Destination.GALLERY));
            gallery.disableProperty().bind(formOpen);
            view.getItems().addAll(new SeparatorMenuItem(), gallery);
        }

        bar.getMenus().addAll(file, go, view);
        show(context.preferences().get());
        preferencesListener = context.preferences().addListener(changed -> Fx.run(() -> show(changed)));
    }

    /** The menu bar to place at the top of the scene (it takes no space when it is the system bar). */
    public MenuBar bar() {
        return bar;
    }

    public void dispose() {
        preferencesListener.close();
    }

    /** The label of the Appearance option for {@code preference}. */
    static String themeLabel(ThemePreference preference) {
        return switch (preference) {
            case SYSTEM -> "Match computer";
            case LIGHT -> "Light";
            case DARK -> "Dark";
        };
    }

    /** Shortcut plus {@code key}, with Shift when {@code shift} is true. */
    static KeyCombination shortcut(KeyCode key, boolean shift) {
        return shift ? new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
                : new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN);
    }

    /** True when the menus belong in the system menu bar (macOS). */
    public static boolean usesSystemMenuBar() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private void show(AppPreferences preferences) {
        RadioMenuItem selected = themes.get(preferences.theme());
        if (selected != null) {
            selected.setSelected(true);
        }
        reduceMotion.setSelected(preferences.reduceMotion());
    }

    private void save(UnaryOperator<AppPreferences> change) {
        UiTasks.run(() -> context.preferences().update(change), this::show, message -> {
            show(context.preferences().get());
            if (navigator.currentView() != null) {
                Toasts.error(navigator.currentView().root(), message);
            }
        });
    }

    /** Closes the window (so it remembers its place, as closing it does), then quits. */
    private static void quit(MainWindow window) {
        if (window.getScene() != null && window.getScene().getWindow() != null) {
            window.getScene().getWindow().hide();
        }
        Platform.exit();
    }

    private static MenuItem item(String label, KeyCombination accelerator, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.setAccelerator(accelerator);
        item.setOnAction(event -> action.run());
        return item;
    }
}
