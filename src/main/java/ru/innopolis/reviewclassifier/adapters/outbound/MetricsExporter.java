package ru.innopolis.reviewclassifier.adapters.outbound;

/** Outbound metrics exposition and adapter-level latency/backlog instrumentation (FR-15, NFR-06). */
public interface MetricsExporter {
    String exportPrometheus();

    void recordHttpRequestDurationMs(long durationMs);

    void recordQaDeliveryLatencySeconds(double latencySeconds);

    void updateOutboxStats(int pendingCount, long oldestAgeSeconds);
}
