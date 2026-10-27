package edu.learnsphere.app.dev;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.core.service.ProfileDraft;

/**
 * Demo content for {@code runDemo} and the screenshot tour. Writes only into the app home of the current
 * run (a temporary folder for both tasks). The courses and progress the demo shows come from
 * {@link DemoWorkspace}.
 */
public final class DemoData {

    public static final String PROFILE_NAME = "Amara Okafor";
    public static final int PROFILE_HUE = 18;
    /** The course studied mid-way, with every module and a conversation. */
    public static final String OPERATING_SYSTEMS = DemoContent.OPERATING_SYSTEMS;
    /** The course started recently. */
    public static final String RESEARCH_METHODS = DemoContent.RESEARCH_METHODS;
    /** The course being written in the Studio. */
    public static final String ORGANIC_CHEMISTRY = DemoContent.ORGANIC_CHEMISTRY;

    private DemoData() {
    }

    /** Adds the demo profile when no profile exists. */
    public static void seedProfiles(AppContext context) {
        if (context.profiles().list().isEmpty()) {
            context.profiles().create(ProfileDraft.of(PROFILE_NAME, PROFILE_HUE));
        }
    }
}
