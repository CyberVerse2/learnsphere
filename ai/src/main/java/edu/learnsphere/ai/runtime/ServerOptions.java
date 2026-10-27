package edu.learnsphere.ai.runtime;

import java.time.Duration;
import java.util.Objects;

/**
 * How {@link LlamaServerManager} runs the servers. {@link #defaults()} holds the production values; tests
 * shorten the timings.
 *
 * @param chatContext     context size of the chat server ({@code -c})
 * @param embedContext    context and micro-batch size of the embedding server
 * @param rerankContext   context and micro-batch size of the reranking server
 * @param gpuLayers       layers offloaded to the GPU ({@code -ngl})
 * @param chatCacheMib    prompt cache the chat server may keep in memory ({@code --cache-ram}, MiB)
 * @param startTimeout    how long a server may take to answer {@code /health} after launch
 * @param healthInterval  pause between {@code /health} polls while a server starts
 * @param maxRestarts     restarts of a crashed server before the runtime reports failure
 * @param restartBackoff  pause before the first restart; doubled for each further one
 * @param stableAfter     a server that ran this long before crashing gets its restart budget back
 * @param readyWait       how long a request waits for a starting server before giving up
 * @param stopTimeout     how long a server may take to exit before it is killed
 * @param launchCommand   builds the command line (tests substitute a stand-in server)
 */
public record ServerOptions(int chatContext, int embedContext, int rerankContext, int gpuLayers, int chatCacheMib,
                            Duration startTimeout, Duration healthInterval, int maxRestarts, Duration restartBackoff,
                            Duration stableAfter, Duration readyWait, Duration stopTimeout,
                            LaunchCommand launchCommand) {

    public ServerOptions {
        positive(chatContext, "chatContext");
        positive(embedContext, "embedContext");
        positive(rerankContext, "rerankContext");
        if (gpuLayers < 0 || chatCacheMib < 0 || maxRestarts < 0) {
            throw new IllegalArgumentException("gpuLayers, chatCacheMib and maxRestarts must be >= 0");
        }
        positive(startTimeout, "startTimeout");
        positive(healthInterval, "healthInterval");
        positive(restartBackoff, "restartBackoff");
        positive(stableAfter, "stableAfter");
        positive(readyWait, "readyWait");
        positive(stopTimeout, "stopTimeout");
        Objects.requireNonNull(launchCommand, "launchCommand");
    }

    /** The production values (the flags documented in docs/ARCHITECTURE.md). */
    public static ServerOptions defaults() {
        return new ServerOptions(16384, 8192, 8192, 99, 2048,
                Duration.ofMinutes(3), Duration.ofMillis(250), 3, Duration.ofSeconds(1),
                Duration.ofMinutes(5), Duration.ofMinutes(3), Duration.ofSeconds(5),
                LaunchCommand.DIRECT);
    }

    /** These options with other timings. */
    public ServerOptions withTimings(Duration start, Duration health, Duration backoff, Duration ready, Duration stop) {
        return new ServerOptions(chatContext, embedContext, rerankContext, gpuLayers, chatCacheMib, start, health,
                maxRestarts, backoff, stableAfter, ready, stop, launchCommand);
    }

    /** These options with another restart budget and stability period. */
    public ServerOptions withRestarts(int restarts, Duration stable) {
        return new ServerOptions(chatContext, embedContext, rerankContext, gpuLayers, chatCacheMib, startTimeout,
                healthInterval, restarts, restartBackoff, stable, readyWait, stopTimeout, launchCommand);
    }

    /** These options with another command builder. */
    public ServerOptions withLaunchCommand(LaunchCommand command) {
        return new ServerOptions(chatContext, embedContext, rerankContext, gpuLayers, chatCacheMib, startTimeout,
                healthInterval, maxRestarts, restartBackoff, stableAfter, readyWait, stopTimeout, command);
    }

    private static void positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be > 0: " + value);
        }
    }

    private static void positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be > 0: " + value);
        }
    }
}
