package edu.learnsphere.app.ui.studio.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.ItemContent;
import edu.learnsphere.core.service.Workspace;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ItemRulesTest {

    @TempDir
    Path dir;

    @Test
    void multipleChoiceNeedsOneCorrectAnswerAndTwoToFourDifferentWrongOptions() {
        assertThat(messages(ItemType.MCQ, "Q?", "A", List.of("B", "C"))).isEmpty();
        assertThat(messages(ItemType.MCQ, "Q?", "A", List.of("B"))).containsExactly(ItemRules.OPTION_COUNT);
        assertThat(messages(ItemType.MCQ, "Q?", "A", List.of("B", "C", "D", "E", "F"))).containsExactly(ItemRules.OPTION_COUNT);
        assertThat(messages(ItemType.MCQ, "Q?", "A", List.of("B", " "))).containsExactly(ItemRules.OPTION_BLANK);
        assertThat(messages(ItemType.MCQ, "Q?", "A", List.of("b", "a"))).containsExactly(ItemRules.OPTIONS_DIFFERENT);
        assertThat(messages(ItemType.MCQ, "", "", List.of("B", "C")))
                .containsExactly("Write the question.", "Write the correct answer.");
    }

    @Test
    void pointsAtTheWrongOptionThatNeedsFixing() {
        List<ItemRules.Problem> problems = ItemRules.check(ItemType.MCQ, "Q?", "A", "", List.of("B", "", "b"));

        assertThat(problems).containsExactly(
                new ItemRules.Problem(ItemRules.Field.OPTIONS, 1, ItemRules.OPTION_BLANK),
                new ItemRules.Problem(ItemRules.Field.OPTIONS, 2, ItemRules.OPTIONS_DIFFERENT));
    }

    @Test
    void eachTypeAsksForItsOwnParts() {
        assertThat(messages(ItemType.TRUE_FALSE, "", "maybe", List.of()))
                .containsExactly("Write the statement to judge as true or false.", ItemRules.TRUE_OR_FALSE);
        assertThat(messages(ItemType.TRUE_FALSE, "Water boils at 100 °C at sea level.", "True", List.of())).isEmpty();
        assertThat(messages(ItemType.CLOZE, "No blank here.", "word", List.of())).containsExactly(ItemRules.ONE_BLANK);
        assertThat(messages(ItemType.CLOZE, "Two ____ and ____.", "x", List.of())).containsExactly(ItemRules.ONE_BLANK);
        assertThat(messages(ItemType.CLOZE, "One ____ here.", "", List.of())).containsExactly("Write the missing words.");
        assertThat(messages(ItemType.FLASHCARD, "Front", "", List.of())).containsExactly("Write the back of the card.");
        assertThat(messages(ItemType.FLASHCARD, "", "Back", List.of())).containsExactly("Write the front of the card.");
        assertThat(messages(ItemType.SHORT_ANSWER, "Why?", "", List.of())).containsExactly("Write a model answer.");
        assertThat(messages(ItemType.SHORT_ANSWER, "Why?",
                new ShortAnswerText("", List.of("a point")).compose(), List.of())).containsExactly("Write a model answer.");
        assertThat(messages(ItemType.SHORT_ANSWER, "Why?",
                new ShortAnswerText("Because.", List.of("a point")).compose(), List.of())).isEmpty();
    }

    @Test
    void longTextIsTooLong() {
        String long_ = "x".repeat(ItemRules.MAX_PROMPT + 1);
        assertThat(messages(ItemType.FLASHCARD, long_, "Back", List.of())).containsExactly(ItemRules.TOO_LONG);
        assertThat(ItemRules.check(ItemType.FLASHCARD, "F", "B", "e".repeat(ItemRules.MAX_EXPLANATION + 1), List.of()))
                .extracting(ItemRules.Problem::field).containsExactly(ItemRules.Field.EXPLANATION);
    }

    /** Saving reports the first problem the editor shows, in the same words. */
    @Test
    void agreesWithWhatSavingReports() {
        List<ItemContent> contents = List.of(
                content(ItemType.MCQ, "Q?", "A", List.of("B")),
                content(ItemType.MCQ, "Q?", "A", List.of("B", "")),
                content(ItemType.MCQ, "Q?", "A", List.of("a", "B")),
                content(ItemType.MCQ, " ", "A", List.of("B", "C")),
                content(ItemType.MCQ, "Q?", "", List.of("B", "C")),
                content(ItemType.TRUE_FALSE, "Statement.", "perhaps", List.of()),
                content(ItemType.TRUE_FALSE, "", "true", List.of()),
                content(ItemType.CLOZE, "No blank.", "x", List.of()),
                content(ItemType.CLOZE, "A ____ and ____.", "x", List.of()),
                content(ItemType.CLOZE, "A ____.", " ", List.of()),
                content(ItemType.FLASHCARD, "Front", "", List.of()),
                content(ItemType.SHORT_ANSWER, "Why?", "", List.of()),
                content(ItemType.FLASHCARD, "x".repeat(ItemRules.MAX_PROMPT + 1), "Back", List.of()));
        List<ItemContent> valid = List.of(
                content(ItemType.MCQ, "Q?", "A", List.of("B", "C", "D", "E")),
                content(ItemType.TRUE_FALSE, "Statement.", "False", List.of()),
                content(ItemType.CLOZE, "A ____ here.", "word", List.of()),
                content(ItemType.FLASHCARD, "Front", "Back", List.of()),
                content(ItemType.SHORT_ANSWER, "Why?", new ShortAnswerText("Because.", List.of("one", "two")).compose(), List.of()));
        try (RealWorkspaces real = RealWorkspaces.open(dir)) {
            Workspace ws = real.workspace();
            Course course = ws.courses().create(CourseDraft.titled("Rules"));
            for (ItemContent content : contents) {
                List<ItemRules.Problem> problems = ItemRules.check(content.type(), content.prompt(), content.answer(),
                        content.explanation(), content.distractors());
                assertThat(problems).as("%s", content).isNotEmpty();
                assertThatThrownBy(() -> ws.items().create(course.id(), null, content)).as("%s", content)
                        .isInstanceOf(InvalidInputException.class)
                        .satisfies(e -> assertThat(LearnSphereException.userMessageOf(e).text())
                                .isEqualTo(problems.getFirst().message()));
            }
            for (ItemContent content : valid) {
                assertThat(ItemRules.check(content.type(), content.prompt(), content.answer(), content.explanation(),
                        content.distractors())).as("%s", content).isEmpty();
                assertThat(ws.items().create(course.id(), null, content).id()).isNotBlank();
            }
        }
    }

    private static List<String> messages(ItemType type, String prompt, String answer, List<String> options) {
        return ItemRules.check(type, prompt, answer, "", options).stream().map(ItemRules.Problem::message).toList();
    }

    private static ItemContent content(ItemType type, String prompt, String answer, List<String> options) {
        return new ItemContent(type, prompt, answer, "", options, Difficulty.MEDIUM, Set.of());
    }
}
