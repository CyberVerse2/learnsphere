package edu.learnsphere.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.EvalReport;
import edu.learnsphere.ai.LocalAi;
import edu.learnsphere.ai.OsNotes;
import edu.learnsphere.ai.RealRuntime;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.spi.AiCapabilities;
import edu.learnsphere.core.spi.ConceptDraft;
import edu.learnsphere.core.spi.GradeResult;
import edu.learnsphere.core.spi.ItemDraft;
import edu.learnsphere.core.spi.ItemGenerationRequest;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.SupportVerdict;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Structured generation against the real chat model: concept extraction, item generation (validity per
 * type, variety, grounding), support checks and short-answer grading, plus JSON validity over every
 * structured reply. Results go to {@code build/reports/ai-eval/generation.md}.
 */
@ExtendWith(RealRuntime.Resolver.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GenerationQualityIT {

    private static AiCapabilities ai;
    private static List<Passage> notes;
    private static long repliesBefore;
    private static long invalidBefore;
    private static final EvalReport REPORT = new EvalReport("generation", "Structured generation (" + OsNotes.TITLE + ")");
    private static final List<String> CONCEPTS = new ArrayList<>();
    private static final List<ItemDraft> ITEMS = new ArrayList<>();

    @BeforeAll
    static void setUp(LocalAi localAi) {
        ai = localAi.capabilities();
        notes = OsNotes.passages();
        repliesBefore = StructuredChat.REPLIES.get();
        invalidBefore = StructuredChat.INVALID_REPLIES.get();
    }

    @AfterAll
    static void summary() {
        long replies = StructuredChat.REPLIES.get() - repliesBefore;
        long invalid = StructuredChat.INVALID_REPLIES.get() - invalidBefore;
        REPORT.line("## JSON validity").line("")
                .line("%d structured replies, %d did not parse (%s valid).", replies, invalid,
                        EvalReport.pct(replies == 0 ? 0 : (double) (replies - invalid) / replies));
        REPORT.write();
        if (!ITEMS.isEmpty()) {
            // Item generation ran (not just a single test picked from this class): enough replies to judge.
            assertThat(replies).as("structured replies").isGreaterThanOrEqualTo(20);
        }
        assertThat(invalid).as("replies that did not parse").isZero();
    }

    @Test
    @Order(1)
    void extractsFiveToFifteenDistinctConcepts() {
        long start = System.nanoTime();
        List<ConceptDraft> concepts = ai.conceptExtractor().extract("Processes, scheduling and memory", notes,
                CancellationToken.NONE);
        double millis = (System.nanoTime() - start) / 1e6;
        REPORT.line("## Concepts (%d, %s)", concepts.size(), EvalReport.ms(millis)).line("");
        concepts.forEach(c -> REPORT.line("- **%s**: %s", c.name(), c.description()));
        REPORT.line("");
        assertThat(concepts).hasSizeBetween(5, 15);
        assertThat(concepts.stream().map(c -> c.name().toLowerCase()).collect(Collectors.toSet())).hasSize(concepts.size());
        assertThat(concepts).allSatisfy(c -> {
            assertThat(c.name().split("\\s+").length).isLessThanOrEqualTo(6);
            assertThat(c.description()).isNotBlank();
        });
        concepts.stream().limit(6).map(ConceptDraft::name).forEach(CONCEPTS::add);
    }

    @Test
    @Order(2)
    void generatesValidVariedGroundedItems() {
        record Run(String label, Set<ItemType> types, int count, Difficulty difficulty, List<String> concepts) {
        }
        List<Run> runs = List.of(
                new Run("every type, mixed difficulty, with concepts", EnumSet.allOf(ItemType.class), 10, null, CONCEPTS),
                new Run("multiple choice, hard", EnumSet.of(ItemType.MCQ), 5, Difficulty.HARD, List.of()),
                new Run("true/false and cloze", EnumSet.of(ItemType.TRUE_FALSE, ItemType.CLOZE), 6, null, List.of()),
                new Run("flashcards and short answers, easy", EnumSet.of(ItemType.FLASHCARD, ItemType.SHORT_ANSWER), 4,
                        Difficulty.EASY, List.of()));
        int requested = 0;
        List<Double> perItem = new ArrayList<>();
        for (Run run : runs) {
            requested += run.count();
            long start = System.nanoTime();
            List<ItemDraft> drafts = ai.itemGenerator().generate(new ItemGenerationRequest(notes, run.concepts(),
                    run.types(), run.count(), run.difficulty()), CancellationToken.NONE);
            double millis = (System.nanoTime() - start) / 1e6;
            if (!drafts.isEmpty()) {
                perItem.add(millis / drafts.size());
            }
            REPORT.line("## Items: %s (%d of %d, %s)", run.label(), drafts.size(), run.count(), EvalReport.ms(millis))
                    .line("");
            for (ItemDraft d : drafts) {
                REPORT.line("- `%s` %s · p[%d] · %s  ", d.type().code(), d.difficulty().name().toLowerCase(),
                        d.passageNumber(), d.conceptNames());
                REPORT.line("  **%s**  ", d.prompt());
                REPORT.line("  → %s%s  ", d.answer(), d.distractors().isEmpty() ? "" : "  ✗ " + String.join(" | ", d.distractors()));
                REPORT.line("  _%s_", d.explanation());
            }
            REPORT.line("");
            drafts.forEach(GenerationQualityIT::assertValid);
            if (run.difficulty() != null) {
                assertThat(drafts).allSatisfy(d -> assertThat(d.difficulty()).isEqualTo(run.difficulty()));
            }
            assertThat(drafts).allSatisfy(d -> assertThat(run.types()).contains(d.type()));
            ITEMS.addAll(drafts);
        }

        Map<Difficulty, Integer> difficulties = new EnumMap<>(Difficulty.class);
        Map<ItemType, Integer> types = new EnumMap<>(ItemType.class);
        Set<Integer> passagesUsed = new HashSet<>();
        int trueStatements = 0;
        int falseStatements = 0;
        for (ItemDraft d : ITEMS) {
            difficulties.merge(d.difficulty(), 1, Integer::sum);
            types.merge(d.type(), 1, Integer::sum);
            passagesUsed.add(d.passageNumber());
            if (d.type() == ItemType.TRUE_FALSE) {
                if (d.answer().equals("true")) {
                    trueStatements++;
                } else {
                    falseStatements++;
                }
            }
        }
        Set<String> prompts = ITEMS.stream().map(d -> d.prompt().toLowerCase()).collect(Collectors.toSet());
        REPORT.line("## Item summary").line("")
                .line("| Measure | Result |").line("|---|---|")
                .line("| Valid drafts kept | %d/%d (%s) |", ITEMS.size(), requested, EvalReport.pct((double) ITEMS.size() / requested))
                .line("| Distinct prompts | %d/%d |", prompts.size(), ITEMS.size())
                .line("| Types | %s |", types)
                .line("| Difficulties | %s |", difficulties)
                .line("| True / false statements | %d / %d |", trueStatements, falseStatements)
                .line("| Passages used | %d of %d |", passagesUsed.size(), notes.size())
                .line("| Time per kept item (median) | %s |", EvalReport.ms(EvalReport.median(perItem)))
                .line("");

        assertThat(ITEMS.size()).as("valid drafts kept").isGreaterThanOrEqualTo((int) Math.ceil(requested * 0.8));
        assertThat(prompts).as("distinct prompts").hasSize(ITEMS.size());
        assertThat(difficulties.keySet()).as("difficulties").containsExactlyInAnyOrder(Difficulty.values());
        assertThat(passagesUsed.size()).as("passages used").isGreaterThanOrEqualTo(5);
        assertThat(trueStatements).isPositive();
        assertThat(falseStatements).isPositive();
    }

    /** The content conventions of {@link ItemType} plus the prompt rules. */
    static void assertValid(ItemDraft draft) {
        assertThat(draft.prompt()).isNotBlank();
        assertThat(draft.answer()).isNotBlank();
        assertThat(draft.prompt().toLowerCase()).doesNotContain("passage").doesNotContain("multiple-choice")
                .doesNotContain("true or false");
        assertThat(draft.passageNumber()).isBetween(1, notes.size());
        assertThat(draft.explanation().toLowerCase()).doesNotContain("passage");
        switch (draft.type()) {
            case MCQ -> assertThat(draft.distractors()).hasSizeBetween(2, 4).doesNotHaveDuplicates()
                    .doesNotContain(draft.answer());
            case TRUE_FALSE -> assertThat(draft.answer()).isIn("true", "false");
            case CLOZE -> {
                assertThat(draft.prompt().split(ItemType.CLOZE_BLANK, -1)).hasSize(2);
                assertThat(draft.prompt().toLowerCase()).doesNotContain(draft.answer().toLowerCase());
            }
            case SHORT_ANSWER, FLASHCARD -> assertThat(draft.distractors()).isEmpty();
        }
    }

    @Test
    @Order(3)
    void supportCheckerSeparatesSupportedFromUnsupportedAnswers() {
        record Case(String prompt, String answer, int passage, boolean supported) {
        }
        List<Case> cases = List.of(
                new Case("What does a TLB cache?", "Recently used page-table entries", 7, true),
                new Case("What does a TLB cache?", "Recently used disk blocks", 7, false),
                new Case("Which scheduling policy gives the minimum average waiting time?", "Shortest-job-first", 3, true),
                new Case("Which scheduling policy gives the minimum average waiting time?", "Round robin", 3, false),
                new Case("Threads of the same process share their stack.", "false", 1, true),
                new Case("Threads of the same process share their address space.", "true", 1, true),
                new Case("Paging avoids internal fragmentation.", "true", 6, false),
                new Case("A deadlock needs ____, hold and wait, no preemption and circular wait.", "mutual exclusion", 5, true),
                new Case("What is Belady's anomaly?", "More frames causing more page faults under FIFO", 9, true),
                new Case("What does the valid bit in a page-table entry record?", "Whether the page was modified", 8, false),
                new Case("What is the default page size on Windows?", "8 KB", 6, false));
        int correct = 0;
        List<Double> millis = new ArrayList<>();
        REPORT.line("## Support checks").line("");
        for (Case c : cases) {
            long start = System.nanoTime();
            SupportVerdict verdict = ai.supportChecker().check(c.prompt(), c.answer(), notes.get(c.passage() - 1),
                    CancellationToken.NONE);
            millis.add((System.nanoTime() - start) / 1e6);
            boolean right = verdict.supported() == c.supported();
            if (right) {
                correct++;
            }
            REPORT.line("- %s %s → %s (expected %s): %s", right ? "✓" : "✗", c.prompt() + " / " + c.answer(),
                    verdict.supported() ? "supported" : "not supported", c.supported() ? "supported" : "not supported",
                    verdict.note());
        }
        int supportedDrafts = 0;
        List<String> unsupported = new ArrayList<>();
        for (ItemDraft d : ITEMS) {
            SupportVerdict verdict = ai.supportChecker().check(d.prompt(), d.answer(), notes.get(d.passageNumber() - 1),
                    CancellationToken.NONE);
            if (verdict.supported()) {
                supportedDrafts++;
            } else {
                unsupported.add(d.type().code() + " p[" + d.passageNumber() + "] " + d.prompt() + " → " + d.answer()
                        + " (" + verdict.note() + ")");
            }
        }
        REPORT.line("")
                .line("Crafted cases right: %d/%d; generated drafts judged supported by their passage: %d/%d; "
                        + "median %s per check.", correct, cases.size(), supportedDrafts, ITEMS.size(),
                        EvalReport.ms(EvalReport.median(millis)))
                .line("");
        unsupported.forEach(u -> REPORT.line("- not supported: %s", u));
        REPORT.line("");
        assertThat(correct).as("support verdicts right").isGreaterThanOrEqualTo(cases.size() - 1);
        if (!ITEMS.isEmpty()) {
            assertThat((double) supportedDrafts / ITEMS.size()).as("generated drafts supported").isGreaterThanOrEqualTo(0.8);
        }
    }

    @Test
    @Order(4)
    void gradesShortAnswersFairlyWithSpecificFeedback() {
        record Case(String question, String reference, String answer, GradeResult.Verdict expected) {
        }
        String raceQ = "What is a race condition?";
        String raceRef = "A race condition happens when two or more threads access shared data at the same time, at least "
                + "one of them writes it, and the result depends on the order in which the accesses happen.";
        String tlbQ = "Why does a TLB speed up address translation?";
        String tlbRef = "The TLB is a small, fast hardware cache of recently used page-table entries, so most "
                + "translations avoid reading the page table in main memory.";
        String deadlockQ = "Name the four conditions for deadlock.";
        String deadlockRef = "Mutual exclusion, hold and wait, no preemption and circular wait.";
        List<Case> cases = List.of(
                new Case(raceQ, raceRef, "When several threads use the same shared variable at once, one of them changes it, "
                        + "and what you get depends on which thread runs first.", GradeResult.Verdict.CORRECT),
                new Case(raceQ, raceRef, "its when threads share data", GradeResult.Verdict.PARTIAL),
                new Case(raceQ, raceRef, "A race condition is when the CPU runs too fast and overheats.", GradeResult.Verdict.INCORRECT),
                new Case(tlbQ, tlbRef, "Because it caches recent page table entries in fast hardware, so the CPU usually "
                        + "doesn't need to read the page table from memory.", GradeResult.Verdict.CORRECT),
                new Case(tlbQ, tlbRef, "It stores whole pages of data so the disk is never needed.", GradeResult.Verdict.INCORRECT),
                new Case(tlbQ, tlbRef, "It is a cache.", GradeResult.Verdict.PARTIAL),
                new Case(deadlockQ, deadlockRef, "mutual exlusion, hold & wait, no pre-emption, circular waiting",
                        GradeResult.Verdict.CORRECT),
                new Case(deadlockQ, deadlockRef, "Mutual exclusion and circular wait.", GradeResult.Verdict.PARTIAL),
                new Case(deadlockQ, deadlockRef, "I don't know", GradeResult.Verdict.INCORRECT));
        int exact = 0;
        int withinOne = 0;
        List<Double> millis = new ArrayList<>();
        REPORT.line("## Short-answer grading").line("");
        for (Case c : cases) {
            long start = System.nanoTime();
            GradeResult grade = ai.grader().grade(c.question(), c.reference(), c.answer(), null, CancellationToken.NONE);
            millis.add((System.nanoTime() - start) / 1e6);
            if (grade.verdict() == c.expected()) {
                exact++;
            }
            if (Math.abs(grade.verdict().ordinal() - c.expected().ordinal()) <= 1) {
                withinOne++;
            }
            REPORT.line("- %s \"%s\" → %.2f %s (expected %s). Feedback: %s Missing: %s",
                    grade.verdict() == c.expected() ? "✓" : "✗", c.answer(), grade.score(), grade.verdict(),
                    c.expected(), grade.feedback(), grade.missingPoints());
            assertThat(grade.feedback()).isNotBlank();
        }
        REPORT.line("").line("Exact verdicts: %d/%d; within one step: %d/%d; median %s per grade.", exact,
                cases.size(), withinOne, cases.size(), EvalReport.ms(EvalReport.median(millis))).line("");
        // 9/9 when last measured; one miss is allowed for run-to-run variation of the model.
        assertThat(exact).as("exact verdicts").isGreaterThanOrEqualTo(cases.size() - 1);
        assertThat(withinOne).as("verdicts within one step").isEqualTo(cases.size());
    }
}
