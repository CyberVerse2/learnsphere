package edu.learnsphere.core.service;

import edu.learnsphere.core.spi.ModelChoice;
import java.util.Objects;

/**
 * A snapshot of every per-profile setting, for the Settings screen. See {@link SettingKeys} for meanings and
 * defaults; app-wide preferences (appearance, onboarding) are in
 * {@link edu.learnsphere.core.profile.AppPreferences}.
 */
public record AppSettings(int newItemsPerDay, double desiredRetention, int sessionSize, boolean describeFigures,
                          ModelChoice modelChoice, double evidenceThreshold) {

    public AppSettings {
        Objects.requireNonNull(modelChoice, "modelChoice");
    }

    /** Every setting at its default. */
    public static AppSettings defaults() {
        return new AppSettings(SettingKeys.NEW_ITEMS_PER_DAY.defaultValue(), SettingKeys.DESIRED_RETENTION.defaultValue(),
                SettingKeys.SESSION_SIZE.defaultValue(), SettingKeys.DESCRIBE_FIGURES.defaultValue(),
                SettingKeys.MODEL_CHOICE.defaultValue(), SettingKeys.EVIDENCE_THRESHOLD.defaultValue());
    }
}
