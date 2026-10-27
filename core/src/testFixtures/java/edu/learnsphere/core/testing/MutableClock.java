package edu.learnsphere.core.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move. Thread-safe. */
public final class MutableClock extends Clock {

    /** The instant {@link #fixed()} starts at: Monday 2026-03-02T09:00:00Z. */
    public static final Instant START = Instant.parse("2026-03-02T09:00:00Z");

    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    public MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    /** A clock at {@link #START} in UTC. */
    public static MutableClock fixed() {
        return new MutableClock(START);
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
