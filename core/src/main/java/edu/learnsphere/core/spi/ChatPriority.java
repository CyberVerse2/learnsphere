package edu.learnsphere.core.spi;

/**
 * Order in which requests waiting for the single chat process are served. Each AI interface documents
 * the priority its implementation uses.
 */
public enum ChatPriority {
    /** The learner is watching: answering a question, grading a short answer. */
    INTERACTIVE,
    /** The user started it and waits for the result: generating items, extracting concepts. */
    USER_WAITING,
    /** Nobody is waiting: reading scanned pages, describing figures. */
    BACKGROUND
}
