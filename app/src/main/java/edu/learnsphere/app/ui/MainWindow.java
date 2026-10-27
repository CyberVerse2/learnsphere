package edu.learnsphere.app.ui;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.components.Page;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;

/**
 * The window content: the {@link Sidebar} on the left and the routed content area in the centre. Screens
 * register with {@link #navigator()} by destination type; the sidebar hides for full-screen routes (study
 * sessions, onboarding, profile choice).
 */
public final class MainWindow extends BorderPane {

    private final Navigator navigator;
    private final Sidebar sidebar;

    public MainWindow(AppContext context) {
        getStyleClass().add("app-shell");
        StackPane content = new StackPane();
        content.getStyleClass().add("content");

        navigator = new Navigator(content, MainWindow::untitledPage);
        navigator.register(Destination.Home.class, destination -> new WelcomePage(context, navigator));

        sidebar = new Sidebar(context, navigator);
        setLeft(sidebar);
        setCenter(content);
        navigator.currentProperty().addListener((obs, old, destination) ->
                setLeft(destination != null && destination.route().isFullScreen() ? null : sidebar));
        navigator.reset(Destination.HOME);
    }

    public Navigator navigator() {
        return navigator;
    }

    /** A screen that is not built yet: its title on an empty page. */
    private static View untitledPage(Destination destination) {
        Label title = new Label(destination.route().title());
        title.getStyleClass().add("text-display");
        Page page = new Page(title);
        return () -> page;
    }

    /** Disposes the screen on display (window closing). */
    public void dispose() {
        navigator.dispose();
        sidebar.dispose();
    }
}
