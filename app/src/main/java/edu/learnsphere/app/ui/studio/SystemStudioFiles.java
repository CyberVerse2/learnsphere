package edu.learnsphere.app.ui.studio;

import edu.learnsphere.core.service.SharingService;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * {@link StudioFiles} with the operating system's file dialogs. Each dialog opens in the folder the creator
 * last used during this run.
 */
final class SystemStudioFiles implements StudioFiles {

    private static volatile File lastFolder;

    @Override
    public List<Path> choosePdfs(Window owner) {
        FileChooser chooser = chooser("Add PDFs", new FileChooser.ExtensionFilter("PDFs", "*.pdf", "*.PDF"));
        List<File> files = chooser.showOpenMultipleDialog(owner);
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        remember(files.getFirst());
        return files.stream().map(File::toPath).toList();
    }

    @Override
    public Path chooseCourseFile(Window owner, String suggestedName) {
        FileChooser chooser = chooser("Export course",
                new FileChooser.ExtensionFilter("LearnSphere courses", "*." + SharingService.COURSE_EXTENSION));
        chooser.setInitialFileName(suggestedName);
        File file = chooser.showSaveDialog(owner);
        if (file == null) {
            return null;
        }
        remember(file);
        Path path = file.toPath();
        String name = path.getFileName().toString();
        // Some systems leave the extension off when the creator types a name without it.
        if (!name.toLowerCase(Locale.ROOT).endsWith(StudioFormats.COURSE_FILE_SUFFIX)) {
            path = path.resolveSibling(name + StudioFormats.COURSE_FILE_SUFFIX);
        }
        return path;
    }

    @Override
    public List<Path> chooseProgressFiles(Window owner) {
        FileChooser chooser = chooser("Add learners' progress",
                new FileChooser.ExtensionFilter("Progress files", "*." + SharingService.PROGRESS_EXTENSION));
        List<File> files = chooser.showOpenMultipleDialog(owner);
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        remember(files.getFirst());
        return files.stream().map(File::toPath).toList();
    }

    @Override
    public Path chooseFeedbackFile(Window owner) {
        FileChooser chooser = chooser("Add learner feedback",
                new FileChooser.ExtensionFilter("Feedback files", "*." + SharingService.FEEDBACK_EXTENSION));
        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return null;
        }
        remember(file);
        return file.toPath();
    }

    @Override
    public void reveal(Path file) throws IOException {
        Path target = file.toAbsolutePath();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            start("open", "-R", target.toString());
        } else if (os.contains("win")) {
            start("explorer.exe", "/select," + target);
        } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
            Desktop.getDesktop().browseFileDirectory(target.toFile());
        } else {
            Path folder = Files.isDirectory(target) ? target : target.getParent();
            start("xdg-open", folder.toString());
        }
    }

    private static void start(String... command) throws IOException {
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static FileChooser chooser(String title, FileChooser.ExtensionFilter filter) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(filter);
        File folder = lastFolder;
        if (folder != null && folder.isDirectory()) {
            chooser.setInitialDirectory(folder);
        }
        return chooser;
    }

    private static void remember(File file) {
        File folder = file.getParentFile();
        if (folder != null) {
            lastFolder = folder;
        }
    }
}
