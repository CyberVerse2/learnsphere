package edu.learnsphere.ai;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import edu.learnsphere.ai.runtime.HardwareProbe;
import edu.learnsphere.ai.runtime.RuntimeLocator;
import edu.learnsphere.ai.runtime.ServerOptions;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.core.util.Subscription;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * One {@link LocalAi} over the real runtime folder, shared by every integration test in the run and closed
 * (stopping its servers) when the run ends. Tests are skipped, not failed, when the runtime folder is
 * missing.
 */
public final class RealRuntime implements AutoCloseable {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(RealRuntime.class);

    private final LocalAi ai;

    private RealRuntime(Path runtime) {
        Path logs = Path.of(System.getProperty("java.io.tmpdir")).resolve("learnsphere-ai-it-logs");
        this.ai = LocalAi.create(Optional.of(runtime), HardwareProbe.probe(), logs, ServerOptions.defaults());
    }

    /** The runtime folder named by {@code learnsphere.runtime}, when it exists. */
    public static Optional<Path> runtimeFolder() {
        String property = System.getProperty(RuntimeLocator.PROPERTY);
        if (property == null || property.isBlank()) {
            return Optional.empty();
        }
        Path path = Path.of(property);
        return Files.isDirectory(path.resolve("models")) ? Optional.of(path) : Optional.empty();
    }

    /** The shared AI for this test run, started and ready; skips the test when the runtime is missing. */
    public static LocalAi ready(ExtensionContext context) {
        Optional<Path> folder = runtimeFolder();
        assumeTrue(folder.isPresent(), "No runtime folder (set -Plearnsphere.runtime)");
        RealRuntime shared = context.getRoot().getStore(NAMESPACE)
                .computeIfAbsent(RealRuntime.class, key -> new RealRuntime(folder.get()), RealRuntime.class);
        shared.awaitReady(Duration.ofMinutes(4));
        return shared.ai;
    }

    private void awaitReady(Duration timeout) {
        if (ai.runtime().status().isReady()) {
            return;
        }
        CountDownLatch ready = new CountDownLatch(1);
        Subscription subscription = ai.runtime().addListener(status -> {
            if (status.isReady() || status.state() == AiStatus.State.FAILED
                    || status.state() == AiStatus.State.UNAVAILABLE && !"stopped".equals(status.detail())) {
                ready.countDown();
            }
        });
        try {
            if (!ai.runtime().status().isReady()) {
                ai.runtime().start(ModelChoice.AUTO);
            }
            if (!ai.runtime().status().isReady() && !ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("The AI runtime was not ready within " + timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            subscription.close();
        }
        if (!ai.runtime().status().isReady()) {
            throw new IllegalStateException("The AI runtime did not start: " + ai.runtime().status());
        }
    }

    @Override
    public void close() {
        ai.close();
    }

    /** Injects the shared, ready {@link LocalAi} into test and lifecycle method parameters. */
    public static final class Resolver implements ParameterResolver {

        @Override
        public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
            return parameter.getParameter().getType() == LocalAi.class;
        }

        @Override
        public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
            return ready(context);
        }
    }
}
