package edu.learnsphere.app.ui.components;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The standard screen body: a vertically scrolling page whose content column is at most
 * {@link #MAX_WIDTH} wide, centred, with 40 px side padding. Add sections to {@link #column()}.
 */
public class Page extends ScrollPane {

    public static final double MAX_WIDTH = 1120;

    private final VBox column = new VBox();

    public Page(Node... sections) {
        getStyleClass().setAll("scroll-pane", "page");
        column.getStyleClass().add("page-column");
        column.setMaxWidth(MAX_WIDTH);
        column.getChildren().addAll(sections);
        HBox.setHgrow(column, Priority.ALWAYS);
        HBox centring = new HBox(column);
        centring.setAlignment(Pos.TOP_CENTER);
        centring.getStyleClass().add("page-centring");
        setContent(centring);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
    }

    /** The content column. */
    public VBox column() {
        return column;
    }
}
