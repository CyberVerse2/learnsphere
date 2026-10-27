package edu.learnsphere.app.ui;

import edu.learnsphere.app.theme.Motion;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Switches the content area between {@link Destination}s with the standard entrance transition and keeps
 * back history. Screens register a factory per destination type; a destination without one shows the
 * fallback view. Each navigation builds a fresh {@link View}: the new one gets {@link View#onShown()} and the
 * one it replaces gets {@link View#dispose()}. FX thread only.
 */
public final class Navigator {

    private static final Logger LOG = LoggerFactory.getLogger(Navigator.class);

    private final StackPane host;
    private final Map<Class<? extends Destination>, Function<Destination, ? extends View>> views = new HashMap<>();
    private final Function<Destination, ? extends View> fallback;
    private final ReadOnlyObjectWrapper<Destination> current = new ReadOnlyObjectWrapper<>(this, "current");
    private final Deque<Destination> history = new ArrayDeque<>();
    private final ReadOnlyBooleanWrapper canGoBack = new ReadOnlyBooleanWrapper(this, "canGoBack", false);
    private View currentView;

    /**
     * @param host     the content area
     * @param fallback builds the view for destinations without a registered factory
     */
    public Navigator(StackPane host, Function<Destination, ? extends View> fallback) {
        this.host = Objects.requireNonNull(host, "host");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /** Registers (or replaces) the view factory for destinations of {@code type}. */
    public <D extends Destination> void register(Class<D> type, Function<? super D, ? extends View> factory) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        views.put(type, destination -> factory.apply(type.cast(destination)));
    }

    /** The destination on screen. */
    public ReadOnlyObjectProperty<Destination> currentProperty() {
        return current.getReadOnlyProperty();
    }

    public Destination current() {
        return current.get();
    }

    /** The screen on screen, or null before the first navigation. */
    public Route currentRoute() {
        Destination destination = current.get();
        return destination == null ? null : destination.route();
    }

    /** The view on screen, or null before the first navigation. */
    public View currentView() {
        return currentView;
    }

    /**
     * Shows {@code destination} with the entrance transition and remembers the previous one. Navigating to
     * the destination already on screen does nothing.
     */
    public void navigate(Destination destination) {
        Fx.requireFxThread();
        Objects.requireNonNull(destination, "destination");
        Destination previous = current.get();
        if (destination.equals(previous)) {
            return;
        }
        show(destination, true);
        if (previous != null) {
            history.push(previous);
        }
        canGoBack.set(!history.isEmpty());
    }

    /** Shows {@code destination} without a transition and clears history (start-up, profile switch, screenshots). */
    public void reset(Destination destination) {
        Fx.requireFxThread();
        Objects.requireNonNull(destination, "destination");
        show(destination, false);
        history.clear();
        canGoBack.set(false);
    }

    /** Returns to the previous destination; false when there is none. */
    public boolean back() {
        Fx.requireFxThread();
        Destination previous = history.peek();
        if (previous == null) {
            return false;
        }
        show(previous, true);
        history.pop();
        canGoBack.set(!history.isEmpty());
        return true;
    }

    public boolean canGoBack() {
        return !history.isEmpty();
    }

    /** True while there is somewhere to go back to (binds the back button). */
    public ReadOnlyBooleanProperty canGoBackProperty() {
        return canGoBack.getReadOnlyProperty();
    }

    /**
     * Replaces the destination on screen without a transition or a history entry: for a screen that changes
     * what it shows in place (a new conversation, another tab) so back and the breadcrumbs stay right. The
     * view is rebuilt only when {@code rebuild} is true.
     */
    public void replace(Destination destination, boolean rebuild) {
        Fx.requireFxThread();
        Objects.requireNonNull(destination, "destination");
        if (rebuild) {
            show(destination, false);
        } else {
            current.set(destination);
        }
    }

    /** Disposes the view on screen and empties the content area (window closing). */
    public void dispose() {
        Fx.requireFxThread();
        View old = currentView;
        currentView = null;
        host.getChildren().clear();
        history.clear();
        canGoBack.set(false);
        current.set(null);
        if (old != null) {
            dispose(old);
        }
    }

    /** Builds the view first, so a factory that throws leaves the screen and history as they were. */
    private void show(Destination destination, boolean animate) {
        Function<Destination, ? extends View> factory = views.getOrDefault(destination.getClass(), fallback);
        View view = Objects.requireNonNull(factory.apply(destination), "view");
        Region root = view.root();
        View old = currentView;
        host.getChildren().setAll(root);
        currentView = view;
        current.set(destination);
        if (old != null) {
            dispose(old);
        }
        view.onShown();
        if (animate) {
            Motion.enter(root);
        }
    }

    private static void dispose(View view) {
        try {
            view.dispose();
        } catch (RuntimeException e) {
            LOG.warn("Disposing {} failed", view.getClass().getSimpleName(), e);
        }
    }
}
