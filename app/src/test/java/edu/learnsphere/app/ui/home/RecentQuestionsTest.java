package edu.learnsphere.app.ui.home;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.components.CitationChip;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.service.RecentQuestion;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.testing.MutableClock;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/** The keyboard on Home's recent questions: a row opens its conversation, a source chip opens its page. */
@ExtendWith(ApplicationExtension.class)
class RecentQuestionsTest {

    private static final Citation PAGE_17 = new Citation(1, null, "doc-1", "Lecture 4: Threads", 17, 17, "");

    private final List<String> opened = new ArrayList<>();
    private final MutableClock clock = MutableClock.fixed();
    private RecentQuestions questions;

    @Start
    void start(Stage stage) {
        Typography.load();
        questions = new RecentQuestions(question -> opened.add("thread"),
                (question, citation) -> opened.add("page " + citation.pageStart()));
        Scene scene = new Scene(new StackPane(questions), 900, 400);
        Theme.install(scene, ThemePreference.LIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @BeforeEach
    void fill(FxRobot robot) {
        robot.interact(() -> questions.show(List.of(new RecentQuestion("t1", "c1", "Operating Systems", 220,
                "What do threads of one process share?", "Its code, data and open files [1].", List.of(PAGE_17),
                clock.millis())), clock));
        opened.clear();
    }

    @Test
    void enterOnASourceChipOpensThatPage(FxRobot robot) {
        CitationChip chip = questions.rows().getFirst().chips().getFirst();
        robot.interact(chip::requestFocus);
        robot.type(KeyCode.ENTER);
        assertThat(opened).containsExactly("page 17");

        robot.type(KeyCode.SPACE);
        assertThat(opened).containsExactly("page 17", "page 17");
    }

    @Test
    void enterOrSpaceOnTheRowOpensTheConversation(FxRobot robot) {
        robot.interact(() -> questions.rows().getFirst().requestFocus());
        robot.type(KeyCode.ENTER);
        robot.type(KeyCode.SPACE);
        assertThat(opened).containsExactly("thread", "thread");
    }
}
