package edu.learnsphere.app;

import edu.learnsphere.app.dev.DemoData;
import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.core.profile.AppPreferences;
import edu.learnsphere.core.util.Subscription;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * The JavaFX application: builds the context, opens the last used profile, applies the theme from the
 * app-wide preferences and shows the main window.
 */
public final class LearnSphereApp extends Application {

    public static final String TITLE = "LearnSphere";
    public static final double DEFAULT_WIDTH = 1360;
    public static final double DEFAULT_HEIGHT = 860;
    public static final double MIN_WIDTH = 1100;
    public static final double MIN_HEIGHT = 720;

    private AppContext context;
    private MainWindow window;
    private Theme theme;
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
        Scene scene = new Scene(window, DEFAULT_WIDTH, DEFAULT_HEIGHT);
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
        stage.show();
    }

    @Override
    public void stop() {
        preferencesListener.close();
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
