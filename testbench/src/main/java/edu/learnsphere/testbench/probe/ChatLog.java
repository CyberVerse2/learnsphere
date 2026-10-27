package edu.learnsphere.testbench.probe;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

/**
 * Collects the per-request statistics the AI client logs, tagged with the phase running at the time:
 *
 * <ul>
 *   <li>{@code LocalChat} (DEBUG) after every chat request: priority, prompt and generated tokens, time to the
 *       first token, total time and finish reason;</li>
 *   <li>{@code StructuredChat} (WARN) when a JSON reply was not one complete JSON object;</li>
 *   <li>{@code LlamaItemGenerator} (DEBUG) for every draft it drops, with the reason.</li>
 * </ul>
 *
 * <p>These messages are diagnostics of the {@code ai} module; this class reads their arguments by position, so a
 * change to those log statements shows up here as missing statistics rather than as a failure.
 * {@link #install(Supplier)} raises the three loggers to DEBUG and attaches the collector; {@link #close()}
 * restores them. Thread-safe.
 */
public final class ChatLog extends AppenderBase<ILoggingEvent> implements AutoCloseable {

    private static final String LOCAL_CHAT = "edu.learnsphere.ai.client.LocalChat";
    private static final String STRUCTURED_CHAT = "edu.learnsphere.ai.client.StructuredChat";
    private static final String ITEM_GENERATOR = "edu.learnsphere.ai.client.LlamaItemGenerator";
    private static final List<String> WATCHED = List.of(LOCAL_CHAT, STRUCTURED_CHAT, ITEM_GENERATOR);

    /** One finished chat request. */
    public record Completion(String phase, String priority, long promptTokens, long completionTokens, long firstTokenMs,
                             long elapsedMs, String finishReason) {

        /** Generated tokens per second after the first token, or 0 when it cannot be worked out. */
        public double generationRate() {
            long generating = elapsedMs - firstTokenMs;
            return completionTokens > 1 && generating > 0 ? (completionTokens - 1) * 1000.0 / generating : 0;
        }

        /** Prompt tokens read per second (up to the first token), or 0 when it cannot be worked out. */
        public double promptRate() {
            return promptTokens > 0 && firstTokenMs > 0 ? promptTokens * 1000.0 / firstTokenMs : 0;
        }
    }

    /** A JSON reply that did not parse. */
    public record InvalidJson(String phase, String name, String finishReason, boolean retried) {
    }

    /** A draft the item generator dropped. */
    public record DroppedDraft(String phase, String type, String reason, String prompt) {
    }

    private final Supplier<String> phase;
    private final List<Completion> completions = new CopyOnWriteArrayList<>();
    private final List<InvalidJson> invalid = new CopyOnWriteArrayList<>();
    private final List<DroppedDraft> dropped = new CopyOnWriteArrayList<>();
    private final List<Logger> raised = new CopyOnWriteArrayList<>();
    private final List<Level> previous = new CopyOnWriteArrayList<>();

    private ChatLog(Supplier<String> phase) {
        this.phase = Objects.requireNonNull(phase, "phase");
    }

    /**
     * Starts collecting; {@code phase} names what is running whenever a message arrives.
     *
     * @throws IllegalStateException when Logback is not the logging backend
     */
    public static ChatLog install(Supplier<String> phase) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            throw new IllegalStateException("The testbench needs Logback to collect chat statistics");
        }
        ChatLog log = new ChatLog(phase);
        log.setContext(context);
        log.setName("testbench-chat-log");
        log.start();
        for (String name : WATCHED) {
            Logger logger = context.getLogger(name);
            log.previous.add(logger.getLevel());
            log.raised.add(logger);
            if (!name.equals(STRUCTURED_CHAT)) {
                logger.setLevel(Level.DEBUG);
            }
            logger.addAppender(log);
        }
        return log;
    }

    @Override
    protected void append(ILoggingEvent event) {
        Object[] args = event.getArgumentArray();
        if (args == null) {
            return;
        }
        String current = phase.get();
        String logger = event.getLoggerName();
        String message = event.getMessage();
        try {
            if (logger.equals(LOCAL_CHAT) && message.startsWith("Chat {} done") && args.length >= 6) {
                completions.add(new Completion(current, String.valueOf(args[0]), number(args[1]), number(args[2]),
                        number(args[3]), number(args[4]), String.valueOf(args[5])));
            } else if (logger.equals(STRUCTURED_CHAT) && message.contains("not complete JSON") && args.length >= 4) {
                invalid.add(new InvalidJson(current, String.valueOf(args[0]), String.valueOf(args[1]),
                        String.valueOf(args[3]).startsWith("trying")));
            } else if (logger.equals(ITEM_GENERATOR) && message.startsWith("Dropped a {} draft") && args.length >= 3) {
                dropped.add(new DroppedDraft(current, String.valueOf(args[0]), String.valueOf(args[1]),
                        String.valueOf(args[2])));
            }
        } catch (RuntimeException e) {
            addWarn("Unexpected chat statistics message: " + event.getFormattedMessage(), e);
        }
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value).strip());
    }

    /** Every finished chat request so far. */
    public List<Completion> completions() {
        return List.copyOf(completions);
    }

    /** Finished chat requests during {@code phaseName}. */
    public List<Completion> completions(String phaseName) {
        return completions.stream().filter(c -> c.phase().equals(phaseName)).toList();
    }

    /** Unparseable JSON replies so far. */
    public List<InvalidJson> invalidJson() {
        return List.copyOf(invalid);
    }

    /** Unparseable JSON replies during {@code phaseName}. */
    public List<InvalidJson> invalidJson(String phaseName) {
        return invalid.stream().filter(c -> c.phase().equals(phaseName)).toList();
    }

    /** Drafts the item generator dropped so far. */
    public List<DroppedDraft> droppedDrafts() {
        return List.copyOf(dropped);
    }

    /** Detaches the collector and restores the loggers' levels. */
    @Override
    public void close() {
        for (int i = 0; i < raised.size(); i++) {
            Logger logger = raised.get(i);
            logger.detachAppender(this);
            logger.setLevel(previous.get(i));
        }
        raised.clear();
        previous.clear();
        stop();
    }
}
