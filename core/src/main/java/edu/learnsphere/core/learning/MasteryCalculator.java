package edu.learnsphere.core.learning;

import edu.learnsphere.core.db.StudyScope;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.service.ConceptMastery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Mastery per concept: how well the learner knows it right now, 0–1.
 *
 * <p>Mastery blends two signals with equal weight: the mean current retrievability (FSRS probability of
 * recall now) of the concept's reviewed approved items, and the share correct over its last
 * {@value #RECENT_ATTEMPTS} attempts. Retrievability says how fresh the memory is; recent accuracy says how
 * the latest answers went.
 *
 * <p>An item whose latest answer was rated Again counts with retrievability 0 until it is answered right
 * again. FSRS puts its retrievability near 1 right after the failed review (the learner has just seen the
 * answer), which would make a concept the learner just failed look well known: higher mastery after a
 * session of wrong answers, and no weak concepts to practise right after a bad session.
 *
 * <p>A concept none of whose items has been reviewed has no mastery. Below
 * {@link ConceptMastery#WEAK_THRESHOLD} a concept is weak.
 *
 * <p>Thread-safe.
 */
public final class MasteryCalculator {

    /** Attempts that count towards a concept's recent accuracy. */
    public static final int RECENT_ATTEMPTS = 10;
    /** Weight of mean retrievability in mastery; recent accuracy gets the rest. */
    public static final double RETRIEVABILITY_WEIGHT = 0.5;

    /** Orders weakest first, concepts without mastery last, then by name. */
    public static final Comparator<ConceptMastery> WEAKEST_FIRST = Comparator
            .comparing((ConceptMastery c) -> c.mastery() == null ? 1 : 0)
            .thenComparing(c -> c.mastery() == null ? 0.0 : c.mastery())
            .thenComparing(c -> c.name().toLowerCase(Locale.ROOT))
            .thenComparing(ConceptMastery::conceptId);

    private final StudyQueries queries;
    private final FsrsScheduler scheduler;

    public MasteryCalculator(StudyQueries queries) {
        this(queries, FsrsScheduler.defaults());
    }

    public MasteryCalculator(StudyQueries queries, FsrsScheduler scheduler) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Mastery of every concept in {@code scope} with at least one approved item, weakest first (concepts
     * without mastery last).
     */
    public List<ConceptMastery> conceptMastery(StudyScope scope, long now) {
        Map<String, StudyQueries.ConceptAttempts> attempts = queries.conceptAttempts(scope, RECENT_ATTEMPTS);
        Map<String, Acc> byConcept = new LinkedHashMap<>();
        for (StudyQueries.ConceptItem row : queries.conceptItems(scope)) {
            Acc acc = byConcept.computeIfAbsent(row.conceptId(), id -> new Acc(row.conceptName(), row.moduleId()));
            acc.items++;
            if (row.reviewed()) {
                acc.reviewed++;
                acc.retrievabilitySum += row.lastFailed() ? 0 : retrievability(row, now);
            }
        }
        List<ConceptMastery> result = new ArrayList<>(byConcept.size());
        byConcept.forEach((conceptId, acc) -> {
            StudyQueries.ConceptAttempts a = attempts.get(conceptId);
            Double accuracy = a == null ? null : a.recentAccuracy();
            Double meanR = acc.reviewed == 0 ? null : acc.retrievabilitySum / acc.reviewed;
            result.add(new ConceptMastery(conceptId, acc.name, acc.moduleId, blend(meanR, accuracy), accuracy,
                    acc.items, acc.reviewed, a == null ? 0 : a.attempts()));
        });
        result.sort(WEAKEST_FIRST);
        return result;
    }

    /**
     * Mastery from a concept's mean retrievability and recent accuracy: null without reviewed items, the
     * retrievability alone without attempts, otherwise the weighted blend.
     */
    public static Double blend(Double meanRetrievability, Double recentAccuracy) {
        if (meanRetrievability == null) {
            return null;
        }
        if (recentAccuracy == null) {
            return clamp(meanRetrievability);
        }
        return clamp(RETRIEVABILITY_WEIGHT * meanRetrievability + (1 - RETRIEVABILITY_WEIGHT) * recentAccuracy);
    }

    private double retrievability(StudyQueries.ConceptItem row, long now) {
        ReviewState state = new ReviewState(row.itemId(), row.phase() == null ? ReviewPhase.NEW : row.phase(),
                row.stability(), 0, now, row.lastReviewAt(), 0, 0, 0, 0, now, now);
        return scheduler.retrievability(state, now);
    }

    private static double clamp(double value) {
        return Math.min(1, Math.max(0, value));
    }

    private static final class Acc {
        final String name;
        final String moduleId;
        int items;
        int reviewed;
        double retrievabilitySum;

        Acc(String name, String moduleId) {
            this.name = name;
            this.moduleId = moduleId;
        }
    }
}
