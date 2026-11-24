package ru.innopolis.reviewclassifier.adapters.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.innopolis.reviewclassifier.adapters.outbound.FileOutboxStore.OutboxEntry;

/** Package-private thread-safe in-memory + durable disk-backed pending QA outbox state holder. */
final class DurableOutboxQueue {
    private static final Logger LOG = LoggerFactory.getLogger(DurableOutboxQueue.class);

    private final ReentrantLock queueLock = new ReentrantLock();
    private final Map<UUID, OutboxEntry> pending = new LinkedHashMap<>();
    private final FileOutboxStore store;

    DurableOutboxQueue(ObjectMapper objectMapper, Path outboxPath) {
        this.store = outboxPath == null ? null : new FileOutboxStore(objectMapper, outboxPath);
    }

    int size() {
        queueLock.lock();
        try {
            return pending.size();
        } finally {
            queueLock.unlock();
        }
    }

    List<OutboxEntry> snapshot() {
        queueLock.lock();
        try {
            return List.copyOf(pending.values());
        } finally {
            queueLock.unlock();
        }
    }

    void recoverFromDisk() {
        if (store == null) {
            return;
        }
        int recoveredCount;
        queueLock.lock();
        try {
            List<OutboxEntry> recovered = store.loadPending();
            for (OutboxEntry entry : recovered) {
                pending.putIfAbsent(entry.card().reviewId(), entry);
            }
            recoveredCount = recovered.size();
        } finally {
            queueLock.unlock();
        }
        if (recoveredCount > 0) {
            LOG.info("Recovered {} pending QA incidents from durable outbox", recoveredCount);
        }
    }

    void enqueue(IncidentCardDto dto) {
        OutboxEntry entry = new OutboxEntry(dto, false);
        queueLock.lock();
        try {
            if (pending.containsKey(dto.reviewId())) {
                return;
            }
            if (store != null) {
                store.appendEntry(entry);
            }
            pending.put(dto.reviewId(), entry);
        } finally {
            queueLock.unlock();
        }
    }

    void appendLocalSink(IncidentCardDto dto) {
        if (store != null) {
            store.appendLocalSink(dto);
        }
    }

    boolean markSlaBreachedIfAbsent(UUID reviewId) {
        queueLock.lock();
        try {
            OutboxEntry current = pending.get(reviewId);
            if (current == null || current.slaBreached()) {
                return false;
            }
            pending.put(reviewId, new OutboxEntry(current.card(), true));
            return true;
        } finally {
            queueLock.unlock();
        }
    }

    void remove(UUID reviewId) {
        queueLock.lock();
        try {
            pending.remove(reviewId);
        } finally {
            queueLock.unlock();
        }
    }

    void persistSnapshot() {
        if (store == null) {
            return;
        }
        queueLock.lock();
        try {
            store.rewriteAll(List.copyOf(pending.values()));
        } finally {
            queueLock.unlock();
        }
    }
}
