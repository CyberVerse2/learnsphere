package edu.learnsphere.app.ui.ask;

import edu.learnsphere.core.model.Citation;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * Sets an answer written in Markdown as reading-type JavaFX nodes: paragraphs, headings, bullet and numbered
 * lists, quotes, emphasis, inline code and code blocks. Citation markers {@code [n]} become the node the
 * {@code chips} function makes for citation {@code n} (a page chip), right where the marker stands; markers
 * without a citation are left out. Links show their text only, since answers never leave the computer.
 *
 * <p>Style classes: {@code md} on the returned box, {@code md-paragraph}, {@code md-heading md-h1…md-h3},
 * {@code md-list}, {@code md-marker}, {@code md-quote}, {@code md-codeblock}, {@code md-rule} on blocks;
 * {@code md-text} (with {@code serif-italic} or {@code serif-medium} for emphasis), {@code md-code} and
 * {@code md-code-text} on text.
 */
final class MarkdownText {

    /** Stands in for a marker while parsing, so Markdown never reads {@code [1]} as link syntax. */
    private static final char OPEN = '';
    private static final char CLOSE = '';
    private static final Pattern HELD = Pattern.compile(OPEN + "(\\d{1,3})" + CLOSE);
    private static final Parser PARSER = Parser.builder().build();

    private final List<Citation> citations;
    private final Function<Citation, Node> chips;

    private MarkdownText(List<Citation> citations, Function<Citation, Node> chips) {
        this.citations = List.copyOf(citations);
        this.chips = chips;
    }

    /**
     * The answer as a box of blocks.
     *
     * @param citations the answer's citations (may be empty: markers are then left out)
     * @param chips     makes the inline node for a citation
     */
    static VBox render(String markdown, List<Citation> citations, Function<Citation, Node> chips) {
        Objects.requireNonNull(chips, "chips");
        String held = AnswerText.MARKER.matcher(markdown == null ? "" : markdown).replaceAll(OPEN + "$1" + CLOSE);
        MarkdownText renderer = new MarkdownText(citations, chips);
        VBox box = new VBox();
        box.getStyleClass().add("md");
        renderer.blocks(PARSER.parse(held), box);
        return box;
    }

    private void blocks(org.commonmark.node.Node parent, VBox into) {
        for (org.commonmark.node.Node block = parent.getFirstChild(); block != null; block = block.getNext()) {
            Node node = block(block);
            if (node != null) {
                into.getChildren().add(node);
            }
        }
    }

    private Node block(org.commonmark.node.Node block) {
        return switch (block) {
            case Paragraph p -> flow(p, "md-paragraph", Style.BODY);
            case Heading h -> flow(h, "md-heading md-h" + Math.min(3, h.getLevel()), Style.HEADING);
            case BulletList list -> list(list, -1);
            case OrderedList list -> list(list, list.getMarkerStartNumber() == null ? 1 : list.getMarkerStartNumber());
            case BlockQuote quote -> {
                VBox box = new VBox();
                box.getStyleClass().add("md-quote");
                blocks(quote, box);
                yield box;
            }
            case FencedCodeBlock code -> codeBlock(code.getLiteral());
            case IndentedCodeBlock code -> codeBlock(code.getLiteral());
            case HtmlBlock html -> plainBlock(html.getLiteral());
            case ThematicBreak rule -> {
                Region line = new Region();
                line.getStyleClass().add("md-rule");
                yield line;
            }
            case LinkReferenceDefinition definition -> null;
            default -> {
                VBox box = new VBox();
                blocks(block, box);
                yield box.getChildren().isEmpty() ? null : box;
            }
        };
    }

    private TextFlow flow(org.commonmark.node.Node block, String styleClasses, Style style) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().addAll(styleClasses.split(" "));
        inlines(block, flow, style);
        return flow;
    }

    private VBox list(ListBlock list, int start) {
        VBox box = new VBox();
        box.getStyleClass().add("md-list");
        if (list.isTight()) {
            box.getStyleClass().add("tight");
        }
        int number = start;
        for (org.commonmark.node.Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) {
                continue;
            }
            Label marker = new Label(start < 0 ? "•" : number + ".");
            marker.getStyleClass().addAll("md-marker", "text-reading");
            marker.setMinWidth(Region.USE_PREF_SIZE);
            HBox markerBox = new HBox(marker);
            markerBox.setAlignment(Pos.TOP_RIGHT);
            markerBox.getStyleClass().add("md-marker-box");
            VBox body = new VBox();
            body.getStyleClass().add("md-item");
            blocks(item, body);
            HBox.setHgrow(body, Priority.ALWAYS);
            body.setMinWidth(0);
            HBox row = new HBox(markerBox, body);
            row.getStyleClass().add("md-list-row");
            row.setAlignment(Pos.TOP_LEFT);
            box.getChildren().add(row);
            number++;
        }
        return box;
    }

    private Node codeBlock(String literal) {
        String code = restore(literal == null ? "" : literal.stripTrailing());
        Text text = new Text(code);
        text.getStyleClass().add("md-code-text");
        TextFlow flow = new TextFlow(text);
        VBox box = new VBox(flow);
        box.getStyleClass().add("md-codeblock");
        return box;
    }

    private Node plainBlock(String literal) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("md-paragraph");
        text(restore(literal == null ? "" : literal.strip()), flow, Style.BODY);
        return flow;
    }

    // ---- inlines -----------------------------------------------------------------------------------

    /** How a run of text is set. */
    private record Style(boolean heading, boolean emphasis, boolean strong) {
        static final Style BODY = new Style(false, false, false);
        static final Style HEADING = new Style(true, false, false);

        Style emphasised() {
            return new Style(heading, true, strong);
        }

        Style strengthened() {
            return new Style(heading, emphasis, true);
        }
    }

    private void inlines(org.commonmark.node.Node parent, TextFlow flow, Style style) {
        for (org.commonmark.node.Node inline = parent.getFirstChild(); inline != null; inline = inline.getNext()) {
            switch (inline) {
                case org.commonmark.node.Text t -> text(t.getLiteral(), flow, style);
                case Emphasis e -> inlines(e, flow, style.emphasised());
                case StrongEmphasis s -> inlines(s, flow, style.strengthened());
                case Code code -> {
                    Label label = new Label(restore(code.getLiteral()));
                    label.getStyleClass().add("md-code");
                    label.setMinWidth(Region.USE_PREF_SIZE);
                    flow.getChildren().add(label);
                }
                case SoftLineBreak b -> text(" ", flow, style);
                case HardLineBreak b -> text("\n", flow, style);
                case HtmlInline html -> text(html.getLiteral(), flow, style);
                default -> inlines(inline, flow, style);
            }
        }
    }

    /** Adds {@code literal}, turning held markers into chips. */
    private void text(String literal, TextFlow flow, Style style) {
        Matcher held = HELD.matcher(literal);
        int last = 0;
        while (held.find()) {
            addText(literal.substring(last, held.start()), flow, style);
            Citation citation = AnswerText.find(citations, Integer.parseInt(held.group(1)));
            if (citation != null) {
                Node chip = chips.apply(citation);
                if (chip != null) {
                    if (!flow.getChildren().isEmpty() && flow.getChildren().getLast().getProperties().containsKey(CHIP)) {
                        addText(" ", flow, style);
                    }
                    chip.getProperties().put(CHIP, Boolean.TRUE);
                    flow.getChildren().add(chip);
                }
            }
            last = held.end();
        }
        addText(literal.substring(last), flow, style);
    }

    private static final String CHIP = MarkdownText.class.getName() + ".chip";

    private static void addText(String value, TextFlow flow, Style style) {
        if (value.isEmpty()) {
            return;
        }
        Text text = new Text(value);
        text.getStyleClass().add("md-text");
        if (style.heading() || style.strong()) {
            text.getStyleClass().add("serif-medium");
        } else if (style.emphasis()) {
            text.getStyleClass().add("serif-italic");
        } else {
            text.getStyleClass().add("text-reading");
        }
        if (style.strong()) {
            text.getStyleClass().add("strong");
        }
        if (style.emphasis()) {
            text.getStyleClass().add("emphasis");
        }
        flow.getChildren().add(text);
    }

    /** Puts back markers held inside code, where they are just text. */
    private static String restore(String text) {
        return HELD.matcher(text).replaceAll("[$1]");
    }
}
