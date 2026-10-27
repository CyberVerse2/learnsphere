package edu.learnsphere.app.ui.ask;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.View;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.CitationChip;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.ChatRole;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.MessageStatus;
import edu.learnsphere.core.service.Answer;
import edu.learnsphere.core.service.AnswerListener;
import edu.learnsphere.core.service.AskPhase;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.RenderedPage;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.spi.NormalizedRect;
import edu.learnsphere.core.util.Cancellable;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.kordamp.ikonli.feather.Feather;

/**
 * Asking about a course: the conversation on the left, answers in reading type with their citations
 * inline, and the source viewer on the right, which opens a cited page with the passage highlighted. An
 * answer the material doesn't support is a calm card that suggests what to try next.
 */
public final class AskPage implements View {

    /** Width of the source viewer. */
    public static final double SOURCE_WIDTH = 440;
    private static final Pattern MARKER = Pattern.compile("\\[(\\d+)]");

    private final AppContext context;
    private final Navigator navigator;
    private final Destination.Ask destination;
    private final Disposer disposer = new Disposer();
    private final HBox root = new HBox();
    private final VBox messages = new VBox(28);
    private final ScrollPane scroller = new ScrollPane();
    private final TextArea question = new TextArea();
    private final Button askButton = new Button("Ask");
    private final VBox viewer = new VBox(14);
    private final Label courseTitle = new Label();
    private String threadId;
    private Cancellable answering;

    public AskPage(AppContext context, Navigator navigator, Destination.Ask destination) {
        this.context = context;
        this.navigator = navigator;
        this.destination = destination;
        this.threadId = destination.threadId();
        root.getStyleClass().add("ask-page");

        courseTitle.getStyleClass().add("text-title");
        Label overline = new Label("ASK");
        overline.getStyleClass().add("text-overline");
        Button newThread = new Button("New conversation", Icons.of(Feather.PLUS));
        newThread.getStyleClass().addAll("small");
        newThread.setOnAction(event -> navigator.navigate(Destination.Ask.newThread(destination.courseId())));
        VBox headText = new VBox(4, overline, courseTitle);
        HBox.setHgrow(headText, Priority.ALWAYS);
        HBox head = new HBox(16, headText, newThread);
        head.setAlignment(Pos.BOTTOM_LEFT);
        head.getStyleClass().add("ask-head");

        messages.getStyleClass().add("ask-messages");
        messages.getChildren().setAll(Skeleton.text(4));
        scroller.setContent(messages);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("ask-scroller");
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        question.setPromptText("Ask a question about this course");
        question.setWrapText(true);
        question.setPrefRowCount(2);
        question.getStyleClass().add("ask-input");
        HBox.setHgrow(question, Priority.ALWAYS);
        askButton.getStyleClass().add("primary");
        askButton.setMinWidth(Region.USE_PREF_SIZE);
        askButton.setGraphic(Icons.of(Feather.ARROW_UP, 16));
        askButton.disableProperty().bind(question.textProperty().map(String::isBlank));
        askButton.setOnAction(event -> ask());
        question.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                if (!askButton.isDisabled()) {
                    ask();
                }
            }
        });
        Label hint = new Label("Answers come only from this course's PDFs. Press Shift+Enter for a new line.");
        hint.getStyleClass().add("text-caption");
        HBox composerRow = new HBox(12, question, askButton);
        composerRow.setAlignment(Pos.BOTTOM_LEFT);
        VBox composer = new VBox(8, composerRow, hint);
        composer.getStyleClass().add("ask-composer");

        VBox conversation = new VBox(head, scroller, composer);
        conversation.getStyleClass().add("ask-conversation");
        HBox.setHgrow(conversation, Priority.ALWAYS);

        viewer.getStyleClass().add("source-viewer");
        viewer.setMinWidth(SOURCE_WIDTH);
        viewer.setPrefWidth(SOURCE_WIDTH);
        viewer.setMaxWidth(SOURCE_WIDTH);
        showViewerHint();

        root.getChildren().addAll(conversation, viewer);
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
        String courseId = destination.courseId();
        disposer.add(UiTasks.run(() -> {
            CourseSummary course = workspace.courses().summary(courseId);
            String thread = threadId;
            List<ChatMessage> list = thread == null ? List.of() : workspace.ask().messages(thread);
            return new Loaded(course, thread, list);
        }, this::render, this::showError));
        if (destination.focus() != null) {
            openSource(destination.focus());
        }
    }

    private record Loaded(CourseSummary course, String threadId, List<ChatMessage> messages) {
    }

    @Override
    public void dispose() {
        if (answering != null) {
            answering.cancel();
        }
        disposer.dispose();
    }

    private void render(Loaded loaded) {
        courseTitle.setText(loaded.course().title());
        threadId = loaded.threadId();
        messages.getChildren().clear();
        if (loaded.messages().isEmpty()) {
            messages.getChildren().add(new EmptyState(EmptyIllustration.Kind.QUESTIONS, "Ask anything about this course",
                    "Answers come from the course's own PDFs, with the pages they're based on."));
        }
        for (ChatMessage message : loaded.messages()) {
            messages.getChildren().add(message.role() == ChatRole.USER ? questionNode(message.content())
                    : answerNode(message.content(), message.status(), message.citations()));
        }
        Fx.run(() -> scroller.setVvalue(1));
        question.requestFocus();
    }

    private void showError(UserMessage message) {
        messages.getChildren().setAll(new EmptyState(EmptyIllustration.Kind.SEARCH, "This conversation couldn't open",
                message.text()).primary("Go to Library", () -> navigator.navigate(Destination.LIBRARY)));
    }

    private Node questionNode(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("ask-question");
        label.setWrapText(true);
        HBox row = new HBox(label);
        row.setAlignment(Pos.CENTER_RIGHT);
        return row;
    }

    private Node answerNode(String text, MessageStatus status, List<Citation> citations) {
        if (status == MessageStatus.INSUFFICIENT) {
            return insufficient();
        }
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("reading-flow");
        Matcher matcher = MARKER.matcher(text);
        int last = 0;
        while (matcher.find()) {
            flow.getChildren().add(readingText(text.substring(last, matcher.start())));
            Citation citation = find(citations, Integer.parseInt(matcher.group(1)));
            if (citation != null) {
                CitationChip chip = new CitationChip(citation);
                chip.setOnOpen(this::openSource);
                chip.getStyleClass().add("inline");
                flow.getChildren().add(chip);
            }
            last = matcher.end();
        }
        flow.getChildren().add(readingText(text.substring(last)));
        VBox box = new VBox(10, flow);
        box.getStyleClass().add("ask-answer");
        if (status == MessageStatus.ERROR || status == MessageStatus.CANCELLED) {
            Label note = new Label(status == MessageStatus.CANCELLED ? "You stopped this answer." : "This answer didn't finish.");
            note.getStyleClass().add("text-caption");
            box.getChildren().add(note);
        }
        return box;
    }

    private static Text readingText(String text) {
        Text node = new Text(text);
        node.getStyleClass().add("text-reading");
        return node;
    }

    private static Citation find(List<Citation> citations, int number) {
        return citations.stream().filter(c -> c.number() == number).findFirst().orElse(null);
    }

    private Node insufficient() {
        Label title = new Label("Your material doesn't cover this");
        title.getStyleClass().add("text-heading");
        Label body = new Label("Try rephrasing with words from the course, or add a PDF that covers it.");
        body.getStyleClass().add("text-muted");
        body.setWrapText(true);
        VBox text = new VBox(4, title, body);
        HBox card = new HBox(14, Icons.of(Feather.BOOK, 20), text);
        card.getStyleClass().add("insufficient-card");
        return card;
    }

    private void ask() {
        Workspace workspace = context.workspace();
        String text = question.getText().strip();
        if (workspace == null || text.isEmpty()) {
            return;
        }
        question.clear();
        if (messages.getChildren().size() == 1 && messages.getChildren().getFirst() instanceof EmptyState) {
            messages.getChildren().clear();
        }
        messages.getChildren().add(questionNode(text));
        TextFlow streaming = new TextFlow();
        streaming.getStyleClass().add("reading-flow");
        Text tokens = readingText("");
        streaming.getChildren().add(tokens);
        HBox thinking = new HBox(10, new ActivityIndicator(16), new Label("Looking through your material…"));
        thinking.getStyleClass().add("ask-thinking");
        thinking.setAlignment(Pos.CENTER_LEFT);
        VBox pending = new VBox(10, thinking, streaming);
        pending.getStyleClass().add("ask-answer");
        messages.getChildren().add(pending);
        Fx.run(() -> scroller.setVvalue(1));
        AnswerListener listener = new AnswerListener() {
            @Override
            public void onPhase(AskPhase phase) {
                Fx.run(() -> ((Label) thinking.getChildren().get(1)).setText(phase == AskPhase.SEARCHING
                        ? "Looking through your material…" : "Writing the answer…"));
            }

            @Override
            public void onToken(String token) {
                Fx.run(() -> {
                    thinking.setVisible(false);
                    thinking.setManaged(false);
                    tokens.setText(tokens.getText() + token);
                    scroller.setVvalue(1);
                });
            }

            @Override
            public void onAnswer(Answer answer) {
                Fx.run(() -> finish(pending, answerNode(answer.text(), answer.status(), answer.citations())));
            }

            @Override
            public void onInsufficientEvidence(Answer answer) {
                Fx.run(() -> finish(pending, insufficient()));
            }

            @Override
            public void onError(UserMessage message) {
                Fx.run(() -> {
                    messages.getChildren().remove(pending);
                    Toasts.error(root, message);
                    answering = null;
                });
            }

            @Override
            public void onCancelled() {
                Fx.run(() -> finish(pending, answerNode(tokens.getText(), MessageStatus.CANCELLED, List.of())));
            }
        };
        String courseId = destination.courseId();
        String existing = threadId;
        disposer.add(UiTasks.run(() -> {
            String thread = existing != null ? existing : workspace.ask().createThread(courseId, null).id();
            return new Started(thread, workspace.ask().ask(thread, text, listener));
        }, started -> {
            threadId = started.threadId();
            answering = started.handle();
            navigator.replace(new Destination.Ask(courseId, threadId, null), false);
        }, message -> {
            messages.getChildren().remove(pending);
            Toasts.error(root, message);
        }));
    }

    private record Started(String threadId, Cancellable handle) {
    }

    private void finish(Node pending, Node answer) {
        int index = messages.getChildren().indexOf(pending);
        if (index >= 0) {
            messages.getChildren().set(index, answer);
            Motion.fadeIn(answer, Motion.ROUTE);
        }
        answering = null;
        Fx.run(() -> scroller.setVvalue(1));
    }

    private void showViewerHint() {
        Label title = new Label("Sources");
        title.getStyleClass().add("text-heading");
        Label hint = new Label("Choose a page reference in an answer to see the passage it came from.");
        hint.getStyleClass().add("text-muted");
        hint.setWrapText(true);
        Region page = new Region();
        page.getStyleClass().add("source-page-placeholder");
        VBox.setVgrow(page, Priority.ALWAYS);
        viewer.getChildren().setAll(title, hint, page);
    }

    private void openSource(Citation citation) {
        Workspace workspace = context.workspace();
        if (workspace == null) {
            return;
        }
        Label title = new Label(citation.documentTitle());
        title.getStyleClass().add("text-heading");
        title.setWrapText(true);
        Label where = new Label(Formats.pages(citation));
        where.getStyleClass().add("text-caption");
        Button close = new Button(null, Icons.of(Feather.X, 16));
        close.getStyleClass().addAll("icon-button", "small");
        close.setAccessibleText("Close source");
        close.setOnAction(event -> showViewerHint());
        VBox text = new VBox(2, title, where);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox head = new HBox(8, text, close);
        head.setAlignment(Pos.TOP_LEFT);
        StackPane page = new StackPane(Skeleton.block(-1, 480));
        page.getStyleClass().add("source-page");
        VBox.setVgrow(page, Priority.ALWAYS);
        viewer.getChildren().setAll(head, page);
        disposer.add(UiTasks.run(() -> new Source(workspace.documents().renderPage(citation.documentId(),
                        citation.pageStart(), 2),
                        workspace.documents().locatePassage(citation.documentId(), citation.pageStart(), citation.snippet())),
                source -> page.getChildren().setAll(pageWithHighlight(source)),
                message -> page.getChildren().setAll(new Label(message.text()))));
    }

    private record Source(RenderedPage page, List<NormalizedRect> highlights) {
    }

    private Node pageWithHighlight(Source source) {
        Image image = new Image(new ByteArrayInputStream(source.page().png()));
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        double width = SOURCE_WIDTH - 48;
        view.setFitWidth(width);
        double height = width * image.getHeight() / Math.max(1, image.getWidth());
        Pane overlay = new Pane();
        overlay.setMouseTransparent(true);
        for (NormalizedRect rect : source.highlights()) {
            Rectangle mark = new Rectangle(rect.x() * width, rect.y() * height, rect.width() * width, rect.height() * height);
            mark.getStyleClass().add("passage-highlight");
            mark.setArcWidth(6);
            mark.setArcHeight(6);
            overlay.getChildren().add(mark);
        }
        StackPane stack = new StackPane(view, overlay);
        stack.getStyleClass().add("source-page-image");
        stack.setMaxSize(width, height);
        ScrollPane scroll = new ScrollPane(new StackPane(stack));
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("source-scroller");
        if (!source.highlights().isEmpty()) {
            double top = source.highlights().getFirst().y();
            Fx.run(() -> scroll.setVvalue(Math.max(0, top - 0.15)));
        }
        return scroll;
    }
}
