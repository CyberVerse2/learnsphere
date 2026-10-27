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
     * @param itemId   one question of the course to show in the {@link StudioTab#REVIEW review queue},
     *                 whatever its status (approved, draft, rejected or retired), or null
     */
    record Studio(String courseId, StudioTab tab, String itemId) implements Destination {
        public Studio {
            Objects.requireNonNull(tab, "tab");
            if (itemId != null && courseId == null) {
                throw new IllegalArgumentException("A question is shown within its course");
            }
        }

        /** A tab of a course, or the list of courses created here when {@code courseId} is null. */
        public Studio(String courseId, StudioTab tab) {
            this(courseId, tab, null);
        }

        /** The list of courses created here. */
        public static Studio courses() {
            return new Studio(null, StudioTab.DETAILS);
        }

        /** One question of a course in the review queue, to read and change whatever its status. */
        public static Studio question(String courseId, String itemId) {
            return new Studio(Objects.requireNonNull(courseId, "courseId"), StudioTab.REVIEW,
                    Objects.requireNonNull(itemId, "itemId"));
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
