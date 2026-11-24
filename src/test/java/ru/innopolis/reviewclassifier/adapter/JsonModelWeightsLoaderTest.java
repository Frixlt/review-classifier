package ru.innopolis.reviewclassifier.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.innopolis.reviewclassifier.adapters.outbound.JsonMapperFactory;
import ru.innopolis.reviewclassifier.adapters.outbound.JsonModelWeightsLoader;
import ru.innopolis.reviewclassifier.domain.exception.ModelLoadException;

class JsonModelWeightsLoaderTest {

    private final ObjectMapper objectMapper = JsonMapperFactory.create();

    @Test
    void loadsValidJsonModelWhenSha256Matches(@TempDir Path tempDir) throws Exception {
        Path model = tempDir.resolve("model.json");
        Path sha = tempDir.resolve("model.json.sha256");
        byte[] content = "{\"отличный\": 2.0, \"брак\": -2.5}".getBytes(StandardCharsets.UTF_8);
        Files.write(model, content);
        Files.writeString(sha, sha256Hex(content) + "  model.json\n", StandardCharsets.UTF_8);

        JsonModelWeightsLoader loader = new JsonModelWeightsLoader(objectMapper);
        loader.load(model, sha);

        assertThat(loader.isReady()).isTrue();
        assertThat(loader.getWeights()).containsEntry("отличный", 2.0).containsEntry("брак", -2.5);
    }

    @Test
    void rejectsChecksumMismatchAndKeepsNotReady(@TempDir Path tempDir) throws IOException {
        Path model = tempDir.resolve("model.json");
        Path sha = tempDir.resolve("model.json.sha256");
        Files.writeString(model, "{\"отличный\": 2.0}", StandardCharsets.UTF_8);
        Files.writeString(sha, "0".repeat(64), StandardCharsets.UTF_8);

        JsonModelWeightsLoader loader = new JsonModelWeightsLoader(objectMapper);

        assertThatThrownBy(() -> loader.load(model, sha))
                .isInstanceOf(ModelLoadException.class)
                .hasMessageContaining("SHA-256");
        assertThat(loader.isReady()).isFalse();
    }

    @Test
    void enforcesRealWallClockDeadlineAndPreventsLatePublication(@TempDir Path tempDir) throws Exception {
        Path model = tempDir.resolve("slow-model.json");
        Path sha = tempDir.resolve("slow-model.json.sha256");
        byte[] content = "{\"отличный\": 2.0, \"брак\": -2.5}".getBytes(StandardCharsets.UTF_8);
        Files.write(model, content);
        Files.writeString(sha, sha256Hex(content) + "  slow-model.json\n", StandardCharsets.UTF_8);

        ObjectMapper slowMapper = new ObjectMapper() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T readValue(byte[] src, TypeReference<T> valueTypeRef) throws IOException {
                waitUninterruptiblyMillis(400);
                return (T) Map.of("отличный", 2.0, "брак", -2.5);
            }
        };
        JsonModelWeightsLoader loader = new JsonModelWeightsLoader(slowMapper);

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> loader.load(model, sha, Duration.ofMillis(30)))
                .isInstanceOf(ModelLoadException.class)
                .hasMessageContaining("deadline");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(elapsedMs).isLessThan(200);
        assertThat(loader.isReady()).isFalse();

        Thread.sleep(450);
        assertThat(loader.isReady()).isFalse();
        assertThat(loader.getWeights()).isEmpty();
    }

    private static void waitUninterruptiblyMillis(long millis) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (true) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0) {
                return;
            }
            try {
                Thread.sleep(remainingMs);
            } catch (InterruptedException ignored) {
                // Keep sleeping to verify caller rejects late completion without publishing weights.
            }
        }
    }

    @Test
    void rejectsMissingCorruptEmptyAndBlankKeys(@TempDir Path tempDir) throws IOException {
        JsonModelWeightsLoader loader = new JsonModelWeightsLoader(objectMapper);

        assertThatThrownBy(() -> loader.load(tempDir.resolve("missing.json"), null))
                .isInstanceOf(ModelLoadException.class);

        Path corrupt = tempDir.resolve("corrupt.json");
        Files.writeString(corrupt, "{not valid json", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> loader.load(corrupt, null)).isInstanceOf(ModelLoadException.class);

        Path empty = tempDir.resolve("empty.json");
        Files.writeString(empty, "{}", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> loader.load(empty, null)).isInstanceOf(ModelLoadException.class);

        Path blankKey = tempDir.resolve("blank.json");
        Files.writeString(blankKey, "{\"  \": 1.0}", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> loader.load(blankKey, null)).isInstanceOf(ModelLoadException.class);
        assertThat(loader.isReady()).isFalse();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
