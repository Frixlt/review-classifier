package ru.innopolis.reviewclassifier.adapters.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Outbound HTTP transport sending {@link IncidentCardDto} with idempotency key and configurable timeout.
 */
final class HttpIncidentSender implements RetryableQaAdapter.IncidentSender, AutoCloseable {
    private static final int HTTP_SUCCESS_MIN = 200;
    private static final int HTTP_SUCCESS_MAX = 299;

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration requestTimeout;

    HttpIncidentSender(ObjectMapper mapper, URI endpoint, Duration requestTimeout) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        this.client = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
    }

    @Override
    public void send(IncidentCardDto card) throws IOException {
        try {
            String payload = mapper.writeValueAsString(card);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Idempotency-Key", card.reviewId().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < HTTP_SUCCESS_MIN || response.statusCode() > HTTP_SUCCESS_MAX) {
                throw new IOException("QA endpoint returned HTTP " + response.statusCode());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending incident card", ex);
        }
    }

    @Override
    public void closeTransport() {
        close();
    }

    @Override
    public void close() {
        client.close();
    }
}
