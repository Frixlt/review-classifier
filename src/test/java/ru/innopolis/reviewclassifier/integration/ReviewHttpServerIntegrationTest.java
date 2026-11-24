package ru.innopolis.reviewclassifier.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.innopolis.reviewclassifier.adapters.outbound.JsonMapperFactory;
import ru.innopolis.reviewclassifier.infrastructure.AppConfig;
import ru.innopolis.reviewclassifier.infrastructure.ApplicationWiring;

class ReviewHttpServerIntegrationTest {

    private final ObjectMapper objectMapper = JsonMapperFactory.create();

    @Test
    void reportsDownReadinessBeforeModelLoadAndReturns503OnClassify(@TempDir Path tempDir) throws Exception {
        AppConfig config = buildTestConfig(
                tempDir.resolve("missing.json"),
                tempDir.resolve("missing.sha256"),
                tempDir.resolve("outbox.jsonl"),
                "");

        try (ApplicationWiring wiring = new ApplicationWiring(config);
                HttpClient client = HttpClient.newHttpClient()) {
            wiring.start();
            String baseUrl = "http://127.0.0.1:" + wiring.getServer().getPort();

            HttpResponse<String> live = get(client, baseUrl + "/health/live");
            assertThat(live.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(live.body()).get("status").asText())
                    .isEqualTo("UP");

            HttpResponse<String> ready = get(client, baseUrl + "/health/ready");
            assertThat(ready.statusCode()).isEqualTo(503);
            assertThat(objectMapper.readTree(ready.body()).get("status").asText())
                    .isEqualTo("DOWN");

            HttpResponse<String> classify =
                    postJson(client, baseUrl + "/api/v1/reviews/classify", validPayload(5, "Ок"));
            assertThat(classify.statusCode()).isEqualTo(503);
        }
    }

    @Test
    void enforcesStrictJsonContractZeroPiiEchoAllow405ExactRoutesAndMetrics(@TempDir Path tempDir) throws Exception {
        Path outboxPath = tempDir.resolve("qa_outbox.jsonl");
        AppConfig config = buildTestConfig(
                Path.of("data/sentiment_v1.json"), Path.of("data/sentiment_v1.json.sha256"), outboxPath, "");

        try (ApplicationWiring wiring = new ApplicationWiring(config);
                HttpClient client = HttpClient.newHttpClient()) {
            wiring.loadModel();
            wiring.start();
            String baseUrl = "http://127.0.0.1:" + wiring.getServer().getPort();

            assertThat(get(client, baseUrl + "/health/ready").statusCode()).isEqualTo(200);
            assertThat(get(client, baseUrl + "/actuator/prometheus").statusCode())
                    .isEqualTo(200);

            // Unknown subpath or path -> 404 Not Found.
            assertThat(postJson(client, baseUrl + "/api/v1/reviews/classify/unknown", validPayload(5, "Ок"))
                            .statusCode())
                    .isEqualTo(404);
            assertThat(get(client, baseUrl + "/no-such-route").statusCode()).isEqualTo(404);

            HttpResponse<String> metricsNotFound = get(client, baseUrl + "/metrics/unknown");
            assertThat(metricsNotFound.statusCode()).isEqualTo(404);
            assertThat(metricsNotFound.headers().firstValue("Content-Type"))
                    .hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));
            JsonNode metricsErrJson = objectMapper.readTree(metricsNotFound.body());
            assertThat(metricsErrJson.get("status").asInt()).isEqualTo(404);

            // GET on POST endpoint -> 405 with Allow: POST.
            HttpResponse<String> wrongMethod = get(client, baseUrl + "/api/v1/reviews/classify");
            assertThat(wrongMethod.statusCode()).isEqualTo(405);
            assertThat(wrongMethod.headers().firstValue("Allow")).contains("POST");

            // Strict JSON validation & zero PII leakage in error responses (B2, M2).
            assertThat(postJson(client, baseUrl + "/api/v1/reviews/classify", "null")
                            .statusCode())
                    .isEqualTo(400);
            assertThat(postJson(client, baseUrl + "/api/v1/reviews/classify", validPayload(5, "Ок") + "{bad")
                            .statusCode())
                    .isEqualTo(400);

            String floatRating = validRawPayload(UUID.randomUUID().toString(), "ORD-1", "2.9", "Текст");
            assertThat(postJson(client, baseUrl + "/api/v1/reviews/classify", floatRating)
                            .statusCode())
                    .isEqualTo(400);

            String stringRatingWithPii =
                    validRawPayload(UUID.randomUUID().toString(), "ORD-1", "\"client_test@shop.ru\"", "Текст");
            HttpResponse<String> stringRatingResp =
                    postJson(client, baseUrl + "/api/v1/reviews/classify", stringRatingWithPii);
            assertThat(stringRatingResp.statusCode()).isEqualTo(400);
            assertThat(stringRatingResp.body()).doesNotContain("client_test@shop.ru");

            String unknownFieldWithPii = """
                    {"reviewId":"%s","orderId":"ORD-1","rating":5,"text":"Ок","createdAt":"2026-11-24T03:00:00Z","Иван +7-999-123-45-67":"x"}
                    """.formatted(UUID.randomUUID());
            HttpResponse<String> unknownResp =
                    postJson(client, baseUrl + "/api/v1/reviews/classify", unknownFieldWithPii);
            assertThat(unknownResp.statusCode()).isEqualTo(400);
            assertThat(unknownResp.body()).doesNotContain("Иван").doesNotContain("+7-999-123-45-67");

            String shortUuid = validRawPayload("\"1-1-1-1-1\"", "ORD-1", "5", "Ок");
            assertThat(postJson(client, baseUrl + "/api/v1/reviews/classify", shortUuid)
                            .statusCode())
                    .isEqualTo(400);

            String oversizedText = "a".repeat(70_000);
            assertThat(postJson(client, baseUrl + "/api/v1/reviews/classify", validPayload(5, oversizedText))
                            .statusCode())
                    .isEqualTo(400);

            // "поломка" and non-greedy name masking (B4, M1).
            HttpResponse<String> defectResp = postJson(
                    client,
                    baseUrl + "/api/v1/reviews/classify",
                    validPayload(4, "Меня зовут Анна поломка на второй день"));
            assertThat(defectResp.statusCode()).isEqualTo(200);
            JsonNode defectJson = objectMapper.readTree(defectResp.body());
            assertThat(defectJson.get("issueCategory").asText()).isEqualTo("DEFECT");
            assertThat(defectJson.get("isCritical").asBoolean()).isTrue();
            assertThat(defectJson.get("maskedText").asText()).isEqualTo("Меня зовут [NAME] поломка на второй день");

            List<String> outboxLines = Files.readAllLines(outboxPath);
            assertThat(outboxLines).hasSize(1);
            assertThat(outboxLines.getFirst()).contains("\"sentiment\":\"negative\"");
        }
    }

    @Test
    void respondsQuicklyWhenQaSlowOrDownAndMeasuresConcurrentBurstLatencies(@TempDir Path tempDir) throws Exception {
        AtomicInteger qaStatus = new AtomicInteger(500);
        AtomicInteger qaDelayMs = new AtomicInteger(1500);
        Queue<String> qaPayloads = new ConcurrentLinkedQueue<>();

        HttpServer mockQa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockQa.createContext("/qa", ex -> {
            int capturedStatus = qaStatus.get();
            int capturedDelay = qaDelayMs.get();
            byte[] body = ex.getRequestBody().readAllBytes();
            if (capturedDelay > 0) {
                try {
                    Thread.sleep(capturedDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (capturedStatus == 200) {
                qaPayloads.add(new String(body, StandardCharsets.UTF_8));
            }
            ex.sendResponseHeaders(capturedStatus, -1);
            ex.close();
        });
        mockQa.start();

        Path outboxPath = tempDir.resolve("async_outbox.jsonl");
        String qaUrl = "http://127.0.0.1:" + mockQa.getAddress().getPort() + "/qa";
        AppConfig config = buildTestConfig(
                Path.of("data/sentiment_v1.json"), Path.of("data/sentiment_v1.json.sha256"), outboxPath, qaUrl);

        try {
            try (ApplicationWiring wiring = new ApplicationWiring(config);
                    HttpClient client = HttpClient.newHttpClient()) {
                wiring.loadModel();
                wiring.start();
                String baseUrl = "http://127.0.0.1:" + wiring.getServer().getPort();

                assertThat(postJson(client, baseUrl + "/api/v1/reviews/classify", validPayload(5, "Хороший товар"))
                                .statusCode())
                        .isEqualTo(200);

                long t0 = System.nanoTime();
                HttpResponse<String> resp = postJson(
                        client, baseUrl + "/api/v1/reviews/classify", validPayload(1, "Ужасный брак, сломался"));
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(elapsedMs).isLessThan(500L);
                assertThat(Files.readAllLines(outboxPath)).isNotEmpty();

                // Concurrent burst of 120 positive requests with per-request client latency percentiles.
                Queue<Double> latenciesMs = new ConcurrentLinkedQueue<>();
                List<CompletableFuture<HttpResponse<String>>> futures = java.util.stream.IntStream.range(0, 120)
                        .mapToObj(i -> {
                            long reqStartNanos = System.nanoTime();
                            return postJsonAsync(
                                            client,
                                            baseUrl + "/api/v1/reviews/classify",
                                            validPayload(5, "Отличный и качественный товар " + i))
                                    .thenApply(response -> {
                                        latenciesMs.add((System.nanoTime() - reqStartNanos) / 1_000_000.0);
                                        return response;
                                    });
                        })
                        .toList();
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .join();
                for (var future : futures) {
                    assertThat(future.get().statusCode()).isEqualTo(200);
                }

                List<Double> sortedLatencies = latenciesMs.stream().sorted().toList();
                assertThat(sortedLatencies).hasSize(120);
                double p95Ms = sortedLatencies.get((int) Math.ceil(0.95 * sortedLatencies.size()) - 1);
                double p99Ms = sortedLatencies.get((int) Math.ceil(0.99 * sortedLatencies.size()) - 1);
                assertThat(p95Ms).isLessThanOrEqualTo(100.0);
                assertThat(p99Ms).isLessThanOrEqualTo(250.0);

                HttpResponse<String> metrics = get(client, baseUrl + "/metrics");
                assertThat(metrics.body()).contains("reviews_total 122").contains("http_request_duration_ms_bucket");
            }

            // Simulate restart after QA recovers -> durable outbox drains cleanly.
            qaStatus.set(200);
            qaDelayMs.set(0);
            try (ApplicationWiring restarted = new ApplicationWiring(config)) {
                restarted.loadModel();
                restarted.getQaAdapter().flushOutbox();
                assertThat(restarted.getQaAdapter().pendingCount()).isZero();
                assertThat(qaPayloads).hasSize(1);
                assertThat(qaPayloads.peek()).contains("\"sentiment\":\"negative\"");
            }
        } finally {
            mockQa.stop(0);
        }
    }

    @Test
    void preservesCapitalizedProductNounsAndAdjectivesWithoutFlippingCriticality(@TempDir Path tempDir)
            throws Exception {
        Path outboxPath = tempDir.resolve("no_false_positive_outbox.jsonl");
        AppConfig config = buildTestConfig(
                Path.of("data/sentiment_v1.json"), Path.of("data/sentiment_v1.json.sha256"), outboxPath, "");

        try (ApplicationWiring wiring = new ApplicationWiring(config);
                HttpClient client = HttpClient.newHttpClient()) {
            wiring.loadModel();
            wiring.start();
            String baseUrl = "http://127.0.0.1:" + wiring.getServer().getPort();

            List<String> positiveTexts = List.of(
                    "Отличный Пластилин без брака",
                    "Отличный пластилин без брака",
                    "Хороший Пластилин",
                    "Отличный Витамин");

            for (String text : positiveTexts) {
                HttpResponse<String> response =
                        postJson(client, baseUrl + "/api/v1/reviews/classify", validPayload(5, text));
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode json = objectMapper.readTree(response.body());
                assertThat(json.get("maskedText").asText()).isEqualTo(text);
                assertThat(json.get("sentiment").asText()).isEqualTo("positive");
                assertThat(json.get("isCritical").asBoolean()).isFalse();
            }

            assertThat(Files.exists(outboxPath)).isFalse();
        }
    }

    private static AppConfig buildTestConfig(Path modelPath, Path shaPath, Path outboxPath, String qaUrl) {
        return AppConfig.fromMap(Map.of(
                "APP_HOST",
                "127.0.0.1",
                "APP_PORT",
                "0",
                "MODEL_PATH",
                modelPath.toString(),
                "MODEL_SHA256_PATH",
                shaPath.toString(),
                "QA_SERVICE_URL",
                qaUrl,
                "OUTBOX_LOG_PATH",
                outboxPath.toString(),
                "QA_RETRY_DELAY_MS",
                "0",
                "QA_HTTP_TIMEOUT_SECONDS",
                "2"));
    }

    private static String validPayload(int rating, String text) {
        return validRawPayload("\"" + UUID.randomUUID() + "\"", "ORD-TEST-01", Integer.toString(rating), text);
    }

    private static String validRawPayload(String reviewIdJson, String orderId, String ratingJson, String text) {
        return """
                {
                  "reviewId": %s,
                  "orderId": "%s",
                  "rating": %s,
                  "text": "%s",
                  "createdAt": "2026-11-24T03:00:00Z"
                }
                """.formatted(reviewIdJson, orderId, ratingJson, text.replace("\"", "\\\""));
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postJson(HttpClient client, String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static CompletableFuture<HttpResponse<String>> postJsonAsync(HttpClient client, String url, String body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
    }
}
