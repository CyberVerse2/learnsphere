package edu.learnsphere.app.ui;

/**
 * The screens of the app, as the sidebar and the full-screen rule see them. A {@link Destination} names
 * the screen and what it shows. {@link #title()} is the screen's name as people see it.
 */
public enum Route {
    ONBOARDING("Welcome"),
    PROFILES("Profiles"),
    HOME("Home"),
    LIBRARY("Library"),
    COURSE("Course"),
    ASK("Ask"),
    STUDY("Study"),
    PROGRESS("Progress"),
    STUDIO("Studio"),
    SETTINGS("Settings");

    private final String title;

    Route(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    /** True for screens that hide the sidebar (focus mode and first-run flows). */
    public boolean isFullScreen() {
        return this == STUDY || this == ONBOARDING || this == PROFILES;
    }
}
