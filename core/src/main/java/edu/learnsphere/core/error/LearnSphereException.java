package edu.learnsphere.core.error;

import java.util.Objects;

/**
 * Base of every exception a service throws. Carries a {@link UserMessage} for the interface and a
 * technical detail (the exception message) plus optional cause for logs. The UI shows only
 * {@link #userMessage()}, never {@link #getMessage()}.
 *
 * <p>Subclasses say what kind of failure it is so callers can react without parsing text:
 * {@link NotFoundException}, {@link InvalidInputException}, {@link UnavailableException},
 * {@link ConflictException}. A plain {@code LearnSphereException} means an unexpected failure.
 */
public class LearnSphereException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UserMessage userMessage;

    public LearnSphereException(UserMessage userMessage, String technicalDetail) {
        this(userMessage, technicalDetail, null);
    }

    public LearnSphereException(UserMessage userMessage, String technicalDetail, Throwable cause) {
        super(technicalDetail, cause);
        this.userMessage = Objects.requireNonNull(userMessage, "userMessage");
    }

    /** An unexpected failure, shown as {@link UserMessage#SOMETHING_WENT_WRONG}. */
    public static LearnSphereException unexpected(String technicalDetail, Throwable cause) {
        return new LearnSphereException(UserMessage.SOMETHING_WENT_WRONG, technicalDetail, cause);
    }

    /** The sentence to show the user. */
    public UserMessage userMessage() {
        return userMessage;
    }

    /**
     * The user message for any throwable: the carried message for a {@code LearnSphereException},
     * otherwise {@link UserMessage#SOMETHING_WENT_WRONG}.
     */
    public static UserMessage userMessageOf(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof LearnSphereException e) {
                return e.userMessage();
            }
        }
        return UserMessage.SOMETHING_WENT_WRONG;
    }
}
