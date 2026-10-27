package edu.learnsphere.app.ui;

import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.service.SessionRequest;
import java.util.Objects;

/**
 * Where the {@link Navigator} can go: a screen plus what it shows. Back history stores destinations, so
 * going back reopens the same course, thread or tab. {@link #route()} names the screen for the sidebar and
 * the full-screen rule.
 */
public sealed interface Destination {

    Home HOME = new Home();
    Library LIBRARY = new Library();
    Settings SETTINGS = new Settings();
    Profiles PROFILES = new Profiles();
    Onboarding ONBOARDING = new Onboarding();
    Gallery GALLERY = new Gallery();

    /** The screen this destination opens. */
    Route route();

    /** The home dashboard. */
    record Home() implements Destination {
        @Override
        public Route route() {
            return Route.HOME;
        }
    }

    /** Every course in the profile. */
    record Library() implements Destination {
        @Override
        public Route route() {
            return Route.LIBRARY;
        }
    }

    /** One course's page. */
    record Course(String courseId) implements Destination {
        public Course {
            Objects.requireNonNull(courseId, "courseId");
        }

        @Override
        public Route route() {
            return Route.COURSE;
        }
    }

    /**
     * Asking questions about a course.
     *
     * @param threadId the conversation to open, or null for a new one
     * @param focus    a citation to open in the source viewer, or null
     */
    record Ask(String courseId, String threadId, Citation focus) implements Destination {
        public Ask {
            Objects.requireNonNull(courseId, "courseId");
        }

        /** A new conversation about {@code courseId}. */
        public static Ask newThread(String courseId) {
            return new Ask(courseId, null, null);
        }

        @Override
        public Route route() {
            return Route.ASK;
        }
    }

    /** A study session (full screen). */
    record Study(SessionRequest request) implements Destination {
        public Study {
            Objects.requireNonNull(request, "request");
        }

        @Override
        public Route route() {
            return Route.STUDY;
        }
    }

    /**
     * The Studio.
     *
     * @param courseId the course being edited, or null for the list of courses created here
     */
    record Studio(String courseId, StudioTab tab) implements Destination {
        public Studio {
            Objects.requireNonNull(tab, "tab");
        }

        /** The list of courses created here. */
        public static Studio courses() {
            return new Studio(null, StudioTab.DETAILS);
        }

        @Override
        public Route route() {
            return Route.STUDIO;
        }
    }

    /**
     * Progress.
     *
     * @param courseId one course, or null for every course being studied
     */
    record Progress(String courseId) implements Destination {

        /** Progress across every course being studied. */
        public static Progress overview() {
            return new Progress(null);
        }

        @Override
        public Route route() {
            return Route.PROGRESS;
        }
    }

    /** Settings. */
    record Settings() implements Destination {
        @Override
        public Route route() {
            return Route.SETTINGS;
        }
    }

    /** Choosing a profile (full screen). */
    record Profiles() implements Destination {
        @Override
        public Route route() {
            return Route.PROFILES;
        }
    }

    /** First run (full screen). */
    record Onboarding() implements Destination {
        @Override
        public Route route() {
            return Route.ONBOARDING;
        }
    }

    /** Every component in both appearances (demo builds only). */
    record Gallery() implements Destination {
        @Override
        public Route route() {
            return Route.GALLERY;
        }
    }
}
