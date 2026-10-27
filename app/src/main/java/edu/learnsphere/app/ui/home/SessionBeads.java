package edu.learnsphere.app.ui.home;

import edu.learnsphere.app.theme.Motion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * One bead per question waiting today: filled beads for reviews that are due, hollow ones for new questions,
 * soft green ones for reviews already done. It makes the size of today's work visible at a glance; past
 * {@link #MAX_BEADS} the rest is a count ("+12").
 */
public final class SessionBeads extends FlowPane {

    /** Most beads drawn; the rest is shown as a number. */
    public static final int MAX_BEADS = 40;

    /** What a bead stands for. */
    public enum Kind {
        DUE("bead-due"),
        NEW("bead-new"),
        DONE("bead-done");

        private final String styleClass;

        Kind(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    private final List<Kind> shown = new ArrayList<>();
    private int more;

    public SessionBeads() {
        getStyleClass().add("session-beads");
        setHgap(6);
        setVgap(6);
        setFocusTraversable(false);
    }

    /** Shows {@code due} reviews followed by {@code fresh} new questions. */
    public void showWork(int due, int fresh) {
        List<Kind> kinds = new ArrayList<>(Collections.nCopies(Math.max(0, due), Kind.DUE));
        kinds.addAll(Collections.nCopies(Math.max(0, fresh), Kind.NEW));
        fill(kinds);
    }

    /** Shows {@code done} reviews finished today. */
    public void showDone(int done) {
        fill(new ArrayList<>(Collections.nCopies(Math.max(0, done), Kind.DONE)));
    }

    /** The beads drawn, in order (tests). */
    public List<Kind> beads() {
        return List.copyOf(shown);
    }

    /** What is left over beyond {@link #MAX_BEADS}. */
    public int more() {
        return more;
    }

    private void fill(List<Kind> kinds) {
        shown.clear();
        shown.addAll(kinds.subList(0, Math.min(MAX_BEADS, kinds.size())));
        more = kinds.size() - shown.size();
        List<Node> nodes = new ArrayList<>();
        for (Kind kind : shown) {
            Region bead = new Region();
            bead.getStyleClass().addAll("bead", kind.styleClass);
            bead.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            bead.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            nodes.add(bead);
        }
        if (more > 0) {
            Label rest = new Label("+" + more);
            rest.getStyleClass().addAll("text-caption", "bead-more");
            nodes.add(rest);
        }
        getChildren().setAll(nodes);
        setVisible(!nodes.isEmpty());
        setManaged(!nodes.isEmpty());
        animate(nodes);
    }

    /** The beads fall into place one after another; with reduced motion they simply appear. */
    private void animate(List<Node> nodes) {
        if (Motion.reduced()) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            node.setOpacity(0);
            FadeTransition fade = new FadeTransition(Duration.millis(220), node);
            fade.setFromValue(0);
            fade.setToValue(1);
            ScaleTransition scale = new ScaleTransition(Duration.millis(260), node);
            scale.setFromX(0.4);
            scale.setFromY(0.4);
            scale.setToX(1);
            scale.setToY(1);
            scale.setInterpolator(Motion.EASE_OUT);
            ParallelTransition in = new ParallelTransition(fade, scale);
            in.setDelay(Duration.millis(80 + i * 10L));
            in.play();
        }
    }
}
