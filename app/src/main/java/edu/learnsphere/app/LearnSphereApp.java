package edu.learnsphere.app;

import edu.learnsphere.app.dev.DemoData;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.core.service.ThemePreference;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** The JavaFX application: builds the context, theme and main window. */
public final class LearnSphereApp extends Application {

    public static final String TITLE = "LearnSphere";
    public static final double DEFAULT_WIDTH = 1360;
    public static final double DEFAULT_HEIGHT = 860;
    public static final double MIN_WIDTH = 1100;
    public static final double MIN_HEIGHT = 720;

    private AppContext context;

    @Override
    public void init() {
        context = AppContext.create();
        if (context.isDemo()) {
            DemoData.seedProfiles(context);
        }
    }

    @Override
    public void start(Stage stage) {
        Typography.load();
        MainWindow window = new MainWindow(context);
        Scene scene = new Scene(window, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        Theme.install(scene, ThemePreference.SYSTEM);
        stage.setTitle(TITLE);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        UiExecutors.shutdown();
        if (context != null) {
            context.close();
        }
    }
}
