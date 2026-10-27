package edu.learnsphere.core.service;

import edu.learnsphere.core.error.InvalidInputException;

/** Typed per-profile settings. Changes publish {@link edu.learnsphere.core.events.SettingChanged}. */
public interface SettingsService {

    /** Every setting. */
    AppSettings current();

    /** One setting (its default when never set). */
    <T> T get(SettingKey<T> key);

    /** Stores a setting. @throws InvalidInputException when the value is out of range */
    <T> void set(SettingKey<T> key, T value);

    /** Returns a setting to its default. */
    void reset(SettingKey<?> key);
}
