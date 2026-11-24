package ru.innopolis.reviewclassifier.adapters.outbound;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import ru.innopolis.reviewclassifier.domain.model.ClassificationResult;
import ru.innopolis.reviewclassifier.domain.model.Sentiment;
import ru.innopolis.reviewclassifier.domain.port.MetricsPort;

/**
 * Thread-safe Prometheus metrics collector with lock-free counters, latency histograms,
 * and outbox backlog gauges (FR-15, NFR-06, M6).
 */
public final class PrometheusMetricsAdapter implements MetricsPort, MetricsExporter {
    private final LongAdder reviewsTotal = new LongAdder();
    private final LongAdder reviewsPositiveTotal = new LongAdder();
    private final LongAdder reviewsNegativeTotal = new LongAdder();
    private final LongAdder criticalReviewsTotal = new LongAdder();
    private final LongAdder fallbackTotal = new LongAdder();
    private final LongAdder validationErrorsTotal = new LongAdder();
    private final LongAdder slaBreachTotal = new LongAdder();
    private final LongAdder processingTimeMsSum = new LongAdder();

    private final AtomicInteger qaOutboxPending = new AtomicInteger(0);
    private final AtomicLong qaOutboxOldestAgeSeconds = new AtomicLong(0L);

    private final PrometheusHistogram httpRequestDurationMs = new PrometheusHistogram(
            "http_request_duration_ms",
            "HTTP POST /api/v1/reviews/classify request latency in ms",
            5,
            10,
            25,
            50,
            100,
            250,
            500,
            1000,
            5000);

    private final PrometheusHistogram qaDeliveryLatencySeconds = new PrometheusHistogram(
            "qa_delivery_latency_seconds",
            "End-to-end delivery latency from createdAt to QA acknowledgment in seconds",
            1,
            5,
            30,
            60,
            300,
            600,
            900);

    @Override
    public void recordClassification(ClassificationResult result) {
        reviewsTotal.increment();
        processingTimeMsSum.add(result.processingTimeMs());
        if (result.sentiment() == Sentiment.POSITIVE) {
            reviewsPositiveTotal.increment();
        } else if (result.sentiment() == Sentiment.NEGATIVE) {
            reviewsNegativeTotal.increment();
        }
        if (result.isCritical()) {
            criticalReviewsTotal.increment();
        }
    }

    @Override
    public void recordValidationError() {
        validationErrorsTotal.increment();
    }

    @Override
    public void recordFallback() {
        fallbackTotal.increment();
    }

    @Override
    public void recordSlaBreach() {
        slaBreachTotal.increment();
    }

    @Override
    public void recordHttpRequestDurationMs(long durationMs) {
        httpRequestDurationMs.observe(durationMs);
    }

    @Override
    public void recordQaDeliveryLatencySeconds(double latencySeconds) {
        qaDeliveryLatencySeconds.observe(latencySeconds);
    }

    @Override
    public void updateOutboxStats(int pendingCount, long oldestAgeSeconds) {
        qaOutboxPending.set(Math.max(0, pendingCount));
        qaOutboxOldestAgeSeconds.set(Math.max(0L, oldestAgeSeconds));
    }

    public long getReviewsTotal() {
        return reviewsTotal.sum();
    }

    public long getCriticalReviewsTotal() {
        return criticalReviewsTotal.sum();
    }

    public long getFallbackTotal() {
        return fallbackTotal.sum();
    }

    public long getSlaBreachTotal() {
        return slaBreachTotal.sum();
    }

    public int getQaOutboxPending() {
        return qaOutboxPending.get();
    }

    public long getQaOutboxOldestAgeSeconds() {
        return qaOutboxOldestAgeSeconds.get();
    }

    @Override
    public String exportPrometheus() {
        StringBuilder sb = new StringBuilder(1536);
        appendMetric(sb, "reviews_total", "counter", "Total number of classified reviews", reviewsTotal.sum());
        appendMetric(
                sb,
                "reviews_positive_total",
                "counter",
                "Total reviews classified as positive",
                reviewsPositiveTotal.sum());
        appendMetric(
                sb,
                "reviews_negative_total",
                "counter",
                "Total reviews classified as negative",
                reviewsNegativeTotal.sum());
        appendMetric(
                sb,
                "critical_reviews_total",
                "counter",
                "Total reviews flagged as critical",
                criticalReviewsTotal.sum());
        appendMetric(
                sb, "fallback_total", "counter", "Total requests handled via Runtime Fallback", fallbackTotal.sum());
        appendMetric(
                sb,
                "validation_errors_total",
                "counter",
                "Total requests rejected with HTTP 400",
                validationErrorsTotal.sum());
        appendMetric(
                sb,
                "sla_breach_total",
                "counter",
                "Total critical incidents exceeding 15-min delivery SLA",
                slaBreachTotal.sum());
        appendMetric(
                sb,
                "classification_processing_ms_sum",
                "counter",
                "Cumulative classification processing time in ms",
                processingTimeMsSum.sum());
        appendMetric(
                sb,
                "qa_outbox_pending",
                "gauge",
                "Current number of pending incidents in durable QA outbox",
                qaOutboxPending.get());
        appendMetric(
                sb,
                "qa_outbox_oldest_age_seconds",
                "gauge",
                "Age in seconds of the oldest pending incident in QA outbox",
                qaOutboxOldestAgeSeconds.get());
        httpRequestDurationMs.appendTo(sb);
        qaDeliveryLatencySeconds.appendTo(sb);
        return sb.toString();
    }

    private static void appendMetric(StringBuilder sb, String name, String type, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        sb.append(name).append(' ').append(value).append('\n');
    }
}
