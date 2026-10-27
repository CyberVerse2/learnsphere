package edu.learnsphere.core.service;

import java.util.List;
import java.util.Objects;

/**
 * What a creator learns from imported learner progress.
 *
 * @param learners learners by label
 * @param concepts concepts, weakest first
 * @param items    items, hardest first
 */
public record ClassInsightSummary(String courseId, List<LearnerInsight> learners, List<ConceptInsight> concepts,
                                  List<ItemInsight> items) {

    public ClassInsightSummary {
        Objects.requireNonNull(courseId, "courseId");
        learners = List.copyOf(learners);
        concepts = List.copyOf(concepts);
        items = List.copyOf(items);
    }

    public int learnerCount() {
        return learners.size();
    }

    public boolean isEmpty() {
        return learners.isEmpty();
    }
}
