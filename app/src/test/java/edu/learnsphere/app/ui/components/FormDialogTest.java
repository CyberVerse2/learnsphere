package edu.learnsphere.app.ui.components;

import static edu.learnsphere.app.testing.TestWindows.fx;
import static edu.learnsphere.app.testing.TestWindows.settle;
import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.service.ThemePreference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/** The small form in a modal card: keyboard, checks, background work and its errors. */
@ExtendWith(ApplicationExtension.class)
class FormDialogTest {

    private StackPane host;
    private final Button behind = new Button("Behind");

    @Start
    void start(Stage stage) {
        Typography.load();
        host = new StackPane(behind);
        Overlays.install(host);
        Scene scene = new Scene(host, 900, 600);
        Theme.install(scene, ThemePreference.LIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void enterConfirmsWithTheFieldFocusedAndTheWorkRunsInTheBackground(FxRobot robot) throws Exception {
        TextField name = new TextField();
        AtomicReference<String> worker = new AtomicReference<>();
        AtomicReference<String> done = new AtomicReference<>();
        AtomicReference<FormDialog.Handle> handle = new AtomicReference<>();
        robot.interact(() -> handle.set(FormDialog.of("Rename")
                .content(FormDialog.field("Name", name, "Shown on the profile."))
                .confirm("Save changes")
                .action(() -> {
                    String chosen = name.getText();
                    return () -> {
                        worker.set(Thread.currentThread().getName());
                        return chosen.toUpperCase();
                    };
                }, done::set)
                .show(host)));
        settle();
        assertThat(fx(name::isFocused)).as("the first field has the focus").isTrue();

        robot.write("ada").type(KeyCode.ENTER);
        assertThat(handle.get().result().get(10, TimeUnit.SECONDS)).isTrue();
        settle();
        assertThat(done.get()).isEqualTo("ADA");
        assertThat(worker.get()).startsWith("ui-task-");
        assertThat(handle.get().isOpen()).isFalse();
        assertThat(fx(behind::isFocused)).as("the focus goes back").isTrue();
    }

    @Test
    void escapeCancelsAndTheFocusStaysInside(FxRobot robot) throws Exception {
        AtomicReference<FormDialog.Handle> handle = new AtomicReference<>();
        robot.interact(() -> handle.set(FormDialog.of("Delete Ada?").message("This can't be undone.")
                .confirm("Delete profile").danger().show(host)));
        settle();
        assertThat(fx(() -> host.getScene().getFocusOwner() instanceof Button b ? b.getText() : ""))
                .as("a dangerous form starts on Cancel").isEqualTo("Cancel");
        robot.interact(behind::requestFocus);
        assertThat(fx(behind::isFocused)).as("focus stays in the form").isFalse();

        robot.type(KeyCode.ESCAPE);
        assertThat(handle.get().result().get(10, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void aCheckOrAFailureKeepsTheFormOpenWithOneSentence(FxRobot robot) throws Exception {
        TextField field = new TextField();
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<FormDialog.Handle> handle = new AtomicReference<>();
        robot.interact(() -> handle.set(FormDialog.of("Add a password")
                .content(FormDialog.field("Password", field))
                .confirm("Add password")
                .validate(() -> field.getText().length() < 3 ? UserMessage.of("Use at least 3 characters.") : null)
                .action(() -> () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new InvalidInputException(UserMessage.of("That password isn't right. Try again."), "test");
                    }
                    return true;
                }, ok -> { })
                .show(host)));
        settle();

        robot.write("ab").type(KeyCode.ENTER);
        settle();
        assertThat(fx(() -> handle.get().errorText())).isEqualTo("Use at least 3 characters.");
        assertThat(attempts.get()).isZero();

        robot.write("c").type(KeyCode.ENTER);
        settle();
        assertThat(fx(() -> handle.get().errorText())).isEqualTo("That password isn't right. Try again.");
        assertThat(handle.get().isOpen()).isTrue();
        assertThat(fx(() -> handle.get().confirmButton().isDisabled())).as("ready again").isFalse();

        robot.clickOn("Add password");
        assertThat(handle.get().result().get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aProgressCardHasNoButtonsAndIgnoresEscape(FxRobot robot) {
        AtomicReference<FormDialog.Handle> handle = new AtomicReference<>();
        robot.interact(() -> handle.set(FormDialog.progress(host, "Backing up Ada", "This can take a minute.")));
        settle();
        robot.type(KeyCode.ESCAPE);
        assertThat(handle.get().isOpen()).isTrue();
        assertThat(robot.lookup(".modal-confirm").tryQuery()).isEmpty();
        robot.interact(() -> handle.get().close());
        settle();
        assertThat(handle.get().isOpen()).isFalse();
    }
}
