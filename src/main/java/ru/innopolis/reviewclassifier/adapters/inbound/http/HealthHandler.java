package ru.innopolis.reviewclassifier.adapters.inbound.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import ru.innopolis.reviewclassifier.domain.port.ModelWeightsPort;

/** Liveness and readiness HTTP probe handler enforcing exact path and Allow header (FR-14, AC-22, AC-23). */
public final class HealthHandler implements HttpHandler {
    private static final int HTTP_OK = 200;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;

    private final ModelWeightsPort modelWeightsPort;
    private final ObjectMapper objectMapper;
    private final boolean checkReadiness;
    private final String expectedPath;

    public HealthHandler(ModelWeightsPort modelWeightsPort, ObjectMapper objectMapper, boolean checkReadiness) {
        this.modelWeightsPort = modelWeightsPort;
        this.objectMapper = objectMapper;
        this.checkReadiness = checkReadiness;
        this.expectedPath = checkReadiness ? "/health/ready" : "/health/live";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!expectedPath.equals(exchange.getRequestURI().getPath())) {
            HttpResponseSupport.sendNotFound(exchange, objectMapper, Instant.now());
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            HttpResponseSupport.sendJson(
                    exchange, objectMapper, HTTP_METHOD_NOT_ALLOWED, Map.of("error", "Method Not Allowed"));
            return;
        }
        if (!checkReadiness || modelWeightsPort.isReady()) {
            HttpResponseSupport.sendJson(exchange, objectMapper, HTTP_OK, Map.of("status", "UP"));
            return;
        }
        HttpResponseSupport.sendJson(
                exchange,
                objectMapper,
                HTTP_SERVICE_UNAVAILABLE,
                Map.of("status", "DOWN", "reason", "Model weights are not loaded"));
    }
}
