package edu.learnsphere.app.dev;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.core.model.Profile;

/**
 * Demo content for {@code runDemo} and the screenshot tour. Writes only into the app home of the current
 * run (a temporary folder for both tasks).
 */
public final class DemoData {

    public static final String PROFILE_NAME = "Amara Okafor";
    public static final int PROFILE_HUE = 18;

    private DemoData() {
    }

    /** Adds the demo profile when no profile exists. */
    public static void seedProfiles(AppContext context) {
        if (context.profiles().isEmpty()) {
            long now = context.clock().millis();
            context.profiles().save(Profile.create(PROFILE_NAME, PROFILE_HUE, now).withLastOpenedAt(now));
        }
    }
}
