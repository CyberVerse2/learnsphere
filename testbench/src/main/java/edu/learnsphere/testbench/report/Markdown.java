package edu.learnsphere.testbench.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Small helpers for writing the report: tables, escaping and number formats. */
public final class Markdown {

    private Markdown() {
    }

    /** A Markdown table built row by row. */
    public static final class Table {

        private final List<String> header;
        private final List<Boolean> rightAligned;
        private final List<List<String>> rows = new ArrayList<>();

        /** A table with these column names; a name starting with {@code >} is right-aligned (the mark is dropped). */
        public Table(String... columns) {
            this.header = Arrays.stream(columns).map(c -> c.startsWith(">") ? c.substring(1) : c).toList();
            this.rightAligned = Arrays.stream(columns).map(c -> c.startsWith(">")).toList();
        }

        /** Adds a row; missing cells are left empty. */
        public Table row(Object... cells) {
            List<String> row = new ArrayList<>();
            for (int i = 0; i < header.size(); i++) {
                row.add(i < cells.length && cells[i] != null ? cell(String.valueOf(cells[i])) : "");
            }
            rows.add(row);
            return this;
        }

        /** Adds a row from a list of cells. */
        public Table row(Collection<?> cells) {
            return row(cells.toArray());
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }

        /** The table followed by a blank line. */
        public String render() {
            StringBuilder out = new StringBuilder();
            out.append("| ").append(String.join(" | ", header)).append(" |\n|");
            for (boolean right : rightAligned) {
                out.append(right ? "---:|" : "---|");
            }
            out.append('\n');
            for (List<String> row : rows) {
                out.append("| ").append(String.join(" | ", row)).append(" |\n");
            }
            return out.append('\n').toString();
        }
    }

    /** Text safe inside a table cell: no line breaks or column separators. */
    public static String cell(String text) {
        return text.replace("\r", "").replace("\n", " ").replace("|", "\\|").strip();
    }

    /** {@code text} cut to {@code max} characters at a word boundary, with an ellipsis when cut. */
    public static String shorten(String text, int max) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        if (flat.length() <= max) {
            return flat;
        }
        int cut = flat.lastIndexOf(' ', max - 1);
        return flat.substring(0, cut > max / 2 ? cut : max - 1) + "…";
    }

    /** A share as a percentage with one decimal ({@code 93.3%}). */
    public static String percent(double share) {
        return String.format(Locale.ROOT, "%.1f%%", share * 100);
    }

    /** A number with {@code decimals} decimals. */
    public static String number(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    /** Milliseconds as seconds ({@code 1.24 s}), or milliseconds under one second ({@code 840 ms}). */
    public static String duration(double millis) {
        if (millis < 1000) {
            return String.format(Locale.ROOT, "%.0f ms", millis);
        }
        if (millis < 120_000) {
            return String.format(Locale.ROOT, "%.2f s", millis / 1000);
        }
        return String.format(Locale.ROOT, "%.1f min", millis / 60_000);
    }

    /** Bytes as MiB or GiB. */
    public static String bytes(double bytes) {
        double mib = bytes / (1024 * 1024);
        return mib >= 1024 ? String.format(Locale.ROOT, "%.2f GiB", mib / 1024) : String.format(Locale.ROOT, "%.0f MiB", mib);
    }

    /** MiB as MiB or GiB. */
    public static String mebibytes(double mib) {
        return bytes(mib * 1024 * 1024);
    }

    /** {@code count} of {@code total} with the share ({@code 28 of 30 (93.3%)}). */
    public static String countOf(long count, long total) {
        return count + " of " + total + (total == 0 ? "" : " (" + percent(count / (double) total) + ")");
    }

    /** Items joined with commas. */
    public static String list(Collection<?> items) {
        return items.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }
}
