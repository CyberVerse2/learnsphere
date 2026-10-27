package edu.learnsphere.app.ui.study;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.core.model.SessionKind;
import edu.learnsphere.core.service.ConceptChange;
import edu.learnsphere.core.service.SessionSummary;
import edu.learnsphere.core.service.StudyNext;
import edu.learnsphere.core.service.ThemePreference;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/** The end-of-session summary's numbers and sections. */
@ExtendWith(ApplicationExtension.class)
class SummaryViewTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T09:00:00Z"), ZoneOffset.UTC);

    private StackPane host;

    @Start
    void start(Stage stage) {
        Typography.load();
        host = new StackPane();
        Scene scene = new Scene(host, 900, 900);
        Theme.install(scene, ThemePreference.LIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void whatImprovedListsEachConceptBeforeAndAfter(FxRobot robot) {
        SessionSummary summary = summary(List.of(
                new ConceptChange("c1", "Research questions", 0.54, 0.80),
                new ConceptChange("c2", "Hypotheses", null, 0.83)));

        SummaryView view = show(robot, summary, new StudyNext(null, 0, 0, 0, null));

        assertThat(view.root().lookupAll(".improved-row")).hasSize(2);
        assertThat(texts(view.root(), ".improved-name")).containsExactly("Research questions", "Hypotheses");
        assertThat(texts(view.root(), ".improved-values")).containsExactly("54%  →  80%", "New  →  83%");
        assertThat(view.primaryButton().getText()).as("nothing more ready").isEqualTo("Back to Home");
    }

    @Test
    void nothingImprovedLeavesTheSectionOut(FxRobot robot) {
        SummaryView view = show(robot, summary(List.of()), new StudyNext(null, 3, 2, 4, null));

        assertThat(view.root().lookupAll(".improved-row")).isEmpty();
        assertThat(texts(view.root(), ".summary-ready")).containsExactly("5 more questions are ready now.");
        assertThat(view.primaryButton().getText()).isEqualTo("Study more");
    }

    private SummaryView show(FxRobot robot, SessionSummary summary, StudyNext more) {
        SummaryView[] view = new SummaryView[1];
        robot.interact(() -> {
            view[0] = new SummaryView(summary, new SummaryView.Extras(more, List.of(), 0), CLOCK, () -> { },
                    () -> { }, () -> { }, "Back to Home");
            host.getChildren().setAll(view[0].root());
            host.getStylesheets().setAll(SummaryViewTest.class.getResource("study.css").toExternalForm());
        });
        return view[0];
    }

    private static SessionSummary summary(List<ConceptChange> improved) {
        return new SessionSummary("s1", SessionKind.STUDY_NEXT, null, 4, 4, 3, 0.75, 300_000, 1, improved, null, 2);
    }

    private static List<String> texts(Node root, String query) {
        return root.lookupAll(query).stream().filter(Labeled.class::isInstance)
                .map(node -> ((Labeled) node).getText()).toList();
    }
}
