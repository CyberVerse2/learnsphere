package edu.learnsphere.core.util;

/** Work that the caller can ask to stop, such as a streaming answer. */
public interface Cancellable {

    /** Asks the work to stop as soon as possible. Safe to call more than once and after completion. */
    void cancel();

    /** True once {@link #cancel()} has been called. */
    boolean isCancelled();
}
