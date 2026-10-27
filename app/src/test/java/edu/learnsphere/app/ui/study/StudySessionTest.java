package edu.learnsphere.app.ui.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.app.ui.components.StatTile;
import edu.learnsphere.core.model.Confidence;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.GenerationRequest;
import edu.learnsphere.core.service.ItemFilter;
import edu.learnsphere.core.service.ItemStats;
import edu.learnsphere.core.service.ItemSummary;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.pdf.testing.TestPdf;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
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
 * The study session over the real services (SQLite, the pdf module, search, the scheduler) with the AI fakes:
 * a course with one approved, generated question of every kind, studied through the keyboard as a learner
 * would. Includes acceptance scenario T05.
 */
@ExtendWith(ApplicationExtension.class)
class StudySessionTest {

    private static final PseudoClass CHOSEN = PseudoClass.getPseudoClass("chosen");
    private static final String LATER_IN_SESSION = "You'll see this again later in this session.";

    @TempDir
    Path dir;

    private Stage stage;
    private RealWorkspaces real;
    private MainWindow window;
    private String courseId;

    @Start
    void start(Stage stage) {
        // The window is built in setUp: opening the profile waits on the FX thread, so it cannot run here.
        this.stage = stage;
    }

    @BeforeEach
    void setUp(FxRobot robot) {
        real = RealWorkspaces.open(dir);
        courseId = seedCourse();
        robot.interact(() -> {
            Typography.load();
            window = new MainWindow(real.context());
            Scene scene = new Scene(window, 1360, 860);
            Theme.install(scene, ThemePreference.LIGHT);
            stage.setScene(scene);
            stage.show();
        });
        settle();
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

    // ---- T05 ------------------------------------------------------------------------------------------

    @Test
    void t05AStudySessionSchedulesReviews(FxRobot robot) {
        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
        robot.clickOn(button(window.getScene().getRoot(), "Start studying"));
        settle();

        assertThat(window.navigator().current()).isInstanceOf(Destination.Study.class);
        assertThat(page().state()).isEqualTo(StudyPage.State.QUESTION);
        assertThat(window.isSidebarShown()).as("full screen").isFalse();
        assertThat(window.topBar().isVisible()).isFalse();
        assertThat(page().root().lookup(".study-progress")).isNotNull();
        assertThat(page().counterText()).isEqualTo("1 of 5");
        assertFocusOnTheCard();

        Set<ItemType> seen = EnumSet.noneOf(ItemType.class);
        String wrongItem = null;
        boolean cameBack = false;
        for (int guard = 0; guard < 12 && page().state() != StudyPage.State.SUMMARY; guard++) {
            SessionItem item = page().currentItem();
            assertThat(item).isNotNull();
            switch (item.type()) {
                case MCQ -> {
                    answer(robot, item, true, Confidence.CERTAIN);
                    assertThat(feedback().rating()).isEqualTo(Rating.EASY);
                    assertThat(verdict()).isEqualTo("Correct");
                    assertThat(nextReview()).startsWith("You'll see this again ").isNotEqualTo(LATER_IN_SESSION);
                }
                case TRUE_FALSE -> {
                    if (item.itemId().equals(wrongItem)) {
                        cameBack = true;
                        assertThat(page().root().lookup(".study-tag.comeback")).as("marked as a comeback").isNotNull();
                        answer(robot, item, true, Confidence.FAIRLY_SURE);
                        assertThat(feedback().correct()).isTrue();
                    } else {
                        answer(robot, item, false, Confidence.FAIRLY_SURE);
                        wrongItem = item.itemId();
                        assertThat(feedback().rating()).isEqualTo(Rating.AGAIN);
                        assertThat(verdict()).isEqualTo("Not quite");
                        assertThat(nextReview()).isEqualTo(LATER_IN_SESSION);
                        assertThat(page().counterText()).endsWith("of 6");
                        assertThat(page().root().lookup(".study-option.revealed")).as("the right answer").isNotNull();
                    }
                }
                case FLASHCARD -> {
                    answer(robot, item, true, null);
                    assertThat(feedback().rating()).isEqualTo(Rating.GOOD);
                    assertThat(verdict()).isEqualTo("Rated Good");
                }
                case SHORT_ANSWER -> {
                    answer(robot, item, false, null);
                    assertThat(feedback().gradedBy()).isEqualTo(GradedBy.AI);
                    assertThat(texts(page().root(), ".graded-by")).containsExactly("Graded by your study assistant");
                    Button dispute = page().feedback().disputeButton();
                    assertThat(dispute).as("a wrong written answer can be disputed").isNotNull();

                    robot.interact(dispute::fire);
                    settle();

                    assertThat(verdict()).isEqualTo("Counted as correct");
                    assertThat(feedback().disputed()).isTrue();
                    assertThat(feedback().rating()).isEqualTo(Rating.GOOD);
                    ItemStats stats = real.workspace().progress().itemStatsFor(item.itemId());
                    assertThat(stats.attempts()).isEqualTo(1);
                    assertThat(stats.correct()).as("the attempt counts as right").isEqualTo(1);
                    assertThat(nextReview()).as("not due again in this session").isNotEqualTo(LATER_IN_SESSION);
                }
                case CLOZE -> {
                    answer(robot, item, true, Confidence.FAIRLY_SURE);
                    assertThat(verdict()).isEqualTo("Correct");
                }
            }
            seen.add(item.type());
            assertThat(page().state()).isEqualTo(StudyPage.State.FEEDBACK);
            assertThat(texts(page().root(), ".explanation")).as("the explanation").isNotEmpty();
            assertThat(sourceLink().getText()).startsWith("From your material");
            assertThat(texts(page().root(), ".next-review")).as("the next review").hasSize(1);
            robot.type(KeyCode.ENTER);
            settle();
        }

        assertThat(seen).containsExactlyInAnyOrder(ItemType.values());
        assertThat(cameBack).as("the wrong answer came back within the session").isTrue();
        assertThat(page().state()).isEqualTo(StudyPage.State.SUMMARY);
        // Five questions; only the true/false one was wrong the first time.
        assertThat(stat("Answered")).isEqualTo("5");
        assertThat(stat("Time")).isEqualTo("<1");
        assertThat(texts(page().root(), ".summary-percent")).containsExactly("80%");
        assertThat(texts(page().root(), ".summary-headline")).containsExactly("Good session");
    }

    // ---- each kind of question --------------------------------------------------------------------------

    @Test
    void numberKeysChooseAnOptionAndEnterChecksIt(FxRobot robot) {
        startStudy(robot, SessionRequest.studyNext());
        advanceTo(robot, ItemType.MCQ);
        ChoiceQuestion question = (ChoiceQuestion) page().question();
        assertThat(page().checkButton().isDisabled()).as("nothing chosen yet").isTrue();

        robot.type(KeyCode.DIGIT2);
        assertThat(question.options().get(1).getPseudoClassStates()).contains(CHOSEN);
        robot.type(KeyCode.DIGIT1);
        assertThat(question.options().get(1).getPseudoClassStates()).doesNotContain(CHOSEN);
        assertThat(question.options().get(0).getPseudoClassStates()).contains(CHOSEN);
        assertThat(page().checkButton().isDisabled()).isFalse();

        robot.type(KeyCode.ENTER);
        settle();

        assertThat(page().state()).isEqualTo(StudyPage.State.FEEDBACK);
        assertThat(question.options()).anyMatch(option -> option.getStyleClass().contains("correct")
                || option.getStyleClass().contains("revealed"));
    }

    @Test
    void trueOrFalseIsAnsweredWithTAndF(FxRobot robot) {
        startStudy(robot, SessionRequest.studyNext());
        advanceTo(robot, ItemType.TRUE_FALSE);
        ChoiceQuestion question = (ChoiceQuestion) page().question();

        robot.type(KeyCode.F);
        assertThat(question.answer()).isEqualTo("false");
        robot.type(KeyCode.T);
        assertThat(question.answer()).isEqualTo("true");
        robot.type(KeyCode.ENTER);
        settle();

        // The statements written from the material are true.
        assertThat(feedback().correct()).isTrue();
        assertThat(question.options().getFirst().getStyleClass()).contains("correct");
    }

    @Test
    void aFilledInBlankIsGradedTolerantly(FxRobot robot) {
        startStudy(robot, SessionRequest.studyNext());
        advanceTo(robot, ItemType.CLOZE);
        ClozeQuestion question = (ClozeQuestion) page().question();
        String answer = answerOf(page().currentItem());

        robot.interact(() -> {
            question.field().setText("  " + answer.toUpperCase(Locale.ROOT) + " ");
            question.field().requestFocus();
        });
        robot.type(KeyCode.ENTER);
        settle();

        assertThat(feedback().correct()).isTrue();
        assertThat(question.field().getStyleClass()).contains("correct");
        assertThat(question.field().isEditable()).isFalse();
    }

    @Test
    void aWrongBlankShowsTheMissingWordsInTheSentence(FxRobot robot) {
        startStudy(robot, SessionRequest.studyNext());
        advanceTo(robot, ItemType.CLOZE);
        SessionItem item = page().currentItem();

        answer(robot, item, false, null);

        assertThat(texts(page().root(), ".cloze-answer")).containsExactly(answerOf(item).strip());
        assertThat(nextReview()).isEqualTo(LATER_IN_SESSION);
    }

    @Test
    void aShortAnswerIsCheckedByTheStudyAssistant(FxRobot robot) {
        startStudy(robot, SessionRequest.studyNext());
        advanceTo(robot, ItemType.SHORT_ANSWER);
        ShortAnswerQuestion question = (ShortAnswerQuestion) page().question();
        assertThat(page().confidence()).as("no confidence for written answers").isNull();

        robot.interact(() -> {
            question.area().setText("First line");
            question.area().requestFocus();
            question.area().end();
        });
        robot.press(KeyCode.SHIFT).type(KeyCode.ENTER).release(KeyCode.SHIFT);
        assertThat(question.area().getText()).as("Shift+Enter starts a new line").isEqualTo("First line\n");
        assertThat(page().state()).isEqualTo(StudyPage.State.QUESTION);

        robot.interact(() -> question.area().setText(answerOf(page().currentItem())));
        robot.type(KeyCode.ENTER);
        settle();

        assertThat(feedback().gradedBy()).isEqualTo(GradedBy.AI);
        assertThat(verdict()).isEqualTo("Correct");
        assertThat(page().feedback().disputeButton()).as("nothing to dispute").isNull();
    }

    @Test
    void checkingWithoutAnAnswerAsksForOne(FxRobot robot) {
        startStudy(robot, SessionRequest.studyNext());
        advanceTo(robot, ItemType.SHORT_ANSWER);
        assertFocusOnTheCard();

        robot.type(KeyCode.ENTER);
        settle();

        assertThat(page().state()).isEqualTo(StudyPage.State.QUESTION);
        assertThat(real.workspace().progress().itemStatsFor(page().currentItem().itemId()).attempts()).isZero();
    }

    @Test
    void aFlashcardTurnsWithSpaceAndEachRatingSaysWhenItComesBack(FxRobot robot) {
        startStudy(robot, SessionRequest.flashcards(courseId, null));
        FlashcardView card = page().flashcard();
        assertThat(card).isNotNull();
        assertThat(card.isTurned()).isFalse();

        robot.type(KeyCode.SPACE);
        settle();

        assertThat(card.isTurned()).isTrue();
        for (Rating rating : Rating.values()) {
            assertThat(card.intervalText(rating)).as(rating.name()).isNotBlank();
        }
        long previewGood = real.workspace().practice().nextReviews(page().currentItem().itemId()).get(Rating.GOOD);

        robot.type(KeyCode.DIGIT3);
        settle();

        assertThat(feedback().rating()).isEqualTo(Rating.GOOD);
        assertThat(feedback().nextDueAt()).isCloseTo(previewGood, within(60_000L));
        assertThat(verdict()).isEqualTo("Rated Good");
    }

    @Test
    void aFlashcardRatedAgainComesBackInTheSession(FxRobot robot) {
        startStudy(robot, SessionRequest.flashcards(courseId, null));
        assertThat(page().counterText()).isEqualTo("1 of 1");

        robot.type(KeyCode.SPACE);
        settle();
        robot.type(KeyCode.DIGIT1);
        settle();

        assertThat(feedback().rating()).isEqualTo(Rating.AGAIN);
        assertThat(page().counterText()).isEqualTo("1 of 2");
        robot.type(KeyCode.ENTER);
        settle();

        assertThat(page().state()).isEqualTo(StudyPage.State.QUESTION);
        assertThat(page().counterText()).isEqualTo("2 of 2");
        assertThat(page().root().lookup(".study-tag.comeback")).isNotNull();
    }

    // ---- hints, the source page and leaving ---------------------------------------------------------------

    @Test
    void aHintRulesOptionsOutAndIsCounted(FxRobot robot) {
        startStudy(robot, SessionRequest.studyNext());
        advanceTo(robot, ItemType.MCQ);

        robot.type(KeyCode.H);
        settle();

        assertThat(page().hintsUsed()).isEqualTo(1);
        assertThat(page().root().lookupAll(".study-option.ruled-out")).isNotEmpty();
        assertThat(page().root().lookup(".hint-callout")).isNotNull();
        assertThat(page().hintButton().getParent()).as("one hint per question").isNull();

        robot.type(KeyCode.H);
        settle();
        assertThat(page().hintsUsed()).isEqualTo(1);
    }

    @Test
    void fromYourMaterialOpensThePageBesideTheSession(FxRobot robot) {
        startStudy(robot, SessionRequest.studyNext());
        advanceTo(robot, ItemType.MCQ);
        answer(robot, page().currentItem(), true, null);

        robot.interact(sourceLink()::fire);
        settle();

        assertThat(page().isSourceOpen()).isTrue();
        SourceSheet sheet = page().sourceSheet();
        assertThat(sheet.lookup(".source-paper")).as("the page").isNotNull();
        assertThat(sheet.hasHighlight()).as("the passage is highlighted").isTrue();

        robot.type(KeyCode.ESCAPE);
        settle();

        assertThat(page().isSourceOpen()).isFalse();
        assertThat(page().state()).isEqualTo(StudyPage.State.FEEDBACK);
        assertThat(window.navigator().current()).isInstanceOf(Destination.Study.class);
    }

    @Test
    void leavingAfterAnAnswerSaysTheAnswersAreSaved(FxRobot robot) {
        startStudy(robot, SessionRequest.studyNext());
        answer(robot, page().currentItem(), true, null);

        robot.type(KeyCode.ESCAPE);
        settle();
        assertThat(texts(window.getScene().getRoot(), ".modal-message").getFirst())
                .startsWith("Your answers so far are saved.");
        robot.clickOn((Node) window.getScene().getRoot().lookup(".modal .modal-cancel"));
        settle();
        assertThat(window.navigator().current()).isInstanceOf(Destination.Study.class);
        assertThat(page().state()).isEqualTo(StudyPage.State.FEEDBACK);

        robot.type(KeyCode.ESCAPE);
        settle();
        Button leave = (Button) window.getScene().getRoot().lookup(".modal .modal-confirm");
        assertThat(leave.getText()).isEqualTo("Leave session");
        robot.clickOn(leave);
        settle();

        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
    }

    @Test
    void leavingBeforeAnsweringGoesStraightBack(FxRobot robot) {
        startStudy(robot, SessionRequest.studyNext());

        robot.type(KeyCode.ESCAPE);
        settle();

        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
    }

    // ---- the ends of a session ------------------------------------------------------------------------

    @Test
    void anEndedSessionOffersANewOne(FxRobot robot) {
        startStudy(robot, SessionRequest.studyNext());
        String first = page().session().id();
        real.workspace().practice().finish(first);

        answerAnyway(robot, page().currentItem());

        assertThat(page().state()).isEqualTo(StudyPage.State.ENDED);
        assertThat(texts(page().root(), ".empty-state-title")).containsExactly("This study session has ended");

        robot.clickOn(button(page().root(), "Start a new session"));
        settle();

        assertThat(page().state()).isEqualTo(StudyPage.State.QUESTION);
        assertThat(page().session().id()).isNotEqualTo(first);
    }

    @Test
    void nothingToReviewSaysSoAndLeadsHome(FxRobot robot) {
        Course empty = real.workspace().courses().create(CourseDraft.titled("Empty course"));
        real.workspace().courses().enrol(empty.id());

        robot.interact(() -> window.navigator().navigate(new Destination.Study(SessionRequest.studyNext(empty.id()))));
        settle();

        assertThat(page().state()).isEqualTo(StudyPage.State.EMPTY);
        assertThat(texts(page().root(), ".empty-state-title")).containsExactly("Nothing to review right now");
        robot.type(KeyCode.ENTER);
        settle();
        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
    }

    @Test
    void studyMoreStartsAgainOnTheSamePage(FxRobot robot) {
        startStudy(robot, SessionRequest.studyNext().withSize(1));
        SessionItem first = page().currentItem();
        answer(robot, first, true, Confidence.CERTAIN);
        robot.type(KeyCode.ENTER);
        settle();
        assertThat(page().state()).isEqualTo(StudyPage.State.SUMMARY);
        assertThat(stat("Answered")).isEqualTo("1");
        assertThat(texts(page().root(), ".summary-ready")).containsExactly("4 more questions are ready now.");
        StudyPage before = page();

        robot.type(KeyCode.ENTER);
        settle();

        assertThat(page()).isSameAs(before);
        assertThat(page().state()).isEqualTo(StudyPage.State.QUESTION);
        assertThat(page().currentItem().itemId()).isNotEqualTo(first.itemId());
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** A course with one approved, generated question of every kind, enrolled. */
    private String seedCourse() {
        Workspace ws = real.workspace();
        Course course = ws.courses().create(CourseDraft.titled("Cell Biology"));
        CourseModule module = ws.courses().addModule(course.id(), "Cells");
        DocumentSummary document = ws.documents().importPdf(course.id(), module.id(), cellsPdf());
        real.awaitSettled(document.id());
        // One request for every kind: the questions come from different passages, so none is a near-duplicate.
        JobUpdate job = ws.generation().generate(new GenerationRequest(course.id(), module.id(),
                EnumSet.allOf(ItemType.class), ItemType.values().length, null, Set.of()));
        RealWorkspaces.await(() -> {
            JobStatus status = ws.jobs().find(job.jobId()).orElseThrow().status();
            return status == JobStatus.DONE || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
        }, Duration.ofSeconds(60), "questions to be written");
        assertThat(ws.jobs().find(job.jobId()).orElseThrow().status()).isEqualTo(JobStatus.DONE);
        List<ItemSummary> drafts = ws.items().list(ItemFilter.reviewQueue(course.id()));
        assertThat(drafts).extracting(summary -> summary.item().type()).containsExactlyInAnyOrder(ItemType.values());
        ws.items().approveAll(drafts.stream().map(summary -> summary.item().id()).toList());
        ws.courses().enrol(course.id());
        return course.id();
    }

    /** Six pages, each opening with a plain statement the questions are written from. */
    private Path cellsPdf() {
        String[][] pages = {
                {"Membranes", "The cell membrane controls which substances enter and leave the cell."},
                {"Mitochondria", "Mitochondria release energy from glucose during aerobic respiration."},
                {"Ribosomes", "Ribosomes build proteins by joining amino acids in the order messenger RNA sets."},
                {"The nucleus", "The nucleus stores the genetic material of the cell as chromosomes."},
                {"Chloroplasts", "Chloroplasts capture light energy and use it to make glucose in plant cells."},
                {"Enzymes", "Enzymes speed up chemical reactions without being used up themselves."}};
        TestPdf.Builder pdf = TestPdf.builder().title("Cells").pageNumbers();
        for (int i = 0; i < pages.length; i++) {
            if (i > 0) {
                pdf.pageBreak();
            }
            pdf.heading(pages[i][0])
                    .paragraph(pages[i][1] + " " + TestPdf.sentences(i + 1, 6))
                    .paragraph(TestPdf.sentences((i + 1) * 31L, 5));
        }
        try {
            return pdf.write(dir.resolve("cells.pdf"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private StudyPage page() {
        return (StudyPage) window.navigator().currentView();
    }

    private Feedback feedback() {
        return page().feedback().feedback();
    }

    private String verdict() {
        return texts(page().feedback().root(), ".verdict").getFirst();
    }

    private String nextReview() {
        return texts(page().feedback().root(), ".next-review").getFirst();
    }

    private Button sourceLink() {
        return (Button) page().feedback().root().lookup(".source-link");
    }

    private String stat(String label) {
        return page().root().lookupAll(".stat-tile").stream().map(StatTile.class::cast)
                .filter(tile -> tile.labelNode().getText().equals(label)).findFirst().orElseThrow().valueText();
    }

    private void startStudy(FxRobot robot, SessionRequest request) {
        robot.interact(() -> window.navigator().navigate(new Destination.Study(request)));
        settle();
        assertThat(page().state()).isEqualTo(StudyPage.State.QUESTION);
        assertFocusOnTheCard();
    }

    /** The question's answer (or the flashcard) has the keyboard focus, so Enter never presses "Leave session". */
    private void assertFocusOnTheCard() {
        Node focused = window.getScene().getFocusOwner();
        Node card = page().flashcard() != null ? page().flashcard().initialFocus() : page().question().initialFocus();
        Node expected = card != null ? card : page().root();
        assertThat(focused).as(() -> "focus on the card, not " + focused).isSameAs(expected);
    }

    /** Answers right and moves on until a question of {@code type} is on screen. */
    private void advanceTo(FxRobot robot, ItemType type) {
        for (int guard = 0; guard < 12; guard++) {
            SessionItem item = page().currentItem();
            if (page().state() == StudyPage.State.QUESTION && item.type() == type) {
                return;
            }
            answer(robot, item, true, null);
            robot.type(KeyCode.ENTER);
            settle();
        }
        throw new AssertionError("No " + type + " question in the session");
    }

    private String answerOf(SessionItem item) {
        return real.workspace().items().get(item.itemId()).item().answer();
    }

    /** Answers the question on screen through the keyboard, rightly or wrongly, and waits for the feedback. */
    private void answer(FxRobot robot, SessionItem item, boolean right, Confidence confidence) {
        give(robot, item, right, confidence);
        assertThat(page().state()).as("answered " + item.type()).isEqualTo(StudyPage.State.FEEDBACK);
    }

    /** Answers the question on screen whatever comes of it. */
    private void answerAnyway(FxRobot robot, SessionItem item) {
        give(robot, item, true, null);
    }

    private void give(FxRobot robot, SessionItem item, boolean right, Confidence confidence) {
        String answer = answerOf(item);
        switch (item.type()) {
            case MCQ -> {
                int index = -1;
                for (int i = 0; i < item.choices().size() && index < 0; i++) {
                    if (item.choices().get(i).strip().equalsIgnoreCase(answer.strip()) == right) {
                        index = i;
                    }
                }
                robot.type(KeyCode.getKeyCode(Integer.toString(index + 1)));
                chooseConfidence(robot, confidence);
                robot.type(KeyCode.ENTER);
            }
            case TRUE_FALSE -> {
                boolean truth = answer.strip().toLowerCase(Locale.ROOT).startsWith("t");
                robot.type(truth == right ? KeyCode.T : KeyCode.F);
                chooseConfidence(robot, confidence);
                robot.type(KeyCode.ENTER);
            }
            case CLOZE, SHORT_ANSWER -> {
                TextInputControl input = (TextInputControl) page().root().lookup(".study-input");
                robot.interact(() -> {
                    input.setText(right ? answer : "nothing like it at all");
                    input.requestFocus();
                });
                robot.type(KeyCode.ENTER);
            }
            case FLASHCARD -> {
                robot.type(KeyCode.SPACE);
                settle();
                robot.type(right ? KeyCode.DIGIT3 : KeyCode.DIGIT1);
            }
        }
        settle();
    }

    private void chooseConfidence(FxRobot robot, Confidence confidence) {
        if (confidence != null) {
            robot.clickOn(page().confidence().chip(confidence));
        }
    }

    private static Button button(Node root, String text) {
        return root.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> text.equals(button.getText()) && button.isVisible()).findFirst()
                .orElseThrow(() -> new AssertionError("No button \"" + text + "\""));
    }

    private static List<String> texts(Node root, String query) {
        return root.lookupAll(query).stream().filter(Node::isVisible).filter(Labeled.class::isInstance)
                .map(node -> ((Labeled) node).getText()).toList();
    }

    private static void settle() {
        for (int round = 0; round < 3; round++) {
            UiExecutors.awaitIdle(Duration.ofSeconds(20));
            WaitForAsyncUtils.waitForFxEvents();
        }
    }
}
