package edu.learnsphere.core.error;

/** Something needed right now is not ready, such as the study assistant while it starts. Retrying later may work. */
public class UnavailableException extends LearnSphereException {

    private static final long serialVersionUID = 1L;

    public UnavailableException(UserMessage userMessage, String technicalDetail) {
        super(userMessage, technicalDetail);
    }

    public UnavailableException(UserMessage userMessage, String technicalDetail, Throwable cause) {
        super(userMessage, technicalDetail, cause);
    }
}
