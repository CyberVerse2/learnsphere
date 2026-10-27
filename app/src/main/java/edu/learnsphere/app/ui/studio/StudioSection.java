package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.Event;
import edu.learnsphere.core.service.Workspace;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.scene.layout.Region;
import javafx.stage.Window;

/**
 * One part of the Studio page (the list of courses, or a tab of a course): it builds its own content,
 * loads its own data and listens to its own events, and releases all of it in {@link #dispose()}. The page
 * shows {@link #root()} under its header and tabs, calls {@link #onShown()} once it is on screen, and
 * disposes the section when another tab replaces it. FX thread only.
 */
abstract class StudioSection {

    protected final StudioHost host;
    protected final Disposer disposer = new Disposer();

    protected StudioSection(StudioHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /** The section's content. */
    abstract Region root();

    /** Called once the section is on screen: start loading here. */
    void onShown() {
    }

    /** True while the section holds edits that are not saved yet. */
    boolean hasUnsavedChanges() {
        return false;
    }

    /** What asking before leaving says about the unsaved edits, in one sentence. */
    String unsavedChangesText() {
        return "Your changes on this tab aren't saved.";
    }

    /** Releases loads, subscriptions and shortcuts. */
    void dispose() {
        disposer.dispose();
    }

    /** The open profile's services. */
    protected Workspace workspace() {
        Workspace workspace = host.context().workspace();
        if (workspace == null) {
            throw new IllegalStateException("No profile is open");
        }
        return workspace;
    }

    /** The course being edited. */
    protected String courseId() {
        return host.courseId();
    }

    /** Runs {@code work} in the background; the outcome is dropped once the section is disposed. */
    protected <T> void run(Callable<? extends T> work, Consumer<? super T> onFx, Consumer<? super UserMessage> onError) {
        disposer.add(UiTasks.run(work, onFx, onError));
    }

    /** Runs {@code work} in the background and shows a toast with {@code done}, or the error. */
    protected void act(Callable<?> work, String done) {
        run(work, ignored -> {
            if (done != null) {
                success(done);
            }
        }, this::error);
    }

    /** Calls {@code onFx} on the FX thread for each event of {@code type} until the section is disposed. */
    protected <E extends Event> void on(Class<E> type, Consumer<? super E> onFx) {
        Workspace workspace = host.context().workspace();
        if (workspace != null) {
            disposer.add(Fx.subscribe(workspace.events(), type, onFx));
        }
    }

    /** An error toast, when the section is still in the window. */
    protected void error(UserMessage message) {
        if (root().getScene() != null) {
            Toasts.error(root(), message);
        }
    }

    /** A success toast, when the section is still in the window. */
    protected void success(String text) {
        if (root().getScene() != null) {
            Toasts.success(root(), text);
        }
    }

    /** The window the section is in, for file dialogs; null before it is shown. */
    protected Window window() {
        return root().getScene() == null ? null : root().getScene().getWindow();
    }
}
