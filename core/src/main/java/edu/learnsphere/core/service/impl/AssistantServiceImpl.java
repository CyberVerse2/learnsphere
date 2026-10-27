package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.events.AssistantStatusChanged;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.service.AssistantService;
import edu.learnsphere.core.service.AssistantStatus;
import edu.learnsphere.core.service.ModelOption;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.SettingsService;
import edu.learnsphere.core.spi.AiRuntime;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.core.util.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@link AssistantService} over the shared {@link AiRuntime}: {@link #start()} starts the runtime with the
 * profile's model setting and forwards its status changes as {@link AssistantStatusChanged} events;
 * {@link #close()} stops it.
 */
public final class AssistantServiceImpl implements AssistantService, AutoCloseable {

    private final AiRuntime runtime;
    private final SettingsService settings;
    private final EventBus events;
    private volatile Subscription forwarding = Subscription.NONE;

    public AssistantServiceImpl(AiRuntime runtime, SettingsService settings, EventBus events) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Starts the runtime in the background with the model setting. */
    public void start() {
        forwarding.close();
        forwarding = runtime.addListener(status -> events.publish(new AssistantStatusChanged(toStatus(status))));
        runtime.start(modelChoice());
    }

    @Override
    public AssistantStatus status() {
        return toStatus(runtime.status());
    }

    @Override
    public void restart() {
        runtime.start(modelChoice());
    }

    @Override
    public ModelChoice modelChoice() {
        return settings.get(SettingKeys.MODEL_CHOICE);
    }

    @Override
    public void setModelChoice(ModelChoice choice) {
        Objects.requireNonNull(choice, "choice");
        settings.set(SettingKeys.MODEL_CHOICE, choice);
        ModelChoice wanted = choice == ModelChoice.AUTO ? runtime.recommendedModel() : choice;
        if (wanted != runtime.status().model()) {
            runtime.start(choice);
        }
    }

    @Override
    public List<ModelOption> modelOptions() {
        ModelChoice selected = modelChoice();
        Set<ModelChoice> available = runtime.availableModels();
        ModelChoice recommended = runtime.recommendedModel();
        List<ModelOption> options = new ArrayList<>();
        options.add(new ModelOption(ModelChoice.AUTO, !available.isEmpty(), false, selected == ModelChoice.AUTO));
        for (ModelChoice choice : ModelChoice.values()) {
            if (choice != ModelChoice.AUTO) {
                options.add(new ModelOption(choice, available.contains(choice), choice == recommended, choice == selected));
            }
        }
        return List.copyOf(options);
    }

    @Override
    public Subscription addListener(Consumer<AssistantStatus> listener) {
        Objects.requireNonNull(listener, "listener");
        return runtime.addListener(status -> listener.accept(toStatus(status)));
    }

    /** Stops forwarding status changes and stops the runtime. */
    @Override
    public void close() {
        forwarding.close();
        runtime.stop();
    }

    static AssistantStatus toStatus(AiStatus status) {
        return switch (status.state()) {
            case STARTING -> AssistantStatus.starting(status.model());
            case READY -> AssistantStatus.ready(status.model());
            case UNAVAILABLE -> AssistantStatus.unavailable();
            case FAILED -> AssistantStatus.failed(status.model());
        };
    }
}
