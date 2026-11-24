package ru.innopolis.reviewclassifier.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.innopolis.reviewclassifier.adapters.outbound.IncidentCardDto;
import ru.innopolis.reviewclassifier.adapters.outbound.JsonMapperFactory;
import ru.innopolis.reviewclassifier.adapters.outbound.PrometheusMetricsAdapter;
import ru.innopolis.reviewclassifier.adapters.outbound.RetryableQaAdapter;
import ru.innopolis.reviewclassifier.domain.model.IncidentCard;
import ru.innopolis.reviewclassifier.domain.model.IssueCategory;
import ru.innopolis.reviewclassifier.domain.model.Sentiment;
import ru.innopolis.reviewclassifier.domain.port.ClockPort;

class RetryableQaAdapterTest {

    private final ObjectMapper objectMapper = JsonMapperFactory.create();

    @Test
    void persistsOnQaFailureRecoversAfterRestartAndSerializesLowercaseSentiment(@TempDir Path tempDir)
            throws Exception {
        Path outboxFile = tempDir.resolve("durable_outbox.jsonl");
        AtomicInteger qaStatus = new AtomicInteger(500);
        Queue<String> receivedBodies = new ConcurrentLinkedQueue<>();
        Queue<String> idempotencyKeys = new ConcurrentLinkedQueue<>();

        HttpServer mockQa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockQa.createContext("/qa", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            int status = qaStatus.get();
            if (status >= 200 && status < 300) {
                receivedBodies.add(new String(body, StandardCharsets.UTF_8));
                idempotencyKeys.add(ex.getRequestHeaders().getFirst("X-Idempotency-Key"));
            }
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        mockQa.start();

        try {
            String qaUrl = "http://127.0.0.1:" + mockQa.getAddress().getPort() + "/qa";
            PrometheusMetricsAdapter metrics1 = new PrometheusMetricsAdapter();
            Instant now = Instant.parse("2026-11-24T04:00:00Z");

            // First JVM run: QA is down (500) -> durable file retains the incident across shutdown.
            try (RetryableQaAdapter firstInstance = RetryableQaAdapter.createConfigured(
                    objectMapper,
                    qaUrl,
                    outboxFile,
                    metrics1,
                    fixedClock(new AtomicReference<>(now)),
                    Duration.ofMinutes(15),
                    1,
                    0L,
                    Duration.ofSeconds(2))) {
                firstInstance.sendCriticalIncident(sampleCard(now.minusSeconds(10)));
                firstInstance.flushOutbox();

                assertThat(firstInstance.pendingCount()).isEqualTo(1);
                assertThat(Files.readString(outboxFile)).contains("\"sentiment\":\"negative\"");
                assertThat(receivedBodies).isEmpty();
            }

            // Second JVM run after restart with QA healthy (200) -> recovers and delivers.
            qaStatus.set(200);
            PrometheusMetricsAdapter metrics2 = new PrometheusMetricsAdapter();
            try (RetryableQaAdapter restartedInstance = RetryableQaAdapter.createConfigured(
                    objectMapper,
                    qaUrl,
                    outboxFile,
                    metrics2,
                    fixedClock(new AtomicReference<>(now)),
                    Duration.ofMinutes(15),
                    2,
                    0L,
                    Duration.ofSeconds(2))) {
                assertThat(restartedInstance.pendingCount()).isEqualTo(1);
                restartedInstance.flushOutbox();

                assertThat(restartedInstance.pendingCount()).isZero();
                assertThat(metrics2.getQaOutboxPending()).isZero();
                assertThat(Files.readAllLines(outboxFile)).isEmpty();
                assertThat(receivedBodies).hasSize(1);
                JsonNode delivered = objectMapper.readTree(receivedBodies.peek());
                assertThat(delivered.get("sentiment").asText()).isEqualTo("negative");
                assertThat(idempotencyKeys.peek())
                        .isEqualTo(delivered.get("reviewId").asText());
            }
        } finally {
            mockQa.stop(0);
        }
    }

    @Test
    void survivesRuntimeExceptionsAndChecksSlaIndependentlyAcrossAllPendingCards(@TempDir Path tempDir) {
        Path outboxFile = tempDir.resolve("fault_outbox.jsonl");
        PrometheusMetricsAdapter metrics = new PrometheusMetricsAdapter();
        AtomicReference<Instant> clockRef = new AtomicReference<>(Instant.parse("2026-11-24T04:00:00Z"));
        AtomicBoolean healthy = new AtomicBoolean(false);
        AtomicInteger delivered = new AtomicInteger();

        RetryableQaAdapter.IncidentSender faultySender = card -> {
            if (!healthy.get()) {
                throw new IllegalStateException("unexpected runtime fault");
            }
            delivered.incrementAndGet();
        };

        try (RetryableQaAdapter adapter = new RetryableQaAdapter(
                faultySender,
                objectMapper,
                outboxFile,
                metrics,
                fixedClock(clockRef),
                Duration.ofMinutes(15),
                1,
                0L,
                true)) {
            Instant overdue = Instant.parse("2026-11-24T03:59:00Z");
            adapter.sendCriticalIncident(sampleCard(overdue));
            adapter.sendCriticalIncident(sampleCard(overdue));
            adapter.sendCriticalIncident(sampleCard(overdue));
            adapter.safeFlushOutbox();

            assertThat(adapter.pendingCount()).isEqualTo(3);

            clockRef.set(overdue.plusSeconds(901));
            adapter.safeFlushOutbox();
            assertThat(metrics.getSlaBreachTotal()).isEqualTo(3L);
            assertThat(adapter.pendingCount()).isEqualTo(3);

            healthy.set(true);
            adapter.flushOutbox();
            assertThat(delivered.get()).isEqualTo(3);
            assertThat(adapter.pendingCount()).isZero();
            assertThat(metrics.getQaOutboxPending()).isZero();
        }
    }

    @Test
    void doesNotRetainCompletedOverdueIdsInMemory() {
        PrometheusMetricsAdapter metrics = new PrometheusMetricsAdapter();
        Instant now = Instant.parse("2026-11-24T05:00:00Z");
        Instant overdue = now.minusSeconds(1000);

        try (RetryableQaAdapter adapter = new RetryableQaAdapter(
                card -> {},
                objectMapper,
                null,
                metrics,
                fixedClock(new AtomicReference<>(now)),
                Duration.ofMinutes(15),
                1,
                0L,
                true)) {
            for (int i = 0; i < 2000; i++) {
                adapter.sendCriticalIncident(sampleCard(overdue));
            }
            adapter.flushOutbox();

            assertThat(adapter.pendingCount()).isZero();
            assertThat(metrics.getSlaBreachTotal()).isEqualTo(2000L);
            assertThat(metrics.getQaOutboxPending()).isZero();
            assertThat(metrics.getQaOutboxOldestAgeSeconds()).isZero();
        }
    }

    @Test
    void appendsConcurrentLocalSinkLinesSafelyWithoutCorruption(@TempDir Path tempDir) throws Exception {
        Path outboxFile = tempDir.resolve("nested/qa_outbox.jsonl");
        PrometheusMetricsAdapter metrics = new PrometheusMetricsAdapter();
        Instant now = Instant.parse("2026-11-24T04:00:00Z");

        int threads = 16;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);

        try (RetryableQaAdapter adapter = RetryableQaAdapter.createConfigured(
                        objectMapper,
                        "",
                        outboxFile,
                        metrics,
                        fixedClock(new AtomicReference<>(now)),
                        Duration.ofMinutes(15),
                        1,
                        0L,
                        Duration.ofSeconds(2));
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        adapter.sendCriticalIncident(sampleCard(now));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            doneGate.await();
        }

        List<String> lines = Files.readAllLines(outboxFile);
        assertThat(lines).hasSize(threads);
        for (String line : lines) {
            IncidentCardDto parsed = objectMapper.readValue(line, IncidentCardDto.class);
            assertThat(parsed.sentiment()).isEqualTo("negative");
            assertThat(parsed.maskedText()).isEqualTo("Товар бракованный");
        }
    }

    @Test
    void preservesAllEnqueuedCardsDuringConcurrentCompaction(@TempDir Path tempDir) throws Exception {
        Path outboxFile = tempDir.resolve("compaction_race.jsonl");
        PrometheusMetricsAdapter metrics = new PrometheusMetricsAdapter();
        Instant now = Instant.parse("2026-11-24T05:10:00Z");

        RetryableQaAdapter.IncidentSender selectiveSender = card -> {
            if (card.orderId().endsWith("-FAIL")) {
                throw new IOException("simulated QA rejection");
            }
        };

        int totalCards = 80;
        AtomicBoolean writersDone = new AtomicBoolean(false);
        CountDownLatch writersGate = new CountDownLatch(totalCards);

        try (RetryableQaAdapter adapter = new RetryableQaAdapter(
                        selectiveSender,
                        objectMapper,
                        outboxFile,
                        metrics,
                        fixedClock(new AtomicReference<>(now)),
                        Duration.ofMinutes(15),
                        1,
                        0L,
                        true);
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var flusher1 = pool.submit(() -> {
                while (!writersDone.get()) {
                    adapter.flushOutbox();
                }
            });
            var flusher2 = pool.submit(() -> {
                while (!writersDone.get()) {
                    adapter.flushOutbox();
                }
            });
            for (int i = 0; i < totalCards; i++) {
                final String orderId = "ORD-" + i + (i % 2 == 0 ? "-OK" : "-FAIL");
                pool.submit(() -> {
                    try {
                        adapter.sendCriticalIncident(new IncidentCard(
                                UUID.randomUUID(),
                                orderId,
                                1,
                                Sentiment.NEGATIVE,
                                false,
                                IssueCategory.DEFECT,
                                "брак",
                                now));
                    } finally {
                        writersGate.countDown();
                    }
                });
            }
            writersGate.await();
            writersDone.set(true);
            flusher1.get();
            flusher2.get();
            adapter.flushOutbox();

            assertThat(adapter.pendingCount()).isEqualTo(40);
            assertThat(Files.readAllLines(outboxFile)).hasSize(40);
        }

        try (RetryableQaAdapter recovered = new RetryableQaAdapter(
                selectiveSender,
                objectMapper,
                outboxFile,
                metrics,
                fixedClock(new AtomicReference<>(now)),
                Duration.ofMinutes(15),
                1,
                0L,
                true)) {
            assertThat(recovered.pendingCount()).isEqualTo(40);
        }
    }

    @Test
    void repairsTornTailOnStartupSoSubsequentAppendsSurviveRestart(@TempDir Path tempDir) throws Exception {
        Path outboxFile = tempDir.resolve("durable_outbox.jsonl");
        Path corruptFile = tempDir.resolve("durable_outbox.jsonl.corrupt");
        AtomicInteger qaStatus = new AtomicInteger(500);
        Queue<String> receivedBodies = new ConcurrentLinkedQueue<>();

        HttpServer mockQa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockQa.createContext("/qa", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            if (qaStatus.get() == 200) {
                receivedBodies.add(new String(body, StandardCharsets.UTF_8));
            }
            ex.sendResponseHeaders(qaStatus.get(), -1);
            ex.close();
        });
        mockQa.start();

        try {
            String qaUrl = "http://127.0.0.1:" + mockQa.getAddress().getPort() + "/qa";
            Instant now = Instant.parse("2026-11-24T04:00:00Z");
            IncidentCard cardA = sampleCard(now.minusSeconds(20));
            IncidentCard cardB = sampleCard(now.minusSeconds(5));

            // Seed a valid outbox line via adapter, then simulate a torn tail without trailing newline.
            try (RetryableQaAdapter seeder = RetryableQaAdapter.createConfigured(
                    objectMapper,
                    qaUrl,
                    outboxFile,
                    new PrometheusMetricsAdapter(),
                    fixedClock(new AtomicReference<>(now)),
                    Duration.ofMinutes(15),
                    1,
                    0L,
                    Duration.ofSeconds(2))) {
                seeder.sendCriticalIncident(cardA);
                seeder.flushOutbox();
            }
            String validLineA = Files.readAllLines(outboxFile).getFirst();
            Files.writeString(outboxFile, validLineA + "\n{\"card\":{\"reviewId\":\"", StandardCharsets.UTF_8);

            // Restart #1: repairs torn tail into .corrupt, rewrites clean outbox, then accepts cardB while QA is 500.
            try (RetryableQaAdapter first = RetryableQaAdapter.createConfigured(
                    objectMapper,
                    qaUrl,
                    outboxFile,
                    new PrometheusMetricsAdapter(),
                    fixedClock(new AtomicReference<>(now)),
                    Duration.ofMinutes(15),
                    1,
                    0L,
                    Duration.ofSeconds(2))) {
                assertThat(first.pendingCount()).isEqualTo(1);
                assertThat(Files.readString(corruptFile)).contains("{\"card\":{\"reviewId\":\"");

                first.sendCriticalIncident(cardB);
                first.flushOutbox();
                assertThat(first.pendingCount()).isEqualTo(2);
                assertThat(Files.readAllLines(outboxFile)).hasSize(2);
            }

            // Restart #2 against healthy 200 QA: both cardA and cardB must survive and deliver.
            qaStatus.set(200);
            try (RetryableQaAdapter second = RetryableQaAdapter.createConfigured(
                    objectMapper,
                    qaUrl,
                    outboxFile,
                    new PrometheusMetricsAdapter(),
                    fixedClock(new AtomicReference<>(now)),
                    Duration.ofMinutes(15),
                    2,
                    0L,
                    Duration.ofSeconds(2))) {
                second.flushOutbox();
                assertThat(second.pendingCount()).isZero();
                assertThat(Files.readAllLines(outboxFile)).isEmpty();
            }

            assertThat(receivedBodies).hasSize(2);
            String allDelivered = String.join("\n", receivedBodies);
            assertThat(allDelivered)
                    .contains(cardA.reviewId().toString())
                    .contains(cardB.reviewId().toString());
            assertThat(Files.readString(corruptFile)).contains("{\"card\":{\"reviewId\":\"");
        } finally {
            mockQa.stop(0);
        }
    }

    @Test
    void failsLoudlyWhenMiddleOfOutboxIsCorrupted(@TempDir Path tempDir) throws Exception {
        Path outboxFile = tempDir.resolve("corrupt_middle_outbox.jsonl");
        Instant now = Instant.parse("2026-11-24T04:00:00Z");
        IncidentCard card = sampleCard(now);

        HttpServer downQa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downQa.createContext("/qa", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        downQa.start();

        try {
            String qaUrl = "http://127.0.0.1:" + downQa.getAddress().getPort() + "/qa";
            try (RetryableQaAdapter seeder = RetryableQaAdapter.createConfigured(
                    objectMapper,
                    qaUrl,
                    outboxFile,
                    new PrometheusMetricsAdapter(),
                    fixedClock(new AtomicReference<>(now)),
                    Duration.ofMinutes(15),
                    1,
                    0L,
                    Duration.ofSeconds(2))) {
                seeder.sendCriticalIncident(card);
                seeder.flushOutbox();
            }
            String validLine = Files.readAllLines(outboxFile).getFirst();
            Files.writeString(outboxFile, "corrupted-middle-line\n" + validLine + "\n", StandardCharsets.UTF_8);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> RetryableQaAdapter.createConfigured(
                            objectMapper,
                            qaUrl,
                            outboxFile,
                            new PrometheusMetricsAdapter(),
                            fixedClock(new AtomicReference<>(now)),
                            Duration.ofMinutes(15),
                            1,
                            0L,
                            Duration.ofSeconds(2)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Corrupted line");
        } finally {
            downQa.stop(0);
        }
    }

    private static ClockPort fixedClock(AtomicReference<Instant> ref) {
        return new ClockPort() {
            @Override
            public Instant now() {
                return ref.get();
            }

            @Override
            public long nanoTime() {
                return System.nanoTime();
            }
        };
    }

    private static IncidentCard sampleCard(Instant createdAt) {
        return new IncidentCard(
                UUID.randomUUID(),
                "ORD-55",
                4,
                Sentiment.NEGATIVE,
                false,
                IssueCategory.DEFECT,
                "Товар бракованный",
                createdAt);
    }
}
