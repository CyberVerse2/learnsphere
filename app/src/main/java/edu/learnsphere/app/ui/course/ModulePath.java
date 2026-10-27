package edu.learnsphere.app.ui.course;

import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.ProgressBarSlim;
import edu.learnsphere.app.ui.components.ProgressRing;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.core.service.ModuleSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The modules of a course as a vertical path: a ring per module that fills as its questions are studied (a
 * check once all are), joined by a line, with the module's title, summary, how much of it is studied, what is
 * due, and a button to quiz it.
 */
public final class ModulePath extends VBox {

    private final List<Step> steps = new ArrayList<>();

    /**
     * @param onQuiz starts a quiz of one module; null hides the quiz buttons
     */
    public ModulePath(List<ModuleSummary> modules, Consumer<ModuleSummary> onQuiz) {
        getStyleClass().add("module-trail");
        for (int i = 0; i < modules.size(); i++) {
            Step step = new Step(modules.get(i), i + 1, i == modules.size() - 1, onQuiz);
            steps.add(step);
            getChildren().add(step);
        }
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    /** "6 of 8 studied", "All 8 studied", "No questions yet". */
    public static String studiedText(ModuleSummary module) {
        if (module.itemCount() == 0) {
            return "No questions yet";
        }
        if (module.isComplete()) {
            return module.itemCount() == 1 ? "Studied" : "All " + Formats.number(module.itemCount()) + " studied";
        }
        long studied = Math.round(module.completion() * module.itemCount());
        return Formats.number(studied) + " of " + Formats.number(module.itemCount()) + " studied";
    }

    /** One module on the path. */
    public static final class Step extends HBox {

        private final ModuleSummary module;
        private final ProgressRing ring = new ProgressRing(36, 3);
        private final Label studied = new Label();
        private final Button quiz;

        Step(ModuleSummary module, int number, boolean last, Consumer<ModuleSummary> onQuiz) {
            this.module = Objects.requireNonNull(module, "module");
            getStyleClass().add("module-trail-step");
            if (module.isComplete()) {
                getStyleClass().add("complete");
            }

            ring.setShowPercent(false);
            Node center;
            if (module.isComplete()) {
                center = Icons.of(Feather.CHECK, 16);
                center.getStyleClass().add("module-trail-check");
            } else {
                Label numberLabel = new Label(Integer.toString(number));
                numberLabel.getStyleClass().addAll("semibold", "module-trail-number");
                center = numberLabel;
            }
            ring.setCenter(center);
            ring.setProgress(module.completion());
            Region line = new Region();
            line.getStyleClass().add("module-trail-line");
            VBox.setVgrow(line, Priority.ALWAYS);
            line.setVisible(!last);
            VBox rail = new VBox(6, ring, line);
            rail.setAlignment(Pos.TOP_CENTER);
            rail.getStyleClass().add("module-trail-rail");
            rail.setMinWidth(36);

            Label title = new Label(module.title());
            title.getStyleClass().addAll("serif-medium", "module-trail-title");
            title.setWrapText(true);
            VBox text = new VBox(4, title);
            String summary = module.module().summary();
            if (!summary.isBlank()) {
                Label summaryLabel = new Label(Formats.shorten(summary, 240));
                summaryLabel.getStyleClass().add("module-trail-summary");
                summaryLabel.setWrapText(true);
                text.getChildren().add(summaryLabel);
            }

            studied.setText(studiedText(module));
            studied.getStyleClass().addAll("text-caption", "module-trail-studied");
            HBox facts = new HBox(10);
            facts.setAlignment(Pos.CENTER_LEFT);
            if (module.itemCount() > 0) {
                ProgressBarSlim bar = new ProgressBarSlim(4);
                bar.setPrefWidth(120);
                bar.setMaxWidth(120);
                bar.setProgress(module.completion());
                if (module.isComplete()) {
                    bar.tone(Tone.SUCCESS);
                }
                facts.getChildren().add(bar);
            }
            facts.getChildren().add(studied);
            if (module.dueCount() > 0) {
                Label due = new Label(Formats.number(module.dueCount()) + " due");
                due.getStyleClass().addAll("text-caption", "module-trail-due");
                facts.getChildren().add(due);
            }
            text.getChildren().add(facts);
            facts.getStyleClass().add("module-trail-facts");
            HBox.setHgrow(text, Priority.ALWAYS);

            getChildren().addAll(rail, text);
            if (onQuiz != null) {
                quiz = new Button("Quiz");
                quiz.getStyleClass().addAll("small", "module-trail-quiz");
                quiz.setDisable(module.itemCount() == 0);
                quiz.setAccessibleText("Quiz " + module.title());
                quiz.setOnAction(event -> onQuiz.accept(module));
                getChildren().add(quiz);
            } else {
                quiz = null;
            }
            setSpacing(16);
            setAlignment(Pos.TOP_LEFT);
            setAccessibleText(module.title() + ", " + studied.getText());
        }

        public ModuleSummary module() {
            return module;
        }

        public ProgressRing ring() {
            return ring;
        }

        public Label studiedLabel() {
            return studied;
        }

        /** The quiz button, or null when the path has none. */
        public Button quizButton() {
            return quiz;
        }
    }
}
