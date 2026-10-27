package edu.learnsphere.core.service;

/** Readiness of the study assistant. */
public enum AssistantState {
    /** Getting ready; features that need it wait or say so. */
    STARTING,
    /** Ready to answer, read and generate. */
    READY,
    /** Cannot run on this computer. */
    UNAVAILABLE,
    /** Stopped unexpectedly; can be restarted. */
    FAILED
}
