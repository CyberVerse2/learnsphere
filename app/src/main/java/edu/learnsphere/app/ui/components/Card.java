package edu.learnsphere.app.ui.components;

import edu.learnsphere.app.theme.Motion;
import javafx.scene.Node;
import javafx.scene.layout.VBox;

/**
 * A surface with the card radius, hairline border and soft shadow. Interactive cards lift 2 px on hover;
 * call {@link #setInteractive(boolean) setInteractive(false)} for static panels.
 */
public class Card extends VBox {

    private boolean interactive = true;

    public Card(Node... children) {
        super(children);
        getStyleClass().add("card");
        hoverProperty().addListener((obs, was, hovering) -> {
            if (interactive) {
                Motion.liftTo(this, hovering ? -2 : 0);
            }
        });
    }

    /** Whether the card lifts on hover (default true). */
    public void setInteractive(boolean value) {
        interactive = value;
        if (value) {
            getStyleClass().remove("flat");
        } else if (!getStyleClass().contains("flat")) {
            getStyleClass().add("flat");
        }
    }
}
