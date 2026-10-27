package edu.learnsphere.app;

import edu.learnsphere.app.dev.DemoData;
import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.AppMenus;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.core.profile.AppPreferences;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.util.Subscription;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JavaFX application: builds the context, opens the last used profile (unless it has a password, which
 * the window then asks for), applies the theme from the app-wide preferences and shows the main window with
 * its menus, where it was last time, titled with the open profile's name. However the app is quit (closing the
 * window, the Quit menu item or the system's Quit), {@link #stop()} saves the window's place, takes the
 * screens down and closes the context: the profile's background work stops, then the study assistant, and
 * nothing is left running.
 */
public final class LearnSphereApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(LearnSphereApp.class);

    public static final String TITLE = "LearnSphere";
    public static final double DEFAULT_WIDTH = 1360;
    public static final double DEFAULT_HEIGHT = 860;
    public static final double MIN_WIDTH = 1100;
    public static final double MIN_HEIGHT = 720;

    private AppContext context;
    private MainWindow window;
    private AppMenus menus;
    private Theme theme;
    private Stage stage;
    private WindowStateStore windowStates;
    /** The window's bounds when it is neither maximised nor minimised: what a maximised window restores to. */
    private Rectangle2D normalBounds;
    private Subscription preferencesListener = Subscription.NONE;

    @Override
    public void init() {
        context = AppContext.create();
        if (context.isDemo()) {
            DemoData.seedProfiles(context);
        }
        context.openLastProfile();
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Typography.load();
        window = new MainWindow(context);
        menus = new AppMenus(window, AppMenus.usesSystemMenuBar());
        BorderPane root = new BorderPane(window);
        root.setTop(menus.bar());
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        AppPreferences preferences = context.preferences().get();
        theme = Theme.install(scene, preferences.theme());
        Motion.reduceMotionProperty().set(preferences.reduceMotion());
        preferencesListener = context.preferences().addListener(changed -> Fx.run(() -> {
            theme.setPreference(changed.theme());
            Motion.reduceMotionProperty().set(changed.reduceMotion());
        }));
        stage.titleProperty().bind(Bindings.createStringBinding(() -> title(context.profile()),
                context.profileProperty()));
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setScene(scene);
        placeWindow(stage);
        stage.setOnHiding(event -> saveWindow(stage));
        stage.show();
    }

    /** The window title while {@code profile} is open (null: none is). */
    public static String title(ProfileSummary profile) {
        return profile == null ? TITLE : profile.displayName() + " \u2014 " + TITLE;
    }

    /** Puts the window where it was last time, fitted to the screens attached now. */
    private void placeWindow(Stage stage) {
        windowStates = WindowStateStore.in(context.paths());
        List<Rectangle2D> screens = new ArrayList<>();
        screens.add(Screen.getPrimary().getVisualBounds());
        Screen.getScreens().stream().filter(s -> !s.equals(Screen.getPrimary()))
                .forEach(s -> screens.add(s.getVisualBounds()));
        Rectangle2D defaultSize = new Rectangle2D(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        WindowState state = windowStates.load()
                .map(saved -> saved.fitTo(screens, MIN_WIDTH, MIN_HEIGHT, defaultSize))
                .orElseGet(() -> WindowState.centred(screens.getFirst(), DEFAULT_WIDTH, DEFAULT_HEIGHT));
        stage.setX(state.x());
        stage.setY(state.y());
        stage.setWidth(state.width());
        stage.setHeight(state.height());
        stage.setMaximized(state.maximized());
        normalBounds = new Rectangle2D(state.x(), state.y(), state.width(), state.height());
        Runnable track = () -> {
            if (!stage.isMaximized() && !stage.isIconified() && !stage.isFullScreen()
                    && stage.getWidth() > 0 && stage.getHeight() > 0) {
                normalBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            }
        };
        stage.xProperty().addListener((obs, old, value) -> track.run());
        stage.yProperty().addListener((obs, old, value) -> track.run());
        stage.widthProperty().addListener((obs, old, value) -> track.run());
        stage.heightProperty().addListener((obs, old, value) -> track.run());
    }

    private void saveWindow(Stage stage) {
        if (stage == null || windowStates == null || normalBounds == null) {
            return;
        }
        windowStates.save(new WindowState(normalBounds.getMinX(), normalBounds.getMinY(), normalBounds.getWidth(),
                normalBounds.getHeight(), stage.isMaximized()));
    }

    @Override
    public void stop() {
        shutDown();
        UiExecutors.shutdown();
    }

    /**
     * Saves the window's place, takes the screens and menus down and closes the context: the open profile's
     * background work stops, then the study assistant and its processes. Runs on the FX thread; blocking.
     * Platform.exit() (the in-window Quit, or the system quitting the app) need not hide the window first,
     * so the place is saved here as well as when the window hides.
     */
    void shutDown() {
        long started = System.nanoTime();
        try {
            saveWindow(stage);
            preferencesListener.close();
            if (menus != null) {
                menus.dispose();
            }
            if (window != null) {
                window.dispose();
            }
            if (theme != null) {
                theme.dispose();
            }
        } catch (RuntimeException e) {
            LOG.warn("Taking the window down failed", e);
        } finally {
            if (context != null) {
                context.close();
            }
            LOG.info("Quit in {} ms", (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** The context this run uses (tests). */
    AppContext context() {
        return context;
    }

    /** The main window, once started (tests). */
    MainWindow window() {
        return window;
    }
}
