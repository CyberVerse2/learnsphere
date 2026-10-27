package edu.learnsphere.app.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The system's file dialogs and file manager, as screens use them: choosing where to save a file, choosing a
 * file to open, and showing a folder in Finder or File Explorer. The main window hands one to the screens
 * that need it; tests replace it with one that answers without a dialog. FX thread.
 */
public interface SystemFiles {

    /**
     * Asks where to save a file.
     *
     * @param title         the dialog's title, e.g. "Back up profile"
     * @param suggestedName the file name offered, with its extension
     * @param kind          what the files are called in the type list, e.g. "LearnSphere backups"
     * @param extension     the extension without a dot, e.g. "zip"
     * @return the chosen file, or empty when the dialog was cancelled
     */
    Optional<Path> chooseSave(Window owner, String title, String suggestedName, String kind, String extension);

    /** Asks for a file to open; empty when cancelled. */
    Optional<Path> chooseOpen(Window owner, String title, String kind, String extension);

    /** True when {@link #reveal} can show a folder on this computer. */
    boolean canReveal();

    /** Shows {@code folder} in the system's file manager. */
    void reveal(Path folder);

    /** What the system's file manager is called here, for "Show in Finder" style buttons. */
    static String fileManagerName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "Finder";
        }
        return os.contains("win") ? "File Explorer" : "Files";
    }

    /** The real dialogs ({@link FileChooser}) and file manager (Finder, File Explorer or the desktop's own). */
    static SystemFiles standard() {
        return Standard.INSTANCE;
    }

    /** {@link #standard()}. */
    final class Standard implements SystemFiles {

        private static final Standard INSTANCE = new Standard();
        private static final Logger LOG = LoggerFactory.getLogger(SystemFiles.class);

        private Standard() {
        }

        @Override
        public Optional<Path> chooseSave(Window owner, String title, String suggestedName, String kind, String extension) {
            FileChooser chooser = chooser(title, kind, extension);
            chooser.setInitialFileName(suggestedName);
            File file = chooser.showSaveDialog(owner);
            if (file == null) {
                return Optional.empty();
            }
            String name = file.getName();
            return Optional.of(name.toLowerCase(Locale.ROOT).endsWith("." + extension) ? file.toPath()
                    : file.toPath().resolveSibling(name + "." + extension));
        }

        @Override
        public Optional<Path> chooseOpen(Window owner, String title, String kind, String extension) {
            return Optional.ofNullable(chooser(title, kind, extension).showOpenDialog(owner)).map(File::toPath);
        }

        @Override
        public boolean canReveal() {
            return command(Path.of(".")).isPresent();
        }

        @Override
        public void reveal(Path folder) {
            command(folder).ifPresent(command -> Thread.ofVirtual().name("reveal-folder").start(() -> {
                try {
                    // The file manager opens the folder and the command returns straight away.
                    Process process = new ProcessBuilder(command).redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                    process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOG.warn("Could not show {}", folder, e);
                }
            }));
        }

        /** The command that shows {@code folder} in the file manager, when this system has one. */
        private static Optional<java.util.List<String>> command(Path folder) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String path = folder.toAbsolutePath().toString();
            if (os.contains("mac")) {
                return Optional.of(java.util.List.of("open", path));
            }
            if (os.contains("win")) {
                return Optional.of(java.util.List.of("explorer.exe", path));
            }
            for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
                if (!dir.isBlank() && java.nio.file.Files.isExecutable(Path.of(dir, "xdg-open"))) {
                    return Optional.of(java.util.List.of("xdg-open", path));
                }
            }
            return Optional.empty();
        }

        private static FileChooser chooser(String title, String kind, String extension) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(title);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(kind, "*." + extension));
            return chooser;
        }
    }
}
