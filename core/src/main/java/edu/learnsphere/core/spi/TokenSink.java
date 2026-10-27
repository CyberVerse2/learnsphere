package edu.learnsphere.core.spi;

/** Receives streamed text as it is generated. Called on the generating thread; must return quickly. */
@FunctionalInterface
public interface TokenSink {

    /** Discards tokens. */
    TokenSink NONE = token -> { };

    /** Receives the next piece of text (a token or a few characters). */
    void accept(String token);
}
