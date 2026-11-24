package ru.innopolis.reviewclassifier.adapters.inbound.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Set;
import ru.innopolis.reviewclassifier.adapters.outbound.MetricsExporter;
import ru.innopolis.reviewclassifier.domain.port.ClockPort;

/** Prometheus text exposition endpoint handler supporting /metrics and /actuator/prometheus (FR-15, AC-25, m2, m6). */
public final class MetricsHandler implements HttpHandler {
    private static final int HTTP_OK = 200;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final String PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    private static final Set<String> VALID_PATHS = Set.of("/metrics", "/actuator/prometheus");

    private final MetricsExporter metricsExporter;
    private final ObjectMapper objectMapper;
    private final ClockPort clockPort;

    public MetricsHandler(MetricsExporter metricsExporter, ObjectMapper objectMapper, ClockPort clockPort) {
        this.metricsExporter = metricsExporter;
        this.objectMapper = objectMapper;
        this.clockPort = clockPort;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!VALID_PATHS.contains(exchange.getRequestURI().getPath())) {
            HttpResponseSupport.sendNotFound(exchange, objectMapper, clockPort.now());
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            HttpResponseSupport.sendText(
                    exchange, HTTP_METHOD_NOT_ALLOWED, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        HttpResponseSupport.sendText(exchange, HTTP_OK, PROMETHEUS_CONTENT_TYPE, metricsExporter.exportPrometheus());
    }
}
