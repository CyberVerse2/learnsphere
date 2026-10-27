package edu.learnsphere.app.ui;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.components.Page;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;

/**
 * The window content: the {@link Sidebar} on the left and the routed content area in the centre. Screens
 * register with {@link #navigator()}; the sidebar hides for full-screen routes (study sessions,
 * onboarding, profile choice).
 */
public final class MainWindow extends BorderPane {

    private final Navigator navigator;
    private final Sidebar sidebar;

    public MainWindow(AppContext context) {
        getStyleClass().add("app-shell");
        StackPane content = new StackPane();
        content.getStyleClass().add("content");

        navigator = new Navigator(content, MainWindow::untitledPage);
        navigator.register(Route.HOME, () -> new WelcomePage(context, navigator));

        sidebar = new Sidebar(context, navigator);
        setLeft(sidebar);
        setCenter(content);
        navigator.currentProperty().addListener((obs, old, route) ->
                setLeft(route != null && route.isFullScreen() ? null : sidebar));
        navigator.reset(Route.HOME);
    }

    public Navigator navigator() {
        return navigator;
    }

    private static Page untitledPage(Route route) {
        Label title = new Label(route.title());
        title.getStyleClass().add("text-display");
        return new Page(title);
    }
}
