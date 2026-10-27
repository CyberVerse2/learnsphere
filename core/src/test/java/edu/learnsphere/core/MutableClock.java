package edu.learnsphere.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move. */
public final class MutableClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    public MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    /** 2026-03-02T09:00:00Z. */
    public static MutableClock fixed() {
        return new MutableClock(Instant.parse("2026-03-02T09:00:00Z"));
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
