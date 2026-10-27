package edu.learnsphere.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.spi.ConceptDraft;
import edu.learnsphere.core.spi.GradeResult;
import edu.learnsphere.core.spi.PageTranscription;
import edu.learnsphere.core.util.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How model replies become results, without a server. */
class ReplyParsingTest {

    private static GradeResult grade(String json) {
        return LlamaShortAnswerGrader.toResult(Json.tree(json));
    }

    @Test
    void aFullyCoveredAnswerIsCorrect() {
        GradeResult result = grade("""
                {"points":[{"point":"a","status":"covered"},{"point":"b","status":"covered"}],"errors":[],"feedback":"Well put."}""");
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.verdict()).isEqualTo(GradeResult.Verdict.CORRECT);
        assertThat(result.missingPoints()).isEmpty();
        assertThat(result.feedback()).isEqualTo("Well put.");
    }

    @Test
    void wrongStatementsCostAQuarterEachUpToTwo() {
        GradeResult one = grade("""
                {"points":[{"point":"a","status":"covered"}],"errors":["claims the TLB is on disk"],"feedback":"x"}""");
        assertThat(one.score()).isEqualTo(0.75);
        assertThat(one.verdict()).isEqualTo(GradeResult.Verdict.CORRECT);
        GradeResult three = grade("""
                {"points":[{"point":"a","status":"covered"}],"errors":["e1","e2","e3"],"feedback":"x"}""");
        assertThat(three.score()).isEqualTo(0.5);
        assertThat(three.verdict()).isEqualTo(GradeResult.Verdict.PARTIAL);
        GradeResult blankErrors = grade("""
                {"points":[{"point":"a","status":"covered"}],"errors":["  "],"feedback":"x"}""");
        assertThat(blankErrors.score()).isEqualTo(1.0);
    }

    @Test
    void scoresNeverGoBelowZeroAndRoundToHundredths() {
        assertThat(grade("""
                {"points":[{"point":"a","status":"missing"}],"errors":["e1","e2"],"feedback":"x"}""").score()).isZero();
        assertThat(grade("""
                {"points":[{"point":"a","status":"covered"},{"point":"b","status":"covered"},{"point":"c","status":"missing"}],
                 "errors":[],"feedback":"x"}""").score()).isEqualTo(0.67);
    }

    @Test
    void aRightStartWithNothingWrongIsPartlyRight() {
        GradeResult vague = grade("""
                {"points":[{"point":"a fast hardware cache","status":"partly"},{"point":"avoids the page table","status":"missing"}],
                 "errors":[],"feedback":"x"}""");
        assertThat(vague.score()).isEqualTo(0.5);
        assertThat(vague.verdict()).isEqualTo(GradeResult.Verdict.PARTIAL);
        GradeResult oneOfThree = grade("""
                {"points":[{"point":"a","status":"covered"},{"point":"b","status":"missing"},{"point":"c","status":"missing"}],
                 "errors":[],"feedback":"x"}""");
        assertThat(oneOfThree.verdict()).isEqualTo(GradeResult.Verdict.PARTIAL);
        // Something wrong, or nothing right, earns no such allowance.
        GradeResult withAnError = grade("""
                {"points":[{"point":"a","status":"partly"},{"point":"b","status":"missing"}],
                 "errors":["says the TLB is on disk"],"feedback":"x"}""");
        assertThat(withAnError.score()).isZero();
        assertThat(withAnError.verdict()).isEqualTo(GradeResult.Verdict.INCORRECT);
        assertThat(grade("""
                {"points":[{"point":"a","status":"missing"}],"errors":[],"feedback":"x"}""").score()).isZero();
    }

    @Test
    void missingFeedbackGetsAPlainDefault() {
        GradeResult result = grade("""
                {"points":[{"point":"the result depends on timing.","status":"missing"}],"errors":[],"feedback":" "}""");
        assertThat(result.verdict()).isEqualTo(GradeResult.Verdict.INCORRECT);
        assertThat(result.feedback()).isNotBlank();
        assertThat(result.missingPoints()).containsExactly("The result depends on timing");
    }

    @Test
    void feedbackNeverTalksAboutSpelling() {
        GradeResult result = grade("""
                {"points":[{"point":"Mutual exclusion","status":"covered"}],"errors":[],
                 "feedback":"Great job naming all four conditions! Just polish the spelling of 'exclusion' and the hyphenation."}""");
        assertThat(result.feedback()).isEqualTo("Great job naming all four conditions!");
        assertThat(LlamaShortAnswerGrader.withoutSpellingNotes("Watch your spelling.")).isEmpty();
        assertThat(grade("""
                {"points":[{"point":"x","status":"covered"}],"errors":[],"feedback":"Mind the typos."}""").feedback())
                .isEqualTo("That covers the key points.");
    }

    @Test
    void lonePageNumbersAtTheEdgesAreDropped() {
        assertThat(LlamaPageReader.toTranscription("# Title\n\nText.\n\n17", false).markdown())
                .isEqualTo("# Title\n\nText.");
        assertThat(LlamaPageReader.dropPageNumbers("- 4 -\nText\nPage 5 of 40")).isEqualTo("Text");
        assertThat(LlamaPageReader.dropPageNumbers("1. First step\n2. Second step")).isEqualTo(
                "1. First step\n2. Second step");
        assertThat(LlamaPageReader.dropPageNumbers("42")).isEqualTo("42");
        assertThat(LlamaPageReader.dropPageNumbers("| a | 1 |\n| b | 2 |")).isEqualTo("| a | 1 |\n| b | 2 |");
    }

    @Test
    void pageRepliesLoseTheirCodeFence() {
        PageTranscription page = LlamaPageReader.toTranscription("```markdown\n# Title\n\nBody text.\n```", false);
        assertThat(page.markdown()).isEqualTo("# Title\n\nBody text.");
        assertThat(page.illegibleSpans()).isEmpty();
    }

    @Test
    void repeatedLinesAreCutAndFlagged() {
        String looped = "Intro\n" + "same line\n".repeat(30) + "End";
        PageTranscription page = LlamaPageReader.toTranscription(looped, false);
        assertThat(page.markdown().split("same line", -1)).hasSize(LlamaPageReader.MAX_REPEATS + 1);
        assertThat(page.markdown()).endsWith("End");
        assertThat(page.illegibleSpans()).containsExactly(LlamaPageReader.REPEATED);
    }

    @Test
    void illegiblePartsAndCutOffPagesAreFlagged() {
        PageTranscription page = LlamaPageReader.toTranscription(
                "Text [illegible] more [illegible: handwritten formula] and", true);
        assertThat(page.illegibleSpans()).containsExactly(LlamaPageReader.UNREADABLE, "Handwritten formula",
                LlamaPageReader.CUT_OFF);
    }

    @Test
    void tableRowsThatRepeatLegitimatelyAreKeptUpToTheLimit() {
        String table = "| a | b |\n|---|---|\n| 1 | 1 |\n| 1 | 1 |";
        assertThat(LlamaPageReader.toTranscription(table, false).markdown()).isEqualTo(table);
    }

    @Test
    void conceptsKeepTheFirstOfEachNameAndStopAtFifteen() {
        StringBuilder json = new StringBuilder("{\"concepts\":[");
        for (int i = 0; i < 20; i++) {
            json.append(i == 0 ? "" : ",").append("{\"name\":\"Concept ").append(i).append("\",\"description\":\"d\"}");
        }
        json.append("]}");
        List<ConceptDraft> concepts = LlamaConceptExtractor.parse(Json.tree(json.toString()));
        assertThat(concepts).hasSize(LlamaConceptExtractor.MAX_CONCEPTS);
        assertThat(LlamaConceptExtractor.parse(Json.tree("""
                {"concepts":[{"name":"","description":"x"},{"name":"Round robin:","description":""}]}""")))
                .containsExactly(new ConceptDraft("Round robin", ""));
    }

    @Test
    void structuredRepliesMustBeOneJsonObject() {
        assertThat(StructuredChat.parse("{\"a\":1}")).isNotNull();
        assertThat(StructuredChat.parse("```json\n{\"a\":1}\n```")).isNotNull();
        assertThat(StructuredChat.parse("{\"a\":")).isNull();
        assertThat(StructuredChat.parse("[1,2]")).isNull();
        assertThat(StructuredChat.parse("")).isNull();
        assertThat(StructuredChat.parse(null)).isNull();
    }

    @Test
    void supportChecksDescribeTheItemKindFromItsContent() {
        assertThat(LlamaSupportChecker.typeOf("TRUE", "Threads share memory.")).isEqualTo("true/false statement");
        assertThat(LlamaSupportChecker.typeOf("TLB", "The ____ caches entries.")).startsWith("fill in the blank");
        assertThat(LlamaSupportChecker.typeOf("Frames", "What is paging?")).isEqualTo("question");
    }

    @Test
    void textCleanupHelpers() {
        assertThat(TextCleanup.oneLine("  a \n\t b ")).isEqualTo("a b");
        assertThat(TextCleanup.sentence("done")).isEqualTo("Done.");
        assertThat(TextCleanup.sentence("done?")).isEqualTo("Done?");
        assertThat(TextCleanup.key("Round-Robin (RR)!")).isEqualTo("round robin rr");
        assertThat(TextCleanup.capitalize("tlb")).isEqualTo("Tlb");
    }
}
