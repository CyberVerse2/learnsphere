package edu.learnsphere.testbench.eval;

import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.SearchHit;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.spi.SearchQuery;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.testbench.metrics.Ranking;
import edu.learnsphere.testbench.metrics.Stats;
import edu.learnsphere.testbench.truth.EvaluationSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scores retrieval on the profile's real search index: for every question with known answering pages, the
 * passages found by keyword search alone, vector search alone, the two fused, and the fused list reordered by
 * the reranker (what the app gives the answerer). A passage is relevant when it comes from the question's
 * document and its page range includes one of the answering pages.
 *
 * <p>It also sweeps the evidence threshold: for each candidate threshold, the share of covered questions that
 * keep at least one relevant passage and the share of uncovered questions left with no passage at all (which
 * the app then answers with the insufficient-evidence card without asking the answerer).
 */
public final class RetrievalEval {

    private static final Logger LOG = LoggerFactory.getLogger(RetrievalEval.class);
    private static final int CANDIDATES = 40;
    private static final int LIMIT = 24;
    private static final int RRF_K = 60;

    /** The ways of ranking passages that are compared. */
    public enum Mode {
        KEYWORD("Keyword (BM25) only"),
        VECTOR("Vector only"),
        HYBRID("Hybrid (rank fusion)"),
        HYBRID_RERANKED("Hybrid + reranker (the app)");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Ranking quality of one mode over a set of questions. */
    public record ModeScore(Mode mode, double recallAt1, double recallAt5, double mrr, double recallAtLimit) {
    }

    /**
     * One question's results.
     *
     * @param firstRelevant    the 1-based rank of the first relevant passage per mode, 0 when none was found
     * @param topScore         the reranker's highest score over the fused candidates
     * @param relevantTopScore the reranker's highest score over relevant candidates, -1 when none was found
     */
    public record QuestionRow(String id, String document, List<Integer> pages, boolean covered,
                              Map<Mode, Integer> firstRelevant, double topScore, double relevantTopScore) {
    }

    /** One candidate evidence threshold. */
    public record ThresholdRow(double threshold, double coveredKept, double uncoveredRefused) {

        /** The mean of the two shares. */
        public double balanced() {
            return (coveredKept + uncoveredRefused) / 2;
        }
    }

    /**
     * Everything the retrieval evaluation measured.
     *
     * @param main          scores over the covered questions of the two chapters
     * @param scanned       scores over the questions about the scanned copy
     * @param bestPassages  for each covered and scanned question, its highest-scoring relevant passage
     */
    public record Result(int mainQuestions, List<ModeScore> main, int scannedQuestions, List<ModeScore> scanned,
                         List<QuestionRow> questions, List<ThresholdRow> sweep, double recommendedThreshold,
                         double currentThreshold, double medianEmbedMs, double medianSearchMs, double medianRerankMs,
                         Map<String, Passage> bestPassages) {

        public Result {
            main = List.copyOf(main);
            scanned = List.copyOf(scanned);
            questions = List.copyOf(questions);
            sweep = List.copyOf(sweep);
            bestPassages = Map.copyOf(bestPassages);
        }

        /** The main-course score of one mode. */
        public ModeScore main(Mode mode) {
            return main.stream().filter(s -> s.mode() == mode).findFirst().orElseThrow();
        }

        /** The sweep row closest to {@code threshold}. */
        public ThresholdRow at(double threshold) {
            return sweep.stream().min(Comparator.comparingDouble(r -> Math.abs(r.threshold() - threshold))).orElseThrow();
        }
    }

    /** The reranked candidates for one question, with their relevance. */
    record Ranked(List<SearchHit> hits, double[] scores) {
    }

    /** One question's ranked list per mode, and the reranker's scores for the last one. */
    private record Searched(Map<Mode, List<SearchHit>> lists, Ranked reranked) {
    }

    private RetrievalEval() {
    }

    /** Runs every question through the four modes. */
    public static Result run(Session session) {
        session.phase("retrieval");
        EvaluationSet truth = session.truth();
        List<QuestionRow> rows = new ArrayList<>();
        Map<String, Passage> best = new LinkedHashMap<>();
        Map<Mode, List<List<Set<String>>>> mainRankings = new EnumMap<>(Mode.class);
        Map<Mode, List<List<Set<String>>>> scanRankings = new EnumMap<>(Mode.class);
        List<Set<String>> mainTargets = new ArrayList<>();
        List<Set<String>> scanTargets = new ArrayList<>();
        List<Long> embedMs = new ArrayList<>();
        List<Long> searchMs = new ArrayList<>();
        List<Long> rerankMs = new ArrayList<>();

        List<EvaluationSet.Question> answerable = new ArrayList<>(truth.covered());
        answerable.addAll(truth.scanned());
        for (EvaluationSet.Question question : answerable) {
            String course = session.courseOf(question.document());
            Set<String> targets = targets(question.document(), question.pages());
            Searched searched = search(session, course, question.question(), embedMs, searchMs, rerankMs);
            boolean scanned = question.document().equals(edu.learnsphere.testbench.material.Material.SCANNED);
            Map<Mode, List<List<Set<String>>>> rankings = scanned ? scanRankings : mainRankings;
            (scanned ? scanTargets : mainTargets).add(targets);
            Map<Mode, Integer> first = new EnumMap<>(Mode.class);
            for (Mode mode : Mode.values()) {
                List<Set<String>> keys = keys(session, searched.lists().get(mode));
                rankings.computeIfAbsent(mode, m -> new ArrayList<>()).add(keys);
                first.put(mode, Ranking.firstRelevantRank(keys, targets));
            }
            Ranked reranked = searched.reranked();
            double top = reranked.scores().length == 0 ? 0 : reranked.scores()[0];
            double relevantTop = -1;
            for (int i = 0; i < reranked.hits().size(); i++) {
                SearchHit hit = reranked.hits().get(i);
                if (relevant(session, hit, targets)) {
                    relevantTop = reranked.scores()[i];
                    best.put(question.id(), hit.toPassage(1, relevantTop));
                    break;
                }
            }
            rows.add(new QuestionRow(question.id(), question.document(), question.pages(), true, first, top, relevantTop));
        }
        for (EvaluationSet.Uncovered question : truth.uncovered()) {
            Searched searched = search(session, session.mainCourseId(), question.question(), embedMs, searchMs, rerankMs);
            Ranked reranked = searched.reranked();
            double top = reranked.scores().length == 0 ? 0 : reranked.scores()[0];
            Map<Mode, Integer> first = new EnumMap<>(Mode.class);
            searched.lists().keySet().forEach(mode -> first.put(mode, 0));
            rows.add(new QuestionRow(question.id(), "", List.of(), false, first, top, -1));
        }

        List<ModeScore> main = scores(mainRankings, mainTargets);
        List<ModeScore> scanned = scores(scanRankings, scanTargets);
        List<ThresholdRow> sweep = sweep(rows);
        double current = session.workspace().settings().get(SettingKeys.EVIDENCE_THRESHOLD);
        double recommended = recommend(sweep, current);
        LOG.info("Retrieval: hybrid + reranker recall@5 {}, MRR {}",
                String.format("%.3f", main.get(Mode.HYBRID_RERANKED.ordinal()).recallAt5()),
                String.format("%.3f", main.get(Mode.HYBRID_RERANKED.ordinal()).mrr()));
        return new Result(mainTargets.size(), main, scanTargets.size(), scanned, rows, sweep, recommended, current,
                Stats.median(embedMs), Stats.median(searchMs), Stats.median(rerankMs), best);
    }

    private static Searched search(Session session, String course, String text, List<Long> embedMs,
                                                     List<Long> searchMs, List<Long> rerankMs) {
        SearchIndex index = session.recorder().index();
        long start = System.nanoTime();
        float[] vector = session.capabilities().embedder().embed(List.of(text), EmbedKind.QUERY, CancellationToken.NONE)
                .getFirst();
        embedMs.add(since(start));
        Map<Mode, List<SearchHit>> lists = new EnumMap<>(Mode.class);
        lists.put(Mode.KEYWORD, index.search(new SearchQuery(text, vector, course, Set.of(), CANDIDATES, 0, LIMIT, RRF_K)));
        lists.put(Mode.VECTOR, index.search(new SearchQuery(text, vector, course, Set.of(), 0, CANDIDATES, LIMIT, RRF_K)));
        start = System.nanoTime();
        List<SearchHit> hybrid = index.search(SearchQuery.forCourse(course, text, vector));
        searchMs.add(since(start));
        lists.put(Mode.HYBRID, hybrid);
        start = System.nanoTime();
        Ranked ranked = rerank(session, text, hybrid);
        rerankMs.add(since(start));
        lists.put(Mode.HYBRID_RERANKED, ranked.hits());
        return new Searched(lists, ranked);
    }

    /** The hits reordered by the reranker's score, highest first, with the scores in the same order. */
    static Ranked rerank(Session session, String query, List<SearchHit> hits) {
        if (hits.isEmpty()) {
            return new Ranked(List.of(), new double[0]);
        }
        double[] scores = session.capabilities().reranker().score(query, hits.stream().map(SearchHit::text).toList(),
                CancellationToken.NONE);
        List<Integer> order = IntStream.range(0, hits.size()).boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> scores[i]).reversed()).toList();
        return new Ranked(order.stream().map(hits::get).toList(), order.stream().mapToDouble(i -> scores[i]).toArray());
    }

    /**
     * The best passage for a question on known pages: the highest-scoring reranked candidate from those pages,
     * or empty when the search finds none.
     */
    public static Optional<Passage> bestPassage(Session session, String course, String question, String document,
                                                List<Integer> pages) {
        float[] vector = session.capabilities().embedder().embed(List.of(question), EmbedKind.QUERY, CancellationToken.NONE)
                .getFirst();
        List<SearchHit> hits = session.recorder().index().search(SearchQuery.forCourse(course, question, vector));
        Ranked ranked = rerank(session, question, hits);
        Set<String> targets = targets(document, pages);
        for (int i = 0; i < ranked.hits().size(); i++) {
            if (relevant(session, ranked.hits().get(i), targets)) {
                return Optional.of(ranked.hits().get(i).toPassage(1, ranked.scores()[i]));
            }
        }
        return Optional.empty();
    }

    /** Page keys ({@code os:5}) of the answering pages. */
    static Set<String> targets(String document, List<Integer> pages) {
        return pages.stream().map(page -> document + ":" + page).collect(Collectors.toSet());
    }

    /** Page keys of the pages each hit spans. */
    static List<Set<String>> keys(Session session, List<SearchHit> hits) {
        return hits.stream().map(hit -> pageKeys(session.documentKey(hit.documentId()), hit.pageStart(), hit.pageEnd()))
                .toList();
    }

    static Set<String> pageKeys(String document, int pageStart, int pageEnd) {
        return IntStream.rangeClosed(pageStart, Math.max(pageStart, pageEnd)).mapToObj(page -> document + ":" + page)
                .collect(Collectors.toSet());
    }

    static boolean relevant(Session session, SearchHit hit, Set<String> targets) {
        return pageKeys(session.documentKey(hit.documentId()), hit.pageStart(), hit.pageEnd()).stream()
                .anyMatch(targets::contains);
    }

    private static List<ModeScore> scores(Map<Mode, List<List<Set<String>>>> rankings, List<Set<String>> targets) {
        List<ModeScore> scores = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            List<List<Set<String>>> lists = rankings.getOrDefault(mode, List.of());
            List<Double> at1 = new ArrayList<>();
            List<Double> at5 = new ArrayList<>();
            List<Double> atLimit = new ArrayList<>();
            List<Double> reciprocal = new ArrayList<>();
            for (int i = 0; i < lists.size(); i++) {
                at1.add(Ranking.recallAtK(lists.get(i), targets.get(i), 1));
                at5.add(Ranking.recallAtK(lists.get(i), targets.get(i), 5));
                atLimit.add(Ranking.recallAtK(lists.get(i), targets.get(i), LIMIT));
                reciprocal.add(Ranking.reciprocalRank(lists.get(i), targets.get(i)));
            }
            scores.add(new ModeScore(mode, Stats.mean(at1), Stats.mean(at5), Ranking.meanReciprocalRank(reciprocal),
                    Stats.mean(atLimit)));
        }
        return scores;
    }

    /** Candidate thresholds 0.00, 0.05, …, 0.95 against the questions' top reranker scores. */
    static List<ThresholdRow> sweep(List<QuestionRow> rows) {
        List<QuestionRow> covered = rows.stream().filter(QuestionRow::covered).toList();
        List<QuestionRow> uncovered = rows.stream().filter(r -> !r.covered()).toList();
        List<ThresholdRow> sweep = new ArrayList<>();
        for (int step = 0; step < 20; step++) {
            double threshold = step / 20.0;
            long kept = covered.stream().filter(r -> r.relevantTopScore() >= threshold).count();
            long refused = uncovered.stream().filter(r -> r.topScore() < threshold).count();
            sweep.add(new ThresholdRow(threshold, Stats.share(kept, covered.size()), Stats.share(refused, uncovered.size())));
        }
        return sweep;
    }

    /**
     * The middle of the thresholds with the best balanced score (the current setting when it is among them), so
     * the recommendation sits away from the edges of the safe range.
     */
    static double recommend(List<ThresholdRow> sweep, double current) {
        double best = sweep.stream().mapToDouble(ThresholdRow::balanced).max().orElse(0);
        List<ThresholdRow> top = sweep.stream().filter(r -> r.balanced() >= best - 1e-9).toList();
        if (top.stream().anyMatch(r -> Math.abs(r.threshold() - current) < 1e-9)) {
            return current;
        }
        return top.get(top.size() / 2).threshold();
    }

    private static long since(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
