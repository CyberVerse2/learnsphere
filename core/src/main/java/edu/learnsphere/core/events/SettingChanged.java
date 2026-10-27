package edu.learnsphere.core.events;

import java.util.Objects;

/** A setting was changed or reset, for example the theme. {@code key} is a {@code SettingKey} key. */
public record SettingChanged(String key) implements Event {

    public SettingChanged {
        Objects.requireNonNull(key, "key");
    }
}
