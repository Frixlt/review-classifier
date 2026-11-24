package ru.innopolis.reviewclassifier.adapters.outbound;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import ru.innopolis.reviewclassifier.domain.exception.ModelLoadException;

/** Package-private integrity (SHA-256) and weights dictionary validator for {@link JsonModelWeightsLoader}. */
final class ModelWeightsValidator {
    private static final Locale RU_LOCALE = Locale.forLanguageTag("ru");

    private ModelWeightsValidator() {}

    static void verifyIntegrity(byte[] content, Path sha256Path) throws IOException {
        if (sha256Path == null) {
            return;
        }
        if (!Files.isRegularFile(sha256Path)) {
            throw new ModelLoadException("SHA-256 checksum file not found: " + sha256Path);
        }
        String rawChecksum =
                Files.readString(sha256Path, StandardCharsets.UTF_8).trim();
        String expectedHex = rawChecksum.split("\\s+")[0].toLowerCase(Locale.ROOT);
        String actualHex = computeSha256Hex(content);
        if (!MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8), actualHex.getBytes(StandardCharsets.UTF_8))) {
            throw new ModelLoadException(
                    "Model SHA-256 verification failed: expected=" + expectedHex + ", actual=" + actualHex);
        }
    }

    static Map<String, Double> validateWeights(Map<String, Double> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            throw new ModelLoadException("Model weights dictionary must not be empty");
        }
        Map<String, Double> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : parsed.entrySet()) {
            addValidatedEntry(entry, clean);
        }
        return Collections.unmodifiableMap(clean);
    }

    private static void addValidatedEntry(Map.Entry<String, Double> entry, Map<String, Double> clean) {
        String token = entry.getKey();
        Double weight = entry.getValue();
        if (token == null || token.isBlank()) {
            throw new ModelLoadException("Model weights dictionary contains a blank token key");
        }
        if (weight == null || !Double.isFinite(weight)) {
            throw new ModelLoadException("Invalid non-finite weight for token: " + token);
        }
        clean.put(token.toLowerCase(RU_LOCALE).replace('ё', 'е'), weight);
    }

    private static String computeSha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new ModelLoadException("SHA-256 algorithm unavailable", ex);
        }
    }
}
