package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.DocumentSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Studio's words for sizes, sharing choices and flags, so every panel says the same thing the same way.
 */
final class StudioFormats {

    /** The extension of a course file, with its dot. */
    static final String COURSE_FILE_SUFFIX = ".learnsphere";

    private StudioFormats() {
    }

    /** A file size as people read it: {@code "640 KB"}, {@code "14.9 MB"}, {@code "1.2 GB"}. */
    static String size(long bytes) {
        if (bytes <= 0) {
            return "0 KB";
        }
        if (bytes < 999_500) {
            return Math.max(1, Math.round(bytes / 1_000.0)) + " KB";
        }
        double mb = bytes / 1_000_000.0;
        if (mb < 999.5) {
            return decimal(mb, mb < 100) + " MB";
        }
        return decimal(bytes / 1_000_000_000.0, true) + " GB";
    }

    private static String decimal(double value, boolean oneDecimal) {
        if (!oneDecimal) {
            return Long.toString(Math.round(value));
        }
        String text = String.format(Locale.ENGLISH, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    /** The short name of a sharing choice, as the picker shows it. */
    static String visibilityLabel(Visibility visibility) {
        return switch (visibility) {
            case PRIVATE -> "Only me";
            case SHAREABLE -> "Anyone with the file";
            case PROTECTED -> "With a code";
        };
    }

    /** What a sharing choice means for learners. */
    static String visibilityDetail(Visibility visibility) {
        return switch (visibility) {
            case PRIVATE -> "Only you can open it, so it can't be exported.";
            case SHAREABLE -> "Anyone you send the course file to can open it in LearnSphere.";
            case PROTECTED -> "Learners also need a code, which you choose each time you export the course.";
        };
    }

    /** Why a question was flagged, in a few words. */
    static String reasonLabel(FlagReason reason) {
        return switch (reason) {
            case WRONG_ANSWER -> "Answer looks wrong";
            case UNCLEAR -> "Unclear question";
            case NOT_IN_MATERIAL -> "Not in the material";
            case TYPO -> "Typo";
            case OTHER -> "Other";
        };
    }

    /** The button that opens the computer's file manager at a saved file. */
    static String revealLabel() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "Show in Finder";
        }
        if (os.contains("win")) {
            return "Show in Explorer";
        }
        return "Show in folder";
    }

    /** A file name for a course file, from the course title: characters files can't hold become spaces. */
    static String courseFileName(String title) {
        String clean = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if (clean.startsWith(".")) {
            clean = clean.substring(1).strip();
        }
        if (clean.length() > 80) {
            clean = clean.substring(0, 80).strip();
        }
        return (clean.isEmpty() ? "Course" : clean) + COURSE_FILE_SUFFIX;
    }

    /** The line under a PDF's title: pages, size and module, e.g. {@code "120 pages · 14.9 MB · Isomerism"}. */
    static String documentMeta(DocumentSummary document) {
        List<String> parts = new ArrayList<>();
        if (document.pageCount() > 0) {
            parts.add(Formats.count(document.pageCount(), "page", "pages"));
        }
        if (document.byteSize() > 0) {
            parts.add(size(document.byteSize()));
        }
        parts.add(document.moduleTitle() == null ? "Whole course" : document.moduleTitle());
        return String.join(" · ", parts);
    }

    /** The keys that move a module, as this computer names them. */
    static String moveKeys() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") ? "Option ↑ or Option ↓" : "Alt+↑ or Alt+↓";
    }
}
