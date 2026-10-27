package edu.learnsphere.app.ui;

/** The sections of the Studio. {@link #title()} is the tab label as people see it. */
public enum StudioTab {
    DETAILS("Details"),
    MODULES("Modules"),
    DOCUMENTS("PDFs"),
    REVIEW("Review queue"),
    PAGES("Scanned pages"),
    PUBLISH("Publish and share");

    private final String title;

    StudioTab(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
