package edu.learnsphere.search;

import edu.learnsphere.core.error.UserMessage;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;
import java.util.Locale;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;

/** The sentences search failures show, and how failures are told apart. */
final class SearchFailures {

    /** A search could not be run; nothing is known to be damaged. */
    static final UserMessage SEARCH_FAILED = UserMessage.of("Search didn't work. Try again.");
    /** Passages could not be added or removed; nothing is known to be damaged. */
    static final UserMessage UPDATE_FAILED = UserMessage.of("Search couldn't be updated. Try again.");
    /** Search is damaged or stopped; it is repaired when LearnSphere starts again. */
    static final UserMessage NEEDS_RESTART = UserMessage.of("Search stopped working. Restart LearnSphere to fix it.");

    private SearchFailures() {
    }

    /** True when {@code error} or a cause says index files are damaged, missing or unreadable. */
    static boolean isDamage(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof CorruptIndexException || t instanceof IndexFormatTooOldException
                    || t instanceof IndexFormatTooNewException || t instanceof NoSuchFileException
                    || t instanceof FileNotFoundException || t instanceof EOFException) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code error} or a cause reports a full disk (macOS, Linux and Windows wording). */
    static boolean isDiskFull(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("no space left on device") || lower.contains("not enough space on the disk")
                        || lower.contains("disk full")) {
                    return true;
                }
            }
        }
        return false;
    }
}
