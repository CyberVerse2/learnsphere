package edu.learnsphere.core.service;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A typed setting: its storage key, default and text conversion. Constants live in {@link SettingKeys}.
 *
 * @param <T> the value type
 */
public final class SettingKey<T> {

    private final String key;
    private final Class<T> type;
    private final T defaultValue;
    private final Function<String, T> parse;
    private final Function<T, String> format;
    private final Predicate<T> valid;

    public SettingKey(String key, Class<T> type, T defaultValue, Function<String, T> parse, Function<T, String> format,
                      Predicate<T> valid) {
        this.key = Objects.requireNonNull(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        this.parse = Objects.requireNonNull(parse, "parse");
        this.format = Objects.requireNonNull(format, "format");
        this.valid = Objects.requireNonNull(valid, "valid");
    }

    /** The key in the {@code settings} table. */
    public String key() {
        return key;
    }

    public Class<T> type() {
        return type;
    }

    public T defaultValue() {
        return defaultValue;
    }

    /** True when {@code value} is acceptable (in range, not null). */
    public boolean isValid(T value) {
        return value != null && valid.test(value);
    }

    /** Converts a stored text back to a value; unreadable or invalid text gives the default. */
    public T parse(String stored) {
        if (stored == null) {
            return defaultValue;
        }
        try {
            T value = parse.apply(stored);
            return isValid(value) ? value : defaultValue;
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    /** Converts a value to its stored text. */
    public String format(T value) {
        return format.apply(value);
    }

    @Override
    public String toString() {
        return key;
    }
}
