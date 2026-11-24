package ru.innopolis.reviewclassifier.adapters.outbound;

import java.time.Instant;
import ru.innopolis.reviewclassifier.domain.port.ClockPort;

/** Production clock implementation backed by UTC system time. */
public final class SystemClockAdapter implements ClockPort {
    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
