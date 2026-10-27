package edu.learnsphere.core.service;

import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.ChatMessage;

/**
 * Receives an answer as it is produced. Called on a background thread: marshal to the FX thread.
 *
 * <p>Order: {@link #onQuestionSaved}, then {@link #onPhase} updates and {@link #onToken} calls, then
 * exactly one terminal call: {@link #onAnswer}, {@link #onInsufficientEvidence}, {@link #onError} or
 * {@link #onCancelled}. Tokens may already have streamed when the answer turns out to have no valid
 * citation; {@link #onInsufficientEvidence} then replaces the streamed text.
 */
public interface AnswerListener {

    /** The question was stored (it is part of the thread from now on). */
    default void onQuestionSaved(ChatMessage question) {
    }

    /** The pipeline moved to another phase. */
    default void onPhase(AskPhase phase) {
    }

    /** The next piece of answer text, possibly containing {@code [n]} markers. */
    void onToken(String token);

    /** The answer is complete and stored, with validated citations. */
    void onAnswer(Answer answer);

    /** The material does not cover the question; {@code answer.text()} says so and suggests what to try. */
    void onInsufficientEvidence(Answer answer);

    /** The answer could not be produced. */
    void onError(UserMessage message);

    /** The learner stopped the answer; nothing more follows. */
    default void onCancelled() {
    }
}
