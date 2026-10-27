package edu.learnsphere.ai.client;

import edu.learnsphere.ai.AiMessages;
import edu.learnsphere.core.error.LearnSphereException;

/** A request to a server took longer than its time limit and was abandoned. */
public final class AiTimeoutException extends LearnSphereException {

    private static final long serialVersionUID = 1L;

    public AiTimeoutException(String technicalDetail) {
        super(AiMessages.TIMED_OUT, technicalDetail);
    }
}
