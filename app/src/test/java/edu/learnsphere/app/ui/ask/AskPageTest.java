package edu.learnsphere.app.ui.ask;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.app.ui.components.CitationChip;
import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.MessageStatus;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.service.ThreadSummary;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.pdf.testing.TestPdf;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Ask over the real services (SQLite, the pdf module, Lucene) with the AI fakes: a course with one PDF whose
 * page 4 is about mitochondria. Answers stream one word every {@value #WORD_DELAY_MS} ms (every
 * {@value #SLOW_WORD_DELAY_MS} ms in tests tagged {@value #SLOW_ANSWERS}).
 */
@ExtendWith(ApplicationExtension.class)
class AskPageTest {

    private static final long WORD_DELAY_MS = 60;
    /** Tests that stop an answer midway get slow answers, so the answer is still on its way however busy the machine. */
    private static final String SLOW_ANSWERS = "slow-answers";
    private static final long SLOW_WORD_DELAY_MS = 400;
    private static final String COVERED = "What do mitochondria produce?";
    private static final String NOT_COVERED = "Who painted the Mona Lisa?";
    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path dir;

    private Stage stage;
    private RealWorkspaces real;
    private Workspace workspace;
    private String courseId;
    private MainWindow window;
    private Theme theme;

    @Start
    void start(Stage stage) {
        this.stage = stage;
    }

    @BeforeEach
    void setUp(FxRobot robot, TestInfo test) throws Exception {
        long delay = test.getTags().contains(SLOW_ANSWERS) ? SLOW_WORD_DELAY_MS : WORD_DELAY_MS;
        real = RealWorkspaces.open(dir, FakeAi.create(Duration.ofMillis(delay)), Clock.systemDefaultZone());
        workspace = real.workspace();
        courseId = workspace.courses().create(CourseDraft.titled("Cell Biology")).id();
        Path pdf = cellsPdf(dir.resolve("cells.pdf"));
        real.awaitSettled(workspace.documents().importPdf(courseId, null, pdf).id());
        robot.interact(() -> {
            Typography.load();
            window = new MainWindow(real.context());
            Scene scene = new Scene(window, 1360, 860);
            theme = Theme.install(scene, ThemePreference.LIGHT);
            stage.setScene(scene);
            stage.show();
        });
        settle();
    }

    @AfterEach
    void tearDown(FxRobot robot) {
        robot.interact(() -> {
            window.dispose();
            theme.dispose();
        });
        real.close();
    }

    @Test
    void streamsTheAnswerThenShowsItWithPageChipsAndSources(FxRobot robot) {
        open(robot, Destination.Ask.newThread(courseId));
        AskPage page = page();
        assertThat(fx(() -> page.exchanges())).isEmpty();
        assertThat(robot.lookup(".ask-empty").tryQuery()).as("the new conversation invites a question").isPresent();

        robot.interact(() -> page.composer().input().setText("What do mitochondria"));
        robot.clickOn(page.composer().input());
        robot.press(KeyCode.SHIFT).type(KeyCode.ENTER).release(KeyCode.SHIFT);
        assertThat(fx(() -> page.composer().input().getText())).as("Shift+Enter starts a new line")
                .isEqualTo("What do mitochondria\n");
        robot.interact(() -> page.composer().input().setText(COVERED));
        robot.type(KeyCode.ENTER);

        ExchangeView exchange = fx(() -> page.exchanges().getFirst());
        waitUntil(() -> exchange.state() == ExchangeView.State.STREAMING && !exchange.streamedText().isBlank(),
                "the answer to stream");
        assertThat(fx(() -> page.composer().input().isDisabled())).as("one question at a time").isTrue();
        assertThat(fx(() -> page.composer().stopButton().isVisible())).isTrue();
        assertThat(fx(() -> page.isAnswering())).isTrue();
        assertThat(fx(() -> exchange.lookup(".citation-chip"))).as("chips come with the final answer").isNull();

        waitUntil(() -> exchange.state() == ExchangeView.State.ANSWERED, "the final answer");
        settle();
        List<Citation> citations = fx(exchange::citations);
        assertThat(citations).isNotEmpty();
        Citation cited = citations.getFirst();
        assertThat(cited.pageStart()).isEqualTo(4);
        assertThat(chipTexts(exchange)).contains("p. 4");
        assertThat(fx(() -> exchange.text())).contains("Mitochondria produce").contains("[1]");
        String documentTitle = workspace.documents().list(courseId).getFirst().title();
        assertThat(fx(() -> labels(exchange, ".ask-source-title"))).containsExactly(documentTitle);
        assertThat(fx(() -> labels(exchange, ".ask-source-section"))).contains("Mitochondria");
        assertThat(fx(() -> page.composer().input().isDisabled())).isFalse();
        assertThat(fx(() -> page.composer().input().getText())).isEmpty();

        String threadId = fx(page::threadId);
        assertThat(threadId).isNotNull();
        assertThat(fx(() -> window.navigator().current())).isEqualTo(new Destination.Ask(courseId, threadId, null));
        waitUntil(() -> page.threadList().shown().stream().anyMatch(t -> t.title().equals(COVERED)),
                "the conversation to be listed under its question");
        assertThat(fx(page::headingText)).isEqualTo(COVERED);
    }

    /** ACCEPTANCE.md T03, steps 2 to 5 in one conversation, then Home. */
    @Test
    void aGreetingGetsAReplyFromTheAssistant(FxRobot robot) {
        open(robot, Destination.Ask.newThread(courseId));
        AskPage page = page();
        ExchangeView exchange = askAndWait(robot, page, "hello", ExchangeView.State.ANSWERED);

        assertThat(fx(exchange::text)).isEqualTo("Hi! What would you like to go over in Cell Biology?");
        assertThat(workspace.ask().messages(fx(page::threadId)).getLast().status()).isEqualTo(MessageStatus.COMPLETE);
    }

    @Test
    @Tag(SLOW_ANSWERS)
    void acceptanceT03AskingAQuestionGivesCitedAnswers(FxRobot robot) {
        open(robot, Destination.Ask.newThread(courseId));
        AskPage page = page();

        // 2. A question answered on page 4: it streams, then the final text brings the chip.
        robot.interact(() -> page.composer().input().setText(COVERED));
        robot.clickOn(page.composer().input()).type(KeyCode.ENTER);
        ExchangeView answered = fx(() -> page.exchanges().getFirst());
        waitUntil(() -> answered.state() == ExchangeView.State.STREAMING && !answered.streamedText().isBlank(),
                "the answer to stream");
        waitUntil(() -> answered.state() == ExchangeView.State.ANSWERED, "the final answer");
        settle();
        assertThat(chipTexts(answered)).contains("p. 4");

        // 3. The first chip opens page 4 with the passage marked.
        robot.clickOn(fx(() -> answered.lookup(".citation-chip")));
        waitUntil(() -> page.viewer().hasPage(), "the cited page");
        assertThat(fx(() -> page.viewer().page())).isEqualTo(4);
        assertThat(fx(() -> page.viewer().highlightCount())).isPositive();

        // 4. Not covered: the assistant says so, with nothing cited.
        ExchangeView notCovered = askAndWait(robot, page, NOT_COVERED, ExchangeView.State.ANSWERED);
        assertThat(fx(notCovered::text)).isEqualTo("Your course material doesn't cover that.");
        assertThat(fx(() -> notCovered.lookup(".citation-chip"))).isNull();

        // 5. Cancelled while streaming: stored as cancelled.
        robot.interact(() -> page.composer().input().setText("How do ribosomes build proteins?"));
        robot.clickOn(page.composer().input()).type(KeyCode.ENTER);
        ExchangeView stopped = fx(() -> page.exchanges().getLast());
        waitUntil(() -> stopped.streamedText().split(" ").length >= 2, "the third answer to stream");
        // Fired rather than clicked: a pointer move can outlast a short answer.
        robot.interact(() -> page.composer().stopButton().fire());
        waitUntil(() -> stopped.state() == ExchangeView.State.STOPPED, "the answer to stop");
        String threadId = fx(page::threadId);
        RealWorkspaces.await(() -> workspace.ask().messages(threadId).getLast().status() == MessageStatus.CANCELLED,
                WAIT, "the stopped answer to be stored");
        assertThat(workspace.ask().messages(threadId).stream().map(ChatMessage::status).toList())
                .containsExactly(MessageStatus.COMPLETE, MessageStatus.COMPLETE, MessageStatus.COMPLETE,
                        MessageStatus.COMPLETE, MessageStatus.COMPLETE, MessageStatus.CANCELLED);

        // The conversation is among the recent questions on Home.
        assertThat(workspace.ask().recentQuestions(5)).anyMatch(q -> q.threadId().equals(threadId));
        open(robot, Destination.HOME);
        String recent = workspace.ask().recentQuestions(5).stream().filter(q -> q.threadId().equals(threadId))
                .findFirst().orElseThrow().question();
        assertThat(robot.lookup(node -> node instanceof javafx.scene.control.Labeled labeled
                && labeled.getText() != null && labeled.getText().contains(recent)).tryQuery()).isPresent();
    }

    @Test
    void aPageChipOpensItsPageWithThePassageMarked(FxRobot robot) {
        open(robot, Destination.Ask.newThread(courseId));
        AskPage page = page();
        assertThat(fx(page::isSourceOpen)).as("the conversation takes the width").isFalse();
        ExchangeView exchange = askAndWait(robot, page, COVERED, ExchangeView.State.ANSWERED);

        CitationChip chip = fx(() -> (CitationChip) exchange.lookup(".citation-chip"));
        robot.clickOn(chip);
        SourceViewer viewer = page.viewer();
        waitUntil(() -> viewer.hasPage(), "the cited page");

        assertThat(fx(page::isSourceOpen)).isTrue();
        assertThat(fx(viewer::page)).isEqualTo(4);
        assertThat(fx(viewer::pageCount)).isEqualTo(6);
        assertThat(fx(viewer::highlightCount)).as("the passage is marked").isPositive();
        assertThat(fx(viewer::noteText)).as("a text page needs no note").isEmpty();
        assertThat(fx(() -> chip.getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("active"))))
                .isTrue();
        Citation cited = fx(chip::citation);
        assertThat(fx(() -> window.navigator().current()))
                .isEqualTo(new Destination.Ask(courseId, fx(page::threadId), cited));

        robot.clickOn(robot.lookup(".ask-source .icon-button").match(n -> "Next page".equals(n.getAccessibleText()))
                .queryButton());
        waitUntil(() -> viewer.page() == 5 && viewer.hasPage(), "the next page");
        assertThat(fx(viewer::highlightCount)).as("only the cited page is marked").isZero();

        robot.clickOn(robot.lookup(".ask-source .icon-button").match(n -> "Close sources".equals(n.getAccessibleText()))
                .queryButton());
        assertThat(fx(page::isSourceOpen)).isFalse();
        assertThat(fx(() -> window.navigator().current()))
                .isEqualTo(new Destination.Ask(courseId, fx(page::threadId), null));
    }

    @Test
    void opensStraightOntoACitation(FxRobot robot) {
        open(robot, Destination.Ask.newThread(courseId));
        ExchangeView exchange = askAndWait(robot, page(), COVERED, ExchangeView.State.ANSWERED);
        Citation cited = fx(() -> exchange.citations().getFirst());
        String threadId = fx(() -> page().threadId());

        open(robot, new Destination.Ask(courseId, threadId, cited));
        AskPage page = page();
        waitUntil(() -> page.viewer().hasPage(), "the cited page");

        assertThat(fx(page::isSourceOpen)).isTrue();
        assertThat(fx(() -> page.viewer().citation())).isEqualTo(cited);
        assertThat(fx(() -> page.viewer().highlightCount())).isPositive();
        assertThat(fx(() -> page.exchanges())).hasSize(1);
    }

    @Test
    void aQuestionTheMaterialDoesNotCoverIsAnsweredSayingSo(FxRobot robot) {
        open(robot, Destination.Ask.newThread(courseId));
        AskPage page = page();
        ExchangeView exchange = askAndWait(robot, page, NOT_COVERED, ExchangeView.State.ANSWERED);

        assertThat(fx(exchange::text)).isEqualTo("Your course material doesn't cover that.");
        assertThat(fx(() -> exchange.lookup(".citation-chip"))).isNull();
        assertThat(workspace.ask().messages(fx(page::threadId)).getLast().status()).isEqualTo(MessageStatus.COMPLETE);
    }

    @Test
    @Tag(SLOW_ANSWERS)
    void stoppingAnAnswerMidwayKeepsItAsStopped(FxRobot robot) {
        open(robot, Destination.Ask.newThread(courseId));
        AskPage page = page();
        robot.interact(() -> page.composer().input().setText(COVERED));
        robot.clickOn(page.composer().input()).type(KeyCode.ENTER);
        ExchangeView exchange = fx(() -> page.exchanges().getFirst());
        waitUntil(() -> exchange.streamedText().split(" ").length >= 2, "a few words");

        // Fired rather than clicked: a pointer move can outlast a short answer.
        robot.interact(() -> page.composer().stopButton().fire());
        waitUntil(() -> exchange.state() == ExchangeView.State.STOPPED, "the answer to stop");
        settle();

        assertThat(fx(() -> labels(exchange, ".ask-stopped-note"))).containsExactly("You stopped this answer.");
        assertThat(fx(page::isAnswering)).isFalse();
        assertThat(fx(() -> page.composer().input().isDisabled())).isFalse();
        List<ChatMessage> stored = workspace.ask().messages(fx(page::threadId));
        assertThat(stored).hasSize(2);
        assertThat(stored.getLast().status()).isEqualTo(MessageStatus.CANCELLED);
        assertThat(COVERED).isEqualTo(stored.getFirst().content());
    }

    @Test
    @Tag(SLOW_ANSWERS)
    void leavingTheScreenStopsTheAnswerOnItsWay(FxRobot robot) {
        open(robot, Destination.Ask.newThread(courseId));
        AskPage page = page();
        robot.interact(() -> page.composer().input().setText(COVERED));
        robot.clickOn(page.composer().input()).type(KeyCode.ENTER);
        ExchangeView exchange = fx(() -> page.exchanges().getFirst());
        waitUntil(() -> !exchange.streamedText().isBlank(), "the answer to stream");
        String threadId = fx(page::threadId);

        robot.interact(() -> window.navigator().navigate(Destination.HOME));

        RealWorkspaces.await(() -> workspace.ask().messages(threadId).getLast().status() == MessageStatus.CANCELLED,
                WAIT, "the answer to be stored as stopped");
    }

    @Test
    void aQuestionWaitsWhileTheStudyAssistantGetsReady(FxRobot robot) {
        open(robot, Destination.Ask.newThread(courseId));
        AskPage page = page();
        real.fakeAi().runtime().setStatus(new AiStatus(AiStatus.State.STARTING, ModelChoice.CHAT_4B, null));
        waitUntil(() -> page.composer().isStatusShown(), "the readiness line");
        assertThat(fx(() -> page.composer().statusText())).isEqualTo("Getting your study assistant ready");

        robot.interact(() -> page.composer().input().setText(COVERED));
        robot.clickOn(page.composer().input()).type(KeyCode.ENTER);
        ExchangeView exchange = fx(() -> page.exchanges().getFirst());
        assertThat(fx(() -> labels(exchange, ".ask-phase"))).containsExactly(AskPage.WAITING);
        assertThat(fx(page::isAnswering)).isTrue();

        real.fakeAi().runtime().setStatus(new AiStatus(AiStatus.State.READY, ModelChoice.CHAT_4B, null));
        waitUntil(() -> exchange.state() == ExchangeView.State.ANSWERED, "the answer once the assistant is ready");
        assertThat(fx(() -> page.composer().isStatusShown())).isFalse();
    }

    @Test
    void anAssistantThatCannotStartGivesOneSentenceAndTryAgain(FxRobot robot) {
        open(robot, Destination.Ask.newThread(courseId));
        AskPage page = page();
        real.fakeAi().runtime().setStatus(new AiStatus(AiStatus.State.FAILED, ModelChoice.CHAT_4B, "crashed"));
        waitUntil(() -> page.composer().isStatusShown(), "the readiness line");

        ExchangeView exchange = askAndWait(robot, page, COVERED, ExchangeView.State.FAILED);
        String sentence = fx(() -> labels(exchange, ".ask-error-text")).getFirst();
        assertThat(sentence).doesNotContain("crashed").endsWith(".");

        real.fakeAi().runtime().setStatus(new AiStatus(AiStatus.State.READY, ModelChoice.CHAT_4B, null));
        waitUntil(() -> !page.composer().isStatusShown(), "the assistant to be ready");
        robot.clickOn(fx(() -> buttons(exchange).stream().filter(b -> b.getText().equals("Try again")).findFirst()
                .orElseThrow()));
        waitUntil(() -> page.exchanges().size() == 1 && page.exchanges().getFirst().state() == ExchangeView.State.ANSWERED,
                "the answer on trying again");
    }

    @Test
    void renamesAndDeletesConversationsFromTheList(FxRobot robot) {
        String older = workspace.ask().createThread(courseId, "Membranes").id();
        WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
        String current = workspace.ask().createThread(courseId, "Energy").id();
        open(robot, new Destination.Ask(courseId, current, null));
        AskPage page = page();
        assertThat(fx(() -> page.threadList().shown().stream().map(ThreadSummary::id).toList()))
                .as("newest first").containsExactly(current, older);

        Node row = fx(() -> page.threadList().rowOf(older));
        robot.clickOn(row);
        waitUntil(() -> older.equals(page.threadId()), "the conversation to open");
        robot.type(KeyCode.F2);
        TextField editor = robot.lookup(".ask-thread-editor").queryAs(TextField.class);
        robot.interact(() -> editor.setText("Cell membranes"));
        robot.type(KeyCode.ENTER);
        RealWorkspaces.await(() -> workspace.ask().threads(courseId).stream()
                .anyMatch(t -> t.id().equals(older) && t.title().equals("Cell membranes")), WAIT, "the rename");
        waitUntil(() -> "Cell membranes".equals(page.headingText()), "the heading to follow the rename");

        robot.clickOn(fx(() -> page.threadList().rowOf(older)));
        robot.type(KeyCode.DELETE);
        robot.clickOn(robot.lookup(".modal-confirm").queryButton());
        RealWorkspaces.await(() -> workspace.ask().threads(courseId).stream().noneMatch(t -> t.id().equals(older)),
                WAIT, "the delete");
        waitUntil(() -> page.threadList().shown().size() == 1, "the list to drop the conversation");
        assertThat(fx(page::threadId)).as("a new conversation replaces the deleted one").isNull();
        assertThat(fx(page::headingText)).isEqualTo("New conversation");
        assertThat(fx(() -> window.navigator().current())).isEqualTo(Destination.Ask.newThread(courseId));
    }

    @Test
    void shortcutStartsANewConversationAndTheListCanStepAside(FxRobot robot) {
        String thread = workspace.ask().createThread(courseId, "Energy").id();
        open(robot, new Destination.Ask(courseId, thread, null));
        AskPage page = page();
        assertThat(fx(page::isThreadListShown)).isTrue();

        robot.clickOn(page.composer().input());
        robot.press(KeyCode.SHORTCUT).type(KeyCode.N).release(KeyCode.SHORTCUT);
        assertThat(fx(page::threadId)).isNull();
        assertThat(fx(() -> window.navigator().current())).isEqualTo(Destination.Ask.newThread(courseId));

        Button toggle = robot.lookup(".ask-head-bar .icon-button")
                .match(n -> "Hide conversations".equals(((Button) n).getAccessibleText())).queryButton();
        robot.clickOn(toggle);
        assertThat(fx(page::isThreadListShown)).isFalse();
        assertThat(fx(toggle::getAccessibleText)).isEqualTo("Show conversations");
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** Six headed pages; only page 4 says what mitochondria produce. */
    private static Path cellsPdf(Path file) throws Exception {
        String[][] pages = {
                {"The cell membrane", "The membrane is a thin double layer of lipids around every cell. It lets water through and keeps larger molecules out."},
                {"The nucleus", "The nucleus holds the chromosomes and controls which genes are read."},
                {"Ribosomes", "Ribosomes read messenger RNA and join amino acids into proteins."},
                {"Mitochondria", "Mitochondria produce most of the cell's ATP through cellular respiration. They turn sugar and oxygen into usable energy."},
                {"Chloroplasts", "Chloroplasts capture light and fix carbon dioxide into sugar in plant cells."},
                {"The cytoskeleton", "The cytoskeleton gives the cell its shape and moves vesicles along its fibres."}};
        TestPdf.Builder pdf = TestPdf.builder().title("Cells").pageNumbers();
        for (int i = 0; i < pages.length; i++) {
            if (i > 0) {
                pdf.pageBreak();
            }
            pdf.heading(pages[i][0]).paragraph(pages[i][1]).paragraph(TestPdf.sentences(i + 7L, 7));
        }
        return pdf.write(file);
    }

    private void open(FxRobot robot, Destination destination) {
        robot.interact(() -> window.navigator().reset(destination));
        settle();
    }

    private AskPage page() {
        return fx(() -> (AskPage) window.navigator().currentView());
    }

    private ExchangeView askAndWait(FxRobot robot, AskPage page, String question, ExchangeView.State outcome) {
        robot.interact(() -> page.composer().input().setText(question));
        robot.clickOn(page.composer().input()).type(KeyCode.ENTER);
        ExchangeView exchange = fx(() -> page.exchanges().getLast());
        waitUntil(() -> exchange.state() == outcome, "the answer (" + outcome + ")");
        settle();
        return exchange;
    }

    private static List<String> chipTexts(Node node) {
        return fx(() -> node.lookupAll(".citation-chip").stream().map(n -> ((CitationChip) n).getText()).toList());
    }

    private static List<String> labels(Node node, String selector) {
        return node.lookupAll(selector).stream().map(n -> ((Label) n).getText()).toList();
    }

    private static List<Button> buttons(Node node) {
        return node.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
    }

    /** Background loads and the FX updates they queue. */
    private static void settle() {
        for (int round = 0; round < 2; round++) {
            UiExecutors.awaitIdle(WAIT);
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    private static <T> T fx(Callable<T> read) {
        try {
            return WaitForAsyncUtils.asyncFx(read).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Reading the screen failed", e);
        }
    }

    private static void waitUntil(Callable<Boolean> condition, String what) {
        Instant end = Instant.now().plus(WAIT);
        while (!fx(condition)) {
            if (Instant.now().isAfter(end)) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
        }
    }
}
