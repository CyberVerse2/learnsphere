package edu.learnsphere.app.ui.progress;

import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.ProgressBarSlim;
import edu.learnsphere.app.ui.components.SectionHeader;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.ModuleProgress;
import java.util.List;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Module completion for one course, in course order: each module's number and title, a bar of the questions
 * started, the count, its mastery and what is due.
 */
final class ModulesSection {

    private ModulesSection() {
    }

    static Card of(List<ModuleProgress> modules) {
        long done = modules.stream().filter(m -> m.itemCount() > 0 && m.startedCount() >= m.itemCount()).count();
        SectionHeader header = new SectionHeader("Modules").subtitle(done == 0
                ? "Questions you've started in each module."
                : "Questions you've started in each module. " + done + " of " + modules.size() + " fully started.");
        GridPane grid = new GridPane();
        grid.getStyleClass().add("module-grid");
        ColumnConstraints number = new ColumnConstraints();
        number.setMinWidth(28);
        ColumnConstraints title = new ColumnConstraints();
        title.setMinWidth(160);
        title.setPrefWidth(240);
        title.setMaxWidth(320);
        ColumnConstraints bar = new ColumnConstraints();
        bar.setHgrow(Priority.ALWAYS);
        bar.setMinWidth(120);
        ColumnConstraints count = new ColumnConstraints();
        count.setMinWidth(96);
        count.setHalignment(HPos.RIGHT);
        ColumnConstraints mastery = new ColumnConstraints();
        mastery.setMinWidth(96);
        mastery.setHalignment(HPos.RIGHT);
        ColumnConstraints due = new ColumnConstraints();
        due.setMinWidth(80);
        due.setHalignment(HPos.RIGHT);
        grid.getColumnConstraints().addAll(number, title, bar, count, mastery, due);

        int row = 0;
        for (ModuleProgress module : modules) {
            Label index = new Label(Integer.toString(row + 1));
            index.getStyleClass().add("module-index");
            StackPane badge = new StackPane(index);
            badge.getStyleClass().add("module-badge");
            if (module.itemCount() > 0 && module.startedCount() >= module.itemCount()) {
                badge.getStyleClass().add("complete");
            }

            Label name = new Label(module.title());
            name.getStyleClass().addAll("medium", "module-name");
            name.setTextOverrun(OverrunStyle.ELLIPSIS);

            ProgressBarSlim progress = new ProgressBarSlim(6);
            progress.setProgress(module.completion());
            progress.setMaxWidth(Double.MAX_VALUE);
            progress.setAccessibleText(module.title() + ": " + Formats.percent(module.completion()) + " started");

            Label started = new Label(module.itemCount() == 0 ? "No questions"
                    : module.startedCount() + " of " + module.itemCount());
            started.getStyleClass().add("text-caption");

            Label masteryLabel = new Label(module.mastery() == null ? "Mastery –"
                    : "Mastery " + Formats.percent(module.mastery()));
            masteryLabel.getStyleClass().add("text-caption");
            if (module.mastery() != null && module.mastery() < ConceptMastery.WEAK_THRESHOLD) {
                masteryLabel.getStyleClass().add("text-warning");
            }

            Label dueLabel = new Label(module.dueCount() == 0 ? "Nothing due" : Formats.number(module.dueCount()) + " due");
            dueLabel.getStyleClass().addAll("text-caption", module.dueCount() == 0 ? "text-faint" : "text-accent");

            grid.addRow(row++, badge, name, progress, started, masteryLabel, dueLabel);
            GridPane.setHalignment(badge, HPos.LEFT);
        }
        VBox body = new VBox(grid);
        body.setAlignment(Pos.TOP_LEFT);
        Card card = new Card(header, body);
        card.setInteractive(false);
        card.getStyleClass().addAll("panel", "modules-card");
        card.setSpacing(20);
        return card;
    }
}
