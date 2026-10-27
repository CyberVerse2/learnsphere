package edu.learnsphere.app.ui.ask;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.Citation;
import java.util.List;
import java.util.stream.Collectors;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/** Answers set from Markdown, with page chips where the citation markers stand. */
@ExtendWith(ApplicationExtension.class)
class MarkdownTextTest {

    private static final Citation ONE = new Citation(1, "c1", "d1", "Lecture 2", 12, 12, "Round robin");
    private static final Citation TWO = new Citation(2, "c2", "d1", "Lecture 2", 14, 15, "Context switch");

    @Start
    void start(Stage stage) {
        stage.setScene(new Scene(new StackPane(), 200, 200));
    }

    @Test
    void setsParagraphsWithEmphasisCodeAndChipsInPlaceOfMarkers() {
        VBox answer = render("Overhead *dominates* with **tiny** quanta [1][2]. Set `slice [1]` and see [7].");

        assertThat(answer.getChildren()).hasSize(1);
        TextFlow paragraph = (TextFlow) answer.getChildren().getFirst();
        assertThat(paragraph.getStyleClass()).contains("md-paragraph");
        assertThat(text(paragraph)).isEqualTo("Overhead dominates with tiny quanta {1}{2}. Set `slice [1]` and see.");
        assertThat(textNode(paragraph, "dominates").getStyleClass()).contains("serif-italic", "emphasis");
        assertThat(textNode(paragraph, "tiny").getStyleClass()).contains("serif-medium", "strong");
        assertThat(textNode(paragraph, "Overhead ").getStyleClass()).contains("text-reading", "md-text");
    }

    @Test
    void keepsARunOfChipsTogetherWithThePunctuationAfterIt() {
        VBox answer = render("Switching costs time [1] [2], and more.");

        TextFlow paragraph = (TextFlow) answer.getChildren().getFirst();
        HBox run = (HBox) paragraph.getChildren().stream().filter(HBox.class::isInstance).findFirst().orElseThrow();
        assertThat(run.getStyleClass()).contains("md-chips");
        assertThat(run.getChildren()).hasSize(3);
        assertThat(((Label) run.getChildren().get(0)).getText()).isEqualTo("{1}");
        assertThat(((Label) run.getChildren().get(1)).getText()).isEqualTo("{2}");
        assertThat(((Text) run.getChildren().get(2)).getText()).isEqualTo(",");
    }

    @Test
    void setsListsHeadingsQuotesAndCodeBlocks() {
        VBox answer = render("""
                ## Two kinds

                - shared code [1]
                - own stack

                3. first
                4. second

                > quoted

                ```
                int quantum = 10; // [2]
                ```

                ---
                """);

        List<Node> blocks = answer.getChildren();
        assertThat(blocks).hasSize(6);
        assertThat(blocks.get(0).getStyleClass()).contains("md-heading", "md-h2");
        assertThat(markers(blocks.get(1))).containsExactly("•", "•");
        assertThat(blocks.get(1).lookupAll(".citation-chip-test")).hasSize(1);
        assertThat(markers(blocks.get(2))).containsExactly("3.", "4.");
        assertThat(blocks.get(3).getStyleClass()).contains("md-quote");
        assertThat(blocks.get(4).getStyleClass()).contains("md-codeblock");
        assertThat(((Text) blocks.get(4).lookup(".md-code-text")).getText()).isEqualTo("int quantum = 10; // [2]");
        assertThat(blocks.get(5).getStyleClass()).contains("md-rule");
    }

    @Test
    void anAnswerWithoutCitationsDropsItsMarkers() {
        VBox answer = MarkdownText.render("Nothing to cite [1].", List.of(), MarkdownTextTest::chip);

        assertThat(text((TextFlow) answer.getChildren().getFirst())).isEqualTo("Nothing to cite.");
    }

    private static VBox render(String markdown) {
        return MarkdownText.render(markdown, List.of(ONE, TWO), MarkdownTextTest::chip);
    }

    /** A stand-in chip whose text names its citation, {@code {n}}. */
    private static Node chip(Citation citation) {
        Label label = new Label("{" + citation.number() + "}");
        label.getStyleClass().add("citation-chip-test");
        return label;
    }

    private static List<String> markers(Node list) {
        return list.lookupAll(".md-marker").stream().map(node -> ((Label) node).getText()).toList();
    }

    /** The paragraph's text with chips as {@code {n}} and inline code in backquotes. */
    private static String text(Parent parent) {
        return parent.getChildrenUnmodifiable().stream().map(node -> switch (node) {
            case Text t -> t.getText();
            case Label l when l.getStyleClass().contains("md-code") -> "`" + l.getText() + "`";
            case Label l -> l.getText();
            case Parent p -> text(p);
            default -> "";
        }).collect(Collectors.joining());
    }

    private static Text textNode(TextFlow flow, String value) {
        return flow.getChildren().stream().filter(Text.class::isInstance).map(Text.class::cast)
                .filter(t -> t.getText().equals(value)).findFirst().orElseThrow();
    }
}
