package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.StudioTab;
import java.util.Objects;

/**
 * What every part of the Studio shares: the app, the navigator, the course being edited (null on the list
 * of courses), the file dialogs and a way to switch tabs without leaving the page.
 */
record StudioHost(AppContext context, Navigator navigator, String courseId, StudioFiles files, Tabs tabs) {

    StudioHost {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(navigator, "navigator");
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(tabs, "tabs");
    }

    /** Switches the Studio to another tab of the same course. */
    @FunctionalInterface
    interface Tabs {

        /**
         * @param documentId a PDF the tab should start with (scanned pages), or null
         */
        void open(StudioTab tab, String documentId);
    }
}
