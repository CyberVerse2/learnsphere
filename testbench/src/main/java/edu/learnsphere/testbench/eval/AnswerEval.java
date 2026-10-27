package edu.learnsphere.testbench.eval;

import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.service.Answer;
import edu.learnsphere.core.service.AnswerListener;
import edu.learnsphere.core.service.AskService;
import edu.learnsphere.testbench.material.Material;
import edu.learnsphere.testbench.metrics.Citations;
import edu.learnsphere.testbench.metrics.KeyTerms;
import edu.learnsphere.testbench.metrics.Stats;
import edu.learnsphere.testbench.probe.ChatLog;
import edu.learnsphere.testbench.probe.Recorder;
import edu.learnsphere.testbench.truth.EvaluationSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks every question through the app's ask service, exactly as a learner would, and scores the answers:
 * whether covered questions are answered with citations to the right pages, whether uncovered questions get the
 * insufficient-evidence card, and how long the learner waits for the first words and for the whole answer.
 */
public final class AnswerEval {

    private static final Logger LOG = LoggerFactory.getLogger(AnswerEval.class);
    static final String PHASE = "answers";
    private static final long ANSWER_TIMEOUT_SECONDS = 300;

    /** What the learner saw. */
    public enum Outcome {
        ANSWERED, NOT_COVERED, ERROR
    }

    /**
     * One question.
     *
     * @param kind          {@code covered}, {@code scanned} or {@code uncovered}
     * @param refusal       for a not-covered outcome, where it was decided: {@code retrieval} (no passage cleared the
     *                      evidence threshold), {@code answerer} (the answerer said the passages do not answer it) or
     *                      {@code no citation} (the answer cited no passage it was given)
     * @param markers       citation markers in the answer as written
     * @param validMarkers  of those, markers naming a passage the answerer was given
     * @param onTruePages   citations of the final answer on an answering page
     * @param firstTokenMs  from asking until the first words appeared, -1 when none did
     */
    public record Row(String id, String kind, String question, Outcome outcome, String refusal, int passages, int markers,
                      int validMarkers, int citations, int onTruePages, double keyTermCoverage, long firstTokenMs,
                      long totalMs, String text) {
    }

    /** Everything the answer evaluation measured. */
    public record Result(List<Row> rows, List<ChatLog.Completion> completions) {

        public Result {
            rows = List.copyOf(rows);
            completions = List.copyOf(completions);
        }

        private List<Row> kind(String... kinds) {
            Set<String> wanted = Set.of(kinds);
            return rows.stream().filter(r -> wanted.contains(r.kind())).toList();
        }

        /** Questions the material answers (chapters and scanned copy). */
        public List<Row> answerable() {
            return kind("covered", "scanned");
        }

        /** Questions the material does not answer. */
        public List<Row> uncovered() {
            return kind("uncovered");
        }

        /** Answered questions the material answers. */
        public List<Row> answered() {
            return answerable().stream().filter(r -> r.outcome() == Outcome.ANSWERED).toList();
        }

        /** Share of citation markers, over every answer written, that name a passage the answerer was given. */
        public double citationValidity() {
            long markers = rows.stream().mapToLong(Row::markers).sum();
            long valid = rows.stream().mapToLong(Row::validMarkers).sum();
            return markers == 0 ? 0 : valid / (double) markers;
        }

        /** Citation markers counted for {@link #citationValidity()}. */
        public long markerCount() {
            return rows.stream().mapToLong(Row::markers).sum();
        }

        /** Share of the answered questions' citations that point at an answering page. */
        public double citationPrecision() {
            long citations = answered().stream().mapToLong(Row::citations).sum();
            long right = answered().stream().mapToLong(Row::onTruePages).sum();
            return citations == 0 ? 0 : right / (double) citations;
        }

        /** Share of answered questions with at least one citation on an answering page. */
        public double groundedShare() {
            return Stats.share(answered().stream().filter(r -> r.onTruePages() > 0).count(), answered().size());
        }

        /** Share of uncovered questions that got the insufficient-evidence card. */
        public double refusalRate() {
            return Stats.share(uncovered().stream().filter(r -> r.outcome() == Outcome.NOT_COVERED).count(),
                    uncovered().size());
        }

        /** Share of answerable questions that did not get an answer. */
        public double falseRefusalRate() {
            return Stats.share(answerable().stream().filter(r -> r.outcome() != Outcome.ANSWERED).count(),
                    answerable().size());
        }

        /** Mean share of key terms in the answered questions' answers. */
        public double keyTermCoverage() {
            return Stats.mean(answered().stream().map(Row::keyTermCoverage).toList());
        }

        /** Time to the first words over the questions that streamed an answer. */
        public List<Long> firstTokenTimes() {
            return rows.stream().filter(r -> r.firstTokenMs() >= 0).map(Row::firstTokenMs).toList();
        }

        /** Time to the full answer (or card) over every question. */
        public List<Long> totalTimes() {
            return rows.stream().map(Row::totalMs).toList();
        }

        /** Answers that ended in an error. */
        public long errors() {
            return rows.stream().filter(r -> r.outcome() == Outcome.ERROR).count();
        }
    }

    private AnswerEval() {
    }

    /** Asks every covered, scanned and uncovered question. */
    public static Result run(Session session) throws InterruptedException {
        session.phase(PHASE);
        EvaluationSet truth = session.truth();
        List<Row> rows = new ArrayList<>();
        for (EvaluationSet.Question question : truth.covered()) {
            rows.add(ask(session, question.id(), "covered", question.question(), session.courseOf(question.document()),
                    question.document(), question.pages(), question.keyTerms()));
        }
        for (EvaluationSet.Question question : truth.scanned()) {
            rows.add(ask(session, question.id(), "scanned", question.question(), session.courseOf(question.document()),
                    question.document(), question.pages(), question.keyTerms()));
        }
        for (EvaluationSet.Uncovered question : truth.uncovered()) {
            rows.add(ask(session, question.id(), "uncovered", question.question(), session.mainCourseId(), "", List.of(),
                    List.of()));
        }
        Result result = new Result(rows, session.chatLog().completions(PHASE));
        LOG.info("Answers: {} of {} answerable answered, {} of {} uncovered refused", result.answered().size(),
                result.answerable().size(), result.uncovered().stream().filter(r -> r.outcome() == Outcome.NOT_COVERED).count(),
                result.uncovered().size());
        return result;
    }

    private static Row ask(Session session, String id, String kind, String question, String course, String document,
                           List<Integer> pages, List<String> keyTerms) throws InterruptedException {
        AskService ask = session.workspace().ask();
        String thread = ask.createThread(course, "Testbench " + id).id();
        CompletableFuture<Object> done = new CompletableFuture<>();
        AtomicLong firstToken = new AtomicLong(-1);
        int earlierCalls = session.recorder().answerCalls().size();
        long start = System.nanoTime();
        ask.ask(thread, question, new AnswerListener() {
            @Override
            public void onToken(String token) {
                firstToken.compareAndSet(-1, (System.nanoTime() - start) / 1_000_000);
            }

            @Override
            public void onAnswer(Answer answer) {
                done.complete(answer);
            }

            @Override
            public void onInsufficientEvidence(Answer answer) {
                done.complete(answer);
            }

            @Override
            public void onError(UserMessage message) {
                done.complete(message);
            }

            @Override
            public void onCancelled() {
                done.complete(UserMessage.SOMETHING_WENT_WRONG);
            }
        });
        Object reply;
        try {
            reply = done.get(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            reply = UserMessage.SOMETHING_WENT_WRONG;
        }
        long total = (System.nanoTime() - start) / 1_000_000;
        List<Recorder.AnswerCall> calls = session.recorder().answerCalls();
        Optional<Recorder.AnswerCall> call = calls.subList(earlierCalls, calls.size()).stream()
                .filter(c -> c.question().strip().equals(question.strip())).reduce((first, last) -> last);
        String raw = call.map(Recorder.AnswerCall::rawText).orElse("");
        int passages = call.map(c -> c.passages().size()).orElse(0);
        List<Integer> markers = call.isPresent() && !call.get().insufficient() ? Citations.markerNumbers(raw) : List.of();
        int valid = (int) Citations.validCount(markers, passages);
        if (reply instanceof Answer answer && !answer.isInsufficient()) {
            Set<String> targets = RetrievalEval.targets(document, pages);
            int onTrue = (int) answer.citations().stream().filter(c -> onPages(session, c, targets)).count();
            LOG.info("{} answered with {} citation(s), {} on the answering pages", id, answer.citations().size(), onTrue);
            return new Row(id, kind, question, Outcome.ANSWERED, "", passages, markers.size(), valid,
                    answer.citations().size(), onTrue, KeyTerms.coverage(keyTerms, answer.text()), firstToken.get(), total,
                    answer.text());
        }
        if (reply instanceof Answer answer) {
            String refusal = call.isEmpty() ? "retrieval" : call.get().insufficient() ? "answerer" : "no citation";
            LOG.info("{} not covered ({})", id, refusal);
            return new Row(id, kind, question, Outcome.NOT_COVERED, refusal, passages, markers.size(), valid, 0, 0, 0,
                    firstToken.get(), total, answer.text());
        }
        LOG.warn("{} failed: {}", id, reply);
        return new Row(id, kind, question, Outcome.ERROR, "", passages, markers.size(), valid, 0, 0, 0, firstToken.get(),
                total, String.valueOf(reply));
    }

    private static boolean onPages(Session session, Citation citation, Set<String> targets) {
        return RetrievalEval.pageKeys(session.documentKey(citation.documentId()), citation.pageStart(), citation.pageEnd())
                .stream().anyMatch(targets::contains);
    }
}
