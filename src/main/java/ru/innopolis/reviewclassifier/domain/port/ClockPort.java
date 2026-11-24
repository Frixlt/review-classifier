package ru.innopolis.reviewclassifier.domain.port;

import java.time.Instant;

/** Port abstracting system time for deterministic unit/adapter testing of SLA and durations. */
public interface ClockPort {
    Instant now();

    long nanoTime();
}
