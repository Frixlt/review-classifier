package ru.innopolis.reviewclassifier.adapters.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.innopolis.reviewclassifier.adapters.outbound.FileOutboxStore.OutboxEntry;

/**
 * Recovers durable QA outbox lines after crash/restart, quarantining torn tails into {@code .corrupt}
 * and failing fast on mid-file corruption.
 */
final class OutboxRecoverySupport {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRecoverySupport.class);

    private OutboxRecoverySupport() {}

    static RecoveryOutcome recover(String rawContent, Path outboxPath, ObjectMapper mapper) throws IOException {
        String[] lines = rawContent.split("\n", -1);
        int lastNonBlankIndex = findLastNonBlankIndex(lines);
        Map<UUID, OutboxEntry> recovered = new LinkedHashMap<>();
        boolean tailCorrupted = false;
        for (int i = 0; i <= lastNonBlankIndex; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.isEmpty()) {
                boolean lastNonBlank = i == lastNonBlankIndex;
                tailCorrupted |= processOutboxLine(trimmed, lastNonBlank, i + 1, outboxPath, mapper, recovered);
            }
        }
        boolean missingTrailingNewline = !rawContent.isEmpty() && rawContent.charAt(rawContent.length() - 1) != '\n';
        return new RecoveryOutcome(List.copyOf(recovered.values()), tailCorrupted || missingTrailingNewline);
    }

    private static int findLastNonBlankIndex(String[] lines) {
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean processOutboxLine(
            String trimmed,
            boolean lastNonBlankLine,
            int lineNumber,
            Path outboxPath,
            ObjectMapper mapper,
            Map<UUID, OutboxEntry> target)
            throws IOException {
        try {
            OutboxEntry entry = readValidEntry(trimmed, outboxPath, mapper);
            target.put(entry.card().reviewId(), entry);
            return false;
        } catch (IOException ex) {
            if (!lastNonBlankLine) {
                throw new IllegalStateException(
                        "Corrupted line " + lineNumber + " in durable QA outbox " + outboxPath, ex);
            }
            quarantineCorruptTail(trimmed, outboxPath, ex);
            return true;
        }
    }

    private static OutboxEntry readValidEntry(String trimmed, Path outboxPath, ObjectMapper mapper) throws IOException {
        OutboxEntry entry = mapper.readValue(trimmed, OutboxEntry.class);
        if (entry == null || entry.card() == null || entry.card().reviewId() == null) {
            throw new IOException("Invalid outbox entry payload in " + outboxPath);
        }
        return entry;
    }

    private static void quarantineCorruptTail(String trimmed, Path outboxPath, IOException cause) throws IOException {
        Path corruptPath = outboxPath.resolveSibling(outboxPath.getFileName() + ".corrupt");
        FileOutboxStore.ensureParentDir(corruptPath);
        byte[] bytes = (trimmed + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                corruptPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            FileOutboxStore.writeFully(channel, bytes);
            channel.force(true);
        }
        LOG.warn(
                "Quarantined torn tail from durable QA outbox {} into {}: {}",
                outboxPath,
                corruptPath,
                cause.getMessage());
    }

    record RecoveryOutcome(List<OutboxEntry> entries, boolean repairNeeded) {}
}
