package edu.learnsphere.app.ui.components;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.components.TopBar.Crumb;
import edu.learnsphere.core.service.ThemePreference;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.geometry.Orientation;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

@ExtendWith(ApplicationExtension.class)
class TopBarTest {

    private static final double WIDTH = 1208;

    private TopBar bar;
    private StackPane host;

    @Start
    void start(Stage stage) {
        Typography.load();
        bar = new TopBar();
        host = new StackPane(bar);
        host.setPrefWidth(WIDTH);
        Scene scene = new Scene(host, WIDTH, 200);
        Theme.install(scene, ThemePreference.LIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void showsCrumbsWithTheLastAsCurrent(FxRobot robot) {
        List<String> opened = new ArrayList<>();
        robot.interact(() -> bar.setCrumbs(List.of(new Crumb("Library", () -> opened.add("Library")),
                new Crumb("Operating Systems Principles", null), new Crumb("Ask", null))));

        assertThat(bar.crumbTexts()).containsExactly("Library", "Operating Systems Principles", "Ask");
        robot.clickOn("Library");
        assertThat(opened).containsExactly("Library");
        assertThat(robot.lookup(".crumb.current").queryLabeled().getText()).isEqualTo("Ask");
    }

    @Test
    void firstCrumbTextLinesUpWithThePageColumn(FxRobot robot) {
        robot.interact(() -> bar.setCrumbs(List.of(new Crumb("Home", null))));
        robot.interact(() -> host.layout());

        double column = (WIDTH - Page.MAX_WIDTH) / 2 + Page.SIDE_PADDING;
        assertThat(textLeft(robot.lookup(".crumb").query()))
                .isCloseTo(column, within(1.5));
    }

    @Test
    void firstCrumbFollowsAPageColumnNarrowedByItsScrollBar(FxRobot robot) {
        Label first = new Label("Good afternoon");
        Region tall = new Region();
        tall.setMinHeight(2000);
        Page page = new Page(first, tall);
        VBox.setVgrow(page, Priority.ALWAYS);
        robot.interact(() -> {
            host.getChildren().clear();
            host.getChildren().setAll(new VBox(bar, page));
            bar.setCrumbs(List.of(new Crumb("Home", null)));
            bar.alignWith(page);
        });
        robot.interact(() -> {
            host.applyCss();
            host.layout();
            host.layout();
        });

        ScrollBar vertical = (ScrollBar) page.lookupAll(".scroll-bar").stream()
                .filter(n -> n instanceof ScrollBar s && s.getOrientation() == Orientation.VERTICAL && s.isVisible())
                .findFirst().orElseThrow();
        assertThat(vertical.getWidth()).as("the page scrolls").isPositive();
        double columnText = textLeft(first);
        assertThat(columnText).isCloseTo((WIDTH - vertical.getWidth() - Page.MAX_WIDTH) / 2 + Page.SIDE_PADDING,
                within(1.5));
        assertThat(textLeft(robot.lookup(".crumb").query())).isCloseTo(columnText, within(1.5));
    }

    @Test
    void backArrowTakesTheColumnEdgeWhenThereIsAWayBack(FxRobot robot) {
        robot.interact(() -> {
            bar.canGoBackProperty().set(true);
            bar.setCrumbs(List.of(new Crumb("Library", () -> { }), new Crumb("Course", null)));
            bar.setColumnMaxWidth(Double.POSITIVE_INFINITY);
        });
        robot.interact(() -> host.layout());

        Node back = bar.backButton();
        assertThat(back.isVisible()).isTrue();
        Node arrow = ((Parent) back).getChildrenUnmodifiable().stream()
                .filter(n -> n.getStyleClass().contains("ikonli-font-icon")).findFirst().orElseThrow();
        assertThat(arrow.localToScene(arrow.getBoundsInLocal()).getMinX())
                .isCloseTo(Page.SIDE_PADDING, within(2.0));
    }

    @Test
    void hidesTheBackButtonWithoutHistory(FxRobot robot) {
        robot.interact(() -> bar.canGoBackProperty().set(false));

        assertThat(bar.backButton().isVisible()).isFalse();
        assertThat(bar.backButton().isManaged()).isFalse();
    }

    private static double textLeft(Node crumb) {
        Text text = (Text) crumb.lookup(".text");
        return text.localToScene(text.getBoundsInLocal()).getMinX();
    }
}
