package edu.learnsphere.app.ui.studio;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;

/**
 * Keeps the focused control of a scrolling page on screen. JavaFX moves focus with Tab without scrolling,
 * so a keyboard user could land on a button below the fold and not see it; this scrolls the page just far
 * enough to show the focused control with a little space around it.
 */
final class FocusScroller {

    /** Space kept between the focused control and the edge of the visible area. */
    private static final double MARGIN = 24;

    private FocusScroller() {
    }

    /** Follows keyboard focus inside {@code page} for as long as the page is in a scene. */
    static void install(ScrollPane page) {
        ChangeListener<Node> follow = (obs, old, focused) -> reveal(page, focused);
        page.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.focusOwnerProperty().removeListener(follow);
            }
            if (newScene != null) {
                newScene.focusOwnerProperty().addListener(follow);
            }
        });
        Scene scene = page.getScene();
        if (scene != null) {
            scene.focusOwnerProperty().addListener(follow);
        }
    }

    /** Scrolls {@code page} so that {@code node} is visible, if it is inside the page and not visible yet. */
    static void reveal(ScrollPane page, Node node) {
        Node content = page.getContent();
        if (node == null || content == null || !isInside(node, content)) {
            return;
        }
        double viewport = page.getViewportBounds().getHeight();
        double scrollable = content.getLayoutBounds().getHeight() - viewport;
        if (viewport <= 0 || scrollable <= 0) {
            return;
        }
        Bounds bounds = content.sceneToLocal(node.localToScene(node.getLayoutBounds()));
        if (bounds == null) {
            return;
        }
        double range = page.getVmax() - page.getVmin();
        double top = (page.getVvalue() - page.getVmin()) / range * scrollable;
        double target = top;
        if (bounds.getMaxY() + MARGIN > top + viewport) {
            target = bounds.getMaxY() + MARGIN - viewport;
        }
        if (bounds.getMinY() - MARGIN < target) {
            target = bounds.getMinY() - MARGIN;
        }
        target = Math.clamp(target, 0, scrollable);
        if (target != top) {
            page.setVvalue(page.getVmin() + target / scrollable * range);
        }
    }

    private static boolean isInside(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }
}
