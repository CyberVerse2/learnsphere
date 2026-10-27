package edu.learnsphere.search;

import edu.learnsphere.core.error.UserMessage;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;
import java.util.Locale;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;

/**
 * The sentences search failures show, and how failures are told apart.
 *
 * <p>Learners never see a "search" feature: they ask questions about their material, import PDFs and remove
 * them. Each sentence names the thing they were doing at the time.
 */
final class SearchFailures {

    /** A search (asking a question) could not be run; nothing is known to be damaged. */
    static final UserMessage SEARCH_FAILED = UserMessage.of("Couldn't look through your material. Try again.");
    /** A PDF's passages could not be added ({@code upsert}); nothing is known to be damaged. */
    static final UserMessage ADD_FAILED = UserMessage.of("Couldn't get this PDF ready for questions. Try again.");
    /** Passages of a PDF or course could not be removed; nothing is known to be damaged. */
    static final UserMessage REMOVE_FAILED = UserMessage.of("Couldn't finish removing this. Try again.");
    /** Added or removed passages could not be saved ({@code commit}); nothing is known to be damaged. */
    static final UserMessage SAVE_FAILED = UserMessage.of("Couldn't save changes to your material. Try again.");
    /** A rebuild did not finish; the next start runs it again, because the index still needs one. */
    static final UserMessage REBUILD_FAILED =
            UserMessage.of("Couldn't get your material ready for questions. Restart LearnSphere to try again.");
    /** Search is damaged or stopped; it is repaired when LearnSphere starts again. */
    static final UserMessage NEEDS_RESTART =
            UserMessage.of("Looking through your material stopped working. Restart LearnSphere to fix it.");

    /** Causes looked at before giving up, so a chain that loops back on itself still ends. */
    private static final int MAX_CAUSES = 32;

    private SearchFailures() {
    }

    /**
     * The sentence for a failed operation: {@link UserMessage#DISK_FULL} for a full disk, {@link #NEEDS_RESTART}
     * for damaged index files, otherwise {@code fallback}, the sentence of the operation itself.
     */
    static UserMessage messageFor(Throwable error, UserMessage fallback) {
        if (isDiskFull(error)) {
            return UserMessage.DISK_FULL;
        }
        if (isDamage(error)) {
            return NEEDS_RESTART;
        }
        return fallback;
    }

    /** True when {@code error} or a cause says index files are damaged, missing or unreadable. */
    static boolean isDamage(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth++ < MAX_CAUSES; t = t.getCause()) {
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
        int depth = 0;
        for (Throwable t = error; t != null && depth++ < MAX_CAUSES; t = t.getCause()) {
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
