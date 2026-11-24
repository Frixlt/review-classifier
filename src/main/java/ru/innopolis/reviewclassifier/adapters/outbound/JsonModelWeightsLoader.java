package ru.innopolis.reviewclassifier.adapters.outbound;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.innopolis.reviewclassifier.domain.exception.ModelLoadException;
import ru.innopolis.reviewclassifier.domain.port.ModelWeightsPort;

/**
 * Safely loads sentiment weights from structured JSON with SHA-256 integrity verification,
 * size bounds, and load-time deadline, rejecting Java ObjectInputStream deserialization (FR-14, NFR-09, M7).
 */
public final class JsonModelWeightsLoader implements ModelWeightsPort {
    public static final long MAX_MODEL_FILE_BYTES = 10L * 1024 * 1024;
    public static final Duration DEFAULT_MAX_LOAD_TIME = Duration.ofSeconds(10);
    private static final TypeReference<Map<String, Double>> WEIGHTS_MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile Map<String, Double> weights = Map.of();

    public JsonModelWeightsLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void load(Path modelPath, Path sha256Path) {
        load(modelPath, sha256Path, DEFAULT_MAX_LOAD_TIME);
    }

    public void load(Path modelPath, Path sha256Path, Duration maxLoadTime) {
        long startNanos = System.nanoTime();
        try {
            byte[] content = readBoundedBytes(modelPath);
            ModelWeightsValidator.verifyIntegrity(content, sha256Path);
            Map<String, Double> parsed = objectMapper.readValue(content, WEIGHTS_MAP_TYPE);
            Map<String, Double> validated = ModelWeightsValidator.validateWeights(parsed);
            checkDeadline(startNanos, maxLoadTime);
            this.weights = validated;
            this.ready.set(true);
        } catch (ModelLoadException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ModelLoadException("Failed to read or parse model weights JSON: " + modelPath, ex);
        }
    }

    @Override
    public boolean isReady() {
        return ready.get();
    }

    @Override
    public Map<String, Double> getWeights() {
        return weights;
    }

    private static byte[] readBoundedBytes(Path modelPath) throws IOException {
        if (modelPath == null || !Files.isRegularFile(modelPath)) {
            throw new ModelLoadException("Model weights file not found: " + modelPath);
        }
        long fileSize = Files.size(modelPath);
        if (fileSize > MAX_MODEL_FILE_BYTES) {
            throw new ModelLoadException("Model weights file exceeds size limit: " + fileSize);
        }
        return Files.readAllBytes(modelPath);
    }

    private static void checkDeadline(long startNanos, Duration maxLoadTime) {
        if (maxLoadTime != null && (System.nanoTime() - startNanos) > maxLoadTime.toNanos()) {
            throw new ModelLoadException("Model loading exceeded deadline of " + maxLoadTime.toMillis() + " ms");
        }
    }
}
