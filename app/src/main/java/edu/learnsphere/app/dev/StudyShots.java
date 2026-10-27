package edu.learnsphere.app.dev;

import edu.learnsphere.app.dev.ScreenshotTour.Shot;
import edu.learnsphere.app.dev.ScreenshotTour.Steps;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.study.StudyPage;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.Workspace;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * The study session's screenshots: each kind of question before and after answering, a hint, the source page,
 * leaving, a comeback, the summary, and the empty and ended states. Each shot starts a demo session and
 * drives it through the keyboard, as a learner would, until the state to capture is on screen.
 */
final class StudyShots {

    /** Most cards to step through looking for a kind of question. */
    private static final int MAX_STEPS = 40;

    private StudyShots() {
    }

    /** Every study shot, in the order they appear in the tour. */
    static List<Shot> shots() {
        return List.of(
                at("study-choice-chosen", ItemType.MCQ, window -> {
                    press(window, KeyCode.DIGIT1, "1");
                    sure(window, "Fairly sure");
                }),
                at("study-choice-correct", ItemType.MCQ, window -> answer(window, true)),
                at("study-choice-hint", ItemType.MCQ, window -> page(window).showHint()),
                at("study-choice-hint-steps", ItemType.MCQ, window -> page(window).showHint(),
                        window -> page(window).showHint()),
                at("study-true-false", ItemType.TRUE_FALSE, window -> press(window, KeyCode.T, "t")),
                at("study-cloze", ItemType.CLOZE, window -> type(window, "")),
                at("study-cloze-wrong", ItemType.CLOZE, window -> answer(window, false)),
                at("study-short-answer", ItemType.SHORT_ANSWER, window -> type(window,
                        "The system saves the running process's registers so it can carry on later.")),
                at("study-short-answer-graded", ItemType.SHORT_ANSWER, window -> answer(window, false)),
                flashcards("study-flashcard", window -> { }),
                flashcards("study-flashcard-back", window -> press(window, KeyCode.SPACE, " ")),
                at("study-source", ItemType.MCQ, window -> {
                    answer(window, false);
                }, window -> page(window).openSource()),
                at("study-leave", ItemType.MCQ, window -> answer(window, true), window -> page(window).requestLeave()),
                new Shot("study-comeback", window -> window.navigator().reset(study(SessionRequest.studyNext().withSize(2))),
                        new Steps(window -> page(window).state() == StudyPage.State.QUESTION
                                && page(window).counterText().startsWith("3 of"), StudyShots::wrongThenOn, MAX_STEPS)),
                // Research Methods has only just started, so a few right answers lift its concepts.
                new Shot("study-summary", window -> window.navigator().reset(study(
                        SessionRequest.studyNext(DemoContent.RESEARCH_METHODS).withSize(4))),
                        new Steps(window -> page(window).state() == StudyPage.State.SUMMARY, StudyShots::rightThenOn,
                                MAX_STEPS)),
                new Shot("study-empty", window -> window.navigator().reset(study(emptyQuiz(window)))),
                new Shot("study-weak-spots", window -> window.navigator().reset(study(
                        SessionRequest.weakConcepts(DemoContent.RESEARCH_METHODS)))),
                new Shot("study-empty-weak", window -> window.navigator().reset(study(
                        SessionRequest.weakConcepts(DemoContent.ORGANIC_CHEMISTRY)))),
                new Shot("study-ended", window -> window.navigator().reset(study(SessionRequest.studyNext())),
                        window -> {
                            // The session ends behind the page's back (as when the app was away), then the learner
                            // answers.
                            Workspace workspace = window.context().workspace();
                            workspace.practice().finish(page(window).session().id());
                            answer(window, true);
                        }));
    }

    private static Destination.Study study(SessionRequest request) {
        return new Destination.Study(request);
    }

    /** A shot of the first question of {@code type} in a "Study next" session, after {@code then}. */
    private static Shot at(String name, ItemType type, Consumer<MainWindow> then) {
        return at(name, type, then, null);
    }

    private static Shot at(String name, ItemType type, Consumer<MainWindow> then, Consumer<MainWindow> after) {
        return new Shot(name, window -> window.navigator().reset(study(SessionRequest.studyNext())),
                new Steps(window -> isAt(window, type), StudyShots::rightThenOn, MAX_STEPS, then, after));
    }

    private static Shot flashcards(String name, Consumer<MainWindow> then) {
        return new Shot(name, window -> window.navigator().reset(study(SessionRequest.flashcards(
                DemoContent.OPERATING_SYSTEMS, null))), new Steps(window -> true, window -> { }, 1, then, null));
    }

    /** A module quiz for a module with no questions to answer (its questions are still drafts). */
    private static SessionRequest emptyQuiz(MainWindow window) {
        String course = DemoContent.ORGANIC_CHEMISTRY;
        String module = window.context().workspace().courses().get(course).modules().stream()
                .filter(m -> m.module().title().equals("Isomerism")).findFirst().orElseThrow().module().id();
        return SessionRequest.moduleQuiz(course, module);
    }

    private static StudyPage page(MainWindow window) {
        return (StudyPage) window.navigator().currentView();
    }

    private static boolean isAt(MainWindow window, ItemType type) {
        StudyPage page = page(window);
        SessionItem item = page.currentItem();
        return page.state() == StudyPage.State.QUESTION && item != null && item.type() == type;
    }

    /** Answers the card on screen correctly, or moves on from its feedback. */
    private static void rightThenOn(MainWindow window) {
        step(window, true);
    }

    /** Answers the card on screen wrongly, or moves on from its feedback. */
    private static void wrongThenOn(MainWindow window) {
        step(window, false);
    }

    private static void step(MainWindow window, boolean right) {
        StudyPage page = page(window);
        switch (page.state()) {
            case QUESTION -> answer(window, right);
            case FEEDBACK -> press(window, KeyCode.ENTER, "\r");
            default -> {
            }
        }
    }

    /**
     * Answers the question on screen, rightly or wrongly, through the keyboard: an option's number (T or F), a
     * typed answer, or turning a flashcard and rating it. FX thread.
     */
    static void answer(MainWindow window, boolean right) {
        StudyPage page = page(window);
        SessionItem item = page.currentItem();
        if (item == null) {
            return;
        }
        String answer = window.context().workspace().items().get(item.itemId()).item().answer();
        switch (item.type()) {
            case MCQ -> {
                int index = -1;
                for (int i = 0; i < item.choices().size(); i++) {
                    boolean isAnswer = item.choices().get(i).strip().equalsIgnoreCase(answer.strip());
                    if (isAnswer == right) {
                        index = i;
                        break;
                    }
                }
                String key = Integer.toString(index + 1);
                press(window, KeyCode.getKeyCode(key), key);
                sure(window, right ? "Certain" : "Fairly sure");
                press(window, KeyCode.ENTER, "\r");
            }
            case TRUE_FALSE -> {
                boolean truth = answer.strip().toLowerCase(Locale.ROOT).startsWith("t");
                boolean say = right == truth;
                press(window, say ? KeyCode.T : KeyCode.F, say ? "t" : "f");
                press(window, KeyCode.ENTER, "\r");
            }
            case CLOZE -> {
                type(window, right ? answer : "spooling");
                press(window, KeyCode.ENTER, "\r");
            }
            case SHORT_ANSWER -> {
                type(window, right ? answer : "The processor stops the process for a moment.");
                press(window, KeyCode.ENTER, "\r");
            }
            case FLASHCARD -> {
                press(window, KeyCode.SPACE, " ");
                press(window, right ? KeyCode.DIGIT3 : KeyCode.DIGIT1, right ? "3" : "1");
            }
        }
    }

    /** Types {@code text} in the answer field on screen. */
    private static void type(MainWindow window, String text) {
        if (page(window).root().lookup(".study-input") instanceof TextInputControl input) {
            input.setText(text);
            input.requestFocus();
            input.end();
        }
    }

    /** Picks the confidence chip labelled {@code label}. */
    private static void sure(MainWindow window, String label) {
        for (Node node : page(window).root().lookupAll(".confidence-chip")) {
            if (node instanceof ToggleButton chip && label.equals(chip.getText())) {
                chip.setSelected(true);
            }
        }
    }

    private static void press(MainWindow window, KeyCode code, String text) {
        Event.fireEvent(page(window).root(),
                new KeyEvent(KeyEvent.KEY_PRESSED, text, text, code, false, false, false, false));
    }
}
