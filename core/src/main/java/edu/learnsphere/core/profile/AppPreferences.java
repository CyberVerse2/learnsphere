package edu.learnsphere.core.profile;

import edu.learnsphere.core.service.ThemePreference;
import java.util.Objects;

/**
 * App-wide preferences, shared by every profile and available before any profile is open (the profile
 * picker and onboarding use them). Stored in {@code app-settings.json} by {@link AppPreferencesStore}.
 *
 * @param theme               light, dark or follow the system
 * @param reduceMotion        replace movement with fades
 * @param lastProfileId       the profile opened most recently, offered first at start-up, or null
 * @param onboardingCompleted true once the first-run steps were finished; onboarding shows again whenever
 *                            no profile exists
 */
public record AppPreferences(ThemePreference theme, boolean reduceMotion, String lastProfileId, boolean onboardingCompleted) {

    /** Follow the system appearance, full motion, no profile yet, onboarding not done. */
    public static final AppPreferences DEFAULTS = new AppPreferences(ThemePreference.SYSTEM, false, null, false);

    public AppPreferences {
        Objects.requireNonNull(theme, "theme");
    }

    public AppPreferences withTheme(ThemePreference value) {
        return new AppPreferences(value, reduceMotion, lastProfileId, onboardingCompleted);
    }

    public AppPreferences withReduceMotion(boolean value) {
        return new AppPreferences(theme, value, lastProfileId, onboardingCompleted);
    }

    public AppPreferences withLastProfileId(String value) {
        return new AppPreferences(theme, reduceMotion, value, onboardingCompleted);
    }

    public AppPreferences withOnboardingCompleted(boolean value) {
        return new AppPreferences(theme, reduceMotion, lastProfileId, value);
    }
}
