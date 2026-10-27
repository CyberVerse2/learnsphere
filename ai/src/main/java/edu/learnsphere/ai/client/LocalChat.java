package edu.learnsphere.ai.client;

import edu.learnsphere.ai.AiMessages;
import edu.learnsphere.ai.runtime.EndpointProvider;
import edu.learnsphere.ai.runtime.ServerEndpoint;
import edu.learnsphere.ai.runtime.ServerRole;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Subscription;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends chat requests to the local chat server through the {@link ChatGate}: waits for the server (starting
 * it on first use), waits for its turn by priority, runs the request, and starts over when the request was
 * preempted by a more urgent one or the server restarted before answering.
 *
 * <p>A request may start over only while nobody has seen any of its output: always when its sink is
 * {@link TokenSink#NONE} (the caller only uses the finished result), otherwise only until the first piece
 * reached the sink, so a reader never sees text repeated. Thread-safe.
 */
public final class LocalChat {

    private static final Logger LOG = LoggerFactory.getLogger(LocalChat.class);

    /** Retries after the chat server could not be reached (it may be restarting). */
    static final int UNREACHABLE_RETRIES = 1;

    private final EndpointProvider endpoints;
    private final OpenAiClient client;
    private final ChatGate gate;

    public LocalChat(EndpointProvider endpoints, OpenAiClient client, ChatGate gate) {
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.client = Objects.requireNonNull(client, "client");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /** The gate requests wait at. */
    public ChatGate gate() {
        return gate;
    }

    /**
     * Runs {@code request} at {@code priority}, streaming text to {@code sink}. Blocks until done.
     *
     * @throws CancellationException when {@code token} is cancelled
     * @throws UnavailableException when the chat server cannot run or is not ready in time, or the request
     *         carries images and the chat model cannot read them
     */
    public ChatResult complete(ChatPriority priority, ChatRequest request, TokenSink sink, CancellationToken token) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(token, "token");
        int unreachable = 0;
        int preempted = 0;
        while (true) {
            token.throwIfCancelled();
            // Wait for the server outside the gate, so a slow start does not hold up the queue.
            endpoints.await(ServerRole.CHAT, token);
            try (ChatGate.Permit permit = gate.acquire(priority, token)) {
                ServerEndpoint endpoint = endpoints.await(ServerRole.CHAT, token);
                if (request.hasImages() && !endpoint.vision()) {
                    throw new UnavailableException(AiMessages.NO_PAGE_READING,
                            "The chat model was started without a vision projector");
                }
                CancellationToken requestToken = token.child();
                Subscription preemption = permit.preemption().onCancel(requestToken::cancel);
                // True once output reached a sink someone sees; from then on the request cannot start over.
                AtomicBoolean streamed = new AtomicBoolean();
                TokenSink tracked = sink == TokenSink.NONE ? TokenSink.NONE : piece -> {
                    streamed.set(true);
                    sink.accept(piece);
                };
                try {
                    ChatResult result = client.chat(endpoint, request, tracked, requestToken);
                    LOG.debug("Chat {} done: {} prompt + {} generated tokens, first token {} ms, {} ms in all ({})",
                            priority, result.promptTokens(), result.completionTokens(), result.firstToken().toMillis(),
                            result.elapsed().toMillis(), result.finishReason());
                    return result;
                } catch (CancellationException e) {
                    if (!token.isCancelled() && permit.isPreempted() && !streamed.get()) {
                        preempted++;
                        LOG.debug("A {} chat request made way for an interactive one ({} times); queuing again",
                                priority, preempted);
                        continue;
                    }
                    throw e;
                } catch (ServerUnreachableException e) {
                    endpoints.reportUnresponsive(endpoint);
                    if (streamed.get() || unreachable >= UNREACHABLE_RETRIES) {
                        throw e;
                    }
                    unreachable++;
                    LOG.info("The chat server did not answer; trying again once it is ready ({})", e.getMessage());
                } finally {
                    preemption.close();
                    // Releases the child's link to the caller's token.
                    requestToken.cancel();
                }
            }
        }
    }
}
