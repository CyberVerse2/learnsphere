package edu.learnsphere.core.service;

/** What the ask pipeline is doing, so the UI can show a quiet progress line before text streams in. */
public enum AskPhase {
    /** Looking through the course material. */
    SEARCHING,
    /** Writing the answer; tokens follow. */
    WRITING
}
