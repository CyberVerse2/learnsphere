package edu.learnsphere.app.ui.progress;

import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.core.service.CourseSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.feather.Feather;

/**
 * Chooses what the Progress screen shows: every course being studied, or one of them. Each course appears
 * with its cover; picking one calls the listener given to {@link #onPick}.
 */
final class CoursePicker extends ComboBox<CoursePicker.Choice> {

    /** One entry: one course ({@code id} set), or every course ({@code id} null). */
    record Choice(String id, String title, int hue) {
        static final Choice ALL = new Choice(null, "All courses", 0);

        static Choice of(CourseSummary course) {
            return new Choice(course.id(), course.title(), course.coverHue());
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private Consumer<Choice> listener = choice -> { };
    private boolean updating;

    CoursePicker() {
        getStyleClass().add("progress-picker");
        setAccessibleText("Course");
        setPrefWidth(300);
        setMaxWidth(300);
        setVisibleRowCount(10);
        setCellFactory(list -> new ChoiceCell(false));
        setButtonCell(new ChoiceCell(true));
        valueProperty().addListener((obs, old, value) -> {
            if (!updating && value != null && !value.equals(old)) {
                listener.accept(value);
            }
        });
    }

    /**
     * Lists "All courses" and {@code courses}, plus {@code current} when it is a course that is not among them,
     * and selects {@code current} without calling the listener.
     */
    void show(List<CourseSummary> courses, Choice current) {
        List<Choice> choices = new ArrayList<>();
        choices.add(Choice.ALL);
        courses.forEach(course -> choices.add(Choice.of(course)));
        if (current.id() != null && choices.stream().noneMatch(c -> current.id().equals(c.id()))) {
            choices.add(current);
        }
        updating = true;
        try {
            getItems().setAll(choices);
            setValue(choices.stream().filter(c -> java.util.Objects.equals(c.id(), current.id())).findFirst()
                    .orElse(Choice.ALL));
        } finally {
            updating = false;
        }
    }

    /** Called when the learner picks another entry. */
    void onPick(Consumer<Choice> listener) {
        this.listener = listener == null ? choice -> { } : listener;
    }

    /** Selects the course with {@code courseId} (null for every course) as if the learner had picked it. */
    void pick(String courseId) {
        getItems().stream().filter(c -> java.util.Objects.equals(c.id(), courseId)).findFirst().ifPresent(this::setValue);
    }

    private static final class ChoiceCell extends ListCell<Choice> {

        private final boolean button;

        ChoiceCell(boolean button) {
            this.button = button;
            getStyleClass().add("progress-picker-cell");
            setGraphicTextGap(10);
            setMinWidth(0);
        }

        @Override
        protected void updateItem(Choice choice, boolean empty) {
            super.updateItem(choice, empty);
            if (empty || choice == null) {
                setText(null);
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            setText(choice.title());
            setGraphic(graphic(choice));
            setAccessibleText(button ? "Course: " + choice.title() : choice.title());
        }

        private static Node graphic(Choice choice) {
            if (choice.id() == null) {
                StackPane all = new StackPane(Icons.of(Feather.LAYERS, 13));
                all.getStyleClass().add("progress-picker-all");
                all.setMinSize(22, 22);
                all.setPrefSize(22, 22);
                all.setMaxSize(22, 22);
                all.setAlignment(Pos.CENTER);
                return all;
            }
            return new CourseCover(choice.hue(), choice.id(), CourseCover.Size.TINY);
        }
    }
}
