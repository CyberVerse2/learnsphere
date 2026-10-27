package edu.learnsphere.app.testing;

import edu.learnsphere.app.ui.SystemFiles;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.stage.Window;

/**
 * {@link SystemFiles} for tests: the save and open dialogs answer with the paths the test set (or are
 * cancelled when none is set), and showing a folder only records it.
 */
public final class RecordingFiles implements SystemFiles {

    private volatile Path saveAnswer;
    private volatile Path openAnswer;
    private final List<String> suggestedNames = new CopyOnWriteArrayList<>();
    private final List<Path> revealed = new CopyOnWriteArrayList<>();

    /** The next save dialogs choose {@code path}; null cancels them. */
    public RecordingFiles saveTo(Path path) {
        saveAnswer = path;
        return this;
    }

    /** The next open dialogs choose {@code path}; null cancels them. */
    public RecordingFiles openFrom(Path path) {
        openAnswer = path;
        return this;
    }

    /** The file names the save dialogs suggested, in order. */
    public List<String> suggestedNames() {
        return List.copyOf(suggestedNames);
    }

    /** The folders shown in the file manager, in order. */
    public List<Path> revealed() {
        return List.copyOf(revealed);
    }

    @Override
    public Optional<Path> chooseSave(Window owner, String title, String suggestedName, String kind, String extension) {
        suggestedNames.add(suggestedName);
        return Optional.ofNullable(saveAnswer);
    }

    @Override
    public Optional<Path> chooseOpen(Window owner, String title, String kind, String extension) {
        return Optional.ofNullable(openAnswer);
    }

    @Override
    public boolean canReveal() {
        return true;
    }

    @Override
    public void reveal(Path folder) {
        revealed.add(folder);
    }
}
