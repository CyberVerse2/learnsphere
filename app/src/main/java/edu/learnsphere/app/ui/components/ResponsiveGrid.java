package edu.learnsphere.app.ui.components;

import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * Lays its children out in equal columns that adapt to the width: as many columns of at least
 * {@code minColumnWidth} as fit (up to {@code maxColumns}), separated by {@code gap}. Every child in a row
 * gets the row's tallest height, so cards line up. Used for course grids and stat rows.
 */
public class ResponsiveGrid extends Pane {

    private final double minColumnWidth;
    private final int maxColumns;
    private final double gap;

    public ResponsiveGrid(double minColumnWidth, int maxColumns, double gap) {
        this.minColumnWidth = minColumnWidth;
        this.maxColumns = Math.max(1, maxColumns);
        this.gap = gap;
        getStyleClass().add("responsive-grid");
    }

    /** Course cards: columns of at least 280 px, at most 3, 24 px apart. */
    public static ResponsiveGrid forCards() {
        return new ResponsiveGrid(280, 3, 24);
    }

    /** The number of columns at {@code width}. */
    public int columnsFor(double width) {
        Insets in = getInsets();
        double inner = width - in.getLeft() - in.getRight();
        int fit = (int) Math.floor((inner + gap) / (minColumnWidth + gap));
        return Math.max(1, Math.min(maxColumns, fit));
    }

    private List<Node> managed() {
        return getManagedChildren();
    }

    private double columnWidth(double width, int columns) {
        Insets in = getInsets();
        double inner = width - in.getLeft() - in.getRight();
        return Math.max(0, (inner - gap * (columns - 1)) / columns);
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets in = getInsets();
        int n = Math.min(maxColumns, Math.max(1, managed().size()));
        return in.getLeft() + in.getRight() + n * minColumnWidth + (n - 1) * gap;
    }

    @Override
    protected double computeMinWidth(double height) {
        Insets in = getInsets();
        return in.getLeft() + in.getRight() + Math.min(minColumnWidth, 200);
    }

    @Override
    protected double computePrefHeight(double width) {
        Insets in = getInsets();
        double w = width > 0 ? width : computePrefWidth(-1);
        List<Node> children = managed();
        int columns = columnsFor(w);
        double colWidth = columnWidth(w, columns);
        double height = 0;
        for (int start = 0; start < children.size(); start += columns) {
            double row = 0;
            for (int i = start; i < Math.min(children.size(), start + columns); i++) {
                row = Math.max(row, children.get(i).prefHeight(colWidth));
            }
            height += row + (start > 0 ? gap : 0);
        }
        return in.getTop() + in.getBottom() + height;
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        List<Node> children = managed();
        int columns = columnsFor(getWidth());
        double colWidth = columnWidth(getWidth(), columns);
        double y = in.getTop();
        for (int start = 0; start < children.size(); start += columns) {
            double row = 0;
            int end = Math.min(children.size(), start + columns);
            for (int i = start; i < end; i++) {
                row = Math.max(row, children.get(i).prefHeight(colWidth));
            }
            for (int i = start; i < end; i++) {
                double x = in.getLeft() + (i - start) * (colWidth + gap);
                children.get(i).resizeRelocate(snapPositionX(x), snapPositionY(y), snapSizeX(colWidth), snapSizeY(row));
            }
            y += row + gap;
        }
    }
}
