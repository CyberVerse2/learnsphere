package edu.learnsphere.core.service;

import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.MessageStatus;
import java.util.List;
import java.util.Objects;

/**
 * A finished, stored answer.
 *
 * @param text      for a complete answer: the text with {@code [n]} markers matching {@code citations};
 *                  for insufficient evidence: the calm explanation to show on the card
 * @param status    {@link MessageStatus#COMPLETE} or {@link MessageStatus#INSUFFICIENT}
 * @param citations sources for the markers; empty when insufficient
 */
public record Answer(String messageId, String threadId, String questionMessageId, String text, MessageStatus status,
                     List<Citation> citations, long createdAt) {

    public Answer {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(status, "status");
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** True when the material does not cover the question. */
    public boolean isInsufficient() {
        return status == MessageStatus.INSUFFICIENT;
    }

    /** The citation for marker {@code number}, or null. */
    public Citation citation(int number) {
        for (Citation citation : citations) {
            if (citation.number() == number) {
                return citation;
            }
        }
        return null;
    }
}
