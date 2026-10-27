package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.db.SettingsRepository;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.SettingChanged;
import edu.learnsphere.core.service.AppSettings;
import edu.learnsphere.core.service.SettingKey;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.SettingsService;
import java.util.Objects;

/** {@link SettingsService} over the {@code settings} table. */
public final class SettingsServiceImpl implements SettingsService {

    private static final UserMessage OUT_OF_RANGE = UserMessage.of("That value isn't allowed here. Pick one in the range shown.");

    private final SettingsRepository repository;
    private final EventBus events;

    public SettingsServiceImpl(SettingsRepository repository, EventBus events) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public AppSettings current() {
        return new AppSettings(get(SettingKeys.NEW_ITEMS_PER_DAY), get(SettingKeys.DESIRED_RETENTION),
                get(SettingKeys.SESSION_SIZE), get(SettingKeys.DESCRIBE_FIGURES), get(SettingKeys.MODEL_CHOICE),
                get(SettingKeys.EVIDENCE_THRESHOLD));
    }

    @Override
    public <T> T get(SettingKey<T> key) {
        return key.parse(repository.get(key.key()).orElse(null));
    }

    @Override
    public <T> void set(SettingKey<T> key, T value) {
        if (!key.isValid(value)) {
            throw new InvalidInputException(OUT_OF_RANGE, "Invalid value for " + key + ": " + value);
        }
        repository.put(key.key(), key.format(value));
        events.publish(new SettingChanged(key.key()));
    }

    @Override
    public void reset(SettingKey<?> key) {
        if (repository.delete(key.key())) {
            events.publish(new SettingChanged(key.key()));
        }
    }
}
