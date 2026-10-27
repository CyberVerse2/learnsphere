package edu.learnsphere.core.error;

/** The request cannot be carried out with the values given (empty title, wrong password, bad file). */
public class InvalidInputException extends LearnSphereException {

    private static final long serialVersionUID = 1L;

    public InvalidInputException(UserMessage userMessage, String technicalDetail) {
        super(userMessage, technicalDetail);
    }

    public InvalidInputException(UserMessage userMessage, String technicalDetail, Throwable cause) {
        super(userMessage, technicalDetail, cause);
    }
}
