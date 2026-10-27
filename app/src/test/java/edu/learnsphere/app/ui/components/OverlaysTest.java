package edu.learnsphere.app.ui.components;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.service.ThemePreference;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Toasts and confirmation dialogs, which live in the window's overlay layers. */
@ExtendWith(ApplicationExtension.class)
class OverlaysTest {

    private StackPane host;
    private Overlays overlays;
    private Button behind;

    @Start
    void start(Stage stage) {
        Typography.load();
        behind = new Button("Behind");
        host = new StackPane(behind);
        overlays = Overlays.install(host);
        Scene scene = new Scene(host, 900, 600);
        Theme.install(scene, ThemePreference.LIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void showsAtMostThreeToastsAtOnce(FxRobot robot) {
        robot.interact(() -> {
            for (int i = 1; i <= 5; i++) {
                Toasts.show(behind, Toasts.Kind.INFO, "Saved " + i, null, null, Duration.INDEFINITE);
            }
        });

        assertThat(openToasts()).hasSize(Toasts.MAX_VISIBLE);
        assertThat(openToasts()).extracting(Toasts.Toast::message).containsExactly("Saved 3", "Saved 4", "Saved 5");
    }

    @Test
    void errorToastShowsTheMessageAndItsActionCloses(FxRobot robot) throws Exception {
        AtomicInteger undone = new AtomicInteger();
        robot.interact(() -> Toasts.error(behind, UserMessage.of("You're offline. Try again when you're connected.")));
        assertThat(openToasts()).singleElement().satisfies(toast -> {
            assertThat(toast.kind()).isEqualTo(Toasts.Kind.ERROR);
            assertThat(toast.message()).isEqualTo("You're offline. Try again when you're connected.");
        });

        Toasts.Toast toast = onFx(() -> Toasts.show(behind, Toasts.Kind.SUCCESS, "Rejected", "Undo",
                undone::incrementAndGet, Duration.INDEFINITE));
        robot.clickOn("Undo");
        assertThat(undone).hasValue(1);
        WaitForAsyncUtils.waitFor(2, TimeUnit.SECONDS, () -> !overlays.toasts().getChildren().contains(toast));
    }

    @Test
    void confirmDialogResolvesTrueWhenConfirmed(FxRobot robot) throws Exception {
        CompletableFuture<Boolean> answer = onFx(() -> ConfirmDialog.of("Delete this course?",
                "Its PDFs, questions and progress are removed from this computer.", "Delete course").danger().show(behind));
        assertThat(overlays.hasModal()).isTrue();

        robot.clickOn("Delete course");

        assertThat(answer.get(2, TimeUnit.SECONDS)).isTrue();
        WaitForAsyncUtils.waitFor(2, TimeUnit.SECONDS, () -> !overlays.hasModal());
    }

    @Test
    void confirmDialogCancelsWithEscape(FxRobot robot) throws Exception {
        CompletableFuture<Boolean> answer = onFx(() -> ConfirmDialog.of("Leave this session?",
                "Your answers so far are kept.", "Leave").show(behind));

        robot.type(KeyCode.ESCAPE);

        assertThat(answer.get(2, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void confirmDialogKeepsFocusInside(FxRobot robot) {
        robot.interact(() -> ConfirmDialog.of("Leave this session?", "Your answers so far are kept.", "Leave").show(behind));

        robot.type(KeyCode.TAB);
        robot.type(KeyCode.TAB);
        robot.type(KeyCode.TAB);

        Node focused = host.getScene().getFocusOwner();
        assertThat(focused).isNotSameAs(behind);
        assertThat(focused).isInstanceOf(Button.class);
        assertThat(((Button) focused).getText()).isIn("Leave", "Cancel");
    }

    private List<Toasts.Toast> openToasts() {
        return overlays.toasts().getChildren().stream()
                .filter(Toasts.Toast.class::isInstance).map(Toasts.Toast.class::cast)
                .filter(Toasts.Toast::isShowing)
                .toList();
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> action) throws Exception {
        return WaitForAsyncUtils.asyncFx(action).get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
