package ru.innopolis.reviewclassifier.infrastructure;

import java.nio.file.Path;
import java.util.Map;

/**
 * Immutable runtime configuration loaded from environment variables and optional {@code .env} file.
 */
public record AppConfig(
        String host,
        int port,
        Path modelPath,
        Path modelSha256Path,
        String qaServiceUrl,
        Path outboxLogPath,
        long slaLimitSeconds,
        int qaMaxRetries,
        long qaRetryDelayMs,
        long qaBackgroundIntervalSeconds,
        long qaHttpTimeoutSeconds,
        long inferenceTimeoutMs,
        long modelLoadTimeoutMs,
        int maxConcurrentRequests,
        long httpMaxReqTimeSeconds) {

    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_PORT = 8080;
    public static final String DEFAULT_MODEL_PATH = "data/sentiment_v1.json";
    public static final String DEFAULT_MODEL_SHA256_PATH = "data/sentiment_v1.json.sha256";
    public static final String DEFAULT_QA_SERVICE_URL = "";
    public static final String DEFAULT_OUTBOX_LOG_PATH = "logs/qa_outbox.jsonl";
    public static final long DEFAULT_SLA_LIMIT_SECONDS = 900L;
    public static final int DEFAULT_QA_MAX_RETRIES = 3;
    public static final long DEFAULT_QA_RETRY_DELAY_MS = 100L;
    public static final long DEFAULT_QA_BACKGROUND_INTERVAL_SECONDS = 30L;
    public static final long DEFAULT_QA_HTTP_TIMEOUT_SECONDS = 5L;
    public static final long DEFAULT_INFERENCE_TIMEOUT_MS = 500L;
    public static final long DEFAULT_MODEL_LOAD_TIMEOUT_MS = 10_000L;
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 128;
    public static final long DEFAULT_HTTP_MAX_REQ_TIME_SECONDS = 10L;

    public static AppConfig fromEnvironment() {
        return AppConfigParser.fromEnvironment();
    }

    public static AppConfig fromMap(Map<String, String> values) {
        return AppConfigParser.fromMap(values);
    }

    public static Map<String, String> loadDotEnv(Path dotEnvPath) {
        return DotEnvLoader.load(dotEnvPath);
    }
}
