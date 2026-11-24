package ru.innopolis.reviewclassifier.infrastructure;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Package-private parser and validator for {@link AppConfig}. */
final class AppConfigParser {
    private AppConfigParser() {}

    static AppConfig fromEnvironment() {
        Map<String, String> merged = new LinkedHashMap<>(DotEnvLoader.load(Path.of(".env")));
        merged.putAll(System.getenv());
        return fromMap(merged);
    }

    static AppConfig fromMap(Map<String, String> values) {
        Objects.requireNonNull(values, "Configuration map must not be null");
        String host = readString(values, "APP_HOST", AppConfig.DEFAULT_HOST);
        int port = (int) readLongInRange(values, "APP_PORT", AppConfig.DEFAULT_PORT, 0L, 65535L);
        Path modelPath = Path.of(readString(values, "MODEL_PATH", AppConfig.DEFAULT_MODEL_PATH));
        Path shaPath = Path.of(readString(values, "MODEL_SHA256_PATH", AppConfig.DEFAULT_MODEL_SHA256_PATH));
        String qaUrl = readString(values, "QA_SERVICE_URL", AppConfig.DEFAULT_QA_SERVICE_URL);
        Path outboxPath = Path.of(readString(values, "OUTBOX_LOG_PATH", AppConfig.DEFAULT_OUTBOX_LOG_PATH));
        long slaSeconds =
                readLongInRange(values, "SLA_LIMIT_SECONDS", AppConfig.DEFAULT_SLA_LIMIT_SECONDS, 1L, Long.MAX_VALUE);
        int maxRetries = (int)
                readLongInRange(values, "QA_MAX_RETRIES", AppConfig.DEFAULT_QA_MAX_RETRIES, 1L, Integer.MAX_VALUE);
        long retryDelayMs =
                readLongInRange(values, "QA_RETRY_DELAY_MS", AppConfig.DEFAULT_QA_RETRY_DELAY_MS, 0L, Long.MAX_VALUE);
        long bgInterval = readLongInRange(
                values,
                "QA_BACKGROUND_INTERVAL_SECONDS",
                AppConfig.DEFAULT_QA_BACKGROUND_INTERVAL_SECONDS,
                1L,
                Long.MAX_VALUE);
        long qaHttpTimeout =
                readLongInRange(values, "QA_HTTP_TIMEOUT_SECONDS", AppConfig.DEFAULT_QA_HTTP_TIMEOUT_SECONDS, 1L, 300L);
        long inferenceTimeout =
                readLongInRange(values, "INFERENCE_TIMEOUT_MS", AppConfig.DEFAULT_INFERENCE_TIMEOUT_MS, 1L, 60_000L);
        long modelLoadTimeout =
                readLongInRange(values, "MODEL_LOAD_TIMEOUT_MS", AppConfig.DEFAULT_MODEL_LOAD_TIMEOUT_MS, 1L, 300_000L);
        int maxConcurrency = (int) readLongInRange(
                values, "MAX_CONCURRENT_REQUESTS", AppConfig.DEFAULT_MAX_CONCURRENT_REQUESTS, 1L, 10_000L);
        long httpMaxReqTime = readLongInRange(
                values, "HTTP_MAX_REQ_TIME_SECONDS", AppConfig.DEFAULT_HTTP_MAX_REQ_TIME_SECONDS, 1L, 300L);

        return new AppConfig(
                host,
                port,
                modelPath,
                shaPath,
                qaUrl,
                outboxPath,
                slaSeconds,
                maxRetries,
                retryDelayMs,
                bgInterval,
                qaHttpTimeout,
                inferenceTimeout,
                modelLoadTimeout,
                maxConcurrency,
                httpMaxReqTime);
    }

    private static String readString(Map<String, String> values, String key, String defaultValue) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return raw.trim();
    }

    private static long readLongInRange(Map<String, String> values, String key, long defaultValue, long min, long max) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": " + raw, ex);
        }
    }
}
