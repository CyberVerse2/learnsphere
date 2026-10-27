package edu.learnsphere.app.ui.studio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javafx.stage.Window;

/**
 * The file dialogs the Studio opens and the file manager it shows saved files in. The app uses
 * {@link #system()}; tests pass their own so they never wait on a dialog. Dialog methods run on the FX
 * thread; {@link #reveal} blocks briefly and runs in the background.
 */
public interface StudioFiles {

    /** PDFs to add to a course; empty when the creator cancels. */
    List<Path> choosePdfs(Window owner);

    /**
     * Where to save a course file, ending in {@code .learnsphere}; null when the creator cancels.
     *
     * @param suggestedName the file name to offer, e.g. {@code "Cell Biology.learnsphere"}
     */
    Path chooseCourseFile(Window owner, String suggestedName);

    /** Learners' progress files to add; empty when the creator cancels. */
    List<Path> chooseProgressFiles(Window owner);

    /** A learner's feedback file; null when the creator cancels. */
    Path chooseFeedbackFile(Window owner);

    /** Opens the computer's file manager with {@code file} selected. */
    void reveal(Path file) throws IOException;

    /** The dialogs and file manager of this computer. */
    static StudioFiles system() {
        return new SystemStudioFiles();
    }
}
