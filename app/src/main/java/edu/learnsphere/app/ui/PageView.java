package edu.learnsphere.app.ui;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Page;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.Event;
import edu.learnsphere.core.service.Workspace;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.layout.Region;

/**
 * Base of a scrolling screen: a {@link Page} column, the open workspace, and loads and event subscriptions
 * that end when the view is disposed. Subclasses build their content in {@link #onShown()} (usually by
 * calling {@link #load}) and put it in {@link #page()}'s column.
 */
public abstract class PageView implements View {

    protected final AppContext context;
    protected final Navigator navigator;
    protected final Disposer disposer = new Disposer();
    private final Page page = new Page();

    protected PageView(AppContext context, Navigator navigator) {
        this.context = Objects.requireNonNull(context, "context");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
    }

    @Override
    public Region root() {
        return page;
    }

    /** The scrolling page; add sections to its column. */
    protected Page page() {
        return page;
    }

    /** Replaces the page's sections. */
    protected void show(Node... sections) {
        page.column().getChildren().setAll(sections);
    }

    /** The open profile's services. */
    protected Workspace workspace() {
        Workspace workspace = context.workspace();
        if (workspace == null) {
            throw new IllegalStateException("No profile is open");
        }
        return workspace;
    }

    /** Runs {@code work} in the background and passes its result to {@code onFx}; errors replace the page. */
    protected <T> void load(Callable<? extends T> work, Consumer<? super T> onFx) {
        disposer.add(UiTasks.run(work, onFx, this::showError));
    }

    /** Runs {@code work} in the background with an explicit error handler. */
    protected <T> void run(Callable<? extends T> work, Consumer<? super T> onFx, Consumer<? super UserMessage> onError) {
        disposer.add(UiTasks.run(work, onFx, onError));
    }

    /** Calls {@code onFx} on the FX thread for each event of {@code type} until the view is disposed. */
    protected <E extends Event> void on(Class<E> type, Consumer<? super E> onFx) {
        Workspace workspace = context.workspace();
        if (workspace != null) {
            disposer.add(Fx.subscribe(workspace.events(), type, onFx));
        }
    }

    /** Shows {@code message} in place of the page's content. */
    protected void showError(UserMessage message) {
        show(new EmptyState(EmptyIllustration.Kind.SEARCH, "This page couldn't open", message.text())
                .primary("Go to Home", () -> navigator.navigate(Destination.HOME)));
    }

    @Override
    public void dispose() {
        disposer.dispose();
    }
}
