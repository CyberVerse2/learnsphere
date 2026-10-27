package edu.learnsphere.app.ui;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.core.events.AssistantStatusChanged;
import edu.learnsphere.core.service.AssistantState;
import edu.learnsphere.core.service.AssistantStatus;
import edu.learnsphere.core.service.Workspace;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.Circle;

/**
 * A small status in the top bar saying whether the study assistant is ready, getting ready or unavailable,
 * with its full message as a tooltip. Clicking it opens Settings, where the assistant is managed.
 */
public final class AssistantIndicator extends Button {

    private final AppContext context;
    private final Tooltip tooltip = new Tooltip();
    private final ChangeListener<Workspace> workspaceListener = (obs, old, workspace) -> follow(workspace);
    private Disposer scope = new Disposer();
    private AssistantState state;

    public AssistantIndicator(AppContext context, Navigator navigator) {
        this.context = context;
        getStyleClass().setAll("assistant-indicator");
        Circle dot = new Circle(4);
        dot.getStyleClass().add("assistant-dot");
        setGraphic(dot);
        setMnemonicParsing(false);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(280);
        setTooltip(tooltip);
        setOnAction(event -> navigator.navigate(Destination.SETTINGS));
        context.workspaceProperty().addListener(workspaceListener);
        follow(context.workspace());
    }

    /** The state shown, or null while no profile is open. */
    public AssistantState state() {
        return state;
    }

    /** Stops following the open profile. */
    public void dispose() {
        context.workspaceProperty().removeListener(workspaceListener);
        scope.dispose();
    }

    private void follow(Workspace workspace) {
        scope.dispose();
        scope = new Disposer();
        if (workspace == null) {
            setVisible(false);
            setManaged(false);
            state = null;
            return;
        }
        setVisible(true);
        setManaged(true);
        scope.add(UiTasks.run(() -> workspace.assistant().status(), this::show, message -> { }));
        scope.add(Fx.subscribe(workspace.events(), AssistantStatusChanged.class, event -> show(event.status())));
    }

    private void show(AssistantStatus status) {
        state = status.state();
        getStyleClass().removeAll("ready", "preparing", "unavailable");
        switch (status.state()) {
            case READY -> {
                getStyleClass().add("ready");
                setText("Study assistant ready");
            }
            case STARTING -> {
                getStyleClass().add("preparing");
                setText("Getting the study assistant ready");
            }
            default -> {
                getStyleClass().add("unavailable");
                setText("Study assistant unavailable");
            }
        }
        tooltip.setText(status.message().text());
        setAccessibleText(status.message().text());
    }
}
