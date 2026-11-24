package ru.innopolis.reviewclassifier.adapters.outbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.innopolis.reviewclassifier.domain.port.ClockPort;
import ru.innopolis.reviewclassifier.domain.port.MetricsPort;

/**
 * Package-private helper managing QA delivery SLA breach detection, structured audit emission,
 * delivery latency metrics, and outbox backlog statistics (M5, M6).
 */
final class QaOutboxSlaTracker {
    private static final Logger LOG = LoggerFactory.getLogger(QaOutboxSlaTracker.class);
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("AUDIT");

    private final ObjectMapper objectMapper;
    private final MetricsPort metricsPort;
    private final MetricsExporter metricsExporter;
    private final ClockPort clockPort;
    private final Duration slaLimit;

    QaOutboxSlaTracker(ObjectMapper objectMapper, MetricsPort metricsPort, ClockPort clockPort, Duration slaLimit) {
        this.objectMapper = objectMapper;
        this.metricsPort = metricsPort;
        this.metricsExporter = metricsPort instanceof MetricsExporter exporter ? exporter : null;
        this.clockPort = clockPort;
        this.slaLimit = slaLimit;
    }

    boolean isOverdue(IncidentCardDto card, Instant now) {
        return card.createdAt() != null
                && Duration.between(card.createdAt(), now).compareTo(slaLimit) > 0;
    }

    void recordBreach(IncidentCardDto card, Instant now) {
        metricsPort.recordSlaBreach();
        Duration elapsed = Duration.between(card.createdAt(), now);
        try {
            String json = objectMapper.writeValueAsString(new SlaBreachEvent(
                    now,
                    "SLA_BREACH_EXTERNAL",
                    card.reviewId(),
                    card.orderId(),
                    elapsed.toSeconds(),
                    slaLimit.toSeconds()));
            AUDIT_LOG.error(json);
        } catch (JsonProcessingException ex) {
            LOG.error("SLA_BREACH_EXTERNAL reviewId={} orderId={}", card.reviewId(), card.orderId(), ex);
        }
    }

    void recordDeliveryLatency(IncidentCardDto card) {
        if (metricsExporter == null || card.createdAt() == null) {
            return;
        }
        Duration elapsed = Duration.between(card.createdAt(), clockPort.now());
        metricsExporter.recordQaDeliveryLatencySeconds(Math.max(0.0, elapsed.toMillis() / 1000.0));
    }

    void updateOutboxStats(Collection<FileOutboxStore.OutboxEntry> entries) {
        if (metricsExporter == null) {
            return;
        }
        Instant now = clockPort.now();
        long oldestAge = 0L;
        for (FileOutboxStore.OutboxEntry entry : entries) {
            if (entry.card().createdAt() != null) {
                long age = Math.max(
                        0L, Duration.between(entry.card().createdAt(), now).toSeconds());
                oldestAge = Math.max(oldestAge, age);
            }
        }
        metricsExporter.updateOutboxStats(entries.size(), oldestAge);
    }

    private record SlaBreachEvent(
            Instant timestamp,
            String eventType,
            UUID reviewId,
            String orderId,
            long elapsedSeconds,
            long slaLimitSeconds) {}
}
