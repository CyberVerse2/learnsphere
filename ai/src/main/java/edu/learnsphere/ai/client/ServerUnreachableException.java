package edu.learnsphere.ai.client;

import edu.learnsphere.ai.runtime.ServerEndpoint;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;

/**
 * A server could not be reached, or the connection broke before it answered. The process may have
 * crashed or be restarting, so the request can be retried once the server is ready again.
 */
public final class ServerUnreachableException extends UnavailableException {

    private static final long serialVersionUID = 1L;

    private final transient ServerEndpoint endpoint;

    public ServerUnreachableException(ServerEndpoint endpoint, String technicalDetail, Throwable cause) {
        super(UserMessage.ASSISTANT_STARTING, technicalDetail, cause);
        this.endpoint = endpoint;
    }

    /** The endpoint that did not answer. */
    public ServerEndpoint endpoint() {
        return endpoint;
    }
}
