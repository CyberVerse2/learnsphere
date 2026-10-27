package edu.learnsphere.app.ui.library;

import edu.learnsphere.core.service.SharingService;
import java.io.File;
import java.nio.file.Path;
import javafx.scene.Node;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Asks the learner for a course file to open or a place to save one. The default uses the system file
 * dialogs; tests put their own in its place, since a headless window has no file dialogs.
 */
public interface FilePicker {

    /** A course package to import, or null when the learner cancelled. */
    Path chooseCourseFile(Node owner);

    /** Where to save a course package named {@code suggestedName}, or null when the learner cancelled. */
    Path chooseExportTarget(Node owner, String suggestedName);

    /** The system file dialogs. */
    static FilePicker system() {
        return new FilePicker() {
            @Override
            public Path chooseCourseFile(Node owner) {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Import course");
                chooser.getExtensionFilters().add(courseFiles());
                File file = chooser.showOpenDialog(window(owner));
                return file == null ? null : file.toPath();
            }

            @Override
            public Path chooseExportTarget(Node owner, String suggestedName) {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Save course");
                chooser.getExtensionFilters().add(courseFiles());
                chooser.setInitialFileName(suggestedName);
                File file = chooser.showSaveDialog(window(owner));
                if (file == null) {
                    return null;
                }
                String name = file.getName();
                String extension = "." + SharingService.COURSE_EXTENSION;
                return name.endsWith(extension) ? file.toPath() : file.toPath().resolveSibling(name + extension);
            }
        };
    }

    /** The filter for course packages. */
    static FileChooser.ExtensionFilter courseFiles() {
        return new FileChooser.ExtensionFilter("LearnSphere courses", "*." + SharingService.COURSE_EXTENSION);
    }

    /** A file name for a course package: the title without characters file systems refuse. */
    static String fileNameFor(String title) {
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80).strip();
        }
        return (cleaned.isEmpty() ? "Course" : cleaned) + "." + SharingService.COURSE_EXTENSION;
    }

    private static Window window(Node owner) {
        return owner == null || owner.getScene() == null ? null : owner.getScene().getWindow();
    }
}
