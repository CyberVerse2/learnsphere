package edu.learnsphere.app.ui.ask;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.service.SourcePassage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One question and its answer in a conversation, set like a page of questions and answers: the question in the
 * title face, the answer in reading type with page chips where it cites the material, then the sources it
 * used and a copy button. The answer area moves through the states of an answer: a quiet progress line, the
 * streamed preview, and then the final answer, the "material doesn't cover this" card, an error with
 * Try again, or the stopped answer. FX thread only.
 */
final class ExchangeView extends VBox {

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeView.class);

    /** How often the streamed preview is set again while words arrive. */
    static final Duration PREVIEW_INTERVAL = Duration.millis(45);
    /** What the card says when the course's material doesn't cover a question. */
    static final String NOT_COVERED = "Your material doesn't cover this. Try rephrasing, or add a document that does.";
    /** How long "Copied" shows after copying. */
    private static final Duration COPIED_DURATION = Duration.seconds(2);

    /** What the answer area shows. */
    enum State {
        /** Waiting or working before any words arrive. */
        WORKING,
        /** Words are arriving. */
        STREAMING,
        /** The final answer with its citations. */
        ANSWERED,
        /** The material doesn't cover the question. */
        INSUFFICIENT,
        /** The answer couldn't be produced. */
        FAILED,
        /** The learner stopped the answer. */
        STOPPED
    }

    /** What the exchange asks of the screen around it. */
    interface Host {

        /** The inline node for a citation (a page chip that opens the source). */
        Node chip(Citation citation);

        /** Opens a cited passage in the source viewer. */
        void openSource(Citation citation);

        /** Asks this exchange's question again. */
        void retry(ExchangeView exchange);

        /** Puts {@code question} in the question box to edit, without asking it. */
        void draft(String question);

        /** Topics of the course, offered when the material doesn't cover a question. */
        List<String> topics();

        /** True when the learner made this course and can add PDFs to it. */
        boolean canAddDocuments();

        /** Opens the place where PDFs are added to the course. */
        void addDocuments();
    }

    private final String question;
    private final Host host;
    private final VBox answer = new VBox();
    private final StringBuilder streamed = new StringBuilder();
    private final PauseTransition previewTimer = new PauseTransition(PREVIEW_INTERVAL);
    private State state = State.WORKING;
    private Label phase;
    private VBox preview;
    private String text = "";
    private List<Citation> citations = List.of();
    private Map<String, SourcePassage> passages = Map.of();
    private VBox sources;

    ExchangeView(String question, Host host) {
        this.question = Objects.requireNonNull(question, "question");
        this.host = Objects.requireNonNull(host, "host");
        getStyleClass().add("ask-exchange");
        Label q = new Label(question);
        q.getStyleClass().addAll("ask-q", "serif-medium");
        q.setWrapText(true);
        q.setMinHeight(Region.USE_PREF_SIZE);
        answer.getStyleClass().add("ask-a");
        getChildren().addAll(q, answer);
        previewTimer.setOnFinished(event -> renderPreview());
    }

    String question() {
        return question;
    }

    State state() {
        return state;
    }

    /** Everything streamed so far, markers included. */
    String streamedText() {
        return streamed.toString();
    }

    /** The final (or stopped) answer text, with its markers. */
    String text() {
        return text;
    }

    List<Citation> citations() {
        return citations;
    }

    /** True while the answer is not finished. */
    boolean isWorking() {
        return state == State.WORKING || state == State.STREAMING;
    }

    // ---- in progress -------------------------------------------------------------------------------

    /** Shows a quiet progress line ("Looking through your material") above anything streamed so far. */
    void showPhase(String label) {
        if (!isWorking()) {
            return;
        }
        if (phase == null) {
            phase = new Label();
            phase.getStyleClass().add("ask-phase");
            ActivityIndicator spinner = new ActivityIndicator(14);
            phase.setGraphic(spinner);
            phase.setGraphicTextGap(10);
            answer.getChildren().addFirst(phase);
        }
        phase.setText(label);
    }

    /** Adds streamed words; the preview is set again at most every {@link #PREVIEW_INTERVAL}. */
    void appendToken(String token) {
        if (!isWorking() || token == null || token.isEmpty()) {
            return;
        }
        streamed.append(token);
        state = State.STREAMING;
        if (previewTimer.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            previewTimer.playFromStart();
        }
    }

    /** Sets the streamed preview now (also run by the timer). */
    void renderPreview() {
        if (state != State.STREAMING) {
            return;
        }
        VBox next = MarkdownText.render(AnswerText.preview(streamed.toString()), List.of(), c -> null);
        next.getStyleClass().add("ask-preview");
        Region caret = new Region();
        caret.getStyleClass().add("ask-caret");
        if (!next.getChildren().isEmpty() && next.getChildren().getLast() instanceof TextFlow flow) {
            flow.getChildren().add(caret);
        } else {
            next.getChildren().add(caret);
        }
        if (preview == null) {
            answer.getChildren().add(next);
        } else {
            answer.getChildren().set(answer.getChildren().indexOf(preview), next);
        }
        preview = next;
    }

    // ---- outcomes ----------------------------------------------------------------------------------

    /** The final answer: the stream gives way to the stored text with its page chips, then the sources. */
    void showAnswer(String answerText, List<Citation> answerCitations) {
        finish(State.ANSWERED);
        text = answerText == null ? "" : answerText;
        citations = List.copyOf(answerCitations);
        VBox body = MarkdownText.render(text, citations, host::chip);
        answer.getChildren().setAll(body);
        addSourcesAndActions(true);
        Motion.fadeIn(body, Motion.ROUTE);
    }

    /** The calm card saying the material doesn't cover the question, with what to try next. */
    void showInsufficient() {
        finish(State.INSUFFICIENT);
        text = NOT_COVERED;
        citations = List.of();
        StackPane icon = new StackPane(Icons.of(Feather.BOOK_OPEN, 18));
        icon.getStyleClass().add("ask-insufficient-icon");
        icon.setMinSize(40, 40);
        icon.setMaxSize(40, 40);
        Label message = new Label(text);
        message.getStyleClass().add("ask-insufficient-text");
        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);
        VBox content = new VBox(message);
        content.getStyleClass().add("ask-insufficient-content");
        HBox.setHgrow(content, Priority.ALWAYS);

        List<String> topics = host.topics();
        if (!topics.isEmpty()) {
            Label hint = new Label("Topics this course covers");
            hint.getStyleClass().add("text-caption");
            FlowPane chips = new FlowPane(8, 8);
            chips.getStyleClass().add("ask-suggestions");
            for (String topic : topics) {
                Button chip = new Button(topic);
                chip.getStyleClass().add("ask-suggestion");
                chip.setMnemonicParsing(false);
                chip.setOnAction(event -> host.draft(suggestion(topic)));
                chip.setAccessibleText("Ask about " + topic);
                chips.getChildren().add(chip);
            }
            content.getChildren().addAll(hint, chips);
        }
        Button rephrase = new Button("Rephrase question", Icons.of(Feather.EDIT_3, 14));
        rephrase.getStyleClass().add("small");
        rephrase.setOnAction(event -> host.draft(question));
        HBox actions = new HBox(8, rephrase);
        actions.getStyleClass().add("ask-insufficient-actions");
        actions.setAlignment(Pos.CENTER_LEFT);
        if (host.canAddDocuments()) {
            Button add = new Button("Add a PDF", Icons.of(Feather.FILE_PLUS, 14));
            add.getStyleClass().addAll("quiet", "small");
            add.setOnAction(event -> host.addDocuments());
            actions.getChildren().add(add);
        }
        content.getChildren().add(actions);
        HBox card = new HBox(16, icon, content);
        card.getStyleClass().add("ask-insufficient");
        card.setAlignment(Pos.TOP_LEFT);
        answer.getChildren().setAll(card);
        Motion.fadeIn(card, Motion.ROUTE);
    }

    /** The question a topic chip puts in the question box. */
    static String suggestion(String topic) {
        return "What does the course say about " + topic.strip() + "?";
    }

    /** One sentence saying what went wrong, with Try again when {@code canRetry}. */
    void showError(UserMessage message, boolean canRetry) {
        finish(State.FAILED);
        text = message.text();
        citations = List.of();
        Label sentence = new Label(message.text());
        sentence.getStyleClass().add("ask-error-text");
        sentence.setWrapText(true);
        sentence.setMinHeight(Region.USE_PREF_SIZE);
        sentence.setGraphic(Icons.of(Feather.ALERT_CIRCLE, 16));
        sentence.setGraphicTextGap(10);
        HBox.setHgrow(sentence, Priority.ALWAYS);
        sentence.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(12, sentence);
        row.getStyleClass().add("ask-error");
        row.setAlignment(Pos.CENTER_LEFT);
        if (canRetry) {
            Button retry = new Button("Try again", Icons.of(Feather.ROTATE_CW, 14));
            retry.getStyleClass().add("small");
            retry.setMinWidth(Region.USE_PREF_SIZE);
            retry.setOnAction(event -> host.retry(this));
            row.getChildren().add(retry);
        }
        answer.getChildren().setAll(row);
        Motion.fadeIn(row, Motion.ROUTE);
    }

    /**
     * The answer as far as it got when the learner stopped it. {@code partial} is the stored text (with
     * markers and {@code partialCitations}), or the streamed text when nothing better is known.
     */
    void showStopped(String partial, List<Citation> partialCitations, boolean canAskAgain) {
        finish(State.STOPPED);
        text = partial == null ? "" : partial.strip();
        citations = List.copyOf(partialCitations);
        List<Node> nodes = new ArrayList<>();
        if (!text.isEmpty()) {
            String shown = citations.isEmpty() ? AnswerText.preview(text) : text;
            nodes.add(MarkdownText.render(shown, citations, host::chip));
        }
        Label note = new Label(text.isEmpty() ? "You stopped this answer before it began." : "You stopped this answer.");
        note.getStyleClass().add("ask-stopped-note");
        note.setGraphic(Icons.of(Feather.STOP_CIRCLE, 14));
        note.setGraphicTextGap(8);
        HBox noteRow = new HBox(4, note);
        noteRow.setAlignment(Pos.CENTER_LEFT);
        noteRow.getStyleClass().add("ask-stopped");
        if (canAskAgain) {
            Button again = new Button("Ask again");
            again.getStyleClass().addAll("quiet", "small");
            again.setOnAction(event -> host.retry(this));
            noteRow.getChildren().add(again);
        }
        nodes.add(noteRow);
        answer.getChildren().setAll(nodes);
        if (!citations.isEmpty()) {
            addSourcesAndActions(false);
        }
    }

    /** Shows a stored answer as it was left. {@code last} offers Try again on a failed or stopped last answer. */
    void showStored(ChatMessage message, boolean last) {
        switch (message.status()) {
            case COMPLETE -> showAnswer(message.content(), message.citations());
            case INSUFFICIENT -> showInsufficient();
            case ERROR -> showError(message.content().isBlank() ? UserMessage.SOMETHING_WENT_WRONG
                    : UserMessage.of(message.content()), last);
            case CANCELLED -> showStopped(message.content(), message.citations(), last);
        }
    }

    /** Names the sections the cited passages come from, once they are known. */
    void setPassages(Map<String, SourcePassage> known) {
        passages = Map.copyOf(known);
        if (sources != null) {
            fillSources();
        }
    }

    private void finish(State outcome) {
        previewTimer.stop();
        state = outcome;
        phase = null;
        preview = null;
        sources = null;
    }

    // ---- sources and actions -------------------------------------------------------------------------

    private void addSourcesAndActions(boolean copy) {
        if (!citations.isEmpty()) {
            Label overline = new Label("SOURCES");
            overline.getStyleClass().addAll("text-overline", "ask-sources-title");
            sources = new VBox();
            sources.getStyleClass().add("ask-sources-list");
            fillSources();
            VBox block = new VBox(overline, sources);
            block.getStyleClass().add("ask-sources");
            answer.getChildren().add(block);
        }
        if (copy) {
            Button copyButton = new Button("Copy answer", Icons.of(Feather.COPY, 14));
            copyButton.getStyleClass().addAll("quiet", "neutral", "small", "ask-copy");
            copyButton.setOnAction(event -> copy(copyButton));
            HBox actions = new HBox(4, copyButton);
            actions.getStyleClass().add("ask-actions");
            actions.setAlignment(Pos.CENTER_LEFT);
            answer.getChildren().add(actions);
        }
    }

    private void fillSources() {
        Map<String, Citation> distinct = new LinkedHashMap<>();
        citations.stream().sorted(java.util.Comparator.comparingInt(Citation::number)).forEach(c ->
                distinct.putIfAbsent(c.documentId() + "|" + c.pageStart() + "|" + c.pageEnd() + "|" + c.chunkId(), c));
        List<Node> rows = new ArrayList<>();
        for (Citation citation : distinct.values()) {
            rows.add(sourceRow(citation));
        }
        sources.getChildren().setAll(rows);
    }

    private Button sourceRow(Citation citation) {
        Label pages = new Label(Formats.pages(citation));
        pages.getStyleClass().add("ask-source-pages");
        pages.setMinWidth(Region.USE_PREF_SIZE);
        String documentTitle = citation.documentTitle().isBlank() ? "Course material" : citation.documentTitle();
        Label title = new Label(documentTitle);
        title.getStyleClass().add("ask-source-title");
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setMinWidth(40);
        HBox graphic = new HBox(10, pages, title);
        graphic.setAlignment(Pos.CENTER_LEFT);
        String section = sectionOf(citation);
        String accessible = documentTitle + ", " + Formats.pages(citation);
        if (section != null) {
            Label sectionLabel = new Label(section);
            sectionLabel.getStyleClass().add("ask-source-section");
            sectionLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            sectionLabel.setMinWidth(40);
            graphic.getChildren().addAll(dot(), sectionLabel);
            accessible += ", " + section;
        }
        Button row = new Button(null, graphic);
        row.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        row.getStyleClass().add("ask-source-row");
        row.setMnemonicParsing(false);
        row.setMaxWidth(Double.MAX_VALUE);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setAccessibleText("Open " + accessible);
        row.getProperties().put(AskPage.CITATION_KEY, citation);
        row.setOnAction(event -> host.openSource(citation));
        return row;
    }

    private static Label dot() {
        Label dot = new Label("·");
        dot.getStyleClass().add("ask-source-section");
        dot.setMinWidth(Region.USE_PREF_SIZE);
        return dot;
    }

    /** The section a citation comes from, when its passage is known and has one. */
    String sectionOf(Citation citation) {
        SourcePassage passage = citation.chunkId() == null ? null : passages.get(citation.chunkId());
        return passage == null || passage.sectionTitle() == null || passage.sectionTitle().isBlank()
                ? null : passage.sectionTitle().strip();
    }

    private void copy(Button button) {
        ClipboardContent content = new ClipboardContent();
        content.putString(AnswerText.forClipboard(text, citations));
        try {
            Clipboard.getSystemClipboard().setContent(content);
        } catch (RuntimeException e) {
            LOG.warn("Could not copy an answer", e);
            return;
        }
        button.setText("Copied");
        button.setGraphic(Icons.of(Feather.CHECK, 14));
        PauseTransition back = new PauseTransition(COPIED_DURATION);
        back.setOnFinished(event -> {
            button.setText("Copy answer");
            button.setGraphic(Icons.of(Feather.COPY, 14));
        });
        back.play();
    }
}
