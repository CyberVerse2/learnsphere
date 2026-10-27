package edu.learnsphere.ai;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.ask.CitationValidator;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.spi.AiCapabilities;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.GroundedAnswer;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Grounded answers against the real models: retrieval of the right passage, citation accuracy, refusal on
 * questions the notes don't answer, and streaming latency. Results go to
 * {@code build/reports/ai-eval/answers.md}.
 */
@ExtendWith(RealRuntime.Resolver.class)
class AnswerQualityIT {

    private static final int TOP_K = 4;
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z\\-*•])|\\n+");

    private static AiCapabilities ai;
    private static List<Passage> notes;
    private static List<float[]> noteVectors;

    @BeforeAll
    static void setUp(LocalAi localAi) {
        ai = localAi.capabilities();
        notes = OsNotes.passages();
        noteVectors = ai.embedder().embed(notes.stream().map(Passage::text).toList(), EmbedKind.DOCUMENT,
                CancellationToken.NONE);
    }

    /** The top passages for a question: vector similarity, then the reranker, renumbered 1..k. */
    private static Ranked rank(String question) {
        float[] q = ai.embedder().embed(question, EmbedKind.QUERY);
        List<Integer> byVector = new ArrayList<>();
        for (int i = 0; i < notes.size(); i++) {
            byVector.add(i);
        }
        byVector.sort(Comparator.comparingDouble((Integer i) -> -dot(q, noteVectors.get(i))));
        List<Passage> candidates = byVector.stream().limit(6).map(notes::get).toList();
        double[] scores = ai.reranker().score(question, candidates.stream().map(Passage::text).toList(),
                CancellationToken.NONE);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer i) -> -scores[i]));
        List<Passage> top = order.stream().limit(TOP_K).map(candidates::get).toList();
        return new Ranked(OsNotes.renumber(top), scores[order.getFirst()],
                OsNotes.originalNumber(candidates.get(order.getFirst())), OsNotes.originalNumber(candidates.getFirst()));
    }

    private record Ranked(List<Passage> passages, double topScore, int topReranked, int topByVector) {
    }

    static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    @Test
    void answersFromThePassagesWithCitationsAndRefusesWhenTheyDontCover() {
        EvalReport report = new EvalReport("answers", "Grounded answers (" + OsNotes.TITLE + ")");
        int answered = 0;
        int citedExpected = 0;
        int allCitationsValid = 0;
        int retrievalTop1 = 0;
        int vectorTop1 = 0;
        double precisionSum = 0;
        double sentenceCoverageSum = 0;
        List<Double> firstToken = new ArrayList<>();
        List<Double> total = new ArrayList<>();
        List<Double> speed = new ArrayList<>();
        List<Double> answerableScores = new ArrayList<>();

        report.line("## Answerable questions").line("");
        for (OsNotes.Answerable question : OsNotes.ANSWERABLE) {
            Ranked ranked = rank(question.question());
            answerableScores.add(ranked.topScore());
            if (question.passages().contains(ranked.topReranked())) {
                retrievalTop1++;
            }
            if (question.passages().contains(ranked.topByVector())) {
                vectorTop1++;
            }
            Timed timed = answer(question.question(), ranked.passages());
            firstToken.add(timed.firstTokenMillis());
            total.add(timed.totalMillis());
            if (timed.tokensPerSecond() > 0) {
                speed.add(timed.tokensPerSecond());
            }
            CitationValidator.Result validated = CitationValidator.validate(timed.answer().text(), ranked.passages());
            Set<Integer> cited = validated.citations().stream()
                    .map(c -> OsNotes.originalNumber(ranked.passages().get(c.number() - 1)))
                    .collect(Collectors.toSet());
            boolean ok = !timed.answer().insufficient() && validated.hasCitations();
            if (ok) {
                answered++;
            }
            if (cited.stream().anyMatch(question.passages()::contains)) {
                citedExpected++;
            }
            if (validated.invalidNumbers().isEmpty()) {
                allCitationsValid++;
            }
            if (!cited.isEmpty()) {
                precisionSum += (double) cited.stream().filter(question.passages()::contains).count() / cited.size();
            }
            sentenceCoverageSum += citedSentenceShare(timed.answer().text());
            report.line("**Q:** %s  ", question.question())
                    .line("expected %s · cited %s · top reranked %d (%.2f) · first token %s · %s",
                            question.passages(), cited, ranked.topReranked(), ranked.topScore(),
                            EvalReport.ms(timed.firstTokenMillis()), EvalReport.ms(timed.totalMillis()))
                    .line("")
                    .line("> " + (timed.answer().insufficient() ? "(insufficient evidence)"
                            : timed.answer().text().replace("\n", "\n> ")))
                    .line("");
        }

        report.line("## Questions the notes don't answer").line("");
        int refused = 0;
        List<Double> unanswerableScores = new ArrayList<>();
        for (String question : OsNotes.UNANSWERABLE) {
            Ranked ranked = rank(question);
            unanswerableScores.add(ranked.topScore());
            Timed timed = answer(question, ranked.passages());
            boolean refusal = timed.answer().insufficient()
                    || !CitationValidator.validate(timed.answer().text(), ranked.passages()).hasCitations();
            if (refusal) {
                refused++;
            }
            report.line("**Q:** %s  ", question)
                    .line("top reranked score %.2f · %s · %s", ranked.topScore(), refusal ? "refused" : "ANSWERED",
                            EvalReport.ms(timed.totalMillis()))
                    .line("")
                    .line("> " + (timed.answer().insufficient() ? "(insufficient evidence)"
                            : timed.answer().text().replace("\n", "\n> ")))
                    .line("");
        }

        int n = OsNotes.ANSWERABLE.size();
        int u = OsNotes.UNANSWERABLE.size();
        report.line("## Summary").line("")
                .line("| Measure | Result |").line("|---|---|")
                .line("| Answered with a valid citation | %d/%d (%s) |", answered, n, EvalReport.pct((double) answered / n))
                .line("| Cited an expected passage | %d/%d (%s) |", citedExpected, n, EvalReport.pct((double) citedExpected / n))
                .line("| Citation precision (cited passages that answer it) | %s |", EvalReport.pct(precisionSum / n))
                .line("| Answers with no out-of-range marker | %d/%d |", allCitationsValid, n)
                .line("| Sentences carrying a citation | %s |", EvalReport.pct(sentenceCoverageSum / n))
                .line("| Refused unanswerable questions | %d/%d (%s) |", refused, u, EvalReport.pct((double) refused / u))
                .line("| Reranker top-1 is an answering passage | %d/%d |", retrievalTop1, n)
                .line("| Vector top-1 is an answering passage | %d/%d |", vectorTop1, n)
                .line("| Reranker top score, answerable (min / median) | %.2f / %.2f |",
                        answerableScores.stream().mapToDouble(Double::doubleValue).min().orElse(0),
                        EvalReport.median(answerableScores))
                .line("| Reranker top score, unanswerable (median / max) | %.2f / %.2f |",
                        EvalReport.median(unanswerableScores),
                        unanswerableScores.stream().mapToDouble(Double::doubleValue).max().orElse(0))
                .line("| First token (median) | %s |", EvalReport.ms(EvalReport.median(firstToken)))
                .line("| Whole answer (median) | %s |", EvalReport.ms(EvalReport.median(total)))
                .line("| Streaming speed (median) | %.1f tokens/s |", EvalReport.median(speed));
        report.write();

        assertThat(answered).as("answered with valid citations").isGreaterThanOrEqualTo((int) Math.ceil(n * 0.85));
        assertThat(citedExpected).as("cited an answering passage").isGreaterThanOrEqualTo((int) Math.ceil(n * 0.85));
        assertThat(refused).as("refused unanswerable questions").isGreaterThanOrEqualTo((int) Math.ceil(u * 0.7));
        assertThat(allCitationsValid).as("no out-of-range markers").isEqualTo(n);
    }

    private record Timed(GroundedAnswer answer, double firstTokenMillis, double totalMillis, int pieces) {
        double tokensPerSecond() {
            double streaming = totalMillis - firstTokenMillis;
            return pieces > 1 && streaming > 0 ? (pieces - 1) * 1000.0 / streaming : 0;
        }
    }

    private static Timed answer(String question, List<Passage> passages) {
        long start = System.nanoTime();
        AtomicLong first = new AtomicLong(-1);
        List<String> pieces = new ArrayList<>();
        GroundedAnswer answer = ai.answerer().answer(question, passages, piece -> {
            first.compareAndSet(-1, System.nanoTime());
            pieces.add(piece);
        }, CancellationToken.NONE);
        long end = System.nanoTime();
        if (!answer.insufficient()) {
            // Everything streamed is part of the final answer (the sentinel never leaks).
            assertThat(String.join("", pieces).strip()).isEqualTo(answer.text());
        }
        long firstAt = first.get() < 0 ? end : first.get();
        return new Timed(answer, (firstAt - start) / 1e6, (end - start) / 1e6, pieces.size());
    }

    /** Share of the answer's sentences that end with or contain a citation marker. */
    static double citedSentenceShare(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        List<String> sentences = new ArrayList<>();
        for (String s : SENTENCE_END.split(text.strip())) {
            if (s.strip().length() > 12) {
                sentences.add(s);
            }
        }
        if (sentences.isEmpty()) {
            return 0;
        }
        long cited = sentences.stream().filter(s -> s.matches("(?s).*\\[\\d+].*")).count();
        return (double) cited / sentences.size();
    }

    @Test
    void citationsPointAtTheRightPages() {
        List<Passage> passages = OsNotes.renumber(List.of(notes.get(6), notes.get(5)));
        GroundedAnswer answer = ai.answerer().answer("What is a TLB hit?", passages, piece -> { }, CancellationToken.NONE);
        CitationValidator.Result validated = CitationValidator.validate(answer.text(), passages);
        assertThat(validated.citations()).as(answer.toString()).extracting(Citation::pageStart).contains(4);
        assertThat(validated.citations()).extracting(Citation::number).contains(1);
    }
}
