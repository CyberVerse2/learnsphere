package edu.learnsphere.app.ui.home;

import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DashboardData;
import edu.learnsphere.core.service.StudyNext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The hero of Home: what to study next across every course being studied. It says how many reviews are due
 * and how many new questions today's limit allows, about how long that takes, shows the work as a row of
 * beads, and has one primary button that starts the session. When nothing is waiting it says so and when
 * the next review comes; a profile that studies nothing, or whose courses have no questions yet, is told
 * how to get started.
 */
public final class StudyNextCard extends Card {

    /** What the card is showing. */
    public enum State {
        /** Reviews or new questions are waiting. */
        STUDY,
        /** Everything for today is done. */
        CAUGHT_UP,
        /** The courses being studied have no questions yet. */
        NO_QUESTIONS,
        /** No course is being studied. */
        NOT_STUDYING
    }

    /** Most courses named under the beads. */
    static final int MAX_COURSES = 3;

    private final Label overline = new Label("STUDY NEXT");
    private final Label headline = new Label();
    private final Label detail = new Label();
    private final SessionBeads beads = new SessionBeads();
    private final Label beadsCaption = new Label();
    private final FlowPane courses = new FlowPane(16, 8);
    private final Button start = new Button("Start studying");
    private final Button library = new Button("Open Library");
    private final Label shortcut = new Label();
    private final HBox actions;
    private State state = State.STUDY;

    /**
     * @param onStart       starts a Study next session
     * @param onOpenLibrary opens the Library
     * @param startShortcut the keys that also start studying, shown beside the button, or null
     */
    public StudyNextCard(Runnable onStart, Runnable onOpenLibrary, KeyCombination startShortcut) {
        setInteractive(false);
        getStyleClass().add("study-hero");
        overline.getStyleClass().addAll("text-overline", "study-hero-overline");
        headline.getStyleClass().addAll("serif-medium", "study-hero-headline");
        headline.setWrapText(true);
        detail.getStyleClass().add("study-hero-detail");
        detail.setWrapText(true);
        beadsCaption.getStyleClass().addAll("text-caption", "study-hero-beads-caption");
        courses.getStyleClass().add("study-hero-courses");

        start.getStyleClass().addAll("primary", "large");
        start.setGraphic(Icons.of(Feather.PLAY, 16));
        start.setOnAction(event -> onStart.run());
        library.getStyleClass().add("large");
        library.setOnAction(event -> onOpenLibrary.run());
        shortcut.getStyleClass().addAll("text-caption", "study-hero-shortcut");
        if (startShortcut != null) {
            shortcut.setText(startShortcut.getDisplayText());
        }
        actions = new HBox(16, start, library, shortcut);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("study-hero-actions");

        VBox heading = new VBox(8, overline, headline, detail);
        VBox work = new VBox(10, beads, beadsCaption, courses);
        Region push = new Region();
        push.setMinHeight(0);
        VBox.setVgrow(push, Priority.ALWAYS);
        getChildren().setAll(heading, work, push, actions);
        setSpacing(24);
    }

    /** Shows {@code data}; {@code clock} turns review times into words. */
    public void show(DashboardData data, Clock clock) {
        StudyNext next = data.studyNext();
        state = stateOf(data);
        headline.setText(headline(data));
        detail.setText(detail(data, clock));
        courses.getChildren().clear();
        switch (state) {
            case STUDY -> {
                beads.showWork(next.dueCount(), next.newCount());
                beadsCaption.setText(null);
                for (CourseSummary course : coursesWithWork(data)) {
                    courses.getChildren().add(courseLine(course, next.newCount() > 0));
                }
            }
            case CAUGHT_UP -> {
                beads.showDone(data.reviewsToday());
                beadsCaption.setText(data.reviewsToday() == 0 ? null
                        : Formats.count(data.reviewsToday(), "review", "reviews") + " done today");
            }
            default -> {
                beads.showDone(0);
                beadsCaption.setText(null);
            }
        }
        visible(beadsCaption, beadsCaption.getText() != null);
        visible(courses, !courses.getChildren().isEmpty());
        visible(start, state == State.STUDY);
        visible(shortcut, state == State.STUDY && !shortcut.getText().isEmpty());
        visible(library, state == State.NOT_STUDYING);
        visible(actions, state == State.STUDY || state == State.NOT_STUDYING);
        visible(beads.getParent(), beads.isVisible() || courses.isVisible());
        pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("caught-up"), state == State.CAUGHT_UP);
        setAccessibleText(headline.getText() + ". " + detail.getText());
    }

    public State state() {
        return state;
    }

    public Label headlineLabel() {
        return headline;
    }

    public Label detailLabel() {
        return detail;
    }

    public SessionBeads beads() {
        return beads;
    }

    public Button startButton() {
        return start;
    }

    public Button libraryButton() {
        return library;
    }

    // ---- words -------------------------------------------------------------------------------

    /** Which state {@code data} puts the card in. */
    public static State stateOf(DashboardData data) {
        StudyNext next = data.studyNext();
        if (!next.caughtUp()) {
            return State.STUDY;
        }
        if (data.continueCourses().isEmpty()) {
            return State.NOT_STUDYING;
        }
        boolean anyQuestions = data.continueCourses().stream().anyMatch(c -> c.itemCount() > 0);
        if (!anyQuestions && next.nextReviewAt() == null) {
            return State.NO_QUESTIONS;
        }
        return State.CAUGHT_UP;
    }

    /** The large line: "7 reviews and 10 new questions", "You're all caught up." … */
    public static String headline(DashboardData data) {
        StudyNext next = data.studyNext();
        return switch (stateOf(data)) {
            case STUDY -> {
                int due = next.dueCount();
                int fresh = next.newCount();
                if (due > 0 && fresh > 0) {
                    yield Formats.count(due, "review", "reviews") + " and "
                            + Formats.count(fresh, "new question", "new questions");
                }
                if (due > 0) {
                    yield due == 1 ? "1 review is due" : Formats.number(due) + " reviews are due";
                }
                yield fresh == 1 ? "1 new question to learn" : Formats.number(fresh) + " new questions to learn";
            }
            case CAUGHT_UP -> "You're all caught up.";
            case NO_QUESTIONS -> "Your courses have no questions yet";
            case NOT_STUDYING -> "Nothing to study yet";
        };
    }

    /** The line under it: how long the session takes, or when the next review comes. */
    public static String detail(DashboardData data, Clock clock) {
        StudyNext next = data.studyNext();
        return switch (stateOf(data)) {
            case STUDY -> next.estimatedMinutes() <= 1 ? "About a minute"
                    : "About " + Formats.number(next.estimatedMinutes()) + " minutes";
            case CAUGHT_UP -> caughtUpDetail(data, clock);
            case NO_QUESTIONS -> "Reviews appear here as soon as your courses have questions. "
                    + "You can already ask about their material.";
            case NOT_STUDYING -> "Start studying a course from the Library, and its reviews appear here.";
        };
    }

    /**
     * When something comes back: later today, tomorrow (new questions past today's limit, or reviews due
     * then), or the day of the next review.
     */
    static String caughtUpDetail(DashboardData data, Clock clock) {
        StudyNext next = data.studyNext();
        boolean newWaiting = data.continueCourses().stream().anyMatch(c -> c.newItemCount() > 0);
        Long nextAt = next.nextReviewAt();
        long days = nextAt == null ? -1 : ChronoUnit.DAYS.between(data.today(),
                Instant.ofEpochMilli(nextAt).atZone(clock.getZone()).toLocalDate());
        if (nextAt != null && days <= 0) {
            return "Your next review is later today.";
        }
        if (nextAt != null && days == 1) {
            return "New reviews unlock tomorrow.";
        }
        if (newWaiting) {
            return nextAt == null ? "New questions unlock tomorrow."
                    : "New questions unlock tomorrow. Your next review is " + Formats.inTime(nextAt, clock) + ".";
        }
        if (nextAt != null) {
            return "Your next review is " + Formats.inTime(nextAt, clock) + ".";
        }
        return "Nothing is scheduled. Practise a course from its page whenever you like.";
    }

    /** The courses that the session draws from, most due first. */
    static List<CourseSummary> coursesWithWork(DashboardData data) {
        boolean newToday = data.studyNext().newCount() > 0;
        return data.continueCourses().stream()
                .filter(c -> c.dueCount() > 0 || newToday && c.newItemCount() > 0)
                .sorted((a, b) -> Integer.compare(b.dueCount(), a.dueCount()))
                .limit(MAX_COURSES)
                .toList();
    }

    private static Node courseLine(CourseSummary course, boolean newToday) {
        CourseCover cover = new CourseCover(course.coverHue(), course.id(), CourseCover.Size.TINY);
        Label title = new Label(Formats.shorten(course.title(), 40));
        title.getStyleClass().addAll("medium", "study-hero-course-title");
        String what = course.dueCount() > 0 ? Formats.number(course.dueCount()) + " due"
                : newToday ? "new questions" : "";
        Label count = new Label(what);
        count.getStyleClass().addAll("text-caption", "study-hero-course-count");
        HBox line = new HBox(8, cover, title, count);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("study-hero-course");
        return line;
    }

    private static void visible(Node node, boolean value) {
        if (node != null) {
            node.setVisible(value);
            node.setManaged(value);
        }
    }
}
