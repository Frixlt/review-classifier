package ru.innopolis.reviewclassifier.domain.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalizes text (lowercase, yo->ye, punctuation removal) into clean tokens (FR-05). */
public final class TextPreprocessor {
    private static final Locale RU_LOCALE = Locale.forLanguageTag("ru");
    private static final Pattern NON_WORD_PATTERN = Pattern.compile("[^\\p{L}\\p{Nd}]+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(RU_LOCALE).replace('ё', 'е');
        String cleaned = NON_WORD_PATTERN.matcher(normalized).replaceAll(" ").trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(WHITESPACE_PATTERN.split(cleaned))
                .filter(token -> !token.isEmpty())
                .toList();
    }
}
