package edu.learnsphere.app.ui.components.charts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.components.charts.BarChart.Bar;
import edu.learnsphere.app.ui.components.charts.ColumnChart.Column;
import edu.learnsphere.app.ui.components.charts.LineChart.Point;
import edu.learnsphere.app.ui.components.charts.WeekStrip.Day;
import edu.learnsphere.core.service.ThemePreference;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/**
 * The Canvas charts with known data: what they tell screen readers, and where they draw, read back from a
 * snapshot in the light theme.
 */
@ExtendWith(ApplicationExtension.class)
class ChartsTest {

    private static final double WIDTH = 400;
    private static final double TOLERANCE = 0.06;

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

    // ---------------------------------------------------------------------------------------------------
    // Bar chart
    // ---------------------------------------------------------------------------------------------------

    @Test
    void barChartDescribesEveryBar(FxRobot robot) {
        BarChart chart = new BarChart();
        robot.interact(() -> chart.setBars(List.of(new Bar("Paging", 0.82, "82%", false),
                new Bar("Deadlock", 0.31, "31%", true))));

        assertThat(chart.getAccessibleText()).isEqualTo("Paging 82%, Deadlock 31%");
        assertThat(chart.prefHeight(-1)).isEqualTo(2 * BarChart.ROW_HEIGHT);
    }

    @Test
    void barsFillTheirTrackInProportionToTheirValue(FxRobot robot) {
        BarChart chart = new BarChart();
        WritableImage image = render(robot, chart, () -> chart.setBars(List.of(
                new Bar("Paging", 1, "100%", false),
                new Bar("Deadlock", 0, "0%", false),
                new Bar("Page tables", 0.5, "50%", true))), WIDTH, 3 * BarChart.ROW_HEIGHT);

        Color accent = chart.accent();
        Color warning = chart.warning();
        Color track = chart.track();
        Runs full = runs(image, rowCentre(0), accent, track);
        Runs empty = runs(image, rowCentre(1), accent, track);
        Runs half = runs(image, rowCentre(2), warning, track);

        assertThat(full.fill()).isPositive();
        assertThat(full.track()).as("a full bar covers its track").isZero();
        assertThat(empty.fill()).as("an empty bar draws only the track").isZero();
        assertThat((double) empty.track()).isCloseTo(full.fill(), within(2.0));
        assertThat(half.fraction()).as("emphasised half bar, in the warning colour").isCloseTo(0.5, within(TOLERANCE));
        assertThat((double) half.fill() + half.track()).isCloseTo(full.fill(), within(2.0));
    }

    @Test
    void barsScaleToTheChartMaximum(FxRobot robot) {
        BarChart chart = new BarChart();
        WritableImage image = render(robot, chart, () -> {
            chart.setMax(20);
            chart.setBars(List.of(new Bar("Reviews", 5, "5", false)));
        }, WIDTH, BarChart.ROW_HEIGHT);

        assertThat(runs(image, rowCentre(0), chart.accent(), chart.track()).fraction()).isCloseTo(0.25, within(TOLERANCE));
        assertThatThrownBy(() -> chart.setMax(0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------------------------------
    // Column chart
    // ---------------------------------------------------------------------------------------------------

    @Test
    void columnChartDescribesEveryColumnWithZeros(FxRobot robot) {
        ColumnChart chart = new ColumnChart();
        robot.interact(() -> chart.setColumns(List.of(new Column("Tue", 4), new Column("Wed", 0),
                new Column("Thu", 2.6, "3 reviews", true))));

        assertThat(chart.getAccessibleText()).isEqualTo("Tue 4, Wed 0, Thu 3 reviews");
    }

    @Test
    void columnsRiseInProportionAndTheHighlightedOneUsesTheFullAccent(FxRobot robot) {
        ColumnChart chart = new ColumnChart();
        double height = 180;
        WritableImage image = render(robot, chart, () -> chart.setColumns(List.of(
                new Column("Mon", 4), new Column("Tue", 8, null, true), new Column("Wed", 0), new Column("Thu", 2))),
                WIDTH, height);

        // Four equal slots; the baseline sits 24 px above the bottom, leaving room for the labels.
        double slot = image.getWidth() / 4;
        int baseline = (int) image.getHeight() - 24 - 2;
        Color soft = blend(chart.accentSoft(), background());
        double tue = columnHeight(image, slot * 1.5, baseline, color -> near(color, chart.accent()));
        double mon = columnHeight(image, slot * 0.5, baseline, color -> near(color, soft));
        double thu = columnHeight(image, slot * 3.5, baseline, color -> near(color, soft));
        double wed = columnHeight(image, slot * 2.5, baseline, color -> near(color, soft) || near(color, chart.accent()));

        assertThat(tue).as("the highlighted, tallest column").isPositive();
        assertThat(mon / tue).isCloseTo(0.5, within(TOLERANCE));
        assertThat(thu / tue).isCloseTo(0.25, within(TOLERANCE));
        assertThat(wed).as("no column for zero").isZero();
    }

    // ---------------------------------------------------------------------------------------------------
    // Line chart and sparkline
    // ---------------------------------------------------------------------------------------------------

    @Test
    void lineChartDescribesItsFirstAndLastPoints(FxRobot robot) {
        LineChart chart = new LineChart();
        robot.interact(() -> chart.setPoints(List.of(new Point("29 Sep", 0.62), new Point("13 Oct", 0.7),
                new Point("27 Oct", 0.81))));

        assertThat(chart.getAccessibleText()).isEqualTo("29 Sep 62% to 27 Oct 81%");

        robot.interact(() -> chart.setFormatter(value -> Math.round(value * 10) + " of 10"));
        assertThat(chart.getAccessibleText()).isEqualTo("29 Sep 6 of 10 to 27 Oct 8 of 10");
        assertThatThrownBy(() -> chart.setRange(1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lineEndsOnADotAtTheLastValue(FxRobot robot) {
        LineChart chart = new LineChart();
        double height = 200;
        WritableImage image = render(robot, chart, () -> chart.setPoints(List.of(new Point("1 Oct", 0.2),
                new Point("27 Oct", 0.75))), WIDTH, height);

        // Plot area: 40 px on the left, 52 on the right, 12 above and 26 below.
        double x = image.getWidth() - 52;
        double y = (image.getHeight() - 26) - (image.getHeight() - 26 - 12) * 0.75;
        assertThat(near(pixel(image, x, y), chart.accent())).as("dot at the last point").isTrue();
        double below = y + 40;
        assertThat(near(pixel(image, x, below), chart.accent())).as("nothing solid under the dot").isFalse();
    }

    @Test
    void sparklineIsSmallAndBare() {
        LineChart sparkline = LineChart.sparkline();

        assertThat(sparkline.isSparkline()).isTrue();
        assertThat(sparkline.getStyleClass()).contains("chart", "line-chart", "sparkline");
        assertThat(sparkline.prefHeight(-1)).isEqualTo(40);
        assertThat(new LineChart().isSparkline()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------------
    // Week strip
    // ---------------------------------------------------------------------------------------------------

    @Test
    void weekStripDescribesEachDay(FxRobot robot) {
        WeekStrip strip = new WeekStrip();
        robot.interact(() -> strip.setDays(List.of(new Day("M", 4, false), new Day("T", 0, false),
                new Day("W", 6, true))));

        assertThat(strip.getAccessibleText()).isEqualTo("M 4, T 0, W 6");
        assertThatThrownBy(() -> new Day("T", -1, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weekStripFillsBusyDaysAndLeavesEmptyDaysAsTrack(FxRobot robot) {
        WeekStrip strip = new WeekStrip();
        double width = 7 * 36;
        WritableImage image = render(robot, strip, () -> strip.setDays(List.of(
                new Day("W", 2, false), new Day("T", 0, false), new Day("F", 4, false), new Day("S", 0, false),
                new Day("S", 1, false), new Day("M", 3, false), new Day("T", 4, true))), width, strip.prefHeight(-1));

        double slot = image.getWidth() / 7;
        double pillBottom = 20 + 44 - 3;
        assertThat(near(pixel(image, slot * 1.5, pillBottom), strip.track())).as("a rest day").isTrue();
        assertThat(near(pixel(image, slot * 6.5, pillBottom), strip.accent())).as("today, in the full accent").isTrue();
        assertThat(near(pixel(image, slot * 6.5, 22), strip.accent())).as("today's busiest pill is full").isTrue();
    }

    // ---------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------

    /**
     * Lays {@code chart} out at {@code width} × {@code height} after {@code setUp} and snapshots what it drew:
     * its canvas, inside the chart's padding, so coordinates are those the chart draws with.
     */
    private WritableImage render(FxRobot robot, CanvasChart chart, Runnable setUp, double width, double height) {
        AtomicReference<WritableImage> image = new AtomicReference<>();
        robot.interact(() -> {
            chart.setMinSize(width, height);
            chart.setPrefSize(width, height);
            chart.setMaxSize(width, height);
            host.getChildren().setAll(chart);
            setUp.run();
            host.applyCss();
            host.layout();
            Canvas canvas = (Canvas) chart.getChildrenUnmodifiable().getFirst();
            image.set(canvas.snapshot(null, null));
        });
        return image.get();
    }

    /** What the charts draw on: the window background. */
    private Color background() {
        return (Color) host.getScene().getFill();
    }

    private static int rowCentre(int row) {
        return (int) (row * BarChart.ROW_HEIGHT + BarChart.ROW_HEIGHT / 2);
    }

    /**
     * Along row {@code y}, the longest stretch of fill and track pixels (the bar, leaving out the labels
     * either side; the few blended pixels where the fill ends do not break it): how many of each it holds.
     */
    private static Runs runs(WritableImage image, int y, Color fill, Color track) {
        PixelReader reader = image.getPixelReader();
        Runs best = new Runs(0, 0);
        int filled = 0;
        int empty = 0;
        int gap = 0;
        for (int x = 0; x <= (int) image.getWidth(); x++) {
            Color color = x < (int) image.getWidth() ? reader.getColor(x, y) : Color.TRANSPARENT;
            if (near(color, fill)) {
                filled++;
                gap = 0;
            } else if (near(color, track)) {
                empty++;
                gap = 0;
            } else if (++gap > 3 || x == (int) image.getWidth()) {
                if (filled + empty > best.fill() + best.track()) {
                    best = new Runs(filled, empty);
                }
                filled = 0;
                empty = 0;
            }
        }
        return best;
    }

    private record Runs(int fill, int track) {
        double fraction() {
            return fill / (double) (fill + track);
        }
    }

    /** How many pixels above {@code baseline} at {@code x} match {@code inColumn}, counting upwards. */
    private static double columnHeight(WritableImage image, double x, int baseline, Predicate<Color> inColumn) {
        int height = 0;
        for (int y = baseline; y >= 0 && inColumn.test(image.getPixelReader().getColor((int) x, y)); y--) {
            height++;
        }
        return height;
    }

    private static Color pixel(WritableImage image, double x, double y) {
        return image.getPixelReader().getColor((int) Math.round(x), (int) Math.round(y));
    }

    private static Color blend(Color top, Color under) {
        double a = top.getOpacity();
        return new Color(top.getRed() * a + under.getRed() * (1 - a), top.getGreen() * a + under.getGreen() * (1 - a),
                top.getBlue() * a + under.getBlue() * (1 - a), 1);
    }

    private static boolean near(Color actual, Color expected) {
        double limit = 6 / 255.0;
        return Math.abs(actual.getRed() - expected.getRed()) <= limit
                && Math.abs(actual.getGreen() - expected.getGreen()) <= limit
                && Math.abs(actual.getBlue() - expected.getBlue()) <= limit;
    }
}
