package edu.learnsphere.core.service;

import edu.learnsphere.core.model.Citation;
import java.util.List;
import java.util.Objects;

/**
 * A recently answered question for the home screen.
 *
 * @param answerPreview the start of the answer without citation markers
 * @param citations     the answer's sources
 */
public record RecentQuestion(String threadId, String courseId, String courseTitle, int coverHue, String question,
                             String answerPreview, List<Citation> citations, long askedAt) {

    public RecentQuestion {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(question, "question");
        answerPreview = Objects.requireNonNullElse(answerPreview, "");
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
