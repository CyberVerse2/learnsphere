package edu.learnsphere.app.ui;

import javafx.scene.layout.Region;

/**
 * One screen. The {@link Navigator} builds a fresh view per navigation, calls {@link #onShown()} once it is
 * on screen and {@link #dispose()} when another view replaces it. A view loads its data with
 * {@link UiTasks#run}, listens to events with {@link Fx#subscribe}, and releases both in {@link #dispose()}
 * (collect them in a {@link Disposer}). FX thread only.
 */
public interface View {

    /** The node to put in the content area. */
    Region root();

    /** Called after the view is put on screen. */
    default void onShown() {
    }

    /** Called after the view is taken off screen: cancel loads and close subscriptions here. */
    default void dispose() {
    }
}
