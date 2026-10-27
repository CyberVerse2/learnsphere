package edu.learnsphere.ai.runtime;

import java.util.Locale;

/** The three llama-server processes LearnSphere runs, one model each. */
public enum ServerRole {

    /** Chat and vision: answers, generation, grading, page reading. Serves one request at a time. */
    CHAT,
    /** Text embeddings for search. */
    EMBED,
    /** Relevance scores for (question, passage) pairs. */
    RERANK;

    /** Lowercase name used in log file names and log lines. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** File name of this role's process log inside the logs folder. */
    public String logFileName() {
        return "llama-" + label() + ".log";
    }
}
