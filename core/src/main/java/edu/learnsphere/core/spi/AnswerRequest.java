package edu.learnsphere.core.spi;

import java.util.List;
import java.util.Objects;

/**
 * What the {@link GroundedAnswerer} replies to: the learner's latest message in a conversation about a course,
 * with numbered passages of the course to reply from.
 *
 * @param course   the course title
 * @param topics   topics the course covers, to suggest when the passages don't cover what was asked
 * @param earlier  the conversation's last exchanges, oldest first, without citation markers
 * @param message  what the learner just wrote
 * @param passages numbered passages of the course, the only source of facts for the reply
 */
public record AnswerRequest(String course, List<String> topics, List<QuestionReader.Turn> earlier, String message,
                            List<Passage> passages) {

    public AnswerRequest {
        Objects.requireNonNull(course, "course");
        Objects.requireNonNull(message, "message");
        topics = List.copyOf(topics);
        earlier = List.copyOf(earlier);
        passages = List.copyOf(passages);
    }

    /** A single question with no conversation around it. */
    public static AnswerRequest question(String question, List<Passage> passages) {
        return new AnswerRequest("", List.of(), List.of(), question, passages);
    }
}
