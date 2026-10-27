package edu.learnsphere.core.error;

/** The requested course, module, document, item or profile does not exist (or no longer exists). */
public class NotFoundException extends LearnSphereException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(UserMessage userMessage, String technicalDetail) {
        super(userMessage, technicalDetail);
    }

    /** Not found, with a technical detail naming the entity, e.g. {@code of(COURSE_NOT_FOUND, "course", id)}. */
    public static NotFoundException of(UserMessage userMessage, String entity, String id) {
        return new NotFoundException(userMessage, entity + " not found: " + id);
    }
}
