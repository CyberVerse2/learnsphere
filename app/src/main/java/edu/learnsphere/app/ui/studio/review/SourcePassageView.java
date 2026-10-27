package edu.learnsphere.app.ui.studio.review;

import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.service.SourcePassage;
import java.util.function.BiConsumer;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The source column of a review card (and of the item editor): where the passage is, the support verdict
 * in plain words, and the passage with the sentences that carry the answer highlighted. Long passages show
 * an excerpt around the highlight with a way to read all of it.
 */
final class SourcePassageView extends VBox {

    /** About how many characters of a long passage the card shows before "Show whole passage". */
    static final int EXCERPT_CHARS = 520;

    /** How the source relates to the item. */
    enum Verdict { BACKED, DOUBTFUL, UNCHECKED, NO_SOURCE, SOURCE_REMOVED }

    private final SourcePassage source;
    private final Verdict verdict;
    private final PassageText passage = new PassageText("serif", "review-passage-run");
    private final Button expand = new Button();
    private String evidence = "";
    private boolean whole;

    /**
     * @param onOpenPage opens the passage's page, given the source and the text to find on it; null hides
     *                   "Open page"
     */
    SourcePassageView(LearningItem item, SourcePassage source, BiConsumer<SourcePassage, String> onOpenPage) {
        this.source = source;
        this.verdict = verdictOf(item, source);
        getStyleClass().addAll("review-source", "source-" + verdict.name().toLowerCase(java.util.Locale.ROOT));
        setSpacing(10);
        setMaxHeight(Region.USE_PREF_SIZE);
        getChildren().add(verdictLine(item));
        if (source == null) {
            Label none = new Label(verdict == Verdict.SOURCE_REMOVED
                    ? "The PDF this came from was removed, so there's no passage to compare with."
                    : "There's no source passage to check it against.");
            none.getStyleClass().add("text-caption");
            none.setWrapText(true);
            getChildren().add(none);
            return;
        }
        Label document = new Label(source.ref().documentTitle());
        document.getStyleClass().addAll("medium", "source-document");
        document.setWrapText(true);
        String where = Formats.pages(source.ref().pageStart(), source.ref().pageEnd());
        if (source.sectionTitle() != null && !source.sectionTitle().isBlank()) {
            where += " · " + source.sectionTitle();
        }
        Label place = new Label(where);
        place.getStyleClass().add("text-caption");
        place.setWrapText(true);
        VBox names = new VBox(2, document, place);
        HBox.setHgrow(names, Priority.ALWAYS);
        HBox head = new HBox(12, names);
        head.setAlignment(Pos.TOP_LEFT);
        if (onOpenPage != null) {
            Button open = new Button("Open page", Icons.of(Feather.BOOK_OPEN, 14));
            open.getStyleClass().addAll("quiet", "small");
            open.setMinWidth(Region.USE_PREF_SIZE);
            open.setAccessibleText("Open page " + source.ref().pageStart() + " of " + source.ref().documentTitle());
            open.setOnAction(event -> onOpenPage.accept(source, textToFind()));
            head.getChildren().add(open);
        }
        expand.getStyleClass().addAll("quiet", "small", "passage-expand");
        expand.setOnAction(event -> {
            whole = !whole;
            render();
        });
        getChildren().addAll(head, passage, expand);
        setEvidence(SupportingText.evidence(item.type(), item.prompt(), item.answer()));
    }

    /** Marks the sentences that carry {@code text} (the answer, as the creator edits it). */
    void setEvidence(String text) {
        evidence = text == null ? "" : text;
        render();
    }

    Verdict verdict() {
        return verdict;
    }

    /** The highlighted text on screen, or null when nothing is highlighted. */
    String highlightedText() {
        return passage.markedText();
    }

    /** The passage text on screen (an excerpt of a long passage until it is expanded). */
    String shownText() {
        return passage.text();
    }

    static Verdict verdictOf(LearningItem item, SourcePassage source) {
        if (source == null) {
            return item.origin() == ItemOrigin.AI || item.sourceChunkId() != null ? Verdict.SOURCE_REMOVED : Verdict.NO_SOURCE;
        }
        return switch (item.support()) {
            case SUPPORTED -> Verdict.BACKED;
            case UNSUPPORTED -> Verdict.DOUBTFUL;
            case UNCHECKED -> Verdict.UNCHECKED;
        };
    }

    /** The verdict in words, as the card and the editor say it. */
    static String verdictText(Verdict verdict) {
        return switch (verdict) {
            case BACKED -> "The source backs this answer";
            case DOUBTFUL -> "Check this: the source may not back it";
            case UNCHECKED -> "Not checked against the source";
            case NO_SOURCE -> "Written by hand";
            case SOURCE_REMOVED -> "Source PDF removed";
        };
    }

    private HBox verdictLine(LearningItem item) {
        Feather icon = switch (verdict) {
            case BACKED -> Feather.CHECK_CIRCLE;
            case DOUBTFUL -> Feather.ALERT_TRIANGLE;
            case UNCHECKED -> Feather.HELP_CIRCLE;
            case NO_SOURCE -> Feather.EDIT_3;
            case SOURCE_REMOVED -> Feather.FILE_MINUS;
        };
        Label title = new Label(verdictText(verdict), Icons.of(icon, 14));
        title.getStyleClass().addAll("semibold", "verdict-title");
        title.setWrapText(true);
        VBox text = new VBox(2, title);
        String note = item.supportNote();
        if (verdict == Verdict.DOUBTFUL && note != null && !note.isBlank()) {
            Label noteLabel = new Label(note);
            noteLabel.getStyleClass().addAll("text-caption", "verdict-note");
            noteLabel.setWrapText(true);
            text.getChildren().add(noteLabel);
        }
        HBox line = new HBox(text);
        line.getStyleClass().add("verdict-line");
        return line;
    }

    private void render() {
        if (source == null) {
            return;
        }
        String full = source.text().strip();
        SupportingText.Range found = verdict == Verdict.DOUBTFUL ? null
                : SupportingText.find(full, evidence).orElse(null);
        boolean isLong = full.length() > EXCERPT_CHARS + 80;
        expand.setVisible(isLong);
        expand.setManaged(isLong);
        if (!isLong || whole) {
            passage.set(full, found);
            expand.setText("Show less");
            expand.setGraphic(Icons.of(Feather.CHEVRON_UP, 14));
            return;
        }
        SupportingText.Range cut = SupportingText.excerpt(full, found, EXCERPT_CHARS);
        String prefix = cut.start() > 0 ? "… " : "";
        String suffix = cut.end() < full.length() ? " …" : "";
        String shown = prefix + full.substring(cut.start(), cut.end()) + suffix;
        SupportingText.Range marked = null;
        if (found != null && found.start() >= cut.start() && found.end() <= cut.end()) {
            int shift = prefix.length() - cut.start();
            marked = new SupportingText.Range(found.start() + shift, found.end() + shift);
        }
        passage.set(shown, marked);
        expand.setText("Show whole passage");
        expand.setGraphic(Icons.of(Feather.CHEVRON_DOWN, 14));
    }

    /** What to look for on the PDF page: the highlighted sentences, or the start of the passage. */
    private String textToFind() {
        String marked = passage.markedText();
        if (marked != null && !marked.isBlank()) {
            return marked;
        }
        String full = source.text().strip();
        return full.length() > 300 ? full.substring(0, 300) : full;
    }

    /** True when a long passage is cut to an excerpt (until "Show whole passage"). */
    boolean isExcerpt() {
        return expand.isVisible() && !whole;
    }

    /** Shows the whole passage, as "Show whole passage" does. */
    void showWhole() {
        whole = true;
        render();
    }
}
