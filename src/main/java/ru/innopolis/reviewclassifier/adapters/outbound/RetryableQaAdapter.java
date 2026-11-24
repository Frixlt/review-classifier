package ru.innopolis.reviewclassifier.adapters.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.innopolis.reviewclassifier.adapters.outbound.FileOutboxStore.OutboxEntry;
import ru.innopolis.reviewclassifier.domain.model.IncidentCard;
import ru.innopolis.reviewclassifier.domain.port.ClockPort;
import ru.innopolis.reviewclassifier.domain.port.MetricsPort;
import ru.innopolis.reviewclassifier.domain.port.QualityAssurancePort;

/**
 * Thread-safe durable Outbox + asynchronous Retry adapter for Quality Assurance delivery
 * with restart recovery and independent 15-minute SLA breach detection (FR-11, FR-12, DEC-06, B3, M4, M5, M6).
 */
public final class RetryableQaAdapter implements QualityAssurancePort, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RetryableQaAdapter.class);

    private final IncidentSender sender;
    private final ClockPort clockPort;
    private final int maxImmediateAttempts;
    private final long retryDelayMs;
    private final DurableOutboxQueue queue;
    private final QaOutboxSlaTracker slaTracker;
    private final boolean durableAsyncMode;

    private final ReentrantLock flushLock = new ReentrantLock();
    private final ScheduledExecutorService retryScheduler;
    private final ExecutorService asyncTriggerExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    public RetryableQaAdapter(
            IncidentSender sender,
            ObjectMapper objectMapper,
            Path outboxLogPath,
            MetricsPort metricsPort,
            ClockPort clockPort,
            Duration slaLimit,
            int maxImmediateAttempts,
            long retryDelayMs,
            boolean durableAsyncMode) {
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        Objects.requireNonNull(metricsPort, "metricsPort must not be null");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort must not be null");
        Objects.requireNonNull(slaLimit, "slaLimit must not be null");
        this.maxImmediateAttempts = Math.max(1, maxImmediateAttempts);
        this.retryDelayMs = Math.max(0L, retryDelayMs);
        this.durableAsyncMode = durableAsyncMode;
        this.queue = new DurableOutboxQueue(objectMapper, outboxLogPath);
        this.slaTracker = new QaOutboxSlaTracker(objectMapper, metricsPort, clockPort, slaLimit);
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("qa-outbox-retry-", 0).factory());
        this.asyncTriggerExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("qa-outbox-dispatch-", 0).factory());

        if (durableAsyncMode) {
            queue.recoverFromDisk();
            slaTracker.updateOutboxStats(queue.snapshot());
        }
    }

    public static RetryableQaAdapter createConfigured(
            ObjectMapper mapper,
            String qaServiceUrl,
            Path outboxLogPath,
            MetricsPort metricsPort,
            ClockPort clockPort,
            Duration slaLimit,
            int maxAttempts,
            long retryDelayMs,
            Duration httpTimeout) {
        if (qaServiceUrl != null && !qaServiceUrl.isBlank()) {
            IncidentSender httpSender = new HttpIncidentSender(mapper, URI.create(qaServiceUrl.trim()), httpTimeout);
            return new RetryableQaAdapter(
                    httpSender,
                    mapper,
                    outboxLogPath,
                    metricsPort,
                    clockPort,
                    slaLimit,
                    maxAttempts,
                    retryDelayMs,
                    true);
        }
        return new RetryableQaAdapter(dto -> {}, mapper, outboxLogPath, metricsPort, clockPort, slaLimit, 1, 0L, false);
    }

    public void startBackgroundRetries(long intervalSeconds) {
        long period = Math.max(1L, intervalSeconds);
        retryScheduler.scheduleWithFixedDelay(this::safeFlushOutbox, period, period, TimeUnit.SECONDS);
    }

    @Override
    public void sendCriticalIncident(IncidentCard card) {
        IncidentCardDto dto = IncidentCardDto.fromDomain(card);
        if (!durableAsyncMode) {
            deliverToLocalSink(dto);
            return;
        }
        queue.enqueue(dto);
        slaTracker.updateOutboxStats(queue.snapshot());
        triggerAsyncFlush();
    }

    public void safeFlushOutbox() {
        try {
            flushOutbox();
        } catch (Throwable ex) {
            LOG.error("Unexpected error during background QA outbox flush: {}", ex.getMessage(), ex);
        }
    }

    public void flushOutbox() {
        flushLock.lock();
        try {
            checkAllPendingSla();
            deliverPendingSnapshot();
            slaTracker.updateOutboxStats(queue.snapshot());
        } finally {
            flushLock.unlock();
        }
    }

    public int pendingCount() {
        return queue.size();
    }

    private void triggerAsyncFlush() {
        if (!closed.get() && flushScheduled.compareAndSet(false, true)) {
            asyncTriggerExecutor.execute(() -> {
                flushScheduled.set(false);
                safeFlushOutbox();
            });
        }
    }

    private void deliverToLocalSink(IncidentCardDto dto) {
        queue.appendLocalSink(dto);
        slaTracker.recordDeliveryLatency(dto);
        Instant now = clockPort.now();
        if (slaTracker.isOverdue(dto, now)) {
            slaTracker.recordBreach(dto, now);
        }
    }

    private void checkAllPendingSla() {
        List<OutboxEntry> snapshot = queue.snapshot();
        Instant now = clockPort.now();
        boolean modified = false;
        for (OutboxEntry entry : snapshot) {
            if (!entry.slaBreached()
                    && slaTracker.isOverdue(entry.card(), now)
                    && queue.markSlaBreachedIfAbsent(entry.card().reviewId())) {
                slaTracker.recordBreach(entry.card(), now);
                modified = true;
            }
        }
        if (modified) {
            queue.persistSnapshot();
        }
    }

    private void deliverPendingSnapshot() {
        List<OutboxEntry> snapshot = queue.snapshot();
        boolean anyDelivered = false;
        for (OutboxEntry entry : snapshot) {
            IncidentCardDto card = entry.card();
            Instant now = clockPort.now();
            if (!entry.slaBreached()
                    && slaTracker.isOverdue(card, now)
                    && queue.markSlaBreachedIfAbsent(card.reviewId())) {
                slaTracker.recordBreach(card, now);
            }
            if (tryDeliverWithRetries(card)) {
                queue.remove(card.reviewId());
                slaTracker.recordDeliveryLatency(card);
                anyDelivered = true;
            }
        }
        if (anyDelivered) {
            queue.persistSnapshot();
        }
    }

    private boolean tryDeliverWithRetries(IncidentCardDto card) {
        for (int attempt = 1; attempt <= maxImmediateAttempts; attempt++) {
            try {
                sender.send(card);
                return true;
            } catch (IOException | RuntimeException ex) {
                LOG.warn(
                        "Failed to deliver incident reviewId={} attempt={}/{}: {}",
                        card.reviewId(),
                        attempt,
                        maxImmediateAttempts,
                        ex.getMessage());
                if (attempt < maxImmediateAttempts && !sleepRetryDelay()) {
                    return false;
                }
            }
        }
        return false;
    }

    private boolean sleepRetryDelay() {
        if (retryDelayMs <= 0L) {
            return true;
        }
        try {
            Thread.sleep(retryDelayMs);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        closed.set(true);
        retryScheduler.shutdownNow();
        asyncTriggerExecutor.shutdownNow();
        sender.closeTransport();
    }

    /** Abstraction over outbound QA delivery transport (HTTP endpoint or custom stub). */
    @FunctionalInterface
    public interface IncidentSender {
        void send(IncidentCardDto card) throws IOException;

        /** Closes any underlying transport resources (no-op by default). */
        default void closeTransport() {}
    }
}
