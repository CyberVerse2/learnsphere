package edu.learnsphere.core.util;

/**
 * A registration that can be removed. Closing is idempotent and never throws, so a subscription can be
 * kept in a field and closed when a view goes away, or used in try-with-resources.
 */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

    /** A subscription that holds nothing. */
    Subscription NONE = () -> { };

    /** Removes the registration. Safe to call more than once. */
    @Override
    void close();
}
