package ru.innopolis.reviewclassifier.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.innopolis.reviewclassifier.infrastructure.AppConfig;
import ru.innopolis.reviewclassifier.infrastructure.Main;

class AppConfigTest {

    @Test
    void usesSaneDefaultsWhenMapIsEmpty() {
        AppConfig config = AppConfig.fromMap(Map.of());

        assertThat(config.host()).isEqualTo(AppConfig.DEFAULT_HOST);
        assertThat(config.port()).isEqualTo(AppConfig.DEFAULT_PORT);
        assertThat(config.modelPath()).isEqualTo(Path.of(AppConfig.DEFAULT_MODEL_PATH));
        assertThat(config.slaLimitSeconds()).isEqualTo(AppConfig.DEFAULT_SLA_LIMIT_SECONDS);
        assertThat(config.qaHttpTimeoutSeconds()).isEqualTo(AppConfig.DEFAULT_QA_HTTP_TIMEOUT_SECONDS);
        assertThat(config.inferenceTimeoutMs()).isEqualTo(AppConfig.DEFAULT_INFERENCE_TIMEOUT_MS);
        assertThat(config.modelLoadTimeoutMs()).isEqualTo(AppConfig.DEFAULT_MODEL_LOAD_TIMEOUT_MS);
        assertThat(config.maxConcurrentRequests()).isEqualTo(AppConfig.DEFAULT_MAX_CONCURRENT_REQUESTS);
        assertThat(config.httpMaxReqTimeSeconds()).isEqualTo(AppConfig.DEFAULT_HTTP_MAX_REQ_TIME_SECONDS);
    }

    @Test
    void parsesExplicitConfigurationOverrides() {
        AppConfig config = AppConfig.fromMap(Map.of(
                "APP_HOST", "127.0.0.1",
                "APP_PORT", "18080",
                "MODEL_PATH", "/opt/model.json",
                "QA_SERVICE_URL", "http://qa.internal/incidents",
                "SLA_LIMIT_SECONDS", "600",
                "QA_MAX_RETRIES", "5",
                "QA_HTTP_TIMEOUT_SECONDS", "3",
                "INFERENCE_TIMEOUT_MS", "250",
                "MAX_CONCURRENT_REQUESTS", "64",
                "HTTP_MAX_REQ_TIME_SECONDS", "15"));

        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(18080);
        assertThat(config.modelPath()).isEqualTo(Path.of("/opt/model.json"));
        assertThat(config.qaServiceUrl()).isEqualTo("http://qa.internal/incidents");
        assertThat(config.slaLimitSeconds()).isEqualTo(600L);
        assertThat(config.qaMaxRetries()).isEqualTo(5);
        assertThat(config.qaHttpTimeoutSeconds()).isEqualTo(3L);
        assertThat(config.inferenceTimeoutMs()).isEqualTo(250L);
        assertThat(config.maxConcurrentRequests()).isEqualTo(64);
        assertThat(config.httpMaxReqTimeSeconds()).isEqualTo(15L);
    }

    @Test
    void rejectsInvalidPortOrNonPositiveSla() {
        assertThatThrownBy(() -> AppConfig.fromMap(Map.of("APP_PORT", "70000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppConfig.fromMap(Map.of("APP_PORT", "abc")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppConfig.fromMap(Map.of("SLA_LIMIT_SECONDS", "0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppConfig.fromMap(Map.of("INFERENCE_TIMEOUT_MS", "0")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadsDotEnvIgnoringCommentsAndStrippingQuotes(@TempDir Path tempDir) throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(
                envFile,
                "# comment\n\nAPP_PORT=\"19090\"\nQA_SERVICE_URL='http://127.0.0.1:9000/qa'\nINVALID_LINE\n",
                StandardCharsets.UTF_8);

        AppConfig parsed = AppConfig.fromMap(AppConfig.loadDotEnv(envFile));
        assertThat(parsed.port()).isEqualTo(19090);
        assertThat(parsed.qaServiceUrl()).isEqualTo("http://127.0.0.1:9000/qa");
        assertThat(AppConfig.loadDotEnv(tempDir.resolve("absent.env"))).isEmpty();
        assertThat(Main.class).isNotNull();
    }
}
