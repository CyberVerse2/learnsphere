package edu.learnsphere.core.sharing;

import edu.learnsphere.core.model.ClassInsight;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.service.ClassInsightSummary;
import edu.learnsphere.core.service.ConceptInsight;
import edu.learnsphere.core.service.ItemInsight;
import edu.learnsphere.core.service.LearnerInsight;
import edu.learnsphere.core.sharing.ProgressFile.ItemProgress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns stored learner progress into class insights: per-learner totals, per-item accuracy (hardest first)
 * and per-concept mastery spread (weakest first). Pure computation; thread-safe.
 */
public final class ClassInsightAggregator {

    /** A learner whose accuracy on a concept is below this is counted as struggling with it. */
    public static final double STRUGGLING_BELOW = 0.6;

    private static final Logger LOG = LoggerFactory.getLogger(ClassInsightAggregator.class);

    private ClassInsightAggregator() {
    }

    /**
     * Aggregates {@code insights} for a course.
     *
     * @param items        the course's items (attempts on other items are ignored)
     * @param concepts     the course's concepts
     * @param conceptLinks concept ids per item id
     */
    public static ClassInsightSummary aggregate(String courseId, List<ClassInsight> insights,
                                                Collection<LearningItem> items, Collection<Concept> concepts,
                                                Map<String, List<String>> conceptLinks) {
        Objects.requireNonNull(courseId, "courseId");
        Map<String, LearningItem> itemsById = new HashMap<>();
        items.forEach(i -> itemsById.put(i.id(), i));

        List<LearnerInsight> learners = new ArrayList<>();
        Map<String, Totals> itemTotals = new LinkedHashMap<>();
        Map<String, Map<String, Totals>> conceptByLearner = new HashMap<>();
        for (ClassInsight insight : insights) {
            ProgressFile progress;
            try {
                progress = ProgressFile.fromPayload(insight.payloadJson());
            } catch (RuntimeException e) {
                LOG.warn("Skipping unreadable class insight {}", insight.id(), e);
                continue;
            }
            Totals learner = new Totals();
            Map<String, Totals> learnerConcepts = new HashMap<>();
            for (ItemProgress p : progress.items()) {
                if (p == null || !itemsById.containsKey(p.itemId()) || p.attempts() <= 0) {
                    continue;
                }
                learner.add(p.attempts(), p.correct());
                itemTotals.computeIfAbsent(p.itemId(), id -> new Totals()).addLearner(p.attempts(), p.correct());
                for (String conceptId : conceptLinks.getOrDefault(p.itemId(), List.of())) {
                    learnerConcepts.computeIfAbsent(conceptId, id -> new Totals()).add(p.attempts(), p.correct());
                }
            }
            learners.add(new LearnerInsight(insight.id(), insight.learnerLabel(), insight.importedAt(), learner.attempts,
                    learner.accuracy()));
            conceptByLearner.put(insight.id(), learnerConcepts);
        }
        learners.sort(Comparator.comparing(LearnerInsight::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(LearnerInsight::importedAt));

        List<ItemInsight> itemInsights = new ArrayList<>();
        itemTotals.forEach((itemId, t) -> itemInsights.add(
                new ItemInsight(itemId, itemsById.get(itemId).prompt(), t.attempts, t.accuracy(), t.learners)));
        itemInsights.sort(Comparator.comparing(ItemInsight::accuracy, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Comparator.comparingInt(ItemInsight::attempts).reversed())
                .thenComparing(ItemInsight::prompt));

        List<ConceptInsight> conceptInsights = new ArrayList<>();
        for (Concept concept : concepts) {
            Totals all = new Totals();
            int attempted = 0;
            int struggling = 0;
            for (Map<String, Totals> learnerConcepts : conceptByLearner.values()) {
                Totals t = learnerConcepts.get(concept.id());
                if (t == null || t.attempts == 0) {
                    continue;
                }
                attempted++;
                all.add(t.attempts, t.correct);
                if (t.accuracy() < STRUGGLING_BELOW) {
                    struggling++;
                }
            }
            conceptInsights.add(new ConceptInsight(concept.id(), concept.name(), all.accuracy(), attempted, struggling));
        }
        conceptInsights.sort(Comparator.comparing(ConceptInsight::accuracy, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ConceptInsight::name, String.CASE_INSENSITIVE_ORDER));

        return new ClassInsightSummary(courseId, learners, conceptInsights, itemInsights);
    }

    /** Attempt and correct counts, and how many learners contributed. */
    private static final class Totals {

        int attempts;
        int correct;
        int learners;

        void add(int a, int c) {
            attempts += a;
            correct += Math.min(c, a);
        }

        void addLearner(int a, int c) {
            add(a, c);
            learners++;
        }

        Double accuracy() {
            return attempts == 0 ? null : (double) correct / attempts;
        }
    }
}
