package edu.learnsphere.ai.runtime;

import java.net.URI;
import java.util.Objects;

/**
 * Where a running, healthy server answers: its base address on {@code 127.0.0.1} and the random key it
 * was started with.
 *
 * @param vision true when the chat server can read images (it was started with a vision projector)
 * @param model  the model name requests must carry in their {@code model} field, or null when the server
 *               serves one model and ignores the field (llama-server)
 */
public record ServerEndpoint(ServerRole role, URI baseUri, String apiKey, boolean vision, String model) {

    public ServerEndpoint {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(apiKey, "apiKey");
    }

    /** An endpoint whose server ignores the request's {@code model} field. */
    public ServerEndpoint(ServerRole role, URI baseUri, String apiKey, boolean vision) {
        this(role, baseUri, apiKey, vision, null);
    }

    /** The absolute address of {@code path} (for example {@code /v1/embeddings}) on this server. */
    public URI resolve(String path) {
        return baseUri.resolve(path);
    }

    /** Never prints the key. */
    @Override
    public String toString() {
        return "ServerEndpoint[" + role.label() + " " + baseUri + (vision ? " vision" : "") + "]";
    }
}
