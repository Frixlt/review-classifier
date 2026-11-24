package ru.innopolis.reviewclassifier.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.innopolis.reviewclassifier.adapters.inbound.http.ClassifyReviewHandler;
import ru.innopolis.reviewclassifier.adapters.inbound.http.HealthHandler;
import ru.innopolis.reviewclassifier.adapters.inbound.http.MetricsHandler;
import ru.innopolis.reviewclassifier.adapters.inbound.http.ReviewHttpServer;
import ru.innopolis.reviewclassifier.adapters.outbound.JsonMapperFactory;
import ru.innopolis.reviewclassifier.adapters.outbound.JsonModelWeightsLoader;
import ru.innopolis.reviewclassifier.adapters.outbound.JsonStdoutAuditLogger;
import ru.innopolis.reviewclassifier.adapters.outbound.PrometheusMetricsAdapter;
import ru.innopolis.reviewclassifier.adapters.outbound.RetryableQaAdapter;
import ru.innopolis.reviewclassifier.adapters.outbound.SystemClockAdapter;
import ru.innopolis.reviewclassifier.application.ClassifyReviewUseCase;
import ru.innopolis.reviewclassifier.domain.port.ClockPort;
import ru.innopolis.reviewclassifier.domain.service.CriticalityEvaluator;
import ru.innopolis.reviewclassifier.domain.service.IssueCategoryDetector;
import ru.innopolis.reviewclassifier.domain.service.PiiMasker;
import ru.innopolis.reviewclassifier.domain.service.ReviewValidator;
import ru.innopolis.reviewclassifier.domain.service.SentimentScoringService;
import ru.innopolis.reviewclassifier.domain.service.TextPreprocessor;

/**
 * Manual dependency injection container connecting domain services, application use case, and adapters.
 */
public final class ApplicationWiring implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationWiring.class);
    private static final int STOP_DELAY_SECONDS = 1;

    private final AppConfig config;
    private final ObjectMapper objectMapper;
    private final ClockPort clockPort;
    private final PrometheusMetricsAdapter metricsAdapter;
    private final JsonModelWeightsLoader modelWeightsLoader;
    private final JsonStdoutAuditLogger auditLogger;
    private final RetryableQaAdapter qaAdapter;
    private final ClassifyReviewUseCase useCase;
    private final ReviewHttpServer server;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ApplicationWiring(AppConfig config) throws IOException {
        this.config = Objects.requireNonNull(config, "AppConfig must not be null");
        configureHttpServerTimeouts(config.httpMaxReqTimeSeconds());
        this.objectMapper = JsonMapperFactory.create();
        this.clockPort = new SystemClockAdapter();
        this.metricsAdapter = new PrometheusMetricsAdapter();
        this.modelWeightsLoader = new JsonModelWeightsLoader(objectMapper);
        this.auditLogger = new JsonStdoutAuditLogger(objectMapper);
        this.qaAdapter = RetryableQaAdapter.createConfigured(
                objectMapper,
                config.qaServiceUrl(),
                config.outboxLogPath(),
                metricsAdapter,
                clockPort,
                Duration.ofSeconds(config.slaLimitSeconds()),
                config.qaMaxRetries(),
                config.qaRetryDelayMs(),
                Duration.ofSeconds(config.qaHttpTimeoutSeconds()));

        this.useCase = new ClassifyReviewUseCase(
                new ReviewValidator(),
                new PiiMasker(),
                new TextPreprocessor(),
                new SentimentScoringService(),
                new IssueCategoryDetector(),
                new CriticalityEvaluator(),
                modelWeightsLoader,
                qaAdapter,
                auditLogger,
                metricsAdapter,
                clockPort,
                config.inferenceTimeoutMs());

        ClassifyReviewHandler classifyHandler = new ClassifyReviewHandler(
                useCase, objectMapper, metricsAdapter, metricsAdapter, clockPort, config.maxConcurrentRequests());
        HealthHandler livenessHandler = new HealthHandler(modelWeightsLoader, objectMapper, false);
        HealthHandler readinessHandler = new HealthHandler(modelWeightsLoader, objectMapper, true);
        MetricsHandler metricsHandler = new MetricsHandler(metricsAdapter, objectMapper, clockPort);

        this.server = new ReviewHttpServer(
                config.host(),
                config.port(),
                classifyHandler,
                livenessHandler,
                readinessHandler,
                metricsHandler,
                objectMapper,
                clockPort);
    }

    static void configureHttpServerTimeouts(long seconds) {
        String value = Long.toString(seconds);
        System.setProperty("sun.net.httpserver.maxReqTime", System.getProperty("sun.net.httpserver.maxReqTime", value));
        System.setProperty("sun.net.httpserver.maxRspTime", System.getProperty("sun.net.httpserver.maxRspTime", value));
    }

    public void loadModel() {
        modelWeightsLoader.load(
                config.modelPath(), config.modelSha256Path(), Duration.ofMillis(config.modelLoadTimeoutMs()));
    }

    public void start() {
        server.start();
        qaAdapter.startBackgroundRetries(config.qaBackgroundIntervalSeconds());
        LOG.info(
                "HTTP server started on {}:{} (modelReady={})",
                config.host(),
                server.getPort(),
                modelWeightsLoader.isReady());
    }

    public void stop() {
        close();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(STOP_DELAY_SECONDS);
        qaAdapter.close();
        LOG.info("Application components stopped");
    }

    public AppConfig getConfig() {
        return config;
    }

    public ReviewHttpServer getServer() {
        return server;
    }

    public JsonModelWeightsLoader getModelWeightsLoader() {
        return modelWeightsLoader;
    }

    public RetryableQaAdapter getQaAdapter() {
        return qaAdapter;
    }

    public PrometheusMetricsAdapter getMetricsAdapter() {
        return metricsAdapter;
    }

    public ClassifyReviewUseCase getUseCase() {
        return useCase;
    }
}
