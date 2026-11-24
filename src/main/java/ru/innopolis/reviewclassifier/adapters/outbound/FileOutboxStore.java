package ru.innopolis.reviewclassifier.adapters.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe durable JSONL persistence helper for the QA incident outbox and local sink mode.
 */
final class FileOutboxStore {
    private static final Logger LOG = LoggerFactory.getLogger(FileOutboxStore.class);

    private final ObjectMapper mapper;
    private final Path outboxPath;
    private final ReentrantLock ioLock = new ReentrantLock();

    FileOutboxStore(ObjectMapper mapper, Path outboxPath) {
        this.mapper = mapper;
        this.outboxPath = outboxPath;
    }

    List<OutboxEntry> loadPending() {
        if (outboxPath == null || !Files.exists(outboxPath)) {
            return List.of();
        }
        ioLock.lock();
        try {
            String rawContent = new String(Files.readAllBytes(outboxPath), StandardCharsets.UTF_8);
            OutboxRecoverySupport.RecoveryOutcome outcome =
                    OutboxRecoverySupport.recover(rawContent, outboxPath, mapper);
            if (outcome.repairNeeded()) {
                rewriteAllLocked(outcome.entries());
            }
            return outcome.entries();
        } catch (IOException ex) {
            LOG.error("Failed to load durable QA outbox from {}: {}", outboxPath, ex.getMessage(), ex);
            throw new IllegalStateException("Failed to load durable QA outbox from " + outboxPath, ex);
        } finally {
            ioLock.unlock();
        }
    }

    void appendEntry(OutboxEntry entry) {
        appendJsonObject(entry);
    }

    void appendLocalSink(IncidentCardDto card) {
        appendJsonObject(card);
    }

    void rewriteAll(Collection<OutboxEntry> entries) {
        if (outboxPath == null) {
            return;
        }
        ioLock.lock();
        try {
            rewriteAllLocked(entries);
        } catch (IOException ex) {
            LOG.error("Failed to compact durable QA outbox {}: {}", outboxPath, ex.getMessage(), ex);
        } finally {
            ioLock.unlock();
        }
    }

    private void rewriteAllLocked(Collection<OutboxEntry> entries) throws IOException {
        ensureParentDir();
        StringBuilder payload = new StringBuilder();
        for (OutboxEntry entry : entries) {
            payload.append(mapper.writeValueAsString(entry)).append('\n');
        }
        Path tempFile = outboxPath.resolveSibling(outboxPath.getFileName() + ".tmp");
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                tempFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(channel, bytes);
            channel.force(true);
        }
        moveReplacing(tempFile, outboxPath);
    }

    private void appendJsonObject(Object payloadObject) {
        if (outboxPath == null) {
            return;
        }
        ioLock.lock();
        try {
            ensureParentDir();
            String line = mapper.writeValueAsString(payloadObject) + '\n';
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    outboxPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                writeFully(channel, bytes);
                channel.force(true);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to durably persist QA outbox record to " + outboxPath, ex);
        } finally {
            ioLock.unlock();
        }
    }

    private void ensureParentDir() throws IOException {
        ensureParentDir(outboxPath);
    }

    static void ensureParentDir(Path targetPath) throws IOException {
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    record OutboxEntry(IncidentCardDto card, boolean slaBreached) {}
}
