package ru.innopolis.reviewclassifier.adapters.inbound.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import ru.innopolis.reviewclassifier.domain.port.ClockPort;

/** Embedded HTTP server backed by JDK HttpServer and Java 25 virtual threads. */
public final class ReviewHttpServer {
    private final HttpServer server;
    private final ExecutorService executor;

    public ReviewHttpServer(
            String host,
            int port,
            ClassifyReviewHandler classifyHandler,
            HealthHandler livenessHandler,
            HealthHandler readinessHandler,
            MetricsHandler metricsHandler,
            ObjectMapper objectMapper,
            ClockPort clockPort)
            throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server.setExecutor(executor);
        this.server.createContext("/api/v1/reviews/classify", classifyHandler);
        this.server.createContext("/health/live", livenessHandler);
        this.server.createContext("/health/ready", readinessHandler);
        this.server.createContext("/metrics", metricsHandler);
        this.server.createContext("/actuator/prometheus", metricsHandler);
        this.server.createContext(
                "/", exchange -> HttpResponseSupport.sendNotFound(exchange, objectMapper, clockPort.now()));
    }

    public void start() {
        server.start();
    }

    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
        executor.shutdown();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }
}
