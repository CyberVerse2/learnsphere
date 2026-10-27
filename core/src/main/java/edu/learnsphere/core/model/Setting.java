package edu.learnsphere.core.model;

import java.util.Objects;

/**
 * A row of {@code settings}: one stored preference as text. Typed access goes through
 * {@code SettingsService} and {@code SettingKeys}.
 */
public record Setting(String key, String value, long createdAt, long updatedAt) {

    public Setting {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }
}
