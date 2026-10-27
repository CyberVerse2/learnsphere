package edu.learnsphere.ai.runtime;

import edu.learnsphere.core.util.CancellationToken;

/**
 * Hands out the endpoint of a server role, starting the runtime on first use. Implemented by
 * {@link LlamaServerManager}; tests use a fixed endpoint.
 */
public interface EndpointProvider {

    /**
     * The endpoint of {@code role} once that server answers requests. Starts the runtime when it is idle
     * and waits (up to a bound) while it starts.
     *
     * @throws edu.learnsphere.core.error.UnavailableException when the server cannot run, has failed, or is
     *         not ready in time
     * @throws java.util.concurrent.CancellationException when {@code token} is cancelled while waiting
     */
    ServerEndpoint await(ServerRole role, CancellationToken token);

    /**
     * Tells the provider that {@code endpoint} did not answer (connection refused or no response), so it can
     * check the process and restart it when it is stuck. Returns quickly.
     */
    default void reportUnresponsive(ServerEndpoint endpoint) {
    }

    /** An endpoint that is always the same (tests, or a server started elsewhere). */
    static EndpointProvider fixed(ServerEndpoint... endpoints) {
        return (role, token) -> {
            token.throwIfCancelled();
            for (ServerEndpoint endpoint : endpoints) {
                if (endpoint.role() == role) {
                    return endpoint;
                }
            }
            throw new edu.learnsphere.core.error.UnavailableException(
                    edu.learnsphere.core.error.UserMessage.ASSISTANT_UNAVAILABLE, "No endpoint for " + role);
        };
    }
}
