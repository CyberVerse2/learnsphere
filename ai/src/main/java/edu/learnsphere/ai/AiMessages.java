package edu.learnsphere.ai;

import edu.learnsphere.core.error.UserMessage;

/**
 * The sentences the AI module's errors carry, written for someone using LearnSphere for the first time.
 * Core's {@link UserMessage#ASSISTANT_STARTING} and {@link UserMessage#ASSISTANT_UNAVAILABLE} cover a study
 * assistant that is starting or has stopped.
 */
public final class AiMessages {

    /** The runtime files are missing or this computer cannot run them. */
    public static final UserMessage CANNOT_RUN = UserMessage.of("Your study assistant can't run on this computer.");
    /** A request failed for a reason the user cannot fix other than by trying again. */
    public static final UserMessage REQUEST_FAILED = UserMessage.of("Your study assistant couldn't finish that. Try again.");
    /** A request took longer than allowed. */
    public static final UserMessage TIMED_OUT = UserMessage.of("Your study assistant took too long. Try again.");
    /** The input does not fit what the assistant can read at once. */
    public static final UserMessage TOO_LONG = UserMessage.of("That's more text than your study assistant can read at once. Try something shorter.");
    /** Page images cannot be read with the installed files. */
    public static final UserMessage NO_PAGE_READING =
            UserMessage.of("Your study assistant can't read scanned pages on this computer.");

    private AiMessages() {
    }
}
