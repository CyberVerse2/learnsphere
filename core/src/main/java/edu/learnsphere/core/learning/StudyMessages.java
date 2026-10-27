package edu.learnsphere.core.learning;

import edu.learnsphere.core.error.UserMessage;

/** The sentences study sessions show when something cannot be done. */
public final class StudyMessages {

    /** A session id that does not exist. */
    public static final UserMessage SESSION_NOT_FOUND = UserMessage.of("This study session isn't available. Start a new one.");
    /** A session that was finished, or started before LearnSphere was last closed. */
    public static final UserMessage SESSION_ENDED = UserMessage.of("This study session has ended. Start a new one.");
    /** An item that is not in the session. */
    public static final UserMessage NOT_IN_SESSION = UserMessage.of("This question isn't part of this study session.");
    /** An item answered twice. */
    public static final UserMessage ALREADY_ANSWERED = UserMessage.of("You've already answered this question.");
    /** A flashcard submitted without a rating. */
    public static final UserMessage RATE_THE_CARD = UserMessage.of("Choose how well you remembered this card.");
    /** The study assistant could not grade a short answer. */
    public static final UserMessage GRADING_UNAVAILABLE =
            UserMessage.of("Your study assistant couldn't check this answer. Try again in a moment.");
    /** A dispute of an answer the study assistant did not grade. */
    public static final UserMessage NOT_DISPUTABLE =
            UserMessage.of("Only answers checked by your study assistant can be disputed.");
    /** An attempt id that does not exist. */
    public static final UserMessage ANSWER_NOT_FOUND = UserMessage.of("This answer isn't available.");
    /** A hint for an item that has none. */
    public static final UserMessage NO_HINT = UserMessage.of("There's no hint for this question.");
    /** A session in a course the learner neither created nor studies. */
    public static final UserMessage NOT_STUDYING = UserMessage.of("Start studying this course to practise it.");

    private StudyMessages() {
    }
}
