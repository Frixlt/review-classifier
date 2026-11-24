package ru.innopolis.reviewclassifier.adapters.inbound.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.innopolis.reviewclassifier.adapters.inbound.http.dto.ErrorResponseDto;
import ru.innopolis.reviewclassifier.adapters.inbound.http.dto.ReviewResponseDto;
import ru.innopolis.reviewclassifier.adapters.outbound.MetricsExporter;
import ru.innopolis.reviewclassifier.application.ClassifyReviewUseCase;
import ru.innopolis.reviewclassifier.domain.exception.ModelNotReadyException;
import ru.innopolis.reviewclassifier.domain.exception.ValidationException;
import ru.innopolis.reviewclassifier.domain.model.ClassificationResult;
import ru.innopolis.reviewclassifier.domain.model.ReviewInput;
import ru.innopolis.reviewclassifier.domain.port.ClockPort;
import ru.innopolis.reviewclassifier.domain.port.MetricsPort;

/**
 * HTTP handler for POST /api/v1/reviews/classify enforcing strict path matching,
 * Allow header, backpressure concurrency limit, strict JSON validation, and zero PII echo (B2, M2, M8, m3).
 */
public final class ClassifyReviewHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ClassifyReviewHandler.class);
    private static final String ENDPOINT_PATH = "/api/v1/reviews/classify";
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int HTTP_OK = 200;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_INTERNAL_ERROR = 500;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;

    private final ClassifyReviewUseCase useCase;
    private final ObjectMapper objectMapper;
    private final MetricsPort metricsPort;
    private final MetricsExporter metricsExporter;
    private final ClockPort clockPort;
    private final Semaphore concurrencyLimiter;
    private final ReviewRequestParser requestParser;

    public ClassifyReviewHandler(
            ClassifyReviewUseCase useCase,
            ObjectMapper objectMapper,
            MetricsPort metricsPort,
            MetricsExporter metricsExporter,
            ClockPort clockPort,
            int maxConcurrentRequests) {
        this.useCase = useCase;
        this.objectMapper = objectMapper;
        this.metricsPort = metricsPort;
        this.metricsExporter = metricsExporter;
        this.clockPort = clockPort;
        this.concurrencyLimiter = new Semaphore(Math.max(1, maxConcurrentRequests));
        this.requestParser = new ReviewRequestParser(objectMapper, metricsPort);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!ENDPOINT_PATH.equals(exchange.getRequestURI().getPath())) {
            HttpResponseSupport.sendNotFound(exchange, objectMapper, clockPort.now());
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendError(exchange, HTTP_METHOD_NOT_ALLOWED, "Method Not Allowed", "Only POST is supported", Map.of());
            return;
        }
        if (!concurrencyLimiter.tryAcquire()) {
            sendError(
                    exchange,
                    HTTP_SERVICE_UNAVAILABLE,
                    "Service Unavailable",
                    "Server concurrency limit reached",
                    Map.of());
            return;
        }
        long startNanos = clockPort.nanoTime();
        try {
            processRequest(exchange);
        } finally {
            concurrencyLimiter.release();
            recordHttpDuration(startNanos);
        }
    }

    private void processRequest(HttpExchange exchange) throws IOException {
        try {
            String rawBody = HttpResponseSupport.readRequestBody(exchange);
            ReviewInput input = requestParser.parse(rawBody);
            ClassificationResult result = useCase.execute(input);
            HttpResponseSupport.sendJson(exchange, objectMapper, HTTP_OK, ReviewResponseDto.fromDomain(result));
        } catch (ValidationException ex) {
            sendError(exchange, HTTP_BAD_REQUEST, "Bad Request", ex.getMessage(), ex.getFieldErrors());
        } catch (HttpResponseSupport.PayloadTooLargeException ex) {
            metricsPort.recordValidationError();
            sendError(exchange, HTTP_BAD_REQUEST, "Bad Request", ex.getMessage(), Map.of("body", ex.getMessage()));
        } catch (ModelNotReadyException ex) {
            sendError(exchange, HTTP_SERVICE_UNAVAILABLE, "Service Unavailable", ex.getMessage(), Map.of());
        } catch (RuntimeException ex) {
            LOG.error("Unhandled error while processing classification request", ex);
            sendError(exchange, HTTP_INTERNAL_ERROR, "Internal Server Error", "Unexpected server error", Map.of());
        }
    }

    private void recordHttpDuration(long startNanos) {
        if (metricsExporter != null) {
            long durationMs = Math.max(0L, (clockPort.nanoTime() - startNanos) / NANOS_PER_MILLI);
            metricsExporter.recordHttpRequestDurationMs(durationMs);
        }
    }

    private void sendError(HttpExchange exchange, int status, String error, String message, Map<String, String> details)
            throws IOException {
        ErrorResponseDto payload = new ErrorResponseDto(clockPort.now(), status, error, message, details);
        HttpResponseSupport.sendJson(exchange, objectMapper, status, payload);
    }
}
