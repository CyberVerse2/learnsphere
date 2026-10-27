package edu.learnsphere.core.error;

/** The request clashes with the current state (a duplicate name, an already running job, a published version). */
public class ConflictException extends LearnSphereException {

    private static final long serialVersionUID = 1L;

    public ConflictException(UserMessage userMessage, String technicalDetail) {
        super(userMessage, technicalDetail);
    }

    public ConflictException(UserMessage userMessage, String technicalDetail, Throwable cause) {
        super(userMessage, technicalDetail, cause);
    }
}
