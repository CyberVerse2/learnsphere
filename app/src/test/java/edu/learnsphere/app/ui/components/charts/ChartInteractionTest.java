package edu.learnsphere.app.ui.components.charts;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.components.charts.BarChart.Bar;
import edu.learnsphere.app.ui.components.charts.CanvasChart.Tip;
import edu.learnsphere.app.ui.components.charts.ColumnChart.Column;
import edu.learnsphere.app.ui.components.charts.LineChart.Point;
import edu.learnsphere.core.service.ThemePreference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/**
 * How the charts behave: empty data, tooltips with exact values, the keyboard, choosing an element, long
 * labels, and the entrance with and without reduced motion.
 */
@ExtendWith(ApplicationExtension.class)
class ChartInteractionTest {

    private StackPane host;

    @Start
    void start(Stage stage) {
        Typography.load();
        host = new StackPane();
        Scene scene = new Scene(host, 600, 400);
        Theme.install(scene, ThemePreference.LIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void restoreMotion() {
        Motion.reduceMotionProperty().set(false);
    }

    @Test
    void chartsWithoutDataDescribeNothingAndTakeNoFocus(FxRobot robot) {
        List<CanvasChart> charts = List.of(new BarChart(), new ColumnChart(), new LineChart(), new WeekStrip(),
                new StreakStrip());
        robot.interact(() -> {
            ((BarChart) charts.get(0)).setBars(List.of());
            ((ColumnChart) charts.get(1)).setColumns(List.of());
            ((LineChart) charts.get(2)).setPoints(List.of());
            ((WeekStrip) charts.get(3)).setDays(List.of());
            ((StreakStrip) charts.get(4)).setDays(List.of(), 0);
            for (CanvasChart chart : charts) {
                show(chart, 400, 200);
                chart.inspect(0);
            }
        });

        for (CanvasChart chart : charts) {
            assertThat(chart.getAccessibleText()).as(chart.getClass().getSimpleName()).isEmpty();
            assertThat(chart.isFocusTraversable()).as(chart.getClass().getSimpleName()).isFalse();
            assertThat(chart.activeIndex()).isEqualTo(-1);
            assertThat(chart.shownTip()).isNull();
            assertThat(chart.isIntroPlaying()).as("nothing to animate").isFalse();
        }
    }

    @Test
    void oneValueIsDrawnAndDescribed(FxRobot robot) {
        LineChart line = new LineChart();
        ColumnChart columns = new ColumnChart();
        robot.interact(() -> {
            line.setAnimated(false);
            columns.setAnimated(false);
            line.setPoints(List.of(new Point("26 Oct", 0.84)));
            columns.setColumns(List.of(new Column("Today", 3, "3", true)));
            show(line, 400, 200);
        });

        assertThat(line.getAccessibleText()).isEqualTo("26 Oct 84%");
        assertThat(line.isFocusTraversable()).isTrue();
        assertThat(columns.getAccessibleText()).isEqualTo("Today 3");
    }

    @Test
    void theTitleIsReadBeforeTheValues(FxRobot robot) {
        ColumnChart chart = new ColumnChart();
        robot.interact(() -> {
            chart.setTitle("Reviews coming up");
            chart.setColumns(List.of(new Column("Today", 3, "3", true, new Tip("Today", "3 reviews due")),
                    new Column("Thu", 0, "", false, new Tip("Thu 29 Oct", "Nothing due"))));
        });

        assertThat(chart.getAccessibleText()).isEqualTo("Reviews coming up: Today 3 reviews due, Thu 29 Oct Nothing due");
        robot.interact(() -> chart.setColumns(List.of()));
        assertThat(chart.getAccessibleText()).as("only the title when empty").isEqualTo("Reviews coming up");
    }

    @Test
    void inspectingAnElementShowsItsExactValue(FxRobot robot) {
        BarChart chart = new BarChart();
        Tip tip = new Tip("Context switching", "83% mastery · 5 of 6 questions seen");
        robot.interact(() -> {
            chart.setAnimated(false);
            chart.setBars(List.of(new Bar("Paging", 1, "100%", false), new Bar("Context switching", 0.83, "83%", false, tip)));
            show(chart, 400, 2 * BarChart.ROW_HEIGHT);
            chart.inspect(1);
            host.layout();
        });

        assertThat(chart.activeIndex()).isEqualTo(1);
        assertThat(chart.shownTip()).isEqualTo(tip);
        assertThat(chart.tooltipNode().isVisible()).isTrue();
        assertThat(chart.getAccessibleHelp()).isEqualTo("Context switching, 83% mastery · 5 of 6 questions seen");
        assertThat(chart.tooltipNode().getLayoutX()).isGreaterThanOrEqualTo(0);
        assertThat(tipRight(chart)).isLessThanOrEqualTo(chart.getWidth());

        robot.interact(() -> chart.inspect(-1));
        assertThat(chart.tooltipNode().isVisible()).isFalse();
        assertThat(chart.getAccessibleHelp()).isNull();
    }

    @Test
    void tooltipsNearTheEdgeStayInsideTheChart(FxRobot robot) {
        ColumnChart chart = new ColumnChart();
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            columns.add(new Column("D" + i, i, Integer.toString(i), i == 0,
                    new Tip("A long day name for day " + i, i + " reviews due")));
        }
        robot.interact(() -> {
            chart.setAnimated(false);
            chart.setColumns(columns);
            show(chart, 300, 180);
            chart.inspect(13);
            host.layout();
        });

        assertThat(tipRight(chart)).isLessThanOrEqualTo(chart.getWidth() + 0.5);
        robot.interact(() -> {
            chart.inspect(0);
            host.layout();
        });
        assertThat(chart.tooltipNode().getLayoutX()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void theKeyboardMovesBetweenElementsAndChoosesOne(FxRobot robot) {
        ColumnChart chart = new ColumnChart();
        List<Integer> chosen = new ArrayList<>();
        robot.interact(() -> {
            chart.setAnimated(false);
            chart.setColumns(List.of(new Column("Mon", 1), new Column("Tue", 2), new Column("Wed", 3)));
            chart.setOnItemActivated(chosen::add);
            show(chart, 400, 180);
            chart.requestFocus();
        });

        robot.type(KeyCode.RIGHT);
        assertThat(chart.activeIndex()).as("the first column").isZero();
        robot.type(KeyCode.RIGHT);
        assertThat(chart.activeIndex()).isEqualTo(1);
        robot.type(KeyCode.END);
        assertThat(chart.activeIndex()).isEqualTo(2);
        robot.type(KeyCode.RIGHT);
        assertThat(chart.activeIndex()).as("stays on the last").isEqualTo(2);
        robot.type(KeyCode.ENTER);
        assertThat(chosen).containsExactly(2);
        robot.type(KeyCode.HOME);
        assertThat(chart.activeIndex()).isZero();
        robot.type(KeyCode.SPACE);
        assertThat(chosen).containsExactly(2, 0);
        robot.type(KeyCode.ESCAPE);
        assertThat(chart.activeIndex()).isEqualTo(-1);
        assertThat(chart.shownTip()).isNull();
    }

    @Test
    void movingThePointerOverAnElementInspectsItAndClickingChoosesIt(FxRobot robot) {
        BarChart chart = new BarChart();
        List<Integer> chosen = new ArrayList<>();
        robot.interact(() -> {
            chart.setAnimated(false);
            chart.setBars(List.of(new Bar("Paging", 1, "100%", false), new Bar("Deadlock", 0.3, "30%", true)));
            chart.setOnItemActivated(chosen::add);
            show(chart, 400, 2 * BarChart.ROW_HEIGHT);
        });

        robot.moveTo(chart.canvas(), javafx.geometry.Pos.TOP_LEFT,
                new javafx.geometry.Point2D(200, BarChart.ROW_HEIGHT * 1.5), org.testfx.robot.Motion.DIRECT);
        assertThat(chart.activeIndex()).isEqualTo(1);
        assertThat(chart.getCursor()).isEqualTo(javafx.scene.Cursor.HAND);
        robot.clickOn(javafx.scene.input.MouseButton.PRIMARY);
        assertThat(chosen).containsExactly(1);

        robot.moveTo(host, javafx.geometry.Pos.BOTTOM_RIGHT, new javafx.geometry.Point2D(-5, -5),
                org.testfx.robot.Motion.DIRECT);
        assertThat(chart.activeIndex()).as("leaving the chart hides the tooltip").isEqualTo(-1);
    }

    @Test
    void longLabelsAreShortenedToFit(FxRobot robot) {
        Font font = CanvasChart.caption();
        String name = "Interprocess communication with shared memory and message passing";

        String fitted = CanvasChart.fit(name, font, 120);

        assertThat(fitted).endsWith("…").hasSizeLessThan(name.length());
        assertThat(CanvasChart.textWidth(fitted, font)).isLessThanOrEqualTo(120);
        assertThat(CanvasChart.fit("Paging", font, 120)).isEqualTo("Paging");
        assertThat(CanvasChart.fit("", font, 0)).isEmpty();
        assertThat(CanvasChart.fit("Paging", font, 1)).as("at least one letter").isEqualTo("P…");
    }

    @Test
    void shorteningKeepsTheLongestStartThatFits(FxRobot robot) {
        Font font = CanvasChart.sans(13);
        String prompt = "Which scheduling algorithm can starve long processes when short ones keep arriving, and why?";
        for (double width : new double[] {40, 96, 160, 233, 320}) {
            // Every start of the prompt, longest first, as a plain search would try them.
            String expected = null;
            for (int length = prompt.length() - 1; length >= 1 && expected == null; length--) {
                String candidate = prompt.substring(0, length).strip() + "…";
                if (CanvasChart.textWidth(candidate, font) <= width) {
                    expected = candidate;
                }
            }
            assertThat(CanvasChart.fit(prompt, font, width)).as("at %s px", width).isEqualTo(expected);
        }
    }

    @Test
    void chartsGrowInWhenTheyFirstGetData(FxRobot robot) throws Exception {
        BarChart chart = new BarChart();
        robot.interact(() -> {
            show(chart, 400, BarChart.ROW_HEIGHT);
            chart.setBars(List.of(new Bar("Paging", 0.8, "80%", false)));
        });

        assertThat(chart.isIntroPlaying()).isTrue();
        assertThat(chart.reveal()).isLessThan(1);

        WaitForAsyncUtils.sleep((long) CanvasChart.introDuration().toMillis() + 300, TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(chart.isIntroPlaying()).isFalse();
        assertThat(chart.reveal()).isEqualTo(1);

        robot.interact(() -> chart.setBars(List.of(new Bar("Paging", 0.9, "90%", false))));
        assertThat(chart.isIntroPlaying()).as("only the first time").isFalse();
    }

    @Test
    void withReducedMotionChartsAppearComplete(FxRobot robot) {
        Motion.reduceMotionProperty().set(true);
        ColumnChart chart = new ColumnChart();
        robot.interact(() -> {
            show(chart, 400, 180);
            chart.setColumns(List.of(new Column("Mon", 3), new Column("Tue", 5)));
        });

        assertThat(chart.reveal()).as("drawn at full size straight away").isEqualTo(1);
    }

    @Test
    void notAnimatedChartsDrawTheirFinalStateAtOnce(FxRobot robot) {
        LineChart chart = new LineChart();
        robot.interact(() -> {
            chart.setAnimated(false);
            show(chart, 400, 200);
            chart.setPoints(List.of(new Point("1 Oct", 0.6), new Point("8 Oct", 0.8)));
        });

        assertThat(chart.isAnimated()).isFalse();
        assertThat(chart.isIntroPlaying()).isFalse();
        assertThat(chart.reveal()).isEqualTo(1);
    }

    /** Where the tooltip's box ends on the right (its shadow aside). */
    private static double tipRight(CanvasChart chart) {
        return chart.tooltipNode().getLayoutX() + chart.tooltipNode().getWidth();
    }

    private void show(CanvasChart chart, double width, double height) {
        chart.setMinSize(width, height);
        chart.setPrefSize(width, height);
        chart.setMaxSize(width, height);
        host.getChildren().setAll(chart);
        host.applyCss();
        host.layout();
    }
}
