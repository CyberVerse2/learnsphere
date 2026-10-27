package edu.learnsphere.core.service;

import edu.learnsphere.core.spi.ModelChoice;
import java.util.Objects;

/** A snapshot of every setting, for the Settings screen. See {@link SettingKeys} for meanings and defaults. */
public record AppSettings(ThemePreference theme, int newItemsPerDay, double desiredRetention, int sessionSize,
                          boolean describeFigures, ModelChoice modelChoice, double evidenceThreshold, boolean reduceMotion,
                          boolean onboardingCompleted) {

    public AppSettings {
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(modelChoice, "modelChoice");
    }

    /** Every setting at its default. */
    public static AppSettings defaults() {
        return new AppSettings(SettingKeys.THEME.defaultValue(), SettingKeys.NEW_ITEMS_PER_DAY.defaultValue(),
                SettingKeys.DESIRED_RETENTION.defaultValue(), SettingKeys.SESSION_SIZE.defaultValue(),
                SettingKeys.DESCRIBE_FIGURES.defaultValue(), SettingKeys.MODEL_CHOICE.defaultValue(),
                SettingKeys.EVIDENCE_THRESHOLD.defaultValue(), SettingKeys.REDUCE_MOTION.defaultValue(),
                SettingKeys.ONBOARDING_COMPLETED.defaultValue());
    }
}
