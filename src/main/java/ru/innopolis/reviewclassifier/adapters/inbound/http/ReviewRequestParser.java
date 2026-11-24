package ru.innopolis.reviewclassifier.adapters.inbound.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import ru.innopolis.reviewclassifier.adapters.inbound.http.dto.ReviewRequestDto;
import ru.innopolis.reviewclassifier.domain.exception.ValidationException;
import ru.innopolis.reviewclassifier.domain.model.ReviewInput;
import ru.innopolis.reviewclassifier.domain.port.MetricsPort;

/**
 * Package-private strict JSON-to-{@link ReviewInput} parser guaranteeing zero PII echo on errors.
 */
final class ReviewRequestParser {
    private static final Pattern CANONICAL_UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Set<String> KNOWN_FIELDS = Set.of("reviewId", "orderId", "rating", "text", "createdAt");

    private final ObjectMapper objectMapper;
    private final MetricsPort metricsPort;

    ReviewRequestParser(ObjectMapper objectMapper, MetricsPort metricsPort) {
        this.objectMapper = objectMapper;
        this.metricsPort = metricsPort;
    }

    ReviewInput parse(String rawBody) {
        ReviewRequestDto dto = deserializeDto(rawBody);
        return toDomainInput(dto);
    }

    private ReviewRequestDto deserializeDto(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return validationFailure("Request body must not be empty", "body", "must not be empty", null);
        }
        try {
            ReviewRequestDto dto = objectMapper.readValue(rawBody, ReviewRequestDto.class);
            if (dto == null) {
                return validationFailure("Request body must be a JSON object", "body", "must not be null", null);
            }
            return dto;
        } catch (UnrecognizedPropertyException ex) {
            return validationFailure("Unknown field in JSON payload", "body", "contains unknown field", ex);
        } catch (MismatchedInputException ex) {
            return validationFailure(
                    "Invalid JSON field type", resolveSafeFieldName(ex), "must match expected type", ex);
        } catch (JsonProcessingException ex) {
            return validationFailure("Malformed JSON payload", "json", "malformed JSON syntax", ex);
        }
    }

    private ReviewRequestDto validationFailure(String message, String field, String detail, Throwable cause) {
        metricsPort.recordValidationError();
        throw new ValidationException(message, Map.of(field, detail), cause);
    }

    private String resolveSafeFieldName(MismatchedInputException ex) {
        for (JsonMappingException.Reference ref : ex.getPath()) {
            if (ref.getFieldName() != null && KNOWN_FIELDS.contains(ref.getFieldName())) {
                return ref.getFieldName();
            }
        }
        return "body";
    }

    private ReviewInput toDomainInput(ReviewRequestDto dto) {
        Map<String, String> parseErrors = new LinkedHashMap<>();
        UUID reviewId = parseUuid(dto.reviewId(), parseErrors);
        Instant createdAt = parseInstant(dto.createdAt(), parseErrors);
        int rating = parseRating(dto.rating(), parseErrors);
        if (!parseErrors.isEmpty()) {
            metricsPort.recordValidationError();
            throw new ValidationException("Review validation failed", parseErrors);
        }
        return new ReviewInput(reviewId, dto.orderId(), rating, dto.text(), createdAt);
    }

    private UUID parseUuid(String rawReviewId, Map<String, String> errors) {
        if (rawReviewId == null || rawReviewId.isBlank()) {
            errors.put("reviewId", "must be a valid UUID and not null");
            return null;
        }
        String trimmed = rawReviewId.trim();
        if (!CANONICAL_UUID_PATTERN.matcher(trimmed).matches()) {
            errors.put("reviewId", "must be a valid canonical UUID");
            return null;
        }
        return UUID.fromString(trimmed);
    }

    private Instant parseInstant(String rawCreatedAt, Map<String, String> errors) {
        if (rawCreatedAt == null || rawCreatedAt.isBlank()) {
            errors.put("createdAt", "must be a valid ISO-8601 timestamp and not null");
            return null;
        }
        try {
            return Instant.parse(rawCreatedAt.trim());
        } catch (DateTimeParseException ex) {
            errors.put("createdAt", "must be a valid ISO-8601 timestamp");
            return null;
        }
    }

    private int parseRating(Integer rawRating, Map<String, String> errors) {
        if (rawRating == null) {
            errors.put("rating", "must not be null");
            return 0;
        }
        return rawRating;
    }
}
