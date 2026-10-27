package edu.learnsphere.app.ui.components;

import edu.learnsphere.app.theme.Theme;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/** A round avatar with initials, coloured from the profile's hue (readable in light and dark). */
public final class Avatar extends StackPane {

    private final Circle circle;
    private final Label initials;
    private final int hue;
    private final ChangeListener<Boolean> themeListener = (obs, was, dark) -> paint();

    public Avatar(String initials, int hue, double size) {
        this.hue = Math.floorMod(hue, 360);
        this.circle = new Circle(size / 2);
        this.initials = new Label(initials);
        this.initials.getStyleClass().add("avatar-initials");
        if (size >= 44) {
            this.initials.setStyle("-fx-font-size: " + Math.round(size * 0.36) + "px;");
        }
        getStyleClass().add("avatar");
        getChildren().addAll(circle, this.initials);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        Theme.darkProperty().addListener(new WeakChangeListener<>(themeListener));
        paint();
    }

    private void paint() {
        boolean dark = Theme.isDark();
        circle.setFill(dark ? Color.hsb(hue, 0.42, 0.36) : Color.hsb(hue, 0.24, 0.94));
        initials.setTextFill(dark ? Color.hsb(hue, 0.18, 0.97) : Color.hsb(hue, 0.78, 0.36));
    }
}
