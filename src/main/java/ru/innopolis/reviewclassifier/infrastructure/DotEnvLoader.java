package ru.innopolis.reviewclassifier.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal package-private {@code .env} key-value reader for local execution fallback. */
final class DotEnvLoader {

    private DotEnvLoader() {}

    static Map<String, String> load(Path dotEnvPath) {
        if (dotEnvPath == null || !Files.isRegularFile(dotEnvPath)) {
            return Map.of();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(dotEnvPath, StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                parseLine(rawLine, parsed);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read .env file: " + dotEnvPath, ex);
        }
        return parsed;
    }

    private static void parseLine(String rawLine, Map<String, String> target) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        int equalsIndex = line.indexOf('=');
        if (equalsIndex <= 0) {
            return;
        }
        String key = line.substring(0, equalsIndex).trim();
        String value = stripMatchingQuotes(line.substring(equalsIndex + 1).trim());
        target.put(key, value);
    }

    private static String stripMatchingQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
