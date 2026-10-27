/**
 * Charts drawn on a canvas in the theme colours, labelled directly instead of with legends: horizontal
 * {@link edu.learnsphere.app.ui.components.charts.BarChart bars} for mastery and accuracy,
 * {@link edu.learnsphere.app.ui.components.charts.ColumnChart columns} for the reviews forecast, a
 * {@link edu.learnsphere.app.ui.components.charts.LineChart line} (or sparkline) for retention, the
 * {@link edu.learnsphere.app.ui.components.charts.StreakStrip streak strip} of study days and the
 * {@link edu.learnsphere.app.ui.components.charts.WeekStrip week strip} on Home.
 *
 * <p>Every chart shares {@link edu.learnsphere.app.ui.components.charts.CanvasChart}'s behaviour: it grows
 * in over 600 ms (a fade when motion is reduced), shows a tooltip with the exact value of the element under
 * the pointer or keyboard focus, and describes itself to screen readers.
 */
package edu.learnsphere.app.ui.components.charts;
