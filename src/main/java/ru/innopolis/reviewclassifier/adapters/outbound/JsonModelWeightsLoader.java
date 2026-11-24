package ru.innopolis.reviewclassifier.adapters.outbound;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.innopolis.reviewclassifier.domain.exception.ModelLoadException;
import ru.innopolis.reviewclassifier.domain.port.ModelWeightsPort;

/**
 * Safely loads sentiment weights from structured JSON with SHA-256 integrity verification,
 * size bounds, and real wall-clock load deadline, rejecting ObjectInputStream deserialization (FR-14, NFR-09).
 */
public final class JsonModelWeightsLoader implements ModelWeightsPort {
    public static final long MAX_MODEL_FILE_BYTES = 10L * 1024 * 1024;
    public static final Duration DEFAULT_MAX_LOAD_TIME = Duration.ofSeconds(10);
    private static final TypeReference<Map<String, Double>> WEIGHTS_MAP_TYPE = new TypeReference<>() {};
    private static final ExecutorService LOAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

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
        Duration effectiveDeadline = requirePositiveDeadline(maxLoadTime);
        Future<Map<String, Double>> future = LOAD_EXECUTOR.submit(() -> loadBlocking(modelPath, sha256Path));
        this.weights = awaitLoadedWeights(future, modelPath, effectiveDeadline);
        this.ready.set(true);
    }

    private static Duration requirePositiveDeadline(Duration maxLoadTime) {
        Duration effectiveDeadline = maxLoadTime != null ? maxLoadTime : DEFAULT_MAX_LOAD_TIME;
        if (!effectiveDeadline.isPositive()) {
            throw new ModelLoadException("Model loading exceeded deadline of " + effectiveDeadline.toMillis() + " ms");
        }
        return effectiveDeadline;
    }

    private static Map<String, Double> awaitLoadedWeights(
            Future<Map<String, Double>> future, Path modelPath, Duration effectiveDeadline) {
        long timeoutNanos = TimeUnit.NANOSECONDS.convert(effectiveDeadline);
        try {
            return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new ModelLoadException(
                    "Model loading exceeded deadline of " + effectiveDeadline.toMillis() + " ms", ex);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ModelLoadException("Interrupted while loading model weights: " + modelPath, ex);
        } catch (ExecutionException ex) {
            throw unwrapExecutionFailure(ex, modelPath);
        }
    }

    private Map<String, Double> loadBlocking(Path modelPath, Path sha256Path) {
        try {
            byte[] content = readBoundedBytes(modelPath);
            ModelWeightsValidator.verifyIntegrity(content, sha256Path);
            Map<String, Double> parsed = objectMapper.readValue(content, WEIGHTS_MAP_TYPE);
            return ModelWeightsValidator.validateWeights(parsed);
        } catch (IOException ex) {
            throw new ModelLoadException("Failed to read or parse model weights JSON: " + modelPath, ex);
        }
    }

    private static ModelLoadException unwrapExecutionFailure(ExecutionException ex, Path modelPath) {
        Throwable cause = ex.getCause();
        if (cause instanceof ModelLoadException modelEx) {
            return modelEx;
        }
        return new ModelLoadException("Failed to read or parse model weights JSON: " + modelPath, cause);
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
}
