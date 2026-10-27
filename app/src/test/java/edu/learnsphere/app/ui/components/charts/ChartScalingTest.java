package edu.learnsphere.app.ui.components.charts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The arithmetic behind the charts: scaling values to pixels, spacing labels, and the streak strip's run. */
class ChartScalingTest {

    // ---- bars and columns ---------------------------------------------------------------------

    @Test
    void barsFillTheirShareOfTheMaximumAndNoMore() {
        assertThat(BarChart.fraction(0.5, 1)).isEqualTo(0.5);
        assertThat(BarChart.fraction(5, 20)).isEqualTo(0.25);
        assertThat(BarChart.fraction(2, 1)).as("over the maximum").isEqualTo(1);
        assertThat(BarChart.fraction(-1, 1)).as("below zero").isZero();
        assertThat(BarChart.fraction(Double.NaN, 1)).isZero();
        assertThat(BarChart.fraction(3, 0)).as("no maximum yet").isZero();
    }

    @Test
    void columnsRiseInProportionToTheTallest() {
        assertThat(ColumnChart.columnHeight(4, 8, 100)).isEqualTo(50);
        assertThat(ColumnChart.columnHeight(8, 8, 100)).isEqualTo(100);
        assertThat(ColumnChart.columnHeight(12, 8, 100)).as("clamped to the plot").isEqualTo(100);
        assertThat(ColumnChart.columnHeight(0, 8, 100)).isZero();
        assertThat(ColumnChart.columnHeight(3, 0, 100)).as("every day empty").isZero();
    }

    @Test
    void columnsTakeMostOfTheirSlotUpToAMaximum() {
        assertThat(ColumnChart.columnWidth(30)).isEqualTo(17);
        assertThat(ColumnChart.columnWidth(200)).as("never wider than 28 px").isEqualTo(28);
        assertThat(ColumnChart.columnWidth(5)).as("narrow slots keep a pixel apart").isEqualTo(4);
        assertThat(ColumnChart.columnWidth(0.5)).isEqualTo(1);
    }

    @Test
    void columnLabelsAreThinnedOnlyWhenTheyWouldTouch() {
        assertThat(ColumnChart.labelStep(30, 60)).as("room for every label").isEqualTo(1);
        assertThat(ColumnChart.labelStep(30, 30)).as("every other label").isEqualTo(2);
        assertThat(ColumnChart.labelStep(40, 10)).isEqualTo(5);
        assertThat(ColumnChart.labelStep(30, 0)).as("no room at all").isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void columnLabelsAtTheEdgesShiftInsteadOfBeingCut() {
        assertThat(ColumnChart.inside(10, 36, 300)).as("first column").isEqualTo(18);
        assertThat(ColumnChart.inside(295, 36, 300)).as("last column").isEqualTo(282);
        assertThat(ColumnChart.inside(150, 36, 300)).as("in the middle").isEqualTo(150);
        assertThat(ColumnChart.inside(10, 400, 300)).as("wider than the chart: centred").isEqualTo(150);
    }

    // ---- lines --------------------------------------------------------------------------------

    @Test
    void pointsSpreadEvenlyAndASinglePointSitsInTheMiddle() {
        assertThat(LineChart.xPositions(3, 0, 100)).containsExactly(0, 50, 100);
        assertThat(LineChart.xPositions(1, 40, 340)).containsExactly(190);
        assertThat(LineChart.xPositions(0, 40, 340)).isEmpty();
    }

    @Test
    void valuesMapOntoThePlotFromBottomToTop() {
        assertThat(LineChart.yOf(0.75, 0.5, 1, 0, 100)).isEqualTo(50);
        assertThat(LineChart.yOf(0.5, 0.5, 1, 0, 100)).isEqualTo(100);
        assertThat(LineChart.yOf(1, 0.5, 1, 0, 100)).isEqualTo(0);
        assertThat(LineChart.yOf(0.2, 0.5, 1, 0, 100)).as("below the range: on the floor").isEqualTo(100);
        assertThat(LineChart.yOf(1.4, 0.5, 1, 0, 100)).as("above the range: at the top").isEqualTo(0);
    }

    @Test
    void fractionRangeLeavesRoomUnderTheLowestValueAndTheGoal() {
        assertThat(LineChart.fractionRange(List.of(0.82, 0.65, 0.9), 0.9)).containsExactly(0.5, 1);
        assertThat(LineChart.fractionRange(List.of(0.95, 0.97), 0.9)).as("at least 30 points tall")
                .containsExactly(0.7, 1);
        assertThat(LineChart.fractionRange(List.of(0.05), null)).as("never below zero").containsExactly(0, 1);
        assertThat(LineChart.fractionRange(List.of(), null)).containsExactly(0.7, 1);
    }

    @Test
    void lineLabelsKeepTheEndsAndDropThoseThatWouldCollide() {
        double[] xs = {0, 20, 40, 60, 200};
        double[] widths = {30, 30, 30, 30, 30};
        assertThat(LineChart.visibleLabels(xs, widths)).containsExactly(true, false, false, true, true);
        assertThat(LineChart.visibleLabels(new double[] {50}, new double[] {30})).containsExactly(true);
        assertThat(LineChart.visibleLabels(new double[0], new double[0])).isEmpty();

        double[] spread = {0, 100, 200, 300};
        assertThat(LineChart.visibleLabels(spread, new double[] {30, 30, 30, 30})).containsExactly(true, true, true, true);
    }

    @Test
    void theGoalLabelMovesAwayFromPointsUnderIt() {
        double[] xs = {50, 150, 250};
        double[] ys = {150, 150, 150};
        double[] clear = LineChart.referenceLabelBox(60, 40, 10, 300, 200, 60, xs, ys);
        assertThat(clear).as("above the line at the right end").containsExactly(234, 42);

        double[] crowded = LineChart.referenceLabelBox(60, 40, 10, 300, 200, 60, new double[] {50, 280}, new double[] {150, 50});
        assertThat(crowded).as("the last point sits there: below the line instead").containsExactly(234, 64);
    }

    // ---- week and streak strips -----------------------------------------------------------------

    @Test
    void weekPillsFillByShareOfTheBusiestDayButStayRound() {
        assertThat(WeekStrip.fillHeight(0, 5, 22)).isZero();
        assertThat(WeekStrip.fillHeight(5, 5, 22)).isEqualTo(WeekStrip.PILL_HEIGHT);
        assertThat(WeekStrip.fillHeight(1, 100, 22)).as("a quiet day still shows a full round end").isEqualTo(22);
        assertThat(WeekStrip.fillHeight(3, 4, 22)).isEqualTo(33);
    }

    @Test
    void studyDotsGrowWithTheSquareRootOfReviews() {
        assertThat(StreakStrip.radius(0, 5)).as("a rest day").isEqualTo(StreakStrip.REST_RADIUS);
        assertThat(StreakStrip.radius(5, 5)).isEqualTo(StreakStrip.MAX_RADIUS);
        assertThat(StreakStrip.radius(1, 4)).isCloseTo(
                StreakStrip.MIN_RADIUS + (StreakStrip.MAX_RADIUS - StreakStrip.MIN_RADIUS) * 0.5, within(1e-9));
        assertThat(StreakStrip.radius(3, 0)).isEqualTo(StreakStrip.REST_RADIUS);
    }

    @Test
    void theStreakRunEndsTodayOrYesterday() {
        List<StreakStrip.Day> studiedToday = days(2, 0, 3, 1, 4);
        assertThat(StreakStrip.streakRun(studiedToday, 3)).containsExactly(2, 4);
        assertThat(StreakStrip.streakRun(studiedToday, 9)).as("longer than the strip").containsExactly(0, 4);

        List<StreakStrip.Day> notYetToday = days(2, 3, 1, 0);
        assertThat(StreakStrip.streakRun(notYetToday, 2)).as("today can still keep it going").containsExactly(1, 2);

        assertThat(StreakStrip.streakRun(studiedToday, 0)).isNull();
        assertThat(StreakStrip.streakRun(List.of(), 3)).isNull();
    }

    @Test
    void streakLabelsAlwaysKeepTodayAndDropCollisions() {
        List<StreakStrip.Day> days = List.of(new StreakStrip.Day("28 Sep", 1, false), new StreakStrip.Day("", 0, false),
                new StreakStrip.Day("5 Oct", 2, false), new StreakStrip.Day("Today", 3, true));
        double[] xs = {10, 40, 70, 95};
        double[] widths = {40, 0, 30, 34};

        boolean[] shown = StreakStrip.visibleLabels(days, xs, widths, 100);

        assertThat(shown[3]).as("today").isTrue();
        assertThat(shown[2]).as("too close to today").isFalse();
        assertThat(shown[1]).as("no label").isFalse();
        assertThat(shown[0]).isTrue();
    }

    // ---- entrance -------------------------------------------------------------------------------

    @Test
    void elementsEnterOneAfterAnother() {
        assertThat(CanvasChart.staggered(0.4, 0, 1)).as("a single element follows the reveal").isEqualTo(0.4);
        assertThat(CanvasChart.staggered(0, 0, 10)).isZero();
        assertThat(CanvasChart.staggered(1, 9, 10)).isEqualTo(1);
        assertThat(CanvasChart.staggered(0.3, 0, 10)).isGreaterThan(CanvasChart.staggered(0.3, 9, 10));
        assertThat(CanvasChart.introDuration().toMillis()).isEqualTo(600);
    }

    private static List<StreakStrip.Day> days(int... counts) {
        return java.util.stream.IntStream.range(0, counts.length)
                .mapToObj(i -> new StreakStrip.Day("", counts[i], i == counts.length - 1)).toList();
    }
}
