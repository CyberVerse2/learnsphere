package edu.learnsphere.app.ui.ask;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.View;
import edu.learnsphere.app.ui.components.CitationChip;
import edu.learnsphere.app.ui.components.ConfirmDialog;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.AssistantStatusChanged;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.ChatRole;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.service.Answer;
import edu.learnsphere.core.service.AnswerListener;
import edu.learnsphere.core.service.AskPhase;
import edu.learnsphere.core.service.AssistantState;
import edu.learnsphere.core.service.AssistantStatus;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.ModuleSummary;
import edu.learnsphere.core.service.SourcePassage;
import edu.learnsphere.core.service.ThreadSummary;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.util.Cancellable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * Asking questions about one course. Three columns: the course's conversations (newest first, with New
 * conversation, rename and delete), the conversation itself, set as questions and answers in reading type
 * with page chips where an answer cites the material, and the source viewer, which opens a cited page with
 * the passage marked. Closing the viewer gives the conversation the width; on narrower windows the list of
 * conversations steps aside for the viewer and comes back with its button.
 *
 * <p>Asking: Enter asks (Shift+Enter starts a new line), the progress line says what is happening in plain
 * words, words stream in, and the final answer replaces them with its citations and sources. One question is
 * answered at a time: the box is disabled and Stop (or Escape) stops the answer, which is kept as stopped.
 * While the study assistant is still getting ready, a question waits for it instead of failing. Opening
 * another conversation leaves an answer on its way running; leaving the screen stops it.
 *
 * <p>{@link Destination.Ask#focus()} opens straight onto a citation. Shortcut+N starts a new conversation.
 */
public final class AskPage implements View {

    /** Property key holding the citation of a source row (for marking the open one). */
    static final String CITATION_KEY = AskPage.class.getName() + ".citation";

    static final String WAITING = "Getting your study assistant ready";
    static final String SEARCHING = "Looking through your material";
    static final String WRITING = "Writing the answer";
    static final String NOT_ENROLLED = "Enrol in this course to ask questions about it.";

    /** Widest the questions and answers are set, for a comfortable line length. */
    static final double COLUMN_MAX = 720;
    /** Side padding of the questions and answers (ask.css), which the question box lines up with. */
    private static final double COLUMN_GUTTER = 32;
    private static final int MAX_TOPICS = 5;
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final PseudoClass ON = PseudoClass.getPseudoClass("on");
    private static final KeyCombination NEW_CONVERSATION = new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN);
    private static final String STYLESHEET = Objects.requireNonNull(AskPage.class.getResource("ask.css"),
            "ask.css").toExternalForm();

    private final AppContext context;
    private final Navigator navigator;
    private final String courseId;
    private final Disposer disposer = new Disposer();
    private final HBox root = new HBox();
    private final ThreadList threadList;
    private final IconButton threadsToggle = new IconButton(Feather.SIDEBAR, "Hide conversations").small();
    private final IconButton sourcesToggle = new IconButton(Feather.BOOK_OPEN, "Show sources").small();
    private final Label heading = new Label(AnswerText.UNTITLED);
    private final VBox column = new VBox();
    private final ScrollPane scroller = new ScrollPane();
    private final Composer composer;
    private final SourceViewer viewer;
    private final ExchangeHost exchangeHost = new ExchangeHost();
    private final Map<String, SourcePassage> passages = new HashMap<>();

    private String threadId;
    private List<ThreadSummary> threads = List.of();
    private CourseSummary course;
    private List<String> topics = List.of();
    private AssistantStatus assistant;
    private boolean canAsk = true;
    private Live live;
    private Disposer threadScope = new Disposer();
    private Boolean threadsWanted;
    private boolean sourcesOpen;
    private Citation openCitation;
    private boolean stickToBottom = true;
    private boolean scrolling;

    public AskPage(AppContext context, Navigator navigator, Destination.Ask destination) {
        this.context = Objects.requireNonNull(context, "context");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        Objects.requireNonNull(destination, "destination");
        this.courseId = destination.courseId();
        this.threadId = destination.threadId();
        root.getStyleClass().add("ask-page");
        root.getStylesheets().add(STYLESHEET);

        threadList = new ThreadList(new ThreadActions(), context.clock());

        threadsToggle.setOnAction(event -> {
            threadsWanted = !threadList.isVisible();
            updateLayout();
        });
        sourcesToggle.setOnAction(event -> setSourcesOpen(!sourcesOpen));
        heading.getStyleClass().addAll("text-title", "ask-heading");
        heading.setTextOverrun(OverrunStyle.ELLIPSIS);
        heading.setMinWidth(0);
        heading.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(heading, Priority.ALWAYS);
        HBox head = new HBox(12, threadsToggle, heading, sourcesToggle);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("ask-head-bar");

        column.getStyleClass().add("ask-column");
        column.setMaxWidth(COLUMN_MAX);
        column.getChildren().setAll(loadingShape());
        HBox.setHgrow(column, Priority.ALWAYS);
        HBox centring = new HBox(column);
        centring.setAlignment(Pos.TOP_CENTER);
        centring.getStyleClass().add("ask-column-centring");
        scroller.setContent(centring);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.getStyleClass().add("ask-scroller");
        VBox.setVgrow(scroller, Priority.ALWAYS);
        scroller.vvalueProperty().addListener((obs, old, value) -> {
            if (!scrolling) {
                stickToBottom = value.doubleValue() >= 0.98;
            }
        });
        centring.heightProperty().addListener((obs, old, height) -> {
            if (stickToBottom) {
                scrollToEnd();
            }
        });

        composer = new Composer(this::submit, this::stop);
        composer.setMaxWidth(COLUMN_MAX - 2 * COLUMN_GUTTER);
        composer.setStatusActions(this::restartAssistant, () -> navigator.navigate(Destination.SETTINGS));
        HBox.setHgrow(composer, Priority.ALWAYS);
        HBox composerRow = new HBox(composer);
        composerRow.setAlignment(Pos.TOP_CENTER);
        composerRow.getStyleClass().add("ask-composer-row");

        VBox conversation = new VBox(head, scroller, composerRow);
        conversation.getStyleClass().add("ask-conversation-pane");
        conversation.setMinWidth(0);
        HBox.setHgrow(conversation, Priority.ALWAYS);

        viewer = new SourceViewer(new ViewerHost());
        sourcesOpen = destination.focus() != null;

        root.getChildren().addAll(threadList, conversation, viewer);
        root.widthProperty().addListener((obs, old, width) -> updateLayout());
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (NEW_CONVERSATION.match(event)) {
                event.consume();
                newConversation();
            }
        });
        root.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && live != null) {
                event.consume();
                stop();
            }
        });
        updateLayout();
        if (destination.focus() != null) {
            openCitation = destination.focus();
        }
    }

    @Override
    public Region root() {
        return root;
    }

    @Override
    public void onShown() {
        Workspace workspace = context.workspace();
        if (workspace == null) {
            return;
        }
        String thread = threadId;
        disposer.add(UiTasks.run(() -> {
            CourseSummary summary = workspace.courses().summary(courseId);
            List<String> courseTopics = topicsOf(workspace);
            List<ThreadSummary> list = workspace.ask().threads(courseId);
            List<ChatMessage> messages = List.of();
            boolean missing = false;
            if (thread != null) {
                try {
                    messages = workspace.ask().messages(thread);
                } catch (NotFoundException e) {
                    missing = true;
                }
            }
            return new Opening(summary, courseTopics, list, messages, missing, workspace.assistant().status());
        }, this::opened, this::showFailure));
        disposer.add(Fx.subscribe(workspace.events(), AssistantStatusChanged.class, event -> assistantChanged(event.status())));
        disposer.add(Fx.subscribe(workspace.events(), CourseChanged.class, event -> {
            if (courseId.equals(event.courseId())) {
                reloadCourse();
            }
        }));
        if (openCitation != null) {
            openSource(openCitation);
        }
    }

    private record Opening(CourseSummary course, List<String> topics, List<ThreadSummary> threads,
                           List<ChatMessage> messages, boolean threadMissing, AssistantStatus assistant) {
    }

    /** Module titles of the course, offered as topics when a question isn't covered. */
    private List<String> topicsOf(Workspace workspace) {
        try {
            return workspace.courses().get(courseId).modules().stream().map(ModuleSummary::title)
                    .filter(title -> title != null && !title.isBlank()).limit(MAX_TOPICS).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public void dispose() {
        if (live != null) {
            live.cancel();
        }
        threadScope.dispose();
        viewer.dispose();
        disposer.dispose();
    }

    // ---- accessors (tests, screenshots) --------------------------------------------------------------

    /** True while a question is on its way to an answer (asking is disabled meanwhile). */
    public boolean isAnswering() {
        return live != null;
    }

    /** The conversation on screen, or null for a new one before its first question. */
    public String threadId() {
        return threadId;
    }

    ThreadList threadList() {
        return threadList;
    }

    Composer composer() {
        return composer;
    }

    SourceViewer viewer() {
        return viewer;
    }

    /** The questions and answers on screen, in order. */
    List<ExchangeView> exchanges() {
        return column.getChildren().stream().filter(ExchangeView.class::isInstance).map(ExchangeView.class::cast).toList();
    }

    boolean isSourceOpen() {
        return sourcesOpen;
    }

    boolean isThreadListShown() {
        return threadList.isVisible();
    }

    String headingText() {
        return heading.getText();
    }

    /** Puts {@code question} in the question box and asks it, as the learner would (screenshots). */
    public void askFromBox(String question) {
        composer.draft(question);
        submit(question);
    }

    // ---- opening ---------------------------------------------------------------------------------

    private void opened(Opening opening) {
        setCourse(opening.course());
        topics = opening.topics();
        assistantChanged(opening.assistant());
        threads = opening.threads();
        threadList.setThreads(threads, threadId);
        if (opening.threadMissing()) {
            threadId = null;
            navigator.replace(new Destination.Ask(courseId, null, openCitation), false);
            updateHeading();
            showNotice(new EmptyState(EmptyIllustration.Kind.SEARCH, "This conversation isn't available",
                    "It may have been deleted. Start a new one below.")
                    .secondary("New conversation", this::newConversation));
            return;
        }
        updateHeading();
        renderConversation(opening.messages());
        Platform.runLater(composer::focus);
    }

    private void setCourse(CourseSummary summary) {
        course = summary;
        canAsk = summary.createdHere() || summary.enrolled();
        composer.setBlocked(canAsk ? null : NOT_ENROLLED);
    }

    private void showFailure(UserMessage message) {
        threadList.showError(message);
        composer.setBlocked(message.text());
        showNotice(new EmptyState(EmptyIllustration.Kind.SEARCH, "Ask couldn't open", message.text())
                .secondary("Go to Library", () -> navigator.navigate(Destination.LIBRARY)));
    }

    private void reloadCourse() {
        Workspace workspace = context.workspace();
        if (workspace == null) {
            return;
        }
        run(() -> workspace.courses().summary(courseId), summary -> {
            boolean before = canAsk;
            setCourse(summary);
            if (before != canAsk && exchanges().isEmpty() && live == null) {
                renderConversation(List.of());
            }
        });
    }

    private void reloadThreads() {
        Workspace workspace = context.workspace();
        if (workspace == null) {
            return;
        }
        run(() -> workspace.ask().threads(courseId), list -> {
            threads = list;
            threadList.setThreads(list, threadId);
            updateHeading();
        });
    }

    private void updateHeading() {
        String title = AnswerText.UNTITLED;
        if (threadId != null) {
            for (ThreadSummary thread : threads) {
                if (thread.id().equals(threadId)) {
                    title = AnswerText.title(thread.title());
                }
            }
        }
        heading.setText(title);
    }

    // ---- the conversation ------------------------------------------------------------------------

    /** Shows {@code messages} as questions and answers, with the answer on its way when it belongs here. */
    private void renderConversation(List<ChatMessage> messages) {
        List<Node> nodes = new ArrayList<>();
        ExchangeView open = null;
        boolean liveHere = live != null && live.threadId != null && live.threadId.equals(threadId);
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            boolean last = i == messages.size() - 1 && !liveHere;
            if (message.role() == ChatRole.USER) {
                if (liveHere && isLiveQuestion(message, i == messages.size() - 1)) {
                    continue;
                }
                if (open != null) {
                    open.showError(UserMessage.of("This question wasn't answered."), false);
                }
                open = new ExchangeView(message.content(), exchangeHost);
                nodes.add(open);
            } else {
                if (open == null) {
                    open = new ExchangeView("", exchangeHost);
                    nodes.add(open);
                }
                open.showStored(message, last);
                open = null;
            }
        }
        if (open != null) {
            open.showError(UserMessage.of("This question wasn't answered."), true);
        }
        if (liveHere) {
            nodes.add(live.exchange);
        }
        column.getChildren().setAll(nodes);
        if (nodes.isEmpty()) {
            column.getChildren().setAll(emptyConversation());
        }
        markActive(openCitation);
        loadPassages(messages.stream().flatMap(m -> m.citations().stream()).toList());
        stickToBottom = true;
        scrollToEnd();
    }

    private boolean isLiveQuestion(ChatMessage message, boolean lastMessage) {
        return message.id().equals(live.questionId) || (live.questionId == null && lastMessage
                && message.content().strip().equals(live.question));
    }

    /** What a conversation shows before its first question. */
    private Node emptyConversation() {
        if (course != null && !canAsk) {
            return new EmptyState(EmptyIllustration.Kind.QUESTIONS, "Enrol to ask questions",
                    "Questions are answered from this course's PDFs, with the pages each answer is based on.")
                    .secondary("Enrol in this course", this::enrol);
        }
        if (course != null && course.documentCount() == 0) {
            EmptyState state = new EmptyState(EmptyIllustration.Kind.DOCUMENTS, "This course has no PDFs yet",
                    course.createdHere() ? "Add a PDF to the course, then ask about anything in it."
                            : "Questions are answered from the course's PDFs, and this course has none yet.");
            if (course.createdHere()) {
                state.secondary("Add a PDF", exchangeHost::addDocuments);
            }
            return state;
        }
        EmptyState state = new EmptyState(EmptyIllustration.Kind.QUESTIONS, "Ask anything about this course",
                "Answers come only from the course's PDFs, with the pages they're based on.");
        state.getStyleClass().add("ask-empty");
        if (!topics.isEmpty()) {
            FlowPane suggestions = new FlowPane(8, 8);
            suggestions.setAlignment(Pos.CENTER);
            suggestions.getStyleClass().add("ask-suggestions");
            suggestions.setMaxWidth(560);
            for (String topic : topics) {
                Button chip = new Button(topic);
                chip.getStyleClass().add("ask-suggestion");
                chip.setMnemonicParsing(false);
                chip.setAccessibleText("Ask about " + topic);
                chip.setOnAction(event -> composer.draft(ExchangeView.suggestion(topic)));
                suggestions.getChildren().add(chip);
            }
            Label hint = new Label("Start from a topic");
            hint.getStyleClass().addAll("text-caption", "ask-suggestions-hint");
            VBox box = new VBox(10, hint, suggestions);
            box.setAlignment(Pos.CENTER);
            state.getChildren().add(box);
        }
        return state;
    }

    private void showNotice(Node notice) {
        column.getChildren().setAll(notice);
    }

    private void enrol() {
        Workspace workspace = context.workspace();
        if (workspace == null) {
            return;
        }
        disposer.add(UiTasks.run(() -> {
            workspace.courses().enrol(courseId);
            return workspace.courses().summary(courseId);
        }, summary -> {
            setCourse(summary);
            if (live == null && exchanges().isEmpty()) {
                renderConversation(List.of());
            }
            composer.focus();
        }, message -> Toasts.error(root, message)));
    }

    private void openThread(String id) {
        Workspace workspace = context.workspace();
        if (workspace == null) {
            return;
        }
        threadId = id;
        navigator.replace(new Destination.Ask(courseId, id, null), false);
        threadList.select(id);
        updateHeading();
        column.getChildren().setAll(loadingShape());
        threadScope.dispose();
        threadScope = new Disposer();
        threadScope.add(UiTasks.run(() -> workspace.ask().messages(id), messages -> {
            if (id.equals(threadId)) {
                renderConversation(messages);
            }
        }, message -> {
            if (id.equals(threadId)) {
                showNotice(new EmptyState(EmptyIllustration.Kind.SEARCH, "This conversation couldn't open",
                        message.text()).secondary("New conversation", this::newConversation));
            }
        }));
    }

    private void newConversation() {
        threadScope.dispose();
        threadScope = new Disposer();
        threadId = null;
        navigator.replace(Destination.Ask.newThread(courseId), false);
        threadList.select(null);
        updateHeading();
        renderConversation(List.of());
        composer.focus();
    }

    private static Node loadingShape() {
        VBox shape = new VBox(16, Skeleton.block(360, 22), Skeleton.text(4), Skeleton.block(260, 22), Skeleton.text(3));
        shape.getStyleClass().add("ask-loading");
        return shape;
    }

    private void loadPassages(List<Citation> citations) {
        Set<String> wanted = new LinkedHashSet<>();
        for (Citation citation : citations) {
            if (citation.chunkId() != null && !passages.containsKey(citation.chunkId())) {
                wanted.add(citation.chunkId());
            }
        }
        if (wanted.isEmpty()) {
            applyPassages();
            return;
        }
        Workspace workspace = context.workspace();
        if (workspace == null) {
            return;
        }
        run(() -> workspace.documents().passages(wanted), found -> {
            for (SourcePassage passage : found) {
                if (passage.ref().chunkId() != null) {
                    passages.put(passage.ref().chunkId(), passage);
                }
            }
            applyPassages();
        });
    }

    private void applyPassages() {
        for (ExchangeView exchange : exchanges()) {
            exchange.setPassages(passages);
        }
        if (live != null) {
            live.exchange.setPassages(passages);
        }
        Citation shown = viewer.citation();
        if (shown != null && shown.chunkId() != null && passages.containsKey(shown.chunkId())) {
            viewer.describe(passages.get(shown.chunkId()).sectionTitle());
        }
    }

    private void scrollToEnd() {
        Platform.runLater(() -> {
            scrolling = true;
            scroller.layout();
            scroller.setVvalue(scroller.getVmax());
            scrolling = false;
        });
    }

    // ---- asking ----------------------------------------------------------------------------------

    private void submit(String question) {
        if (live != null || !canAsk || context.workspace() == null) {
            return;
        }
        composer.input().clear();
        ask(question);
    }

    private void ask(String question) {
        if (live != null || !canAsk || context.workspace() == null) {
            return;
        }
        column.getChildren().removeIf(node -> !(node instanceof ExchangeView));
        ExchangeView exchange = new ExchangeView(question, exchangeHost);
        column.getChildren().add(exchange);
        Live run = new Live(exchange, question, threadId);
        live = run;
        composer.setAnswering(true);
        stickToBottom = true;
        scrollToEnd();
        proceed(run);
    }

    /** Sends the question once the study assistant is ready (now, or when it says so). */
    private void proceed(Live run) {
        if (run.isCancelled() || live != run) {
            return;
        }
        AssistantStatus status = assistant;
        if (status != null && status.state() == AssistantState.STARTING) {
            run.waiting = true;
            run.exchange.showPhase(WAITING);
            return;
        }
        if (status != null && !status.isReady()) {
            run.waiting = false;
            run.started = true;
            run.finished = true;
            run.exchange.showError(status.message(), true);
            settle(run);
            return;
        }
        run.waiting = false;
        run.exchange.showPhase(SEARCHING);
        Workspace workspace = context.workspace();
        String existing = run.threadId;
        String question = run.question;
        UiTasks.run(() -> {
            String thread = existing != null ? existing : workspace.ask().createThread(courseId, null).id();
            if (!run.isCancelled()) {
                run.streaming(workspace.ask().ask(thread, question, new Listener(run)));
            }
            return thread;
        }, thread -> started(run, thread), message -> {
            run.started = true;
            run.finished = true;
            run.exchange.showError(message, true);
            settle(run);
        });
    }

    private void started(Live run, String thread) {
        run.threadId = thread;
        run.started = true;
        if (disposer.isDisposed()) {
            return;
        }
        if (threadId == null && run.exchange.getParent() == column) {
            threadId = thread;
            navigator.replace(new Destination.Ask(courseId, thread, sourcesOpen ? openCitation : null), false);
        }
        if (run.isCancelled() && run.handle == null) {
            run.finished = true;
            run.exchange.showStopped("", List.of(), true);
        }
        reloadThreads();
        settle(run);
    }

    /** Stops the answer on its way; it is kept as stopped. */
    private void stop() {
        Live run = live;
        if (run == null) {
            return;
        }
        run.cancel();
        if (run.waiting) {
            run.waiting = false;
            run.started = true;
            run.finished = true;
            run.exchange.showStopped("", List.of(), true);
            settle(run);
        }
    }

    /** Lets the next question go once {@code run} has started and finished, in either order. */
    private void settle(Live run) {
        if (run.started && run.finished && live == run) {
            live = null;
            composer.setAnswering(false);
            Node focused = root.getScene() == null ? null : root.getScene().getFocusOwner();
            if (focused == null || focused == composer.stopButton() || focused.isDisabled()) {
                composer.focus();
            }
        }
    }

    private void assistantChanged(AssistantStatus status) {
        assistant = status;
        composer.setAssistantStatus(status);
        Live run = live;
        if (run != null && run.waiting && status != null) {
            if (status.isReady()) {
                proceed(run);
            } else if (status.state() != AssistantState.STARTING) {
                run.waiting = false;
                run.started = true;
                run.finished = true;
                run.exchange.showError(status.message(), true);
                settle(run);
            }
        }
    }

    private void restartAssistant() {
        Workspace workspace = context.workspace();
        if (workspace != null) {
            run(() -> {
                workspace.assistant().restart();
                return workspace.assistant().status();
            }, this::assistantChanged);
        }
    }

    /** The answer on its way, from the question to its outcome. */
    private static final class Live implements Cancellable {

        final ExchangeView exchange;
        final String question;
        /** FX thread: the conversation, once known. */
        String threadId;
        /** FX thread: the stored question's id, once saved. */
        String questionId;
        /** FX thread: waiting for the study assistant. */
        boolean waiting;
        /** FX thread: the conversation is known (or the question failed before it). */
        boolean started;
        /** FX thread: the answer arrived, failed or stopped. */
        boolean finished;
        volatile Cancellable handle;
        private volatile boolean cancelled;

        Live(ExchangeView exchange, String question, String threadId) {
            this.exchange = exchange;
            this.question = question;
            this.threadId = threadId;
        }

        /** Background thread: the answer is streaming; stops it at once when it was stopped meanwhile. */
        void streaming(Cancellable value) {
            handle = value;
            if (cancelled) {
                value.cancel();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            Cancellable h = handle;
            if (h != null) {
                h.cancel();
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** Hands the answer's progress to the FX thread. */
    private final class Listener implements AnswerListener {

        private final Live run;

        Listener(Live run) {
            this.run = run;
        }

        @Override
        public void onQuestionSaved(ChatMessage question) {
            Fx.run(() -> {
                run.questionId = question.id();
                if (!disposer.isDisposed()) {
                    reloadThreads();
                }
            });
        }

        @Override
        public void onPhase(AskPhase phase) {
            Fx.run(() -> run.exchange.showPhase(phase == AskPhase.WRITING ? WRITING : SEARCHING));
        }

        @Override
        public void onToken(String token) {
            Fx.run(() -> run.exchange.appendToken(token));
        }

        @Override
        public void onAnswer(Answer answer) {
            Fx.run(() -> {
                run.exchange.showAnswer(answer.text(), answer.citations());
                markActive(openCitation);
                finished(run);
                loadPassages(answer.citations());
            });
        }

        @Override
        public void onInsufficientEvidence(Answer answer) {
            Fx.run(() -> {
                run.exchange.showInsufficient();
                finished(run);
            });
        }

        @Override
        public void onError(UserMessage message) {
            Fx.run(() -> {
                if (UserMessage.ASSISTANT_STARTING.equals(message) && !run.isCancelled() && !disposer.isDisposed()) {
                    // The assistant went back to getting ready after the question left: wait and ask again.
                    run.handle = null;
                    run.waiting = true;
                    run.exchange.showPhase(WAITING);
                    Workspace workspace = context.workspace();
                    if (workspace != null) {
                        run(() -> workspace.assistant().status(), AskPage.this::assistantChanged);
                    }
                    return;
                }
                run.exchange.showError(message, true);
                finished(run);
            });
        }

        @Override
        public void onCancelled() {
            Fx.run(() -> {
                run.exchange.showStopped(run.exchange.streamedText(), List.of(), true);
                finished(run);
            });
        }

        private void finished(Live live) {
            live.finished = true;
            live.waiting = false;
            settle(live);
            if (!disposer.isDisposed()) {
                reloadThreads();
            }
        }
    }

    // ---- sources ---------------------------------------------------------------------------------

    private void openSource(Citation citation) {
        openCitation = citation;
        SourcePassage passage = citation.chunkId() == null ? null : passages.get(citation.chunkId());
        viewer.show(citation, passage == null ? null : passage.sectionTitle(), passage == null ? null : passage.text());
        setSourcesOpen(true);
        markActive(citation);
        if (!disposer.isDisposed() && navigator.current() instanceof Destination.Ask) {
            navigator.replace(new Destination.Ask(courseId, threadId, citation), false);
        }
    }

    private void setSourcesOpen(boolean open) {
        sourcesOpen = open;
        if (open && viewer.citation() == null) {
            viewer.showHint();
        }
        if (!open) {
            openCitation = null;
            markActive(null);
            if (navigator.current() instanceof Destination.Ask) {
                navigator.replace(new Destination.Ask(courseId, threadId, null), false);
            }
        }
        updateLayout();
    }

    /** Marks the chips and source rows of the citation open in the viewer. */
    private void markActive(Citation citation) {
        for (Node node : column.lookupAll(".citation-chip")) {
            if (node instanceof CitationChip chip) {
                chip.pseudoClassStateChanged(ACTIVE, citation != null && citation.equals(chip.citation()));
            }
        }
        for (Node node : column.lookupAll(".ask-source-row")) {
            node.pseudoClassStateChanged(ACTIVE, citation != null && citation.equals(node.getProperties().get(CITATION_KEY)));
        }
    }

    private void updateLayout() {
        double width = root.getWidth();
        AskLayout.Widths widths = AskLayout.compute(width > 0 ? width : 1208, sourcesOpen, threadsWanted);
        threadList.setVisible(widths.threads());
        threadList.setManaged(widths.threads());
        viewer.setVisible(sourcesOpen);
        viewer.setManaged(sourcesOpen);
        viewer.setMinWidth(widths.source());
        viewer.setPrefWidth(widths.source());
        viewer.setMaxWidth(widths.source());
        String threadsLabel = widths.threads() ? "Hide conversations" : "Show conversations";
        threadsToggle.setAccessibleText(threadsLabel);
        threadsToggle.getTooltip().setText(threadsLabel);
        threadsToggle.pseudoClassStateChanged(ON, widths.threads());
        String sourcesLabel = sourcesOpen ? "Hide sources" : "Show sources";
        sourcesToggle.setAccessibleText(sourcesLabel);
        sourcesToggle.getTooltip().setText(sourcesLabel);
        sourcesToggle.pseudoClassStateChanged(ON, sourcesOpen);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private <T> void run(Callable<? extends T> work, Consumer<? super T> onFx) {
        disposer.add(UiTasks.run(work, onFx, message -> Toasts.error(root, message)));
    }

    /** The conversation list's actions. */
    private final class ThreadActions implements ThreadList.Actions {

        @Override
        public void open(ThreadSummary thread) {
            if (!thread.id().equals(threadId)) {
                openThread(thread.id());
            }
        }

        @Override
        public void newConversation() {
            AskPage.this.newConversation();
        }

        @Override
        public void rename(ThreadSummary thread, String title) {
            Workspace workspace = context.workspace();
            if (workspace == null) {
                return;
            }
            disposer.add(UiTasks.run(() -> {
                workspace.ask().renameThread(thread.id(), title);
                return Boolean.TRUE;
            }, done -> reloadThreads(), message -> {
                Toasts.error(root, message);
                reloadThreads();
            }));
        }

        @Override
        public void delete(ThreadSummary thread) {
            Workspace workspace = context.workspace();
            if (workspace == null) {
                return;
            }
            ConfirmDialog.of("Delete this conversation?",
                            "Its questions and answers are removed from this computer.", "Delete conversation")
                    .danger().show(root).thenAccept(confirmed -> {
                        if (!confirmed || disposer.isDisposed()) {
                            return;
                        }
                        if (live != null && thread.id().equals(live.threadId)) {
                            stop();
                        }
                        disposer.add(UiTasks.run(() -> {
                            workspace.ask().deleteThread(thread.id());
                            return Boolean.TRUE;
                        }, done -> {
                            if (thread.id().equals(threadId)) {
                                AskPage.this.newConversation();
                            }
                            reloadThreads();
                            Toasts.success(root, "Conversation deleted");
                        }, message -> Toasts.error(root, message)));
                    });
        }
    }

    /** What each question and answer asks of the page. */
    private final class ExchangeHost implements ExchangeView.Host {

        @Override
        public Node chip(Citation citation) {
            CitationChip chip = new CitationChip(citation);
            chip.getStyleClass().add("ask-chip");
            chip.setOnOpen(AskPage.this::openSource);
            chip.pseudoClassStateChanged(ACTIVE, citation.equals(openCitation));
            return chip;
        }

        @Override
        public void openSource(Citation citation) {
            AskPage.this.openSource(citation);
        }

        @Override
        public void retry(ExchangeView exchange) {
            if (live != null) {
                return;
            }
            column.getChildren().remove(exchange);
            ask(exchange.question());
        }

        @Override
        public void draft(String question) {
            composer.draft(question);
        }

        @Override
        public List<String> topics() {
            return topics;
        }

        @Override
        public boolean canAddDocuments() {
            return course != null && course.createdHere();
        }

        @Override
        public void addDocuments() {
            navigator.navigate(new Destination.Studio(courseId, StudioTab.DOCUMENTS));
        }
    }

    /** What the source viewer asks of the page. */
    private final class ViewerHost implements SourceViewer.Host {

        @Override
        public Workspace workspace() {
            return context.workspace();
        }

        @Override
        public void close() {
            setSourcesOpen(false);
            composer.focus();
        }

        @Override
        public void openCourse() {
            navigator.navigate(new Destination.Course(courseId));
        }
    }
}
