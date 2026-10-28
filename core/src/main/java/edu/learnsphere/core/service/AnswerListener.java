package edu.learnsphere.core.service;

import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.ChatMessage;

/**
 * Receives an answer as it is produced. Called on a background thread: marshal to the FX thread.
 *
 * <p>Order: {@link #onQuestionSaved}, then {@link #onPhase} updates and {@link #onToken} calls, then
 * exactly one terminal call: {@link #onAnswer}, {@link #onInsufficientEvidence}, {@link #onGuidance},
 * {@link #onPractice}, {@link #onError} or {@link #onCancelled}.
 *
 * <p>Streamed tokens are a preview. The stored answer can differ from them: citation markers that point
 * at no passage are removed, groups such as {@code [1, 3]} are split into {@code [1][3]}, and an answer
 * without a valid citation becomes insufficient evidence. So in {@link #onAnswer} the UI always replaces
 * the streamed text with {@code answer.text()} (and renders citation chips from {@code answer.citations()}),
 * and in {@link #onInsufficientEvidence} it replaces the streamed text with the insufficient-evidence card.
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

    /** The answer is complete and stored, with validated citations; replace the streamed text with {@code answer.text()}. */
    void onAnswer(Answer answer);

    /** The material does not cover the question; {@code answer.text()} says so and suggests what to try. */
    void onInsufficientEvidence(Answer answer);

    /**
     * The message was a greeting, or asked about no particular subject: {@code answer.text()} says how to ask
     * and {@code answer.suggestions()} holds topics of the course to offer. Nothing was searched. By default
     * shown like {@link #onInsufficientEvidence}.
     */
    default void onGuidance(Answer answer) {
        onInsufficientEvidence(answer);
    }

    /**
     * The message asked to be quizzed and the course has practice questions: {@code answer.text()} offers a quiz
     * on the course. Nothing was searched. By default shown like {@link #onGuidance}.
     */
    default void onPractice(Answer answer) {
        onGuidance(answer);
    }

    /** The answer could not be produced. */
    void onError(UserMessage message);

    /** The learner stopped the answer; nothing more follows. */
    default void onCancelled() {
    }
}
