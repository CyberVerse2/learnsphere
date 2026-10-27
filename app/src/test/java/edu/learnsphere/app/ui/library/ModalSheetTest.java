package edu.learnsphere.app.ui.library;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.components.Overlays;
import edu.learnsphere.core.service.ThemePreference;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/** Sheets close with the view that opened them, and a closed sheet is not kept by that view. */
@ExtendWith(ApplicationExtension.class)
class ModalSheetTest {

    private Button behind;

    @Start
    void start(Stage stage) {
        Typography.load();
        behind = new Button("Behind");
        StackPane host = new StackPane(behind);
        Overlays.install(host);
        Scene scene = new Scene(host, 900, 600);
        Theme.install(scene, ThemePreference.LIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void aViewForgetsClosedSheetsAndClosesTheOpenOneWhenItGoes(FxRobot robot) {
        Disposer view = new Disposer();
        robot.interact(() -> {
            for (int i = 0; i < 5; i++) {
                ModalSheet sheet = new ModalSheet("Sheet " + i).closeWith(view);
                sheet.show(behind);
                sheet.close();
            }
        });
        assertThat(ModalSheet.openIn(view)).isZero();

        AtomicReference<ModalSheet> open = new AtomicReference<>();
        robot.interact(() -> {
            ModalSheet sheet = new ModalSheet("Still open").closeWith(view);
            sheet.show(behind);
            open.set(sheet);
        });
        assertThat(ModalSheet.openIn(view)).isEqualTo(1);
        assertThat(open.get().isShowing()).isTrue();

        robot.interact(view::dispose);
        assertThat(open.get().isShowing()).isFalse();
        assertThat(ModalSheet.openIn(view)).isZero();
    }

    @Test
    void aSheetForAViewThatHasGoneNeverOpens(FxRobot robot) {
        Disposer view = new Disposer();
        view.dispose();
        AtomicReference<ModalSheet> late = new AtomicReference<>();
        robot.interact(() -> {
            ModalSheet sheet = new ModalSheet("Too late").closeWith(view);
            sheet.show(behind);
            late.set(sheet);
        });
        assertThat(late.get().isShowing()).isFalse();
        assertThat(Overlays.find(behind).hasModal()).isFalse();
    }
}
