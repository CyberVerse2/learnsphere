package edu.learnsphere.app.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

@ExtendWith(ApplicationExtension.class)
class NavigatorTest {

    /** A view that records what the navigator did to it. */
    static final class RecordingView implements View {
        final Destination destination;
        final StackPane root;
        int shown;
        int disposed;

        RecordingView(Destination destination) {
            this.destination = destination;
            this.root = new StackPane(new Label(destination.toString()));
        }

        @Override
        public Region root() {
            return root;
        }

        @Override
        public void onShown() {
            shown++;
        }

        @Override
        public void dispose() {
            disposed++;
        }
    }

    private final List<RecordingView> built = new ArrayList<>();
    private StackPane host;
    private Navigator navigator;

    @Start
    void start(Stage stage) {
        host = new StackPane();
        navigator = new Navigator(host, this::record);
        navigator.register(Destination.Course.class, this::record);
        stage.setScene(new Scene(host, 400, 300));
        stage.show();
    }

    private RecordingView record(Destination destination) {
        RecordingView view = new RecordingView(destination);
        built.add(view);
        return view;
    }

    @Test
    void goesBackToTheSameDestinationAndDisposesEveryReplacedView(FxRobot robot) {
        robot.interact(() -> {
            navigator.reset(Destination.HOME);
            navigator.navigate(new Destination.Course("a"));
            navigator.navigate(new Destination.Course("b"));
        });
        RecordingView home = built.get(0);
        RecordingView courseA = built.get(1);
        RecordingView courseB = built.get(2);
        assertThat(home.disposed).isEqualTo(1);
        assertThat(courseA.disposed).isEqualTo(1);
        assertThat(courseB.shown).isEqualTo(1);
        assertThat(host.getChildren()).containsExactly(courseB.root);

        robot.interact(() -> assertThat(navigator.back()).isTrue());

        assertThat(navigator.current()).isEqualTo(new Destination.Course("a"));
        assertThat(courseB.disposed).isEqualTo(1);
        RecordingView reopened = built.getLast();
        assertThat(reopened).isNotSameAs(courseA);
        assertThat(reopened.destination).isEqualTo(new Destination.Course("a"));
        assertThat(reopened.shown).isEqualTo(1);

        robot.interact(() -> assertThat(navigator.back()).isTrue());
        assertThat(navigator.current()).isEqualTo(Destination.HOME);
        robot.interact(() -> assertThat(navigator.back()).isFalse());
        assertThat(navigator.canGoBack()).isFalse();
    }

    @Test
    void navigatingToTheDestinationOnScreenKeepsItsView(FxRobot robot) {
        robot.interact(() -> {
            navigator.reset(new Destination.Course("a"));
            navigator.navigate(new Destination.Course("a"));
        });
        assertThat(built).hasSize(1);
        assertThat(navigator.canGoBack()).isFalse();
    }

    @Test
    void aFactoryThatFailsLeavesTheScreenAndHistoryAsTheyWere(FxRobot robot) {
        robot.interact(() -> {
            navigator.reset(Destination.HOME);
            navigator.navigate(Destination.LIBRARY);
            navigator.register(Destination.Settings.class, destination -> {
                throw new IllegalStateException("broken screen");
            });
            assertThatThrownBy(() -> navigator.navigate(Destination.SETTINGS)).isInstanceOf(IllegalStateException.class);
        });
        RecordingView library = built.getLast();
        assertThat(navigator.current()).isEqualTo(Destination.LIBRARY);
        assertThat(library.disposed).isZero();
        assertThat(host.getChildren()).containsExactly(library.root);
        robot.interact(() -> assertThat(navigator.back()).isTrue());
        assertThat(navigator.current()).isEqualTo(Destination.HOME);
    }

    @Test
    void resetClearsHistoryAndDisposeReleasesTheViewOnScreen(FxRobot robot) {
        robot.interact(() -> {
            navigator.reset(Destination.HOME);
            navigator.navigate(Destination.LIBRARY);
            navigator.reset(Destination.PROFILES);
        });
        assertThat(navigator.canGoBack()).isFalse();
        assertThat(navigator.currentRoute()).isEqualTo(Route.PROFILES);
        assertThat(navigator.currentRoute().isFullScreen()).isTrue();

        RecordingView profiles = built.getLast();
        robot.interact(navigator::dispose);
        assertThat(profiles.disposed).isEqualTo(1);
        assertThat(host.getChildren()).isEmpty();
        assertThat(navigator.current()).isNull();
    }

    @Test
    void refusesCallsOffTheFxThread() {
        assertThatThrownBy(() -> navigator.navigate(Destination.LIBRARY)).isInstanceOf(IllegalStateException.class);
    }
}
