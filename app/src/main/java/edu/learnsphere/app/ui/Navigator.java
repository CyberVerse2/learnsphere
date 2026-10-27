package edu.learnsphere.app.ui;

import edu.learnsphere.app.theme.Motion;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

/**
 * Switches the content area between routes with the standard entrance transition and keeps back history.
 * Screens register a factory per route; a route without one shows a page with just its title. Each
 * navigation builds a fresh view. FX thread only.
 */
public final class Navigator {

    private final StackPane host;
    private final Map<Route, Supplier<? extends Node>> views = new EnumMap<>(Route.class);
    private final ReadOnlyObjectWrapper<Route> current = new ReadOnlyObjectWrapper<>(this, "current");
    private final Deque<Route> history = new ArrayDeque<>();
    private final Function<Route, Node> fallback;

    /**
     * @param host     the content area
     * @param fallback builds the view for routes without a registered factory
     */
    public Navigator(StackPane host, Function<Route, Node> fallback) {
        this.host = Objects.requireNonNull(host, "host");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /** Registers (or replaces) the view factory for {@code route}. */
    public void register(Route route, Supplier<? extends Node> factory) {
        views.put(Objects.requireNonNull(route, "route"), Objects.requireNonNull(factory, "factory"));
    }

    /** The route on screen. */
    public ReadOnlyObjectProperty<Route> currentProperty() {
        return current.getReadOnlyProperty();
    }

    public Route current() {
        return current.get();
    }

    /** Shows {@code route} with the entrance transition and remembers the previous route. */
    public void navigate(Route route) {
        Fx.requireFxThread();
        if (current.get() != null && current.get() != route) {
            history.push(current.get());
        }
        show(route, true);
    }

    /** Shows {@code route} without a transition or history entry (start-up, screenshots). */
    public void reset(Route route) {
        Fx.requireFxThread();
        history.clear();
        show(route, false);
    }

    /** Returns to the previous route; false when there is none. */
    public boolean back() {
        Fx.requireFxThread();
        Route previous = history.poll();
        if (previous == null) {
            return false;
        }
        show(previous, true);
        return true;
    }

    public boolean canGoBack() {
        return !history.isEmpty();
    }

    private void show(Route route, boolean animate) {
        Supplier<? extends Node> factory = views.get(route);
        Node view = factory != null ? factory.get() : fallback.apply(route);
        host.getChildren().setAll(view);
        current.set(route);
        if (animate) {
            Motion.enter(view);
        }
    }
}
