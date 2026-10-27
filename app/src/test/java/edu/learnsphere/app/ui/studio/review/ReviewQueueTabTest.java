package edu.learnsphere.app.ui.studio.review;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.app.ui.components.Overlays;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.ItemCounts;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.service.Workspace;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The review queue in the Studio over the real services and the AI fakes: a course "Cell Biology" with one
 * module, "Cells", whose PDF is ready.
 */
@ExtendWith(ApplicationExtension.class)
class ReviewQueueTabTest {

    @TempDir
    Path dir;

    private Stage stage;
    private RealWorkspaces real;
    private Workspace ws;
    private Course course;
    private CourseModule cells;
    private MainWindow window;
    private ReviewQueueTab tab;
    /** Every toast shown, kept after it times out: a slow machine may look only after it has gone. */
    private final List<Toasts.Toast> toasts = new CopyOnWriteArrayList<>();

    @Start
    void start(Stage stage) {
        this.stage = stage;
    }

    @BeforeEach
    void setUp(FxRobot robot) {
        real = RealWorkspaces.open(dir);
        ws = real.workspace();
        course = ws.courses().create(CourseDraft.titled("Cell Biology"));
        cells = ws.courses().addModule(course.id(), "Cells");
        real.awaitSettled(ws.documents().importPdf(course.id(), cells.id(), CellsPdf.write(dir, "cells.pdf")).id());
        robot.interact(() -> {
            Typography.load();
            window = new MainWindow(real.context());
            Scene scene = new Scene(window, 1360, 900);
            Theme.install(scene, ThemePreference.LIGHT);
            stage.setScene(scene);
            stage.show();
            Overlays.find(window).toasts().getChildren().addListener((ListChangeListener<Node>) change -> {
                while (change.next()) {
                    for (Node added : change.getAddedSubList()) {
                        if (added instanceof Toasts.Toast toast) {
                            toasts.add(toast);
                        }
                    }
                }
            });
        });
        openQueue(robot, course.id());
    }

    @AfterEach
    void tearDown(FxRobot robot) {
        if (window != null) {
            robot.interact(window::dispose);
        }
        if (real != null) {
            real.close();
        }
    }

    @Test
    void findsAModulesKeyConceptsAndNamesTheOnesTheAssistantFound(FxRobot robot) {
        assertThat(fx(() -> tab.conceptsPanel().isExpanded())).as("open while there are no questions").isTrue();

        robot.clickOn(fx(() -> button(tab.root(), "Find key concepts")));

        await("the key concepts", () -> tab.conceptsPanel().conceptNames(cells.id()).size() == 5);
        assertThat(fx(() -> tab.conceptsPanel().conceptNames(cells.id())))
                .containsExactlyInAnyOrder("Chromosomes", "Golgi", "Membrane", "Nucleus", "Ribosomes");
        await("the result line", () -> new ConceptsPanel.Status(ConceptsPanel.Status.Kind.DONE, "Found 5 key concepts.")
                .equals(tab.conceptsPanel().status(cells.id())));
        assertThat(fx(() -> texts(tab.conceptsPanel()))).contains("5 concepts · 5 found by AI");
        assertThat(fx(() -> nodes(tab.conceptsPanel(), n -> n.getStyleClass().contains("concept-chip"))))
                .hasSize(5).allMatch(chip -> chip.getStyleClass().contains("found"));

        // Renaming one makes it yours.
        robot.clickOn(fx(() -> button(tab.conceptsPanel(), "Nucleus")));
        TextField editor = fx(() -> (TextField) nodes(tab.conceptsPanel(),
                n -> n.getStyleClass().contains("concept-editor")).getFirst());
        robot.interact(() -> {
            editor.requestFocus();
            editor.setText("Cell nucleus");
        });
        robot.type(KeyCode.ENTER);

        await("the renamed concept", () -> tab.conceptsPanel().conceptNames(cells.id()).contains("Cell nucleus"));
        await("its chip", () -> button(tab.conceptsPanel(), "Cell nucleus") != null);
        Node renamed = fx(() -> button(tab.conceptsPanel(), "Cell nucleus").getParent());
        assertThat(renamed.getStyleClass()).contains("yours");
        assertThat(fx(() -> texts(tab.conceptsPanel()))).contains("5 concepts · 4 found by AI");
    }

    @Test
    void draftsQuestionsInTheBackgroundAndSaysWhatCameOfThem(FxRobot robot) {
        robot.clickOn(fx(() -> button(tab.root(), "Generate questions")));
        GenerateDialog dialog = (GenerateDialog) fx(() -> ReviewModal.top(window).owner());
        robot.interact(() -> {
            dialog.setType(ItemType.CLOZE, false);
            dialog.setType(ItemType.SHORT_ANSWER, false);
            dialog.setCount(6);
        });
        assertThat(fx(() -> dialog.generateButton().getText())).isEqualTo("Generate 6 questions");

        robot.interact(() -> dialog.generateButton().fire());

        await("the dialog to close", () -> ReviewModal.top(window) == null);
        await("the drafts", () -> tab.isBannerShown() && tab.bannerText().getText().contains("drafted"));
        int doubtful = ws.items().counts(course.id()).unsupported();
        assertThat(doubtful).as("the fakes draft some the source doesn't back").isPositive();
        assertThat(fx(() -> tab.bannerText().getText()))
                .isEqualTo(JobOutcomes.draftedSentence(new JobOutcomes.Drafted(6, 0, doubtful)))
                .startsWith("6 questions drafted. Check " + doubtful + " closely");
        await("the cards", () -> tab.shownItemIds().size() == 6);
        assertThat(fx(() -> tab.statusControl().button(ItemStatus.DRAFT).getText())).isEqualTo("Drafts  6");

        List<ReviewCard> cards = fx(tab::cards);
        assertThat(cards).allMatch(card -> card.item().origin() == ItemOrigin.AI);
        List<String> verdicts = fx(() -> cards.stream()
                .map(card -> SourcePassageView.verdictText(card.sourceView().verdict())).toList());
        assertThat(verdicts).containsOnly("The source backs this answer", "Check this: the source may not back it");
        assertThat(verdicts.stream().filter(v -> v.startsWith("Check this")).count()).isEqualTo(doubtful);
        assertThat(fx(() -> cards.stream().filter(card -> card.sourceView().verdict() == SourcePassageView.Verdict.BACKED)
                .allMatch(card -> card.sourceView().highlightedText() != null)))
                .as("every backed answer marks its supporting text").isTrue();
    }

    /** Acceptance test T04: concepts, six drafts, approve, edit, reject, regenerate, publish. */
    @Test
    void approvesEditsRejectsAndRegeneratesDrafts(FxRobot robot) {
        robot.clickOn(fx(() -> button(tab.root(), "Find key concepts")));
        await("the key concepts", () -> tab.conceptsPanel().conceptNames(cells.id()).size() == 5);
        List<String> ids = generate(robot, 6);

        // Every draft beside its source passage and page.
        for (String id : ids) {
            ReviewCard card = fx(() -> tab.card(id));
            assertThat(card.detail().source()).as("source of %s", id).isNotNull();
            assertThat(fx(() -> texts(card.sourceView())))
                    .contains(card.detail().source().ref().documentTitle())
                    .anyMatch(text -> text.startsWith("p. " + card.detail().source().ref().pageStart())
                            || text.startsWith("pp. " + card.detail().source().ref().pageStart()));
            assertThat(fx(() -> card.sourceView().shownText())).isNotBlank();
        }

        // Approve with A.
        robot.interact(() -> tab.showItem(ids.get(0)));
        robot.type(KeyCode.A);
        await("the first to leave the drafts", () -> !tab.shownItemIds().contains(ids.get(0)));
        assertThat(fx(this::toastMessages)).contains("Approved");

        // Approve with the button.
        Button approve = fx(() -> tab.card(ids.get(1)).action("Approve"));
        robot.interact(approve::fire);
        await("the second to leave the drafts", () -> !tab.shownItemIds().contains(ids.get(1)));

        // Edit with E, then save and approve.
        robot.interact(() -> tab.showItem(ids.get(2)));
        robot.type(KeyCode.E);
        await("the editor", () -> ReviewModal.top(window) != null);
        ItemEditorDialog editor = (ItemEditorDialog) fx(() -> ReviewModal.top(window).owner());
        assertThat(fx(() -> editor.primaryButton().getText())).isEqualTo("Save and approve");
        robot.interact(() -> editor.explanationField().setText("Checked against the chapter on the cell."));
        robot.interact(() -> editor.primaryButton().fire());
        await("the editor to close", () -> ReviewModal.top(window) == null);
        await("the edited one to leave the drafts", () -> !tab.shownItemIds().contains(ids.get(2)));
        assertThat(ws.items().get(ids.get(2)).item().explanation()).isEqualTo("Checked against the chapter on the cell.");

        // Reject with R.
        robot.interact(() -> tab.showItem(ids.get(3)));
        robot.type(KeyCode.R);
        await("the rejected one to leave the drafts", () -> !tab.shownItemIds().contains(ids.get(3)));

        // Regenerate: the card stays and shows the new version.
        String before = ws.items().get(ids.get(4)).item().prompt();
        Button regenerate = fx(() -> tab.card(ids.get(4)).action("Regenerate"));
        robot.interact(regenerate::fire);
        await("the new version", () -> tab.card(ids.get(4)) != null && tab.card(ids.get(4)).isFresh()
                && !tab.card(ids.get(4)).isBusy());
        assertThat(ws.items().get(ids.get(4)).item().prompt()).isNotEqualTo(before);
        assertThat(fx(() -> texts(tab.card(ids.get(4))))).contains("New version");

        ItemCounts counts = ws.items().counts(course.id());
        assertThat(counts.approved()).isEqualTo(3);
        assertThat(counts.rejected()).isEqualTo(1);
        assertThat(counts.draft()).isEqualTo(2);
        assertThat(ws.items().get(ids.get(4)).item().status()).isEqualTo(ItemStatus.DRAFT);
        await("the counts", () -> tab.statusControl().button(ItemStatus.APPROVED).getText().equals("Approved  3"));
        assertThat(fx(() -> tab.statusControl().button(ItemStatus.REJECTED).getText())).isEqualTo("Rejected  1");
        assertThat(fx(() -> tab.statusControl().button(ItemStatus.DRAFT).getText())).isEqualTo("Drafts  2");
        assertThat(fx(tab::shownItemIds)).containsExactlyInAnyOrder(ids.get(4), ids.get(5));

        CourseVersion first = ws.courses().publish(course.id(), "First version");
        assertThat(first.number()).isEqualTo(1);
        assertThat(first.notes()).isEqualTo("First version");
    }

    @Test
    void approvesSelectedDraftsAndUndoes(FxRobot robot) {
        List<String> ids = generate(robot, 6);
        assertThat(fx(() -> button(tab.root(), "Approve selected").isDisabled())).isTrue();

        robot.interact(() -> ids.subList(0, 3).forEach(id -> tab.selectItem(id, true)));
        Button approveSelected = fx(() -> button(tab.root(), "Approve 3 questions"));
        assertThat(approveSelected).isNotNull();
        robot.interact(approveSelected::fire);

        await(() -> "the toast, among " + fx(this::toastMessages), () -> toastMessages().contains("Approved 3 questions"));

        Button undo = fx(() -> (Button) toast("Approved 3 questions").lookup(".toast-action"));
        assertThat(undo.getText()).isEqualTo("Undo");
        assertThat(ws.items().counts(course.id()).approved()).isEqualTo(3);
        await("the approved count", () -> tab.statusControl().button(ItemStatus.APPROVED).getText().equals("Approved  3"));
        robot.interact(undo::fire);
        await("the undo", () -> tab.statusControl().button(ItemStatus.DRAFT).getText().equals("Drafts  6"));
        assertThat(ws.items().counts(course.id()).approved()).isZero();
    }

    @Test
    void movesBetweenCardsWithJAndK(FxRobot robot) {
        List<String> ids = generate(robot, 6);
        robot.interact(() -> tab.showItem(ids.get(0)));

        robot.type(KeyCode.J);
        assertThat(fx(tab::currentItemId)).isEqualTo(ids.get(1));
        robot.type(KeyCode.J);
        assertThat(fx(tab::currentItemId)).isEqualTo(ids.get(2));
        robot.type(KeyCode.K);
        assertThat(fx(tab::currentItemId)).isEqualTo(ids.get(1));
        assertThat(fx(() -> tab.card(ids.get(1)).isCurrent())).isTrue();
        assertThat(fx(() -> tab.card(ids.get(2)).isCurrent())).isFalse();

        // The page scrolls to the card the keys act on.
        robot.interact(() -> tab.showItem(ids.getLast()));
        settle();
        assertThat(fx(this::pageScroll)).isGreaterThan(0.5);
        robot.type(KeyCode.K);
        assertThat(fx(tab::currentItemId)).isEqualTo(ids.get(ids.size() - 2));
    }

    @Test
    void showsAFlagWithItsReasonUntilItIsResolved(FxRobot robot) {
        List<String> ids = generate(robot, 6);
        ws.items().addFlag(ids.get(0), FlagReason.WRONG_ANSWER, "The chapter says the opposite.");

        await("the flag", () -> tab.card(ids.get(0)) != null
                && texts(tab.card(ids.get(0))).contains("You flagged this · The answer looks wrong"));
        assertThat(fx(() -> texts(tab.card(ids.get(0))))).anyMatch(text -> text.contains("The chapter says the opposite."));

        robot.interact(() -> tab.showFlaggedOnly(true));
        await("only the flagged question", () -> tab.shownItemIds().equals(List.of(ids.get(0))));

        Button resolve = fx(() -> button(tab.card(ids.get(0)), "Resolve"));
        robot.interact(resolve::fire);
        await(() -> "the toast, among " + fx(this::toastMessages), () -> toastMessages().contains("Flag resolved"));
        await("the flag to go", () -> tab.shownItemIds().isEmpty());
        assertThat(ws.items().flags(course.id(), false)).isEmpty();
    }

    @Test
    void editorChecksEachTypeAsItIsWritten(FxRobot robot) {
        robot.interact(tab::openNewQuestion);
        ItemEditorDialog editor = (ItemEditorDialog) fx(() -> ReviewModal.top(window).owner());
        assertThat(fx(() -> editor.primaryButton().getText())).isEqualTo("Save and approve");

        // Multiple choice: a question, one correct answer and two to four different wrong options.
        robot.interact(() -> editor.typeControl().setValue(ItemType.MCQ));
        robot.interact(() -> editor.secondaryButton().fire());
        assertThat(fx(() -> editor.shownError(ItemRules.Field.PROMPT))).isEqualTo("Write the question.");
        assertThat(fx(() -> editor.shownError(ItemRules.Field.ANSWER))).isEqualTo("Write the correct answer.");
        assertThat(fx(() -> editor.shownError(ItemRules.Field.OPTIONS))).isEqualTo(ItemRules.OPTION_BLANK);
        assertThat(fx(editor::footerErrorText)).isEqualTo("Fix what's marked to save this question.");
        robot.interact(() -> {
            editor.promptField().setText("Which organelle sorts proteins?");
            editor.answerField().setText("The Golgi apparatus");
            editor.optionFields().get(0).setText("The nucleus");
            editor.optionFields().get(1).setText("the nucleus");
        });
        assertThat(fx(() -> editor.shownError(ItemRules.Field.OPTIONS))).isEqualTo(ItemRules.OPTIONS_DIFFERENT);
        robot.interact(() -> editor.optionFields().get(1).setText("A ribosome"));
        assertThat(fx(editor::problems)).isEmpty();
        assertThat(fx(() -> editor.shownError(ItemRules.Field.OPTIONS))).isNull();
        assertThat(fx(editor::footerErrorText)).isNull();
        robot.interact(() -> editor.addOptionButton().fire());
        assertThat(fx(editor::problems)).extracting(ItemRules.Problem::message).containsOnly(ItemRules.OPTION_BLANK);
        assertThat(fx(() -> editor.shownError(ItemRules.Field.OPTIONS))).as("not while it waits to be filled in").isNull();
        robot.interact(() -> editor.addOptionButton().fire());
        assertThat(fx(() -> editor.optionFields().size())).isEqualTo(4);
        assertThat(fx(() -> editor.addOptionButton().isDisabled())).as("four wrong options at most").isTrue();
        robot.interact(() -> editor.secondaryButton().fire());
        assertThat(fx(() -> editor.shownError(ItemRules.Field.OPTIONS))).isEqualTo(ItemRules.OPTION_BLANK);
        assertThat(fx(() -> ReviewModal.top(window))).as("not saved").isNotNull();

        // Fill in the blank: exactly one blank.
        robot.interact(() -> {
            editor.typeControl().setValue(ItemType.CLOZE);
            editor.promptField().setText("The nucleus holds the chromosomes.");
            editor.answerField().setText("nucleus");
        });
        assertThat(fx(() -> editor.shownError(ItemRules.Field.PROMPT))).isEqualTo(ItemRules.ONE_BLANK);
        robot.interact(() -> {
            editor.promptField().setText("The ____ holds the chromosomes.");
        });
        assertThat(fx(editor::problems)).isEmpty();

        // Flashcard: front and back.
        robot.interact(() -> {
            editor.typeControl().setValue(ItemType.FLASHCARD);
            editor.promptField().setText("Ribosome");
            editor.longAnswerField().setText("");
        });
        assertThat(fx(() -> editor.shownError(ItemRules.Field.ANSWER))).isEqualTo("Write the back of the card.");
        robot.interact(() -> editor.longAnswerField().setText("Where proteins are made."));
        assertThat(fx(editor::problems)).isEmpty();

        // Short answer: a model answer, with optional key points.
        robot.interact(() -> {
            editor.typeControl().setValue(ItemType.SHORT_ANSWER);
            editor.promptField().setText("Why does a cell need a membrane?");
            editor.longAnswerField().setText("");
            editor.addPointButton().fire();
        });
        robot.interact(() -> editor.pointFields().getFirst().setText("controls what enters"));
        assertThat(fx(() -> editor.shownError(ItemRules.Field.ANSWER))).isEqualTo("Write a model answer.");
        robot.interact(() -> editor.longAnswerField().setText("It keeps the inside of the cell apart from outside."));
        assertThat(fx(editor::problems)).isEmpty();
        assertThat(fx(() -> editor.content().answer()))
                .isEqualTo("It keeps the inside of the cell apart from outside.\n\nKey points:\n• controls what enters");

        // True or false: a statement, and true or false.
        robot.interact(() -> {
            editor.typeControl().setValue(ItemType.TRUE_FALSE);
            editor.promptField().setText("");
        });
        assertThat(fx(() -> editor.shownError(ItemRules.Field.PROMPT)))
                .isEqualTo("Write the statement to judge as true or false.");
        robot.interact(() -> {
            editor.promptField().setText("Ribosomes make proteins.");
            editor.truthControl().setValue(ItemEditorDialog.Truth.TRUE);
        });
        assertThat(fx(editor::problems)).isEmpty();

        // Saving as a draft adds a question written by hand.
        robot.interact(() -> editor.secondaryButton().fire());
        await("the editor to close", () -> ReviewModal.top(window) == null);
        await("the new draft", () -> tab.shownItemIds().size() == 1);
        ReviewCard card = fx(() -> tab.cards().getFirst());
        assertThat(card.item().origin()).isEqualTo(ItemOrigin.MANUAL);
        assertThat(card.item().type()).isEqualTo(ItemType.TRUE_FALSE);
        assertThat(card.item().prompt()).isEqualTo("Ribosomes make proteins.");
        assertThat(fx(() -> texts(card))).contains("Written by hand", "The statement is true.");
    }

    @Test
    void asksForAPdfBeforeDraftingWhenACourseHasNone(FxRobot robot) {
        Course empty = ws.courses().create(CourseDraft.titled("Genetics"));
        ws.courses().addModule(empty.id(), "Inheritance");

        openQueue(robot, empty.id());

        assertThat(fx(() -> texts(tab.root()))).contains("Add a PDF to draft questions from");
        assertThat(fx(() -> button(tab.root(), "Write a question"))).isNotNull();
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private void openQueue(FxRobot robot, String courseId) {
        robot.interact(() -> window.navigator().reset(new Destination.Studio(courseId, StudioTab.REVIEW)));
        settle();
        await("the review queue", () -> ReviewQueueTab.find(window) != null && ReviewQueueTab.find(window).snapshot() != null);
        tab = fx(() -> ReviewQueueTab.find(window));
        settle();
    }

    /** Drafts {@code count} questions (multiple choice, true or false, flashcards) and returns their ids. */
    private List<String> generate(FxRobot robot, int count) {
        robot.interact(tab::openGenerateDialog);
        GenerateDialog dialog = (GenerateDialog) fx(() -> ReviewModal.top(window).owner());
        robot.interact(() -> {
            dialog.setScope(cells.id());
            dialog.setType(ItemType.CLOZE, false);
            dialog.setType(ItemType.SHORT_ANSWER, false);
            dialog.setCount(count);
        });
        assertThat(fx(dialog::request).moduleId()).isEqualTo(cells.id());
        assertThat(fx(dialog::request).types()).containsExactlyInAnyOrder(ItemType.MCQ, ItemType.TRUE_FALSE, ItemType.FLASHCARD);
        robot.interact(() -> dialog.generateButton().fire());
        await("the drafts", () -> tab.shownItemIds().size() == count);
        settle();
        return fx(tab::shownItemIds);
    }

    /** How far down the page is scrolled, from 0 to 1. */
    private double pageScroll() {
        for (Node n = tab.root().getParent(); n != null; n = n.getParent()) {
            if (n instanceof ScrollPane pane) {
                return pane.getVvalue();
            }
        }
        throw new AssertionError("The queue is not in a scrolling page");
    }

    /** Messages of the toasts shown so far. */
    private List<String> toastMessages() {
        return toasts.stream().map(Toasts.Toast::message).toList();
    }

    /** The latest toast with this message. */
    private Toasts.Toast toast(String message) {
        for (int i = toasts.size() - 1; i >= 0; i--) {
            if (toasts.get(i).message().equals(message)) {
                return toasts.get(i);
            }
        }
        throw new AssertionError("No toast " + message + " among " + toastMessages());
    }

    /** The first button under {@code root} labelled {@code text}, or null. */
    private static Button button(Node root, String text) {
        List<Node> found = nodes(root, n -> n instanceof Button b && text.equals(b.getText()));
        return found.isEmpty() ? null : (Button) found.getFirst();
    }

    /** Texts of the visible labels and buttons under {@code root}. */
    private static List<String> texts(Node root) {
        return nodes(root, n -> n instanceof Labeled).stream().map(n -> ((Labeled) n).getText()).toList();
    }

    /** Visible nodes under {@code root} (itself included) that match. */
    private static List<Node> nodes(Node root, Predicate<Node> match) {
        List<Node> found = new ArrayList<>();
        collect(root, match, found);
        return found;
    }

    private static void collect(Node node, Predicate<Node> match, List<Node> found) {
        if (!node.isVisible()) {
            return;
        }
        if (match.test(node)) {
            found.add(node);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, match, found);
            }
        }
    }

    private static <T> T fx(Callable<T> read) {
        try {
            return WaitForAsyncUtils.asyncFx(read).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Reading the screen failed", e);
        }
    }

    private static void await(String what, Callable<Boolean> condition) {
        await(() -> what, condition);
    }

    private static void await(java.util.function.Supplier<String> what, Callable<Boolean> condition) {
        try {
            WaitForAsyncUtils.waitFor(60, TimeUnit.SECONDS, () -> fx(condition));
        } catch (TimeoutException e) {
            throw new AssertionError("Timed out waiting for " + what.get());
        }
    }

    private static void settle() {
        assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(10))).isTrue();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(10))).isTrue();
        WaitForAsyncUtils.waitForFxEvents();
    }
}
