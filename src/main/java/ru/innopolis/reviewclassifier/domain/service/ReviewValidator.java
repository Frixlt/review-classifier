package ru.innopolis.reviewclassifier.domain.service;

import java.util.LinkedHashMap;
import java.util.Map;
import ru.innopolis.reviewclassifier.domain.exception.ValidationException;
import ru.innopolis.reviewclassifier.domain.model.ReviewInput;

/** Validates incoming review requests against FR-02 contract constraints. */
public final class ReviewValidator {
    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;
    public static final int MAX_TEXT_LENGTH = 5000;

    public void validate(ReviewInput input) {
        if (input == null) {
            throw new ValidationException("Request body must not be null", Map.of("body", "must not be null"));
        }
        Map<String, String> errors = new LinkedHashMap<>();
        validateMetadata(input, errors);
        validateText(input.text(), errors);
        if (!errors.isEmpty()) {
            throw new ValidationException("Review validation failed", errors);
        }
    }

    private void validateMetadata(ReviewInput input, Map<String, String> errors) {
        if (input.reviewId() == null) {
            errors.put("reviewId", "must be a valid UUID and not null");
        }
        if (input.orderId() == null || input.orderId().isBlank()) {
            errors.put("orderId", "must not be blank");
        }
        if (input.rating() < MIN_RATING || input.rating() > MAX_RATING) {
            errors.put("rating", "must be between " + MIN_RATING + " and " + MAX_RATING);
        }
        if (input.createdAt() == null) {
            errors.put("createdAt", "must be a valid ISO-8601 timestamp and not null");
        }
    }

    private void validateText(String text, Map<String, String> errors) {
        if (text == null || text.isBlank()) {
            errors.put("text", "must not be null or blank");
            return;
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            errors.put("text", "length must not exceed " + MAX_TEXT_LENGTH + " characters");
        }
    }
}
