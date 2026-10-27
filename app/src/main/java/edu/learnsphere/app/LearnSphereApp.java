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
import edu.learnsphere.core.util.Subscription;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * The JavaFX application: builds the context, opens the last used profile, applies the theme from the
 * app-wide preferences and shows the main window with its menus, where it was last time.
 */
public final class LearnSphereApp extends Application {

    public static final String TITLE = "LearnSphere";
    public static final double DEFAULT_WIDTH = 1360;
    public static final double DEFAULT_HEIGHT = 860;
    public static final double MIN_WIDTH = 1100;
    public static final double MIN_HEIGHT = 720;

    private AppContext context;
    private MainWindow window;
    private AppMenus menus;
    private Theme theme;
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
        stage.setTitle(TITLE);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setScene(scene);
        placeWindow(stage);
        stage.setOnHiding(event -> saveWindow(stage));
        stage.show();
    }

    /** Puts the window where it was last time, fitted to the screens attached now. */
    private void placeWindow(Stage stage) {
        windowStates = new WindowStateStore(context.paths().windowStateFile());
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
        if (windowStates == null || normalBounds == null) {
            return;
        }
        windowStates.save(new WindowState(normalBounds.getMinX(), normalBounds.getMinY(), normalBounds.getWidth(),
                normalBounds.getHeight(), stage.isMaximized()));
    }

    @Override
    public void stop() {
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
        UiExecutors.shutdown();
        if (context != null) {
            context.close();
        }
    }
}
